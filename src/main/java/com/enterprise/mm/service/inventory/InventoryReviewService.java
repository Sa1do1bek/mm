package com.enterprise.mm.service.inventory;

import com.enterprise.mm.entity.*;
import com.enterprise.mm.enums.*;
import com.enterprise.mm.error.*;
import com.enterprise.mm.mapper.*;
import com.enterprise.mm.payload.*;
import com.enterprise.mm.payload.InventoryReviewDtos.*;
import com.enterprise.mm.repository.*;
import com.enterprise.mm.service.InventoryLineHistoryService;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional
public class InventoryReviewService {
    private final InventoryAccessService access;
    private final InventoryRevisionService revisions;
    private final InventoryEvidenceService evidence;
    private final InventoryItemRepository items;
    private final InventoryDocumentRepository documents;
    private final StockMovementRepository movements;
    private final InventoryItemMapper mapper;
    private final InventoryDocumentMapper documentMapper;
    private final InventoryLineHistoryService history;
    private final EntityManager em;

    public InventoryItemResponse decide(UUID id, Decision c) {
        if (c == null) throw new BadRequestException("A decision is required");
        var line = lockedLine(id); var d = revisions.lock(line.getInventoryDocumentId());
        access.requireCommissionReview(d); InventoryRevisionService.requireEditable(d);
        version(line, c.expectedLineVersion(), c.countRevision());
        String reason = reason(c.reason());
        InventoryDiscrepancyDecisionType decision;
        try { decision = InventoryDiscrepancyDecisionType.valueOf(c.decisionType()); } catch (RuntimeException e) { throw new BadRequestException("Choose a supported discrepancy decision"); }
        if (line.getCountedQuantity() == null || line.getDifferenceQuantity() == null || line.getDifferenceQuantity().signum() == 0) throw new BadRequestException("Only a counted discrepancy can be reviewed");
        validateDecisionSign(decision, line.getDifferenceQuantity());
        if (line.getSourceCorrectionMovementId() != null && isPostingDecision(decision)) throw new BusinessException("Source correction requires reconciliation before any stock adjustment; cancel and restart with a fresh snapshot if reconciliation is unavailable");
        if (Set.of(InventoryExplanationStatus.REQUESTED, InventoryExplanationStatus.PROVIDED, InventoryExplanationStatus.REJECTED).contains(line.getExplanationStatus())) throw new BusinessException("Finish the requested explanation review first");
        var files = evidence.validate(d, id, c.evidenceIds(), isPostingDecision(decision));
        boolean correction = decision == InventoryDiscrepancyDecisionType.CORRECT_SOURCE_OPERATION || decision == InventoryDiscrepancyDecisionType.CORRECT_PREVIOUS_DOCUMENT;
        if (correction) validateCorrection(d, line, c.sourceCorrectionMovementId());
        else if (c.sourceCorrectionMovementId() != null) throw new BadRequestException("A source movement belongs only to a source-correction decision");
        var before = snapshot(line); revisions.invalidate(d.getId(), "Discrepancy decision changed: " + reason);
        line.setDecisionType(decision); line.setDecisionCountRevision(line.getCountRevision()); line.setDecisionComment(reason);
        line.setDecisionEvidenceIds(files); line.setDecidedBy(access.username()); line.setDecidedAt(LocalDateTime.now());
        if (c.sourceCorrectionMovementId() != null) line.setSourceCorrectionMovementId(c.sourceCorrectionMovementId()); line.setReconciledSourceMovementId(null);
        line.setReviewBlocker(line.getSourceCorrectionMovementId() != null ? "SOURCE_CORRECTION_REQUIRES_RECONCILIATION" : isPostingDecision(decision) ? null : "DISCREPANCY_UNRESOLVED");
        line.setLineStatus(isPostingDecision(decision) ? InventoryLineStatus.RESOLVED : InventoryLineStatus.DISCREPANCY); line.setStatus(line.getLineStatus().name());
        d.setStatus(InventoryStatus.DISCREPANCY_REVIEW.name());
        return save("REVIEW_DECISION", before, line, reason);
    }

    public InventoryItemResponse requestRecount(UUID id, Recount c) {
        if (c == null) throw new BadRequestException("A recount reason and line version are required");
        var line = lockedLine(id); var d = revisions.lock(line.getInventoryDocumentId());
        access.requireCommissionReview(d); InventoryRevisionService.requireEditable(d); version(line, c.expectedLineVersion(), line.getCountRevision());
        String reason = reason(c.reason()); var before = snapshot(line);
        line.setCountRevision(revisions.invalidate(d.getId(), "Recount requested: " + reason));
        InventoryCountMutationService.clearReview(line);
        line.setCountedQuantity(null); line.setCountedAt(null); line.setCountedBy(null); line.setCountComment(null);
        line.setDifferenceQuantity(BigDecimal.ZERO); line.setDifferenceAmount(BigDecimal.ZERO); line.setDiscrepancyType(null);
        line.setRecountRequestedAt(LocalDateTime.now()); line.setRecountReason(reason);
        line.setLineStatus(InventoryLineStatus.NEEDS_RECOUNT); line.setStatus(line.getLineStatus().name());
        d.setStatus(InventoryStatus.IN_PROGRESS.name());
        return save("REQUEST_RECOUNT", before, line, reason);
    }

