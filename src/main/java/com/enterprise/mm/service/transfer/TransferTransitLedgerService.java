package com.enterprise.mm.service.transfer;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.TransferTransitEntry;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.TransferShipmentAllocationRepository;
import com.enterprise.mm.repository.TransferTransitEntryRepository;
import com.enterprise.mm.service.TransferPostingContext.Kind;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class TransferTransitLedgerService {
    private final TransferTransitEntryRepository entries;
    private final TransferShipmentAllocationRepository allocations;
    public record TransitBalance(BigDecimal quantity, BigDecimal bookValue) {}

    @Transactional(readOnly = true)
    public TransitBalance balance(UUID allocationId) {
        var facts = entries.findByTenantIdAndCompanyIdAndAllocationIdOrderByCreatedAtAscIdAsc(TenantContext.currentTenant(), CompanyContext.currentCompany(), allocationId);
        return new TransitBalance(facts.stream().map(TransferTransitEntry::getQuantityDelta).reduce(BigDecimal.ZERO, BigDecimal::add),
            facts.stream().map(TransferTransitEntry::getValueDelta).reduce(BigDecimal.ZERO, BigDecimal::add));
    }
    @Transactional
    public void record(UUID allocationId, UUID operationId, Kind kind, BigDecimal quantityDelta, BigDecimal valueDelta) {
        record(allocationId, operationId, kind, quantityDelta, valueDelta, LocalDate.now());
    }
    @Transactional
    public void record(UUID allocationId, UUID operationId, Kind kind, BigDecimal quantityDelta, BigDecimal valueDelta, LocalDate postingDate) {
        var allocation = allocations.findScopedForUpdate(allocationId, TenantContext.currentTenant(), CompanyContext.currentCompany())
            .orElseThrow(() -> new BusinessException("Shipment allocation is outside the current company"));
        var existing = balance(allocationId);
        boolean dispatch = kind == Kind.DISPATCH;
        if (dispatch && !entries.findByTenantIdAndCompanyIdAndAllocationIdOrderByCreatedAtAscIdAsc(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), allocationId).isEmpty())
            throw new BusinessException("Shipment allocation was already dispatched");
        if (operationId == null || postingDate == null || quantityDelta == null || valueDelta == null
            || (dispatch ? quantityDelta.signum() <= 0 || valueDelta.signum() < 0 : quantityDelta.signum() >= 0 || valueDelta.signum() > 0)
            || existing.quantity().add(quantityDelta).signum() < 0 || existing.bookValue().add(valueDelta).signum() < 0
            || (dispatch && (existing.quantity().signum() != 0 || quantityDelta.compareTo(allocation.getDispatchedQuantity()) != 0 || valueDelta.compareTo(allocation.getBookValue()) != 0))
            || (existing.quantity().add(quantityDelta).signum() == 0 && existing.bookValue().add(valueDelta).signum() != 0))
            throw new BusinessException("Transfer transit quantity/value does not reconcile");
        if (!dispatch && TransferValueSplit.take(existing.quantity(), existing.bookValue(), quantityDelta.abs()).compareTo(valueDelta.abs()) != 0)
            throw new BusinessException("Clearance must consume the exact allocated book value");
        var entry = new TransferTransitEntry(); entry.setName("Transfer " + kind); entry.setAllocationId(allocationId);
        entry.setOperationId(operationId); entry.setKind(kind.name()); entry.setPostingDate(postingDate);
        entry.setQuantityDelta(quantityDelta); entry.setValueDelta(valueDelta); entries.saveAndFlush(entry);
    }
    @Transactional(readOnly = true)
    public BigDecimal inventoryValue(UUID sourceWarehouseId) {
        return entries.sumBookValue(TenantContext.currentTenant(), CompanyContext.currentCompany(), sourceWarehouseId);
    }
}
