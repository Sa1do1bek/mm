package com.enterprise.mm.service.transfer;

import com.enterprise.mm.common.*;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.enums.*;
import com.enterprise.mm.error.*;
import com.enterprise.mm.repository.*;
import com.enterprise.mm.service.RoleDataScopeService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Shared case state only: deliberately independent of posting and the approval engine. */
@Service @RequiredArgsConstructor
public class TransferExceptionStateService {
    private final TransferExceptionRepository cases;
    private final TransferDocumentRepository transfers;
    private final QualityQuarantineLotRepository lots;
    private final AttachmentRepository attachments;
    private final ApprovalInstanceRepository instances;
    private final ApprovalTaskRepository tasks;
    private final RoleDataScopeService scopes;
    private final EntityManager entityManager;
    private final TransferProgressService progress;
    private final UserRepository users;
    public static boolean open(TransferException c) { return !Set.of("RESOLVED", "POSTED", "RETURNED").contains(c.getStatus()); }
    public static String actor() {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null || !auth.isAuthenticated()) throw new AccessDeniedException("Authentication is required");
        return auth.getName();
    }
    public TransferDocument transfer(UUID id, boolean lock) {
        var t=(lock ? transfers.findScopedForUpdate(id,TenantContext.currentTenant(),CompanyContext.currentCompany()) : transfers.findByIdAndIsDeletedFalse(id))
            .filter(e->TenantContext.currentTenant().equals(e.getTenantId()) && CompanyContext.currentCompany().equals(e.getCompanyId()))
            .orElseThrow(()->new NotFoundException("Transfer not found"));
        if(!scopes.canAccessWarehouse(t.getSourceWarehouseId()) && !scopes.canAccessWarehouse(t.getDestinationWarehouseId()))
            throw new AccessDeniedException("Transfer warehouse authority is required");
        return t;
    }
    public TransferException get(UUID id) {
        var c=cases.findById(id).filter(e->!e.isDeleted() && TenantContext.currentTenant().equals(e.getTenantId()) && CompanyContext.currentCompany().equals(e.getCompanyId()))
            .orElseThrow(()->new NotFoundException("Transfer exception not found"));
        transfer(c.getTransferDocumentId(),false); return c;
    }
    public TransferException lock(UUID id) {
        var c=get(id); transfer(c.getTransferDocumentId(),true); entityManager.refresh(c); return c;
    }
    /** Refreshes the engine's managed objects after acquiring the document lock. */
    public void refreshApprovalTask(UUID instanceId, UUID taskId) {
        var instance=instances.findById(instanceId).orElseThrow(()->new BusinessException("Approval cycle is stale"));entityManager.refresh(instance);
        var task=tasks.findById(taskId).orElseThrow(()->new BusinessException("Approval task is stale"));entityManager.refresh(task);
        if(instance.isDeleted() || instance.getApprovalStatus()!=ApprovalInstanceStatus.PENDING || task.isDeleted()
            || task.getTaskStatus()!=ApprovalTaskStatus.PENDING || !instance.getId().equals(task.getApprovalInstanceId())
            || !Objects.equals(instance.getCurrentStepOrder(),task.getStepOrder())) throw new BusinessException("Approval task is stale");
    }
    public List<TransferException> forTransfer(UUID id) {
        transfer(id,false); return cases.findByTenantIdAndCompanyIdAndTransferDocumentIdAndIsDeletedFalseOrderByCreatedAtAscIdAsc(TenantContext.currentTenant(),CompanyContext.currentCompany(),id);
    }
    public List<TransferException> forAllocation(UUID id) {
        return cases.findByTenantIdAndCompanyIdAndAllocationIdAndIsDeletedFalseOrderByCreatedAtAscIdAsc(TenantContext.currentTenant(),CompanyContext.currentCompany(),id);
    }
    public List<UUID> validateEvidence(UUID transferId,List<UUID> ids) {
        if(ids!=null && ids.stream().anyMatch(Objects::isNull)) throw new BusinessException("Evidence attachment is required");
        var result=ids==null?List.<UUID>of():List.copyOf(new LinkedHashSet<>(ids));
        for(var id:result) {
            var a=attachments.findById(id).filter(e->!e.isDeleted() && TenantContext.currentTenant().equals(e.getTenantId()) && CompanyContext.currentCompany().equals(e.getCompanyId()))
                .orElseThrow(()->new BusinessException("Evidence attachment is outside the current company"));
            if(!"TRANSFER_DOCUMENT".equals(a.getEntityType()) || !transferId.equals(a.getEntityId()))
                throw new BusinessException("Evidence must be attached to this transfer");
        }
        return result;
    }
    public List<Attachment> evidence(UUID caseId) {
        var c=get(caseId);
        return validateEvidence(c.getTransferDocumentId(),c.getAttachmentIds()).stream()
            .map(id->attachments.findById(id).orElseThrow()).toList();
    }
    public void history(TransferException c,String action,String reason) {
        var h=new ArrayList<>(c.getHistory());
        String actorName=actor();
        String displayName=users.resolveActiveUserByUsername(TenantContext.currentTenant(),CompanyContext.currentCompany(),actorName)
            .map(User::getFullName).filter(n->!n.isBlank()).orElse(actorName);
        h.add(new TransferException.History(c.getRevision(),action,actorName,LocalDateTime.now(),c.getQuantity(),c.getBookValue(),reason,List.copyOf(c.getAttachmentIds()),displayName));
        c.setHistory(h); c.setUpdatedAt(LocalDateTime.now());
    }
    public void invalidateApproval(TransferException c,String reason) {
        if(c.getApprovalInstanceId()!=null) instances.findById(c.getApprovalInstanceId()).ifPresent(i->{
            if (i.getApprovalStatus()==ApprovalInstanceStatus.PENDING) { i.setApprovalStatus(ApprovalInstanceStatus.CANCELLED); i.setStatus("CANCELLED"); }
            i.setDeleted(true); instances.saveAndFlush(i);
            tasks.findByTenantIdAndApprovalInstanceIdAndIsDeletedFalseOrderByStepOrder(c.getTenantId(),i.getId()).stream()
                .filter(t->t.getTaskStatus()==ApprovalTaskStatus.PENDING).forEach(t->{t.setTaskStatus(ApprovalTaskStatus.CANCELLED);t.setCommentText(reason);tasks.save(t);});
        });
        c.setApprovalInstanceId(null); c.setApprovalRevision(null); c.setFinanceApprovedBy(null); c.setReviewedBy(null); c.setReviewedAt(null);
        c.setDecision(null); c.setRevision(c.getRevision()+1);
    }
    public void onPhysicalClearance(TransferShipmentAllocation origin,TransferAllocationClearance clearance,StockMovement movement,UUID selectedCaseId) {
        if(clearance.getKind()==TransferAllocationClearance.Kind.TRANSIT_LOSS) return;
        var allocationCases = forAllocation(origin.getId());
        if (selectedCaseId != null && (clearance.getKind() != TransferAllocationClearance.Kind.TRANSIT_RETURN || allocationCases.stream().noneMatch(c ->
            selectedCaseId.equals(c.getId()) && open(c) && "MISSING".equals(c.getKind()) && "RETURN_TO_SOURCE".equals(c.getDecision())
                && clearance.getQuantity().compareTo(c.getQuantity()) <= 0)))
            throw new BusinessException("Physical return does not match its outstanding missing-stock case");
        BigDecimal remaining=clearance.getQuantity();
        for(var c:allocationCases) {
            if(!open(c) || !"MISSING".equals(c.getKind())) continue;
            // Allocation units are fungible: conservatively invalidate the loss proposal on every physical clearance.
            boolean continuingReturn = clearance.getKind()==TransferAllocationClearance.Kind.TRANSIT_RETURN && "RETURN_TO_SOURCE".equals(c.getDecision());
            String reviewedBy=c.getReviewedBy(); LocalDateTime reviewedAt=c.getReviewedAt();
            invalidateApproval(c,"Physical stock was found or returned");
            if(continuingReturn){c.setDecision("RETURN_TO_SOURCE");c.setReviewedBy(reviewedBy);c.setReviewedAt(reviewedAt);}
            // A confirmed case return belongs only to that case; other proposals still need fresh approval.
            BigDecimal found=selectedCaseId == null || selectedCaseId.equals(c.getId()) ? remaining.min(c.getQuantity()) : BigDecimal.ZERO;
            if(found.signum()>0) {
                BigDecimal value=TransferValueSplit.take(c.getQuantity(),c.getBookValue(),found);
                c.setQuantity(c.getQuantity().subtract(found)); c.setBookValue(c.getBookValue().subtract(value)); remaining=remaining.subtract(found);
            }
            c.setStatus(c.getQuantity().signum()==0?"RESOLVED":continuingReturn?"REVIEWED":"OPEN");
            var links=new ArrayList<>(c.getRelatedDocumentIds()); links.add(movement.getId()); c.setRelatedDocumentIds(links);
            history(c,"PHYSICAL_CLEARANCE","Physical receipt/return supersedes the unposted loss proposal"); cases.saveAndFlush(c);
        }
        if(clearance.getKind()!=TransferAllocationClearance.Kind.QUARANTINE_RECEIPT) return;
        var lot=new QualityQuarantineLot(); lot.setName("Transfer quarantine");lot.setStatus("QUARANTINED");
        lot.setQuarantineNumber("TQ-"+UUID.randomUUID().toString().substring(0,8));
        lot.setTransferAllocationId(origin.getId());lot.setTransferClearanceId(clearance.getId());
        lot.setMaterialId(origin.getMaterialId());lot.setWarehouseId(movement.getWarehouseId());lot.setStorageLocationId(movement.getStorageLocationId());
        lot.setStockBatchId(movement.getStockBatchId());lot.setStockMovementId(movement.getId());
        lot.setQuarantinedQuantity(clearance.getQuantity());lot.setRemainingQuantity(clearance.getQuantity());lot.setQuarantineStartedAt(LocalDateTime.now());
        lots.saveAndFlush(lot);
        var c=new TransferException(); c.setName("Damaged transfer receipt");c.setStatus("OPEN");c.setKind("DAMAGED");
        c.setTransferDocumentId(origin.getTransferDocumentId());c.setAllocationId(origin.getId());c.setQuantity(clearance.getQuantity());c.setBookValue(clearance.getBookValue());
        c.setReason(movement.getDescription()==null || movement.getDescription().isBlank()?"Damaged stock received into quarantine":movement.getDescription());lot.setQuarantineReason(c.getReason());c.setQuarantineClearanceId(clearance.getId());c.setQuarantineLotId(lot.getId());
        c.setRelatedDocumentIds(new ArrayList<>(List.of(movement.getId())));history(c,"REPORTED",c.getReason());cases.saveAndFlush(c);
    }
    public void disposition(TransferException c,BigDecimal quantity,String disposition) {
        if(c.getQuarantineLotId()==null) return;
        var lot=lots.findCompanyLotForUpdate(c.getTenantId(),c.getCompanyId(),c.getQuarantineLotId()).orElseThrow();
        if(quantity.compareTo(lot.getRemainingQuantity())>0) throw new BusinessException("Disposition exceeds the linked quarantine lot");
        lot.setRemainingQuantity(lot.getRemainingQuantity().subtract(quantity));lot.setDisposition(disposition);
        if("RELEASE".equals(disposition)) lot.setReleasedQuantity(lot.getReleasedQuantity().add(quantity));
        else lot.setRejectedQuantity(lot.getRejectedQuantity().add(quantity));
        if(lot.getRemainingQuantity().signum()==0) {lot.setStatus("RELEASE".equals(disposition)?"RELEASED":"REJECTED");lot.setClosedAt(LocalDateTime.now());}
        lots.save(lot);
    }
    public void attachReceiptEvidence(UUID allocationId, UUID movementId, List<UUID> evidence) {
        for(var c:forAllocation(allocationId)) if(c.getRelatedDocumentIds().contains(movementId) && "DAMAGED".equals(c.getKind())) {
            c.setAttachmentIds(evidence);history(c,"RECEIPT_EVIDENCE",c.getReason());save(c);
        }
    }
    public void requireEvidenceDeletionAllowed(UUID transferId) {
        if(forTransfer(transferId).stream().anyMatch(c->!c.getAttachmentIds().isEmpty()))
            throw new BusinessException("Evidence linked to a transfer exception must be retained");
    }
    public TransferException save(TransferException c){
        var saved=cases.saveAndFlush(c);progress.refresh(transfer(c.getTransferDocumentId(),true));return saved;
    }
}
