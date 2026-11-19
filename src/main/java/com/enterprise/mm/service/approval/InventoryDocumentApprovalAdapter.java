package com.enterprise.mm.service.approval;

import com.enterprise.mm.entity.*;
import com.enterprise.mm.enums.*;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.ApprovalDtos.*;
import com.enterprise.mm.repository.*;
import com.enterprise.mm.service.AuditService;
import com.enterprise.mm.service.inventory.*;
import jakarta.persistence.EntityManager;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component @RequiredArgsConstructor @Transactional
public class InventoryDocumentApprovalAdapter implements ApprovalModuleAdapter {
    public static final String MODULE_KEY = "INVENTORY_DOCUMENT";
    private final InventoryDocumentRepository repository;
    private final InventoryItemRepository items;
    private final AuditService audit;
    private final InventoryAccessService access;
    private final InventoryRevisionService revisions;
    private final InventoryEvidenceService evidencePolicy;
    private final ApprovalInstanceRepository instances;
    private final ApprovalTaskRepository tasks;
    private final AttachmentRepository attachments;
    private final EntityManager em;

    public String moduleKey() { return MODULE_KEY; }
    public UUID companyId(UUID id) { return access.document(id).getCompanyId(); }
    // The engine still renders a decision receipt after a return invalidates the actor's task access.
    public String displayName(UUID id) {
        return repository.findByTenantIdAndIdAndIsDeletedFalse(com.enterprise.mm.common.TenantContext.currentTenant(), id)
            .filter(d -> com.enterprise.mm.common.CompanyContext.currentCompany().equals(d.getCompanyId()))
            .orElseThrow(() -> new com.enterprise.mm.error.NotFoundException("Inventory document not found")).getName();
    }
    public void requireDetailRead(UUID id) { access.requireVisibleExport(access.document(id)); }

