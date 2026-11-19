package com.enterprise.mm.service.transfer;

import com.enterprise.mm.common.*;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.error.*;
import com.enterprise.mm.payload.TransferExceptionDtos.*;
import com.enterprise.mm.repository.*;
import com.enterprise.mm.service.*;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional
public class TransferExceptionService {
    private final TransferExceptionStateService state;
    private final TransferShipmentAllocationRepository allocations;
    private final TransferAllocationClearanceRepository clearances;
    private final TransferTransitLedgerService transit;
    private final TransferPostingService posting;
    private final TransferReturnService returns;
    private final StockMovementRepository movements;
    private final TransferDocumentRepository transfers;
    private final IdempotencyService idempotency;
    private final FinancialAuthorizationService finance;
    private final RoleDataScopeService scopes;
    private final ApprovalEngineService approvals;
    public record DecisionState(long revision,long approvalRevision,boolean warehouseReviewed,boolean financeApproved) {
        public boolean canPostLoss(){return warehouseReviewed && financeApproved && revision==approvalRevision;}
    }
    public List<CaseResponse> list(UUID transferId){return state.forTransfer(transferId).stream().map(this::response).toList();}
    public CaseResponse get(UUID id){return response(state.get(id));}
    public CaseResponse report(UUID transferId,Report command,String key) {
        warehouse(false);var t=state.transfer(transferId,true);requiredKey(key);
        return masked(idempotency.execute("TRANSFER_EXCEPTION_REPORT",transferId,key,command,CaseResponse.class,()->{
            requireActive(t);version(t,command.expectedVersion());quantity(command.quantity());reason(command.reason());
            if (t.getReturnOfTransferId()!=null) throw new BusinessException("Use the original exception to resolve return transit");
            if(!"MISSING".equals(command.kind())) throw new BusinessException("Damaged cases are created by a physical quarantine receipt");
            var a=allocation(command.allocationId(),transferId);
            quantityScale(command.quantity(), a.getQuantityScale());
            if(a.getSerialNumber()!=null && !a.getSerialNumber().isBlank() && command.quantity().stripTrailingZeros().scale()>0) throw new BadRequestException("Serialized stock requires a whole quantity");
            var balance=transit.balance(a.getId());
            var claimed=state.forAllocation(a.getId()).stream().filter(TransferExceptionStateService::open).filter(c->"MISSING".equals(c.getKind()))
                .map(TransferException::getQuantity).reduce(BigDecimal.ZERO,BigDecimal::add);
            if(command.quantity().add(claimed).compareTo(balance.quantity())>0) throw new BusinessException("Quantity overlaps an existing case or exceeds unresolved transit");
            var c=new TransferException();c.setTransferDocumentId(transferId);c.setAllocationId(a.getId());c.setKind("MISSING");c.setName("Missing transfer stock");c.setStatus("OPEN");
            c.setQuantity(command.quantity());c.setBookValue(TransferValueSplit.take(balance.quantity(),balance.bookValue(),command.quantity()));c.setReason(command.reason().trim());
            c.setAttachmentIds(state.validateEvidence(transferId,command.attachmentIds()));state.history(c,"REPORTED",c.getReason());state.save(c);
            t.setStatus("DISCREPANCY_REVIEW");t.setUpdatedAt(LocalDateTime.now());transfers.saveAndFlush(t);return response(c);
        }));
    }
    public CaseResponse review(UUID id,Review command,String key) {
        warehouse(true);var c=state.lock(id);requiredKey(key);
        return masked(idempotency.execute("TRANSFER_EXCEPTION_REVIEW",id,key,command,CaseResponse.class,()->{
            version(c,command.expectedVersion());if(!TransferExceptionStateService.open(c)||"RETURN_IN_TRANSIT".equals(c.getStatus())) throw new ConflictException("Case is no longer reviewable");
            reason(command.reason());if(command.decision()==null || !Set.of("CONFIRM_LOSS","RETURN_TO_SOURCE","RELEASE_AFTER_QUALITY","KEEP_INVESTIGATING").contains(command.decision())) throw new BadRequestException("Choose a supported disposition");
            if("RELEASE_AFTER_QUALITY".equals(command.decision()) && !"DAMAGED".equals(c.getKind())) throw new BusinessException("Only physically received quarantine can be released");
            if ("MISSING".equals(c.getKind())) {
                var remaining = transit.balance(c.getAllocationId());
                if (c.getQuantity().compareTo(remaining.quantity())>0) throw new BusinessException("Case quantity exceeds remaining transit");
                c.setBookValue(TransferValueSplit.take(remaining.quantity(),remaining.bookValue(),c.getQuantity()));
            }
            state.invalidateApproval(c,"Disposition changed");c.setDecision(command.decision());c.setReviewedBy(TransferExceptionStateService.actor());c.setReviewedAt(LocalDateTime.now());
            c.setStatus("KEEP_INVESTIGATING".equals(command.decision())?"OPEN":"REVIEWED");state.history(c,"WAREHOUSE_REVIEW",command.reason());return response(state.save(c));
        }));
    }
    public CaseResponse submit(UUID id,Post command,String key) {
        warehouse(true);var c=state.lock(id);requiredKey(key);
        return masked(idempotency.execute("TRANSFER_EXCEPTION_SUBMIT",id,key,command,CaseResponse.class,()->{
            version(c,command.expectedVersion());approvals.start("TRANSFER_EXCEPTION",id);return response(state.get(id));
        }));
    }
    public CaseResponse evidence(UUID id,Report command,String key){
        warehouse(false);var c=state.lock(id);requiredKey(key);
        return masked(idempotency.execute("TRANSFER_EXCEPTION_EVIDENCE",id,key,command,CaseResponse.class,()->{
            version(c,command.expectedVersion());if(!TransferExceptionStateService.open(c)||"RETURN_IN_TRANSIT".equals(c.getStatus())) throw new ConflictException("Case cannot be changed");reason(command.reason());
            state.invalidateApproval(c,"Evidence updated");var ids=new LinkedHashSet<>(c.getAttachmentIds());ids.addAll(state.validateEvidence(c.getTransferDocumentId(),command.attachmentIds()));
            c.setAttachmentIds(new ArrayList<>(ids));c.setReason(command.reason());c.setStatus("OPEN");state.history(c,"EVIDENCE_UPDATED",command.reason());return response(state.save(c));
        }));
    }
    public CaseResponse post(UUID id,Post command,String key) {
        var c=state.lock(id);requiredKey(key);var t=state.transfer(c.getTransferDocumentId(),false);
        if("CONFIRM_LOSS".equals(c.getDecision())) finance.requireFinance("post a transfer loss");else warehouse(true);
        return masked(idempotency.execute("TRANSFER_EXCEPTION_POST",id,key,command,CaseResponse.class,()->{
            requireActive(t);version(c,command.expectedVersion());if(command.postingDate()==null) throw new BusinessException("Actual posting date is required");
            boolean loss="CONFIRM_LOSS".equals(c.getDecision());
            if(loss) {
                if(!"APPROVED".equals(c.getStatus()) || !new DecisionState(c.getRevision(),c.getApprovalRevision()==null?-1:c.getApprovalRevision(),c.getReviewedBy()!=null,c.getFinanceApprovedBy()!=null).canPostLoss())
                    throw new BusinessException("Current independent finance approval is required before posting loss");
                if ("MISSING".equals(c.getKind())) {
                    var remaining = transit.balance(c.getAllocationId());
                    if (c.getQuantity().compareTo(remaining.quantity())>0 || TransferValueSplit.take(remaining.quantity(),remaining.bookValue(),c.getQuantity()).compareTo(c.getBookValue())!=0)
                        throw new BusinessException("Remaining quantity/value changed; review and approve the loss again");
                }
                state.validateEvidence(t.getId(),c.getAttachmentIds());if(c.getAttachmentIds().isEmpty()) throw new BusinessException("Loss evidence is required");
            } else if(!"REVIEWED".equals(c.getStatus()) || !"RELEASE_AFTER_QUALITY".equals(c.getDecision())) throw new BusinessException("Quality release review is required");
            var a=allocation(c.getAllocationId(),t.getId());var m=movement(c,t,a);m.setQuantity(c.getQuantity());m.setMovementDate(command.postingDate());
            var kind=loss?("MISSING".equals(c.getKind())?TransferPostingContext.Kind.TRANSIT_LOSS:TransferPostingContext.Kind.QUARANTINE_WRITE_OFF):TransferPostingContext.Kind.QUARANTINE_RELEASE;
            var result=posting.post(new TransferPostingContext(t.getId(),a.getShipmentId(),a.getId(),kind),m);
            state.disposition(c,c.getQuantity(),loss?"WRITE_OFF":"RELEASE");var links=new ArrayList<>(c.getRelatedDocumentIds());links.add(result.movementId());c.setRelatedDocumentIds(links);
            c.setStatus(loss?"POSTED":"RESOLVED");state.history(c,loss?"LOSS_POSTED":"QUALITY_RELEASED",c.getReason());return response(state.save(c));
        }));
    }
    public CaseResponse dispatchReturn(UUID id,Post command,String key){
        warehouse(true);var c=state.lock(id);requiredKey(key);
        return masked(idempotency.execute("TRANSFER_EXCEPTION_RETURN_DISPATCH",id,key,command,CaseResponse.class,()->{
            version(c,command.expectedVersion());if(!"REVIEWED".equals(c.getStatus()) || !"RETURN_TO_SOURCE".equals(c.getDecision()) || !"DAMAGED".equals(c.getKind())) throw new BusinessException("A reviewed damaged return is required");
            var reverse=returns.dispatch(c,command.postingDate(),key);c.setReverseTransferId(reverse);c.setStatus("RETURN_IN_TRANSIT");state.disposition(c,c.getQuantity(),"RETURN_TO_SOURCE");
            var links=new ArrayList<>(c.getRelatedDocumentIds());links.add(reverse);c.setRelatedDocumentIds(links);state.history(c,"RETURN_DISPATCHED","Physical quarantine return dispatched");return response(state.save(c));
        }));
    }
    public CaseResponse confirmReturn(UUID id,ReturnReceipt command,String key) {
        warehouse(false);var c=state.lock(id);requiredKey(key);var t=state.transfer(c.getTransferDocumentId(),false);
        if(!scopes.canAccessWarehouse(t.getSourceWarehouseId())) throw new AccessDeniedException("Source warehouse authority is required to confirm a return");
        return masked(idempotency.execute("TRANSFER_EXCEPTION_RETURN_RECEIVE",id,key,command,CaseResponse.class,()->{
            version(c,command.expectedVersion());quantity(command.quantity());quantityScale(command.quantity(), allocation(c.getAllocationId(),t.getId()).getQuantityScale());if(command.quantity().compareTo(c.getQuantity())>0) throw new BusinessException("Return exceeds outstanding case quantity");
            if(!"RETURN_TO_SOURCE".equals(c.getDecision()) || !Set.of("REVIEWED","RETURN_IN_TRANSIT").contains(c.getStatus())) throw new BusinessException("A reviewed physical return is required");
            if("DAMAGED".equals(c.getKind())) {
                if(c.getReverseTransferId()==null) throw new BusinessException("Dispatch quarantine stock before confirming its return");
                returns.receive(c,command,key);BigDecimal value=TransferValueSplit.take(c.getQuantity(),c.getBookValue(),command.quantity());c.setBookValue(c.getBookValue().subtract(value));c.setQuantity(c.getQuantity().subtract(command.quantity()));
            } else {
                var a=allocation(c.getAllocationId(),t.getId());var m=movement(c,t,a);m.setQuantity(command.quantity());m.setMovementDate(command.receivedDate());m.setStorageLocationId(command.storageLocationId());m.setStorageBinId(command.storageBinId());
                posting.post(new TransferPostingContext(t.getId(),a.getShipmentId(),a.getId(),TransferPostingContext.Kind.TRANSIT_RETURN,null,c.getId()),m);
            }
            if(c.getQuantity().signum()==0)c.setStatus("RETURNED");
            state.history(c,"RETURN_RECEIVED","Source warehouse confirmed physical receipt");return response(state.save(c));
        }));
    }
    private StockMovement movement(TransferException c,TransferDocument t,TransferShipmentAllocation a){
        var m=new StockMovement();m.setMaterialId(a.getMaterialId());
        if("MISSING".equals(c.getKind())) {m.setWarehouseId(t.getSourceWarehouseId());m.setStorageLocationId(a.getSourceStorageLocationId());m.setStorageBinId(a.getSourceStorageBinId());}
        else {
            var receipt=clearances.findById(c.getQuarantineClearanceId()).orElseThrow();var original=movements.findById(receipt.getMovementId()).orElseThrow();
            m.setWarehouseId(t.getDestinationWarehouseId());m.setStorageLocationId(original.getStorageLocationId());m.setStorageBinId(original.getStorageBinId());m.setStockBatchId(receipt.getDestinationStockBatchId());
        }return m;
    }
    private TransferShipmentAllocation allocation(UUID id,UUID transferId){if(id==null)throw new BadRequestException("Choose a shipment allocation");return allocations.findById(id).filter(a->a.getTransferDocumentId().equals(transferId)&&a.getTenantId().equals(TenantContext.currentTenant())&&a.getCompanyId().equals(CompanyContext.currentCompany())).orElseThrow(()->new BusinessException("Allocation does not belong to this transfer"));}
    private void warehouse(boolean review){if(!TransferActionPolicy.hasRole("ADMIN","MM_MANAGER","WAREHOUSE_MANAGER","WAREHOUSE_HEAD") && (review || !TransferActionPolicy.hasRole("WAREHOUSE_OPERATOR","WAREHOUSE_KEEPER"))) throw new AccessDeniedException("Warehouse authority is required");}
    private void requireActive(TransferDocument t){if(Set.of("CLOSED","CANCELLED","DRAFT","SUBMITTED").contains(t.getStatus())) throw new ConflictException("Transfer is not open for physical operations");}
    private static void requiredKey(String key){if(key==null||key.isBlank()||key.length()>160)throw new BadRequestException("A valid Idempotency-Key is required");}
    private static void version(BusinessEntity c,Long expected){if(expected==null||!expected.equals(c.getVersion()))throw new ConflictException("This record changed; reload and review your draft");}
    private static void reason(String s){if(s==null||s.isBlank())throw new BadRequestException("Reason is required");}
    private static void quantity(BigDecimal q){if(q==null||q.signum()<=0||q.stripTrailingZeros().scale()>StockUomPolicy.MAX_QUANTITY_SCALE||q.precision()-q.scale()>15)throw new BadRequestException("Enter a positive quantity with at most seven decimals");}
    private static void quantityScale(BigDecimal q, int scale) { if(q.stripTrailingZeros().scale()>scale) throw new BadRequestException("Quantity exceeds the shipment unit's allowed decimal precision"); }
    private CaseResponse response(TransferException c){
        String owner=switch(c.getStatus()){case "PENDING_APPROVAL","APPROVED"->"FINANCE_CONTROLLER";case "RETURN_IN_TRANSIT"->"SOURCE_WAREHOUSE";case "POSTED","RESOLVED","RETURNED"->"NONE";default->"WAREHOUSE_MANAGER";};
        return masked(new CaseResponse(c.getId(),c.getVersion(),c.getStatus(),owner,c.getQuantity(),c.getBookValue(),c.getRelatedDocumentIds(),c.getTransferDocumentId(),c.getAllocationId(),c.getKind(),c.getDecision(),c.getReason(),c.getAttachmentIds(),c.getRevision(),c.getApprovalInstanceId(),c.getReverseTransferId(),c.getQuarantineLotId(),c.getHistory(),List.of()));
    }
    public static List<String> caseActions(String status,String decision,String kind,boolean manager,boolean warehouse,boolean finance,boolean source) {
        var actions=new ArrayList<String>();
        boolean active=!Set.of("POSTED","RESOLVED","RETURNED","RETURN_IN_TRANSIT").contains(status);
        if(active && manager) actions.add("REVIEW");
        if(active && warehouse) actions.add("EVIDENCE");
        if("REVIEWED".equals(status) && "CONFIRM_LOSS".equals(decision) && manager) actions.add("SUBMIT");
        if(("APPROVED".equals(status) && "CONFIRM_LOSS".equals(decision) && finance) || ("REVIEWED".equals(status) && "RELEASE_AFTER_QUALITY".equals(decision) && manager)) actions.add("POST");
        if("REVIEWED".equals(status) && "RETURN_TO_SOURCE".equals(decision) && "DAMAGED".equals(kind) && manager) actions.add("DISPATCH_RETURN");
        if(source && warehouse && "RETURN_TO_SOURCE".equals(decision) && ("RETURN_IN_TRANSIT".equals(status) || ("REVIEWED".equals(status) && "MISSING".equals(kind)))) actions.add("CONFIRM_RETURN");
        return List.copyOf(actions);
    }
    private CaseResponse masked(CaseResponse c){
        var t=state.transfer(c.transferId(),false);
        boolean manager=TransferActionPolicy.hasRole("ADMIN","MM_MANAGER","WAREHOUSE_MANAGER","WAREHOUSE_HEAD");
        boolean warehouse=manager || TransferActionPolicy.hasRole("WAREHOUSE_OPERATOR","WAREHOUSE_KEEPER");
        boolean financial=finance.hasAnyRole("ADMIN","FINANCE_CONTROLLER","ACCOUNTANT");
        var actions=caseActions(c.status(),c.decision(),c.kind(),manager,warehouse,financial,scopes.canAccessWarehouse(t.getSourceWarehouseId()));
        return new CaseResponse(c.id(),c.version(),c.status(),c.nextOwner(),c.quantity(),financial?c.bookValue():null,c.relatedDocumentIds(),c.transferId(),c.allocationId(),c.kind(),c.decision(),c.reason(),c.attachmentIds(),c.revision(),c.approvalInstanceId(),c.reverseTransferId(),c.quarantineLotId(),financial?c.history():c.history().stream().map(h->new TransferException.History(h.revision(),h.action(),h.actor(),h.at(),h.quantity(),null,h.reason(),h.attachmentIds(),h.actorDisplayName())).toList(),actions);
    }
}
