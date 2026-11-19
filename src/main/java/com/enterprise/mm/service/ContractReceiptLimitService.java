package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ContractSpecification;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.StockMovementRepository;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Contract quantities are shared across warehouses; callers lock the specification before posting. */
@Service
@RequiredArgsConstructor
public class ContractReceiptLimitService {
    private final StockMovementRepository movements;

    public BigDecimal remaining(UUID specificationId, BigDecimal limit) {
        if (limit == null || limit.signum() <= 0) return BigDecimal.ZERO;
        BigDecimal received = movements.sumEffectiveContractReceipts(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), specificationId);
        return limit.subtract(received).max(BigDecimal.ZERO);
    }

    public void validate(ContractSpecification spec, BigDecimal contractQuantity, BigDecimal alreadyRequested) {
        if (spec.getQuantityLimit() == null || spec.getQuantityLimit().signum() <= 0)
            throw new BusinessException("Receipt requires a positive contract quantity limit");
        if (contractQuantity == null || contractQuantity.signum() <= 0)
            throw new BusinessException("Receipt quantity must be positive");
        BigDecimal available = remaining(spec.getId(), spec.getQuantityLimit());
        if (alreadyRequested.add(contractQuantity).compareTo(available) > 0)
            throw new BusinessException("Quantity exceeds remaining contract quantity (" + available + ")");
    }
}
