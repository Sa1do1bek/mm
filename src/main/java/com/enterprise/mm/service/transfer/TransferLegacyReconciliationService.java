package com.enterprise.mm.service.transfer;

import com.enterprise.mm.common.*;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.error.*;
import com.enterprise.mm.payload.TransferDocumentResponse;
import com.enterprise.mm.payload.TransferLegacyReconciliationDtos.*;
import com.enterprise.mm.repository.*;
import com.enterprise.mm.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.*;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional
public class TransferLegacyReconciliationService {
    private final TransferExceptionStateService state;
    private final TransferDocumentRepository transfers;
    private final TransferDocumentLineRepository lines;
    private final StockMovementRepository movements;
    private final StockMovementAllocationRepository stockAllocations;
    private final StockMovementAllocationCostLayerRepository lineages;
    private final InventoryCostLayerRepository costs;
    private final StockBatchRepository batches;
    private final TransferShipmentRepository shipments;
    private final TransferShipmentAllocationRepository allocations;
    private final TransferAllocationClearanceRepository clearances;
    private final TransferTransitLedgerService transit;
    private final TransferProgressService progress;
    private final TransferDocumentService documents;
    private final IdempotencyService idempotency;
    private final RoleDataScopeService scopes;
    private final AuditService audit;
    private final ObjectMapper json;

    public static boolean canAutoReconcile(int matchingDispatches,int completeLineageRecords,int expectedLineageRecords,boolean balanced) {
        return matchingDispatches==1 && expectedLineageRecords>0 && completeLineageRecords==expectedLineageRecords && balanced;
    }
    private record Source(TransferDocumentLine line,StockMovement movement,StockMovementAllocation stock,StockMovementAllocationCostLayer cost,StockBatch batch) {}
    private record Receipt(int source,StockMovement movement,StockMovementAllocation stock,BigDecimal quantity,BigDecimal value) {}
    private record Proof(List<StockMovement> movements,List<Source> sources,List<Receipt> receipts,List<String> blockers) {}