    public InventoryDocumentResponse returnForCorrection(UUID id, ReturnForCorrection c) {
        if (c == null) throw new BadRequestException("A return reason and document version are required");
        var d = revisions.lock(id); access.requireReview(d);
        if (!Objects.equals(d.getVersion(), c.expectedDocumentVersion())) throw new ConflictException("This count changed. Refresh before returning it.", "DOCUMENT_VERSION_CONFLICT");
        if (!Set.of("WAITING_APPROVAL", "APPROVED", "REJECTED", "COUNTING_COMPLETED", "DISCREPANCY_REVIEW").contains(d.getStatus())) throw new BusinessException("This count cannot be returned for correction");
        String reason = reason(c.reason()); revisions.invalidate(id, "Returned for correction: " + reason);
        d.setCorrectionReason(reason); d.setStatus(InventoryStatus.IN_PROGRESS.name());
        return documentMapper.toResponse(documents.saveAndFlush(d));
    }

    public InventoryItemResponse valueFoundStock(UUID id, Valuation c) {
        if (c == null) throw new BadRequestException("A valuation basis is required");
        var line = lockedLine(id); var d = revisions.lock(line.getInventoryDocumentId()); access.requireReview(d);
        if (!access.hasRole("ADMIN", "FINANCE_CONTROLLER", "ACCOUNTANT")) throw new org.springframework.security.access.AccessDeniedException("Finance valuation authority is required");
        InventoryRevisionService.requireEditable(d); version(line, c.expectedLineVersion(), c.countRevision());
        if (!line.isFoundStock() || line.getCountedQuantity() == null || line.getCountedQuantity().signum() <= 0) throw new BadRequestException("Only positively counted found stock requires a valuation");
        InventoryCountMutationService.validateQuantity(c.unitCost(), false, 4, false);
        if (c.unitCost().signum() <= 0) throw new BadRequestException("A positive evidenced unit cost is required");
        String reason = reason(c.reason()); var files = evidence.validate(d, id, c.evidenceIds(), true); var before = snapshot(line);
        revisions.invalidate(d.getId(), "Found-stock valuation changed");
        line.setUnitCost(c.unitCost()); line.setDifferenceAmount(line.getDifferenceQuantity().abs().multiply(c.unitCost()));
        line.setValuationBasis(reason); line.setValuationEvidenceIds(files); line.setValuationCountRevision(line.getCountRevision());
        line.setValuedBy(access.username()); line.setValuedAt(LocalDateTime.now());
        return save("VALUE_FOUND_STOCK", before, line, reason);
    }

    public InventoryItemResponse explanation(UUID id, InventoryExplanationRequest c, String action) {
        if (c == null) throw new BadRequestException("Explanation details and line version are required");
        var line = lockedLine(id); var d = revisions.lock(line.getInventoryDocumentId()); InventoryRevisionService.requireEditable(d);
        if ("SUBMIT_EXPLANATION".equals(action)) access.requireCount(d); else access.requireCommissionReview(d);
        version(line, c.getExpectedLineVersion(), c.getCountRevision());
        if (line.getCountedQuantity() == null || line.getDifferenceQuantity().signum() == 0) throw new BadRequestException("Explanation requires a counted discrepancy");
        var before = snapshot(line); String text;
        switch (action) {
            case "REQUEST_EXPLANATION" -> {
                text = reason(c.getReason()); line.setExplanationStatus(InventoryExplanationStatus.REQUESTED);
                line.setExplanationRequestedFrom(c.getRequestedFrom()); line.setExplanationRequestedAt(LocalDateTime.now()); line.setDiscrepancyReason(text);
            }
            case "SUBMIT_EXPLANATION" -> {
                if (!Set.of(InventoryExplanationStatus.REQUESTED, InventoryExplanationStatus.REJECTED).contains(line.getExplanationStatus())) throw new BusinessException("An explanation has not been requested");
                text = reason(c.getExplanation()); line.setExplanationText(text); line.setExplanationProvidedAt(LocalDateTime.now()); line.setExplanationStatus(InventoryExplanationStatus.PROVIDED);
            }
            case "ACCEPT_EXPLANATION", "REJECT_EXPLANATION" -> {
                if (line.getExplanationStatus() != InventoryExplanationStatus.PROVIDED) throw new BusinessException("Review a provided explanation first");
                text = reason(c.getReviewComment()); line.setExplanationReviewComment(text);
                line.setExplanationStatus("ACCEPT_EXPLANATION".equals(action) ? InventoryExplanationStatus.ACCEPTED : InventoryExplanationStatus.REJECTED);
            }
            default -> throw new BadRequestException("Unsupported explanation action");
        }
        revisions.invalidate(d.getId(), action + ": " + text);
        line.setDecisionType(null); line.setDecisionCountRevision(null); line.setDecisionEvidenceIds(List.of()); line.setDecisionComment(null); line.setDecidedBy(null); line.setDecidedAt(null);
        line.setReviewBlocker("DISCREPANCY_UNRESOLVED"); line.setLineStatus("ACCEPT_EXPLANATION".equals(action) ? InventoryLineStatus.DISCREPANCY : InventoryLineStatus.EXPLANATION_REQUIRED); line.setStatus(line.getLineStatus().name());
        d.setStatus(InventoryStatus.DISCREPANCY_REVIEW.name());
        return save(action, before, line, text);
    }

