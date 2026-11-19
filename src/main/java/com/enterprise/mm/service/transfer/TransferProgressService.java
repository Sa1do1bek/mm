package com.enterprise.mm.service.transfer;

import com.enterprise.mm.common.*;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.repository.*;
import com.enterprise.mm.service.RoleDataScopeService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class TransferProgressService {
    private final TransferDocumentRepository transfers;
    private final TransferDocumentLineRepository lines;
    private final TransferShipmentAllocationRepository allocations;
    private final TransferAllocationClearanceRepository clearances;
    private final TransferExceptionRepository cases;
    private final StockMovementRepository movements;
    private final MaterialRepository materials;
    private final UserRepository users;
    private final TransferTransitLedgerService transit;
    private final TransferActionPolicy policy;
    private final RoleDataScopeService scope;
    @Value("${app.warehouse.transfers.shipment-flow-enabled:false}") private boolean enabled;

    public record Progress(boolean canDispatch, boolean canReceive, boolean canClose, List<String> closeBlockers) {}
    public record UnitSummary(String unitCode, String unitLabel, BigDecimal planned, BigDecimal dispatched,
        BigDecimal received, BigDecimal inTransit, BigDecimal remaining, BigDecimal cancelled, BigDecimal returned, BigDecimal postedLoss) {}
    public record LineQuantities(TransferQuantityState state, BigDecimal damaged, BigDecimal missing, BigDecimal transitValue, BigDecimal dispatchValue, boolean legacy, UUID destinationBatchId) {}
    public record Projection(Map<UUID,LineQuantities> lines, Progress progress, List<UnitSummary> units, int openCases, UUID returnExceptionId) {}

    public String actorDisplayName(String actor) {
        if(actor==null)return null;
        return users.resolveActiveUserByUsername(TenantContext.currentTenant(),CompanyContext.currentCompany(),actor)
            .map(User::getFullName).filter(n->!n.isBlank()).orElse(actor);
    }
    public static Progress evaluate(TransferQuantityState state, int openCases, int unpostedOperations) {
        List<String> blockers=new ArrayList<>();
        if(state.stillToDispatch().signum()>0) blockers.add("UNDISPATCHED_REMAINDER");
        if(state.unresolvedTransit().signum()>0) blockers.add("UNRESOLVED_TRANSIT");
        if(openCases>0) blockers.add("OPEN_EXCEPTIONS");
        if(unpostedOperations>0) blockers.add("UNPOSTED_OPERATIONS");
        return new Progress(state.stillToDispatch().signum()>0,state.unresolvedTransit().signum()>0,blockers.isEmpty(),List.copyOf(blockers));
    }
    @Transactional(readOnly=true)
    public Progress get(UUID id) {
        var t=transfers.findByIdAndIsDeletedFalse(id).filter(d->TenantContext.currentTenant().equals(d.getTenantId()) && CompanyContext.currentCompany().equals(d.getCompanyId())
            && scope.canAccessWarehouse(d.getSourceWarehouseId(),d.getDestinationWarehouseId())).orElseThrow(()->new NotFoundException("Transfer not found"));
        return describe(t).progress();
    }
    public Projection describe(TransferDocument t) {
        var facts=allocations.findByTenantIdAndCompanyIdAndTransferDocumentIdOrderByCreatedAtAscIdAsc(t.getTenantId(),t.getCompanyId(),t.getId());
        var exceptions=cases.findByTenantIdAndCompanyIdAndTransferDocumentIdAndIsDeletedFalseOrderByCreatedAtAscIdAsc(t.getTenantId(),t.getCompanyId(),t.getId());
        var open=exceptions.stream().filter(TransferExceptionStateService::open).toList();
        Map<UUID,LineQuantities> projection=new LinkedHashMap<>(); Map<String,UnitSummary> units=new LinkedHashMap<>();Set<UUID> postingIds=new HashSet<>();
        BigDecimal planned=BigDecimal.ZERO, dispatched=BigDecimal.ZERO, cancelled=BigDecimal.ZERO, received=BigDecimal.ZERO, returned=BigDecimal.ZERO, loss=BigDecimal.ZERO;
        boolean legacy=!Set.of("NOT_REQUIRED","RECONCILED").contains(t.getReconciliationStatus());
        int missingPostingIds=0;
        for(var line:lines.findByTenantIdAndTransferDocumentIdAndIsDeletedFalseOrderByCreatedAtAsc(t.getTenantId(),t.getId())) {
            var selected=facts.stream().filter(a->a.getTransferLineId().equals(line.getId())).toList();
            BigDecimal d=BigDecimal.ZERO,r=BigDecimal.ZERO,ret=BigDecimal.ZERO,lost=BigDecimal.ZERO,value=BigDecimal.ZERO,transitValue=BigDecimal.ZERO;
            UUID destinationBatchId = null;
            boolean old=selected.isEmpty() && (line.getDispatchedQuantity().signum()!=0 || line.getReceivedQuantity().signum()!=0 || line.getInTransitQuantity().signum()!=0 || line.getDamagedQuantity().signum()!=0 || line.getMissingQuantity().signum()!=0);legacy|=old;
            for(var a:selected) {
                d=d.add(a.getDispatchedQuantity());value=value.add(a.getBookValue());transitValue=transitValue.add(transit.balance(a.getId()).bookValue());
                if(a.getSourceStockMovementId()==null)missingPostingIds++;else postingIds.add(a.getSourceStockMovementId());
                for(var c:clearances.findByTenantIdAndCompanyIdAndAllocationIdOrderByCreatedAtAscIdAsc(t.getTenantId(),t.getCompanyId(),a.getId())) {
                    if(c.getKind()==TransferAllocationClearance.Kind.GOOD_RECEIPT || c.getKind()==TransferAllocationClearance.Kind.QUARANTINE_RECEIPT) destinationBatchId=c.getDestinationStockBatchId();
                    switch(c.getKind()){case GOOD_RECEIPT,QUARANTINE_RECEIPT->r=r.add(c.getQuantity());case TRANSIT_RETURN->ret=ret.add(c.getQuantity());case TRANSIT_LOSS->lost=lost.add(c.getQuantity());}
                    if(c.getMovementId()==null)missingPostingIds++;else postingIds.add(c.getMovementId());
                }
            }
            // Historical projections remain readable but can never authorize operations without reconciliation.
            if(old){d=line.getDispatchedQuantity().max(BigDecimal.ZERO);r=line.getReceivedQuantity().max(BigDecimal.ZERO).min(d);ret=BigDecimal.ZERO;lost=BigDecimal.ZERO;}
            var q=new TransferQuantityState(old?line.getPlannedQuantity().max(d.add(line.getCancelledQuantity())):line.getPlannedQuantity(),d,line.getCancelledQuantity(),r,ret,lost);
            var allocationIds=selected.stream().map(TransferShipmentAllocation::getId).collect(java.util.stream.Collectors.toSet());
            var lineCases=open.stream().filter(c->allocationIds.contains(c.getAllocationId())).toList();
            BigDecimal damaged=lineCases.stream().filter(c->"DAMAGED".equals(c.getKind())&&!"RETURN_IN_TRANSIT".equals(c.getStatus())).map(TransferException::getQuantity).reduce(BigDecimal.ZERO,BigDecimal::add);
            BigDecimal missing=lineCases.stream().filter(c->"MISSING".equals(c.getKind())).map(TransferException::getQuantity).reduce(BigDecimal.ZERO,BigDecimal::add);
            projection.put(line.getId(),new LineQuantities(q,old?line.getDamagedQuantity():damaged,old?line.getMissingQuantity():missing,old?null:transitValue,value,old,destinationBatchId));
            planned=planned.add(q.planned());dispatched=dispatched.add(d);cancelled=cancelled.add(q.cancelled());received=received.add(r);returned=returned.add(ret);loss=loss.add(lost);
            var material=line.getMaterial()!=null?line.getMaterial():materials.findByIdAndIsDeletedFalse(line.getMaterialId()).orElse(null);
            var unit=material==null?null:material.getUnitOfMeasure();String code=unit==null?"":unit.getCode();String label=UnitOfMeasureLabels.uzbekShort(unit);
            String group=unit==null?line.getId().toString():unit.getId().toString();
            var prior=units.getOrDefault(group,new UnitSummary(code,label,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO));
            units.put(group,new UnitSummary(code,label,prior.planned().add(q.planned()),prior.dispatched().add(d),prior.received().add(r),prior.inTransit().add(q.unresolvedTransit()),prior.remaining().add(q.stillToDispatch()),prior.cancelled().add(q.cancelled()),prior.returned().add(ret),prior.postedLoss().add(lost)));
        }
        var posted=movements.findAllById(postingIds).stream().filter(m->!m.isDeleted() && t.getTenantId().equals(m.getTenantId()) && t.getCompanyId().equals(m.getCompanyId()) && "POSTED".equals(m.getStatus())).count();
        var physical=evaluate(new TransferQuantityState(planned,dispatched,cancelled,received,returned,loss),open.size(),missingPostingIds+postingIds.size()-(int)posted);
        var blockers=new ArrayList<>(physical.closeBlockers());
        if(legacy)blockers.add("LEGACY_RECONCILIATION_REQUIRED");
        if(open.stream().anyMatch(c->"RETURN_IN_TRANSIT".equals(c.getStatus())))blockers.add("RETURN_IN_TRANSIT");
        if(open.stream().anyMatch(c->"DAMAGED".equals(c.getKind())&&!"RETURN_IN_TRANSIT".equals(c.getStatus())))blockers.add("QUARANTINE_PENDING");
        UUID returnCase=t.getReturnOfTransferId()==null?null:cases.findByTenantIdAndCompanyIdAndReverseTransferIdAndIsDeletedFalse(t.getTenantId(),t.getCompanyId(),t.getId()).map(TransferException::getId).orElse(null);
        if(Set.of("CLOSED","CANCELLED").contains(t.getStatus())) blockers.clear();
        var progress=new Progress(enabled&&!legacy&&physical.canDispatch()&&policy.canAct(t,TransferActionPolicy.Action.DISPATCH),
            enabled&&!legacy&&physical.canReceive()&&t.getReturnOfTransferId()==null&&policy.canAct(t,TransferActionPolicy.Action.RECEIVE),
            blockers.isEmpty()&&policy.canAct(t,TransferActionPolicy.Action.CLOSE),List.copyOf(blockers));
        return new Projection(projection,progress,List.copyOf(units.values()),open.size(),returnCase);
    }
    /** Called only inside commands that already hold the transfer parent lock. */
    public void refresh(TransferDocument t) {
        var p=describe(t);boolean pending=false;
        for(var line:lines.findByTenantIdAndTransferDocumentIdAndIsDeletedFalseOrderByCreatedAtAsc(t.getTenantId(),t.getId())) {
            var fact=p.lines().get(line.getId());if(fact.legacy())continue;var q=fact.state();
            line.setDestinationStockBatchId(fact.destinationBatchId());
            line.setDispatchedQuantity(q.dispatched());line.setInTransitQuantity(q.unresolvedTransit());line.setReceivedQuantity(q.received());line.setDamagedQuantity(fact.damaged());line.setMissingQuantity(fact.missing());
            line.setUnitCost(q.dispatched().signum()==0?BigDecimal.ZERO:fact.dispatchValue().divide(q.dispatched(),6,RoundingMode.HALF_UP));lines.save(line);
            pending|=q.stillToDispatch().signum()>0||q.unresolvedTransit().signum()>0;
        }
        if(Set.of("APPROVED","DISPATCHED","IN_TRANSIT","DISCREPANCY_REVIEW","RECEIVED").contains(t.getStatus()))
            t.setStatus(p.openCases()>0?"DISCREPANCY_REVIEW":pending?"IN_TRANSIT":"RECEIVED");
        t.setUpdatedAt(LocalDateTime.now());transfers.saveAndFlush(t);
    }
}
