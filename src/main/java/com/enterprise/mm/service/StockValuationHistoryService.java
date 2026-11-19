package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.StockValuationHistory;
import com.enterprise.mm.payload.StockValuationHistoryResponse;
import com.enterprise.mm.repository.StockValuationHistoryRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StockValuationHistoryService {
    private final StockValuationHistoryRepository repository;

    @Transactional(readOnly = true)
    public Page<StockValuationHistoryResponse> search(
        UUID warehouseId,
        List<UUID> materialIds,
        com.enterprise.mm.enums.ValuationMethod valuationMethod,
        LocalDate dateFrom,
        LocalDate dateTo,
        Pageable pageable
    ) {
        List<UUID> normalizedMaterialIds = materialIds == null ? List.of() : materialIds;
        return repository.search(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            warehouseId,
            normalizedMaterialIds,
            normalizedMaterialIds.isEmpty(),
            valuationMethod,
            dateFrom == null ? null : dateFrom.atStartOfDay(),
            dateTo == null ? null : dateTo.atTime(LocalTime.MAX),
            pageable
        ).map(this::toResponse);
    }

    private StockValuationHistoryResponse toResponse(StockValuationHistory history) {
        return new StockValuationHistoryResponse(
            history.getId(),
            history.getMaterialId(),
            history.getMaterialName(),
            history.getWarehouseId(),
            history.getWarehouseName(),
            history.getQuantity(),
            history.getValuationMethod(),
            history.getUnitCost(),
            history.getTotalValue(),
            history.getCarryingValue(),
            history.getValuationBasis(),
            history.getSourceType(),
            history.getSourceId(),
            history.getCalculatedAt(),
            history.getCreatedAt()
        );
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