    public void validateCanSubmit(UUID id) {
        var d = revisions.lock(id); access.requireReview(d);
        if (!"COUNTING_COMPLETED".equals(d.getStatus())) throw new BusinessException("Finish counting and discrepancy review before submitting approval");
        validateReviewed(d);
    }
    public void validateReviewed(InventoryDocument d) {
        if (d.getSnapshotAt() == null || !d.isFreezeOperations()) throw new BusinessException("UNFROZEN_SNAPSHOT_REQUIRES_RESTART");
        if (d.getUnidentifiedStock().stream().anyMatch(o -> o.resolvedLineId() == null)) throw new BusinessException("UNIDENTIFIED_STOCK");
        var rows = lines(d);
        if (rows.isEmpty()) throw new BusinessException("The count has no snapshot lines");
        for (var line : rows) {
            if (line.getCountedQuantity() == null || line.getLineStatus() == InventoryLineStatus.NEEDS_RECOUNT) throw new BusinessException("UNCOUNTED_LINES");
            boolean serialized = line.getObservedSerialNumber() != null || line.getStockBatch() != null && line.getStockBatch().getSerialNumber() != null;
            InventoryCountMutationService.validateQuantity(line.getCountedQuantity(), false, serialized ? 0 : 4, serialized);
            if (line.getDifferenceQuantity().signum() == 0) continue;
            if (line.getLineStatus() != InventoryLineStatus.RESOLVED || !InventoryReviewService.isPostingDecision(line.getDecisionType()) || !Objects.equals(line.getDecisionCountRevision(), line.getCountRevision()) || line.getReviewBlocker() != null || line.getSourceCorrectionMovementId() != null)
                throw new BusinessException("UNRESOLVED_DIFFERENCES");
            InventoryReviewService.validateDecisionSign(line.getDecisionType(), line.getDifferenceQuantity());
            if (Set.of(InventoryExplanationStatus.REQUESTED, InventoryExplanationStatus.PROVIDED, InventoryExplanationStatus.REJECTED).contains(line.getExplanationStatus())) throw new BusinessException("EXPLANATION_PENDING");
            InventoryReviewService.reason(line.getDecisionComment()); evidencePolicy.validate(d, line.getId(), line.getDecisionEvidenceIds(), true);
            if (line.isFoundStock()) {
                if (line.getUnitCost() == null || line.getUnitCost().signum() <= 0 || !Objects.equals(line.getValuationCountRevision(), line.getCountRevision()) || line.getValuedBy() == null) throw new BusinessException("FOUND_STOCK_VALUATION_REQUIRED");
                InventoryReviewService.reason(line.getValuationBasis()); evidencePolicy.validate(d, line.getId(), line.getValuationEvidenceIds(), true);
            }
        }
    }
    public void markSubmitted(UUID id) {
        var d = revisions.lock(id);
        d.setApprovalInstanceId(instances.findByTenantIdAndCompanyIdAndModuleKeyAndEntityIdAndIsDeletedFalse(d.getTenantId(), d.getCompanyId(), MODULE_KEY, id).orElseThrow(() -> new BusinessException("Approval cycle is missing")).getId());
        d.setSubmittedCountRevision(d.getCountRevision()); d.setApprovedCountRevision(null);
        d.setCommissionApprovedRevision(null); d.setCommissionApprovedBy(null); d.setCommissionApprovedAt(null);
        d.setFinanceApprovedRevision(null); d.setFinanceApprovedBy(null); d.setFinanceApprovedAt(null);
        d.setStatus("WAITING_APPROVAL"); repository.save(d); audit.log("InventoryDocument", id, "SUBMIT_APPROVAL", "Count revision " + d.getCountRevision(), null, null);
    }
    public void lockApprovalRevision(UUID id, UUID instanceId, UUID taskId) {
        var d = revisions.lock(id); requireCurrent(d);
        if (!Objects.equals(d.getApprovalInstanceId(), instanceId)) throw new BusinessException("This approval cycle is stale");
        var instance = instances.findById(instanceId).orElseThrow(); em.refresh(instance);
        var task = tasks.findById(taskId).orElseThrow(); em.refresh(task);
        if (instance.isDeleted() || task.isDeleted() || instance.getApprovalStatus() != ApprovalInstanceStatus.PENDING || task.getTaskStatus() != ApprovalTaskStatus.PENDING || !instanceId.equals(task.getApprovalInstanceId()) || !Objects.equals(instance.getCurrentStepOrder(), task.getStepOrder())) throw new BusinessException("This approval task is stale");
    }
    public Boolean isApprovalTaskActive(UUID id) { var d = access.document(id); return "WAITING_APPROVAL".equals(d.getStatus()) && Objects.equals(d.getSubmittedCountRevision(), d.getCountRevision()) && d.getApprovalInstanceId() != null; }
    public void markApproved(UUID id, String comment) {
        var d = revisions.lock(id); requireCurrent(d); validateReviewed(d);
        d.setApprovedCountRevision(d.getCountRevision()); d.setStatus("APPROVED"); repository.save(d); audit.log("InventoryDocument", id, "APPROVE", comment, null, null);
    }
    public void markRejected(UUID id, String comment) { returned(id, comment); }
    public void onReturned(UUID id, String comment) { returned(id, comment); }
    public void onCancelled(UUID id, String comment) { returned(id, comment); }
    private void returned(UUID id, String comment) { var d = revisions.lock(id); requireCurrent(d); String reason = comment == null ? null : comment.trim(); revisions.invalidate(id, reason); d.setCorrectionReason(reason); d.setStatus("IN_PROGRESS"); repository.save(d); audit.log("InventoryDocument", id, "RETURN_FOR_CORRECTION", reason, null, null); }
    private void requireCurrent(InventoryDocument d) { if (!"WAITING_APPROVAL".equals(d.getStatus()) || !Objects.equals(d.getSubmittedCountRevision(), d.getCountRevision()) || d.getApprovalInstanceId() == null) throw new BusinessException("This inventory approval revision is stale"); }
    private List<InventoryItem> lines(InventoryDocument d) { return items.findByTenantIdAndInventoryDocumentIdAndIsDeletedFalse(d.getTenantId(), d.getId()); }
    public List<ApprovalDetailItem> documentLines(UUID id) {
        var d = access.document(id); access.requireVisibleExport(d);
        return lines(d).stream().map(l -> { var values = new LinkedHashMap<String,Object>(); values.put("systemQuantity", l.getSystemQuantity()); values.put("countedQuantity", l.getCountedQuantity()); values.put("differenceQuantity", l.getDifferenceQuantity()); values.put("unitCost", l.getUnitCost()); values.put("decision", l.getDecisionType()); values.put("reason", l.getDecisionComment()); values.put("countRevision", l.getCountRevision()); values.values().removeIf(Objects::isNull); return new ApprovalDetailItem(l.getId().toString(), l.getMaterial() == null ? l.getName() : l.getMaterial().getName(), values); }).toList();
    }
    public List<ApprovalDetailItem> evidence(UUID id) {
        var d = access.document(id); access.requireVisibleExport(d); var ids = new LinkedHashSet<UUID>();
        for (var l : lines(d)) { ids.addAll(evidencePolicy.validate(d, l.getId(), l.getFoundEvidenceIds(), false)); ids.addAll(evidencePolicy.validate(d, l.getId(), l.getDecisionEvidenceIds(), false)); ids.addAll(evidencePolicy.validate(d, l.getId(), l.getValuationEvidenceIds(), false)); }
        return ids.stream().map(file -> attachments.findById(file).orElseThrow()).map(a -> { var v = new LinkedHashMap<String,Object>(); v.put("fileName", a.getOriginalFileName()); v.put("contentType", a.getContentType()); v.put("sizeBytes", a.getSizeBytes()); v.put("uploadedAt", a.getUploadedAt()); v.put("downloadable", a.isDownloadable()); v.put("previewable", a.isPreviewable()); v.values().removeIf(Objects::isNull); return new ApprovalDetailItem(a.getId().toString(), a.getOriginalFileName(), v); }).toList();
    }
}