    private InventoryItem lockedLine(UUID id) {
        var line = access.line(id); revisions.lock(line.getInventoryDocumentId()); em.refresh(line, LockModeType.PESSIMISTIC_WRITE); return line;
    }
    private InventoryItemResponse save(String action, InventoryItem before, InventoryItem line, String reason) {
        items.saveAndFlush(line); history.record(action, before, line, reason, access.username());
        var d = revisions.lock(line.getInventoryDocumentId());
        d.setCountedItems((int) items.countByTenantIdAndInventoryDocumentIdAndCountedQuantityIsNotNullAndIsDeletedFalse(d.getTenantId(), d.getId()));
        d.setDiscrepancyItems((int) items.countByTenantIdAndInventoryDocumentIdAndDifferenceQuantityNotAndIsDeletedFalse(d.getTenantId(), d.getId(), BigDecimal.ZERO));
        documents.saveAndFlush(d);
        return InventoryReadPolicy.project(mapper.toResponse(line), access.canSeeSystemQuantity(d));
    }
    private void validateCorrection(InventoryDocument d, InventoryItem line, UUID id) {
        if (id == null) throw new BadRequestException("Link the posted source correction movement");
        var m = movements.findByIdAndIsDeletedFalse(id).filter(v -> d.getTenantId().equals(v.getTenantId()) && d.getCompanyId().equals(v.getCompanyId())).orElseThrow(() -> new BadRequestException("Source correction is outside this company"));
        if (!"POSTED".equals(m.getStatus()) || !Objects.equals(line.getMaterialId(), m.getMaterialId()) || !Objects.equals(line.getWarehouseId(), m.getWarehouseId())
            || !Objects.equals(line.getStorageLocationId(), m.getStorageLocationId()) || !Objects.equals(line.getStorageBinId(), m.getStorageBinId()) || !Objects.equals(line.getStockBatchId(), m.getStockBatchId())
            || m.getSourceInventoryItemId() != null || m.getReferenceId() == null) throw new BadRequestException("Source correction must be a posted, linked movement for this exact stock position");
    }
    public static InventoryItem snapshot(InventoryItem line) {
        var before = new InventoryItem(); before.setCountRevision(line.getCountRevision()); before.setCountedQuantity(line.getCountedQuantity()); before.setDifferenceQuantity(line.getDifferenceQuantity()); before.setLineStatus(line.getLineStatus()); return before;
    }
    public static void version(InventoryItem line, Long version, Long revision) {
        if (!Objects.equals(line.getVersion(), version) || !Objects.equals(line.getCountRevision(), revision)) throw new ConflictException("This observation changed. Review the current count before continuing.", "LINE_VERSION_CONFLICT");
    }
    public static String reason(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 1000) throw new BadRequestException("Enter a reason of 1 to 1000 characters"); return value.trim();
    }
    public static boolean isPostingDecision(InventoryDiscrepancyDecisionType d) { return d == InventoryDiscrepancyDecisionType.WRITE_OFF_SHORTAGE || d == InventoryDiscrepancyDecisionType.POST_SURPLUS; }
    public static void validateDecisionSign(InventoryDiscrepancyDecisionType d, BigDecimal difference) {
        if (difference == null || difference.signum() == 0 || d == InventoryDiscrepancyDecisionType.WRITE_OFF_SHORTAGE && difference.signum() >= 0 || d == InventoryDiscrepancyDecisionType.POST_SURPLUS && difference.signum() <= 0)
            throw new BadRequestException("The stock decision must match the observed shortage or surplus");
    }
}