    @Transactional(readOnly=true)
    public ReconciliationPreview inspect(UUID transferId) {
        var t=state.transfer(transferId,false);
        if(terminal(t)) return preview("HISTORICAL_READ_ONLY",List.of(),List.of(),canConfirm(t));
        if(hasFacts(t)) return progress.describe(t).progress().closeBlockers().contains("LEGACY_RECONCILIATION_REQUIRED") ? preview("RECONCILIATION_REQUIRED",List.of(),List.of("LEGACY_PARTIAL_SHIPMENT_HISTORY"),false) : preview("RECONCILED",List.of(),List.of(),false);
        if(!legacyActivity(t)) return preview("NOT_REQUIRED",List.of(),List.of(),false);
        var proof=prove(t,List.of());
        return preview(proof.blockers().isEmpty()?"READY_TO_RECONCILE":"RECONCILIATION_REQUIRED",proof.movements(),proof.blockers(),canConfirm(t));
    }
    public TransferDocumentResponse confirm(UUID transferId,ReconciliationCommand command,String key) {
        var t=state.transfer(transferId,true);
        if(!canConfirm(t)) throw new AccessDeniedException("Scoped warehouse manager or finance authority is required");
        if(key==null || key.isBlank() || key.length()>160) throw new BadRequestException("Idempotency-Key is required");
        idempotency.execute("TRANSFER_LEGACY_RECONCILIATION",transferId,key,command,Boolean.class,()->{
            if(terminal(t)) throw new BusinessException("Closed transfer history is read-only");
            if(command.expectedVersion()==null || !command.expectedVersion().equals(t.getVersion())) throw new ConflictException("Transfer changed; review its current evidence");
            if(command.reason()==null || command.reason().isBlank()) throw new BadRequestException("Reconciliation reason is required");
            if(hasFacts(t)) return true;
            if(!legacyActivity(t)) throw new BusinessException("This transfer has no historical shipment activity");
            var attachmentIds=state.validateEvidence(transferId,command.attachmentIds());
            var proof=prove(t,command.sourceMovementIds()==null?List.of():command.sourceMovementIds());
            t.setReconciliationProvenance(provenance(proof,attachmentIds,command.reason()));
            if(!proof.blockers().isEmpty()) {
                t.setReconciliationStatus("RECONCILIATION_REQUIRED");transfers.saveAndFlush(t);
                audit.logInCurrentTransaction("TransferDocument",t.getId(),"RECONCILIATION_BLOCKED",command.reason(),null,null);return false;
            }
            importFacts(t,proof);t.setReconciliationStatus("RECONCILED");progress.refresh(t);
            audit.logInCurrentTransaction("TransferDocument",t.getId(),"RECONCILED",command.reason(),null,null);return true;
        });
        return documents.get(transferId);
    }
    private boolean legacyActivity(TransferDocument t) {
        return t.getDispatchMovementDocumentId()!=null || t.getReceiveMovementDocumentId()!=null || !movements.findByTenantIdAndCompanyIdAndReferenceIdOrderByMovementDateAscCreatedAtAscIdAsc(t.getTenantId(),t.getCompanyId(),t.getId()).isEmpty()
            || lines.findByTenantIdAndTransferDocumentIdAndIsDeletedFalseOrderByCreatedAtAsc(t.getTenantId(),t.getId()).stream().anyMatch(l->nvl(l.getDispatchedQuantity()).signum()!=0 || nvl(l.getReceivedQuantity()).signum()!=0 || nvl(l.getInTransitQuantity()).signum()!=0 || nvl(l.getDamagedQuantity()).signum()!=0 || nvl(l.getMissingQuantity()).signum()!=0);
    }
    private boolean terminal(TransferDocument t){return Set.of("CLOSED","CANCELLED").contains(t.getStatus());}
    private boolean canConfirm(TransferDocument t){return !terminal(t) && TransferActionPolicy.hasRole("ADMIN","MM_MANAGER","WAREHOUSE_MANAGER","WAREHOUSE_HEAD","FINANCE_CONTROLLER","ACCOUNTANT") && scopes.canAccessWarehouse(t.getSourceWarehouseId());}
    private boolean hasFacts(TransferDocument t){return !allocations.findByTenantIdAndCompanyIdAndTransferDocumentIdOrderByCreatedAtAscIdAsc(t.getTenantId(),t.getCompanyId(),t.getId()).isEmpty();}
    private ReconciliationPreview preview(String status,List<StockMovement> source,List<String> blockers,boolean canConfirm){
        return new ReconciliationPreview(status,source.stream().map(StockMovement::getId).toList(),blockers,source.stream().map(m->new MovementEvidence(m.getId(),m.getCode(),m.getName(),m.getMovementDate(),m.getReferenceType(),m.getQuantity())).toList(),canConfirm,"WAREHOUSE_MANAGER");
    }
    private String provenance(Proof proof,List<UUID> attachments,String reason){
        try{return json.writeValueAsString(Map.of("algorithm","immutable-stock-cost-evidence-v1","movementIds",proof.movements().stream().map(StockMovement::getId).toList(),"attachmentIds",attachments,"reason",reason,"actor",TransferExceptionStateService.actor(),"checkedAt",LocalDateTime.now().toString(),"blockers",proof.blockers()));}
        catch(Exception e){throw new BusinessException("Could not record reconciliation provenance");}
    }
    private Proof prove(TransferDocument t,List<UUID> supplied) {
        var blockers=new LinkedHashSet<String>();
        var plan=lines.findByTenantIdAndTransferDocumentIdAndIsDeletedFalseOrderByCreatedAtAsc(t.getTenantId(),t.getId());
        var discovered=movements.findByTenantIdAndCompanyIdAndReferenceIdOrderByMovementDateAscCreatedAtAscIdAsc(t.getTenantId(),t.getCompanyId(),t.getId()).stream()
            .filter(m->Set.of("TRANSFER_DISPATCH","TRANSFER_RECEIVE").contains(m.getReferenceType())).toList();
        var ids=discovered.stream().map(StockMovement::getId).collect(java.util.stream.Collectors.toSet());
        if(!ids.containsAll(supplied)) throw new BadRequestException("Every evidence movement must be authoritatively linked to this transfer and company");
        var sources=new ArrayList<Source>();var receipts=new ArrayList<Receipt>();
        Map<UUID,BigDecimal> dispatchByLine=new HashMap<>(),receiptByLine=new HashMap<>();
        for(var m:discovered) {
            if(m.isDeleted() || !"POSTED".equals(m.getStatus()) || m.getMovementDate()==null || movements.existsByTenantIdAndCompanyIdAndReversalOfIdAndIsDeletedFalse(t.getTenantId(),t.getCompanyId(),m.getId())) { blockers.add("LEGACY_MOVEMENT_NOT_FINAL");continue; }
            boolean dispatch="TRANSFER_DISPATCH".equals(m.getReferenceType());
            if(!Objects.equals(m.getWarehouseId(),dispatch?t.getSourceWarehouseId():t.getDestinationWarehouseId()) || m.getMovementType()!=(dispatch?MovementType.ISSUE:MovementType.RECEIPT)) {blockers.add("LEGACY_MOVEMENT_SCOPE_MISMATCH");continue;}
            var candidates=plan.stream().filter(l->l.getMaterialId().equals(m.getMaterialId())).toList();
            if(candidates.size()!=1) {blockers.add("LEGACY_LINE_AMBIGUOUS");continue;}
            var line=candidates.get(0);
            var evidence=stockAllocations.findByTenantIdAndCompanyIdAndMovementId(t.getTenantId(),t.getCompanyId(),m.getId());
            var lineage=lineages.findByTenantIdAndCompanyIdAndMovementId(t.getTenantId(),t.getCompanyId(),m.getId());
            if(evidence.isEmpty() || lineage.isEmpty()) {blockers.add("LEGACY_COST_EVIDENCE_MISSING");continue;}
            BigDecimal qty=BigDecimal.ZERO,value=BigDecimal.ZERO;
            for(var e:evidence) {
                if(!Objects.equals(e.getMaterialId(),m.getMaterialId()) || e.getOnHandDelta().signum()!=(dispatch?-1:1)) {blockers.add("LEGACY_STOCK_EFFECT_UNPROVEN");continue;}
                var linked=lineage.stream().filter(c->c.getAllocationId().equals(e.getId())).toList();
                BigDecimal costQty=BigDecimal.ZERO,costValue=BigDecimal.ZERO;
                for(var c:linked) {
                    var cost=c.getCostLayerId()==null?null:costs.findByIdAndIsDeletedFalse(c.getCostLayerId()).orElse(null);
                    if(cost==null || !t.getTenantId().equals(cost.getTenantId()) || !t.getCompanyId().equals(cost.getCompanyId()) || !m.getMaterialId().equals(cost.getMaterialId()) || !m.getWarehouseId().equals(cost.getWarehouseId())
                        || c.getQuantityDelta().signum()!=(dispatch?-1:1) || c.getValueDelta().signum()*(dispatch?-1:1)<0 || c.getCurrencyCodeSnapshot()==null || c.getExchangeRateSnapshot()==null || c.getExchangeRateSnapshot().signum()<=0) {blockers.add("LEGACY_COST_EVIDENCE_MISSING");continue;}
                    costQty=costQty.add(c.getQuantityDelta().abs());costValue=costValue.add(c.getValueDelta().abs());
                    if(dispatch) {
                        var batch=e.getSourceStockBatchIdSnapshot()==null?null:batches.findByIdAndIsDeletedFalse(e.getSourceStockBatchIdSnapshot()).orElse(null);
                        if(batch==null || !t.getTenantId().equals(batch.getTenantId()) || !t.getCompanyId().equals(batch.getCompanyId()) || !t.getSourceWarehouseId().equals(batch.getWarehouseId()) || !m.getMaterialId().equals(batch.getMaterialId()) || !Objects.equals(cost.getStockBatchId(),batch.getId()) || !Objects.equals(e.getSourceWarehouseIdSnapshot(),t.getSourceWarehouseId())) {blockers.add("LEGACY_BATCH_EVIDENCE_MISSING");continue;}
                        sources.add(new Source(line,m,e,c,batch));
                    }
                }
                if(costQty.compareTo(e.getOnHandDelta().abs())!=0 || costValue.compareTo(e.getTotalValueDelta().abs())!=0) blockers.add("LEGACY_VALUE_UNBALANCED");
                qty=qty.add(e.getOnHandDelta().abs());value=value.add(e.getTotalValueDelta().abs());
            }
            if(qty.compareTo(m.getQuantity())!=0 || value.compareTo(m.getTotalCost())!=0) blockers.add("LEGACY_VALUE_UNBALANCED");
            (dispatch?dispatchByLine:receiptByLine).merge(line.getId(),qty,BigDecimal::add);
        }
        if(sources.isEmpty()) blockers.add("LEGACY_DISPATCH_EVIDENCE_MISSING");
        for(var line:plan) {
            var d=dispatchByLine.getOrDefault(line.getId(),BigDecimal.ZERO);var r=receiptByLine.getOrDefault(line.getId(),BigDecimal.ZERO);
            if(d.compareTo(nvl(line.getDispatchedQuantity()))!=0 || r.compareTo(nvl(line.getReceivedQuantity()))!=0 || d.subtract(r).compareTo(nvl(line.getInTransitQuantity()))!=0 || d.add(nvl(line.getCancelledQuantity())).compareTo(nvl(line.getPlannedQuantity()))>0 || r.compareTo(d)>0) blockers.add("LEGACY_QUANTITY_UNBALANCED");
            if(nvl(line.getDamagedQuantity()).signum()!=0 || nvl(line.getMissingQuantity()).signum()!=0) blockers.add("LEGACY_DISPOSITION_UNPROVEN");
        }
        if(blockers.isEmpty()) matchReceipts(t,discovered,sources,receipts,blockers);
        return new Proof(discovered,List.copyOf(sources),List.copyOf(receipts),List.copyOf(blockers));
    }
    private void matchReceipts(TransferDocument t,List<StockMovement> discovered,List<Source> sources,List<Receipt> receipts,Set<String> blockers) {
        var remaining=sources.stream().map(s->s.cost().getQuantityDelta().abs()).toArray(BigDecimal[]::new);
        var values=sources.stream().map(s->s.cost().getValueDelta().abs()).toArray(BigDecimal[]::new);
        for(var m:discovered) if("TRANSFER_RECEIVE".equals(m.getReferenceType())) {
            for(var e:stockAllocations.findByTenantIdAndCompanyIdAndMovementId(t.getTenantId(),t.getCompanyId(),m.getId())) {
                var q=e.getOnHandDelta();var value=e.getTotalValueDelta();var matches=new ArrayList<Integer>();
                for(int i=0;i<sources.size();i++) {
                    var source=sources.get(i);
                    if(source.line().getMaterialId().equals(m.getMaterialId()) && !source.movement().getMovementDate().isAfter(m.getMovementDate()) && remaining[i].signum()>0) matches.add(i);
                }
                if(matches.size()!=1 || (matches.size()==1 && (remaining[matches.get(0)].compareTo(q)<0 || TransferValueSplit.take(remaining[matches.get(0)],values[matches.get(0)],q).compareTo(value)!=0)) || e.getTargetStockBatchIdSnapshot()==null || e.getQuarantineDelta().signum()!=0 || !t.getDestinationWarehouseId().equals(e.getTargetWarehouseIdSnapshot())) {blockers.add("LEGACY_RECEIPT_LINEAGE_AMBIGUOUS");continue;}
                int index=matches.get(0);remaining[index]=remaining[index].subtract(q);values[index]=values[index].subtract(value);receipts.add(new Receipt(index,m,e,q,value));
            }
        }
    }
    private void importFacts(TransferDocument t,Proof proof) {
        Map<UUID,TransferShipment> byMovement=new HashMap<>();var imported=new ArrayList<TransferShipmentAllocation>();int sequence=0;
        for(var source:proof.sources()) {
            var m=source.movement();var e=source.stock();var c=source.cost();var batch=source.batch();
            var shipment=byMovement.computeIfAbsent(m.getId(),id->{var s=new TransferShipment();s.setName("Reconciled historical shipment");s.setTransferDocumentId(t.getId());s.setDispatchedDate(m.getMovementDate());s.setOperationKey("legacy:"+id);s.setMovementDocumentId(m.getDocumentId());s.setDescription("Original movement "+id);return shipments.saveAndFlush(s);});
            var a=new TransferShipmentAllocation();a.setName("Reconciled historical allocation");a.setShipmentId(shipment.getId());a.setTransferDocumentId(t.getId());a.setTransferLineId(source.line().getId());a.setMaterialId(m.getMaterialId());a.setUnitOfMeasureId(e.getUomIdSnapshot());a.setUnitCode(e.getUomCodeSnapshot()==null?"":e.getUomCodeSnapshot());a.setQuantityScale(batch.getSerialNumber()!=null&&!batch.getSerialNumber().isBlank()?0:StockUomPolicy.quantityScale(source.line().getMaterial()));
            a.setSourceWarehouseId(e.getSourceWarehouseIdSnapshot());a.setSourceStorageLocationId(e.getSourceStorageLocationIdSnapshot());a.setSourceStorageBinId(e.getSourceStorageBinIdSnapshot());a.setSourceStockBatchId(batch.getId());a.setBatchNumber(e.getSourceBatchNumberSnapshot());a.setSerialNumber(batch.getSerialNumber());a.setExpiryDate(batch.getExpirationDate());
            a.setDispatchedQuantity(c.getQuantityDelta().abs());a.setBookValue(c.getValueDelta().abs());a.setCurrencyCode(c.getCurrencyCodeSnapshot());a.setBookCurrencyCode("UZS");a.setExchangeRate(c.getExchangeRateSnapshot());a.setOriginalValue(a.getBookValue().divide(a.getExchangeRate(),4,RoundingMode.HALF_UP));a.setSourceMovementAllocationId(e.getId());a.setSourceStockMovementId(m.getId());a.setConsumedCostLayerIds(List.of(c.getCostLayerId()));a.setAllocationSequence(++sequence);a.setDescription("Legacy evidence "+c.getId());
            a=allocations.saveAndFlush(a);imported.add(a);transit.record(a.getId(),m.getId(),TransferPostingContext.Kind.DISPATCH,a.getDispatchedQuantity(),a.getBookValue(),m.getMovementDate());
        }
        for(var receipt:proof.receipts()) {
            var a=imported.get(receipt.source());var c=new TransferAllocationClearance();c.setName("Reconciled historical receipt");c.setAllocationId(a.getId());c.setKind(TransferAllocationClearance.Kind.GOOD_RECEIPT);c.setQuantity(receipt.quantity());c.setBookValue(receipt.value());c.setPostingDate(receipt.movement().getMovementDate());c.setOperationKey("legacy:"+receipt.stock().getId());c.setDestinationStockBatchId(receipt.stock().getTargetStockBatchIdSnapshot());c.setMovementId(receipt.movement().getId());c=clearances.saveAndFlush(c);
            transit.record(a.getId(),c.getId(),TransferPostingContext.Kind.GOOD_RECEIPT,receipt.quantity().negate(),receipt.value().negate(),c.getPostingDate());
        }
    }
    private static BigDecimal nvl(BigDecimal value){return value==null?BigDecimal.ZERO:value;}
}
