package com.enterprise.mm.service;

import com.enterprise.mm.entity.StockValuationCostJob;
import com.enterprise.mm.enums.ValuationMethod;
import com.enterprise.mm.repository.StockValuationCostJobRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StockValuationCostJobCreationService {
    private final StockValuationCostJobRepository jobRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public StockValuationCostJob create(
            String tenantId, UUID companyId, UUID warehouseId,
            List<UUID> warehouseIds, ValuationMethod valuationMethod, String requestedBy) {
        StockValuationCostJob job = StockValuationCostJob.queued(
            tenantId, companyId, warehouseId, valuationMethod, requestedBy);
        if (warehouseIds != null && !warehouseIds.isEmpty()) {
            job.getWarehouseIds().addAll(warehouseIds);
        }
        return jobRepository.saveAndFlush(job);
    }
}
