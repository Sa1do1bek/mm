package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.StockValuationCostJob;
import com.enterprise.mm.enums.ValuationMethod;
import com.enterprise.mm.payload.StockValuationSelectedStocksResponse.StockCost;
import com.enterprise.mm.repository.StockValuationCostJobRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class StockValuationCostJobWorker {
    private final StockValuationCostJobRepository jobRepository;
    private final StockValuationCostJobTransactionService transactionService;
    private final StockValuationCostSelectionService selectionService;

    @Async("valuationJobExecutor")
    public void run(UUID jobId) {
        try {
            StockValuationCostJob job = jobRepository.findWithWarehouseIdsById(jobId).orElse(null);
            if (job == null) {
                return;
            }
            log.info("Starting stock valuation cost job: jobId={}", jobId);
            TenantContext.setTenant(job.getTenantId());
            CompanyContext.setCompany(job.getCompanyId());
            transactionService.markRunning(jobId);
            ValuationMethod valuationMethod = job.getValuationMethod();
             List<StockGroup> groups = job.getWarehouseIds() != null && !job.getWarehouseIds().isEmpty()
                ? selectionService.findPositiveStockGroupsForWarehouses(job.getWarehouseIds())
                : selectionService.findPositiveStockGroups(job.getWarehouseId());
            transactionService.saveTotalItems(jobId, groups.size());
            int failedItems = 0;
            int sequenceNo = 1;
            for (StockGroup group : groups) {
                StockCost result;
                try {
                    result = selectionService.calculateOne(group, valuationMethod);
                    if (result == null) {
                        result = unavailable(group, null);
                    }
                } catch (RuntimeException ex) {
                    result = unavailable(group, ex);
                }
                result = sanitizeUnavailable(result);
                if (!result.available()) {
                    failedItems++;
                }
                transactionService.saveResultAndIncrementCounters(jobId, sequenceNo++, result);
            }
            transactionService.markFinalizing(jobId);
            if (failedItems == 0) {
                transactionService.markCompleted(jobId);
                log.info("Stock valuation cost job completed: jobId={}", jobId);
            } else {
                transactionService.markCompletedWithErrors(jobId);
                log.warn("Stock valuation cost job completed with errors: jobId={} failedItems={}",
                    jobId, failedItems);
            }
        } catch (RuntimeException ex) {
            log.error("Stock valuation cost job failed: jobId={}", jobId, ex);
            try {
                transactionService.markFailed(jobId, StockValuationCostJobErrorSanitizer.sanitizeError(ex));
            } catch (RuntimeException ignored) {
                // Preserve the worker's context cleanup even if failure persistence is unavailable.
            }
        } finally {
            TenantContext.clear();
            CompanyContext.clear();
        }
    }

    private StockCost unavailable(StockGroup group, Throwable throwable) {
        return new StockCost(
            group.materialId(),
            group.materialName(),
            group.warehouseId(),
            group.warehouseName(),
            group.quantity(),
            null,
            null,
            false,
            StockValuationCostJobErrorSanitizer.sanitizeError(throwable));
    }

    private StockCost sanitizeUnavailable(StockCost result) {
        if (result.available()) {
            return result;
        }
        return new StockCost(
            result.materialId(),
            result.materialName(),
            result.warehouseId(),
            result.warehouseName(),
            result.quantity(),
            result.valuationMethod(),
            result.totalCost(),
            false,
            StockValuationCostJobErrorSanitizer.sanitizeError(result.errorMessage()));
    }

}
