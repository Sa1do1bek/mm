package com.enterprise.mm.service;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.repository.InventoryPeriodCloseRepository;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class InventoryPeriodGuardService {
    private final InventoryPeriodCloseRepository periodCloseRepository;

    public void ensureOpen(UUID warehouseId, LocalDate operationDate, String operation) {
        LocalDate postingDate = operationDate == null ? LocalDate.now() : operationDate;
        if (periodCloseRepository.existsClosedForPostingDate(TenantContext.currentTenant(), CompanyContext.currentCompany(), warehouseId == null, warehouseId, postingDate)) {
            throw new BusinessException("Inventory period is closed for " + operation + " date " + postingDate);
        }
    }

    public boolean isClosed(UUID warehouseId, LocalDate operationDate) {
        LocalDate postingDate = operationDate == null ? LocalDate.now() : operationDate;
        return periodCloseRepository.existsClosedForPostingDate(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            warehouseId == null,
            warehouseId,
            postingDate
        );
    }
}
