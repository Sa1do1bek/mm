package com.enterprise.mm.service;

import com.enterprise.mm.entity.StockValuationCostJobResult;
import com.enterprise.mm.entity.StockValuationHistory;
import com.enterprise.mm.enums.StockValuationCostJobPhase;
import com.enterprise.mm.enums.StockValuationCostJobStatus;
import com.enterprise.mm.payload.StockValuationSelectedStocksResponse.StockCost;
import com.enterprise.mm.repository.StockValuationCostJobRepository;
import com.enterprise.mm.repository.StockValuationCostJobResultRepository;
import com.enterprise.mm.repository.StockValuationHistoryRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StockValuationCostJobTransactionService {
    private final StockValuationCostJobRepository jobRepository;
    private final StockValuationCostJobResultRepository resultRepository;
    private final StockValuationHistoryRepository historyRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markRunning(UUID jobId) {
        jobRepository.findForUpdate(jobId).ifPresent(job -> {
            job.setStatus(StockValuationCostJobStatus.RUNNING);
            job.setPhase(StockValuationCostJobPhase.PREPARING);
            job.setStartedAt(LocalDateTime.now());
            jobRepository.save(job);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveTotalItems(UUID jobId, int totalItems) {
        jobRepository.findForUpdate(jobId).ifPresent(job -> {
            job.setTotalItems(Math.max(0, totalItems));
            if (totalItems == 0) {
                job.setProgressPercent(new BigDecimal("100.00"));
            }
            job.setPhase(StockValuationCostJobPhase.CALCULATING);
            jobRepository.save(job);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveResultAndIncrementCounters(UUID jobId, int sequenceNo, StockCost result) {
        jobRepository.findForUpdate(jobId).ifPresent(job -> {
            StockCost storedResult = sanitizeResult(result);
            resultRepository.save(StockValuationCostJobResult.from(jobId, sequenceNo, storedResult));
            job.setProcessedItems(job.getProcessedItems() + 1);
            if (storedResult.available()) {
                job.setSuccessfulItems(job.getSuccessfulItems() + 1);
                historyRepository.save(StockValuationHistory.fromJobResult(job, storedResult));
            } else {
                job.setFailedItems(job.getFailedItems() + 1);
            }
            job.setProgressPercent(progress(job.getProcessedItems(), job.getTotalItems()));
            jobRepository.save(job);
        });
    }

    private StockCost sanitizeResult(StockCost result) {
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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFinalizing(UUID jobId) {
        jobRepository.findForUpdate(jobId).ifPresent(job -> {
            job.setPhase(StockValuationCostJobPhase.FINALIZING);
            jobRepository.save(job);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCompleted(UUID jobId) {
        jobRepository.findForUpdate(jobId).ifPresent(job -> {
            job.setStatus(StockValuationCostJobStatus.COMPLETED);
            job.setPhase(StockValuationCostJobPhase.DONE);
            job.setProgressPercent(new BigDecimal("100.00"));
            job.setFinishedAt(LocalDateTime.now());
            jobRepository.save(job);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCompletedWithErrors(UUID jobId) {
        jobRepository.findForUpdate(jobId).ifPresent(job -> {
            job.setStatus(StockValuationCostJobStatus.COMPLETED_WITH_ERRORS);
            job.setPhase(StockValuationCostJobPhase.DONE);
            job.setProgressPercent(new BigDecimal("100.00"));
            job.setFinishedAt(LocalDateTime.now());
            jobRepository.save(job);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID jobId, String message) {
        jobRepository.findForUpdate(jobId).ifPresent(job -> {
            job.setStatus(StockValuationCostJobStatus.FAILED);
            job.setPhase(StockValuationCostJobPhase.DONE);
            job.setErrorMessage(StockValuationCostJobErrorSanitizer.sanitizeError(message));
            job.setFinishedAt(LocalDateTime.now());
            jobRepository.save(job);
        });
    }

    private BigDecimal progress(int processedItems, int totalItems) {
        if (totalItems <= 0) {
            return new BigDecimal("100.00");
        }
        return BigDecimal.valueOf(processedItems)
            .multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(totalItems), 2, RoundingMode.HALF_UP);
    }
}
