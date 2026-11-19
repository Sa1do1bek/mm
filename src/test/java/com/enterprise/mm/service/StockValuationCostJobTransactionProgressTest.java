package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.enums.ValuationMethod;
import com.enterprise.mm.entity.StockValuationCostJob;
import com.enterprise.mm.entity.StockValuationCostJobResult;
import com.enterprise.mm.entity.StockValuationHistory;
import com.enterprise.mm.payload.StockValuationSelectedStocksResponse.StockCost;
import com.enterprise.mm.repository.StockValuationCostJobRepository;
import com.enterprise.mm.repository.StockValuationCostJobResultRepository;
import com.enterprise.mm.repository.StockValuationHistoryRepository;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StockValuationCostJobTransactionProgressTest {
    @Mock
    private StockValuationCostJobRepository jobRepository;
    @Mock
    private StockValuationCostJobResultRepository resultRepository;
    @Mock
    private StockValuationHistoryRepository historyRepository;

    @Test
    void savesResultAndUpdatesCountersAndRoundedProgress() {
        UUID jobId = UUID.randomUUID();
        StockValuationCostJob job = StockValuationCostJob.queued("tenant", UUID.randomUUID(), null, "user");
        job.setId(jobId);
        job.setTotalItems(3);
        when(jobRepository.findForUpdate(jobId)).thenReturn(Optional.of(job));
        StockCost result = new StockCost(UUID.randomUUID(), "Steel", UUID.randomUUID(), "Main", BigDecimal.TEN,
            ValuationMethod.FIFO, new BigDecimal("10.0000"), true, null);

        new StockValuationCostJobTransactionService(jobRepository, resultRepository, historyRepository)
            .saveResultAndIncrementCounters(jobId, 1, result);

        ArgumentCaptor<StockValuationCostJobResult> saved = ArgumentCaptor.forClass(StockValuationCostJobResult.class);
        verify(resultRepository).save(saved.capture());
        ArgumentCaptor<StockValuationHistory> history = ArgumentCaptor.forClass(StockValuationHistory.class);
        verify(historyRepository).save(history.capture());
        assertThat(saved.getValue().getSequenceNo()).isEqualTo(1);
        assertThat(saved.getValue().getMaterialId()).isEqualTo(result.materialId());
        assertThat(history.getValue().getSourceType()).isEqualTo("STOCK_COST_JOB");
        assertThat(history.getValue().getSourceId()).isEqualTo(jobId);
        assertThat(history.getValue().getUnitCost()).isEqualByComparingTo("1.0000");
        assertThat(job.getProcessedItems()).isEqualTo(1);
        assertThat(job.getSuccessfulItems()).isEqualTo(1);
        assertThat(job.getFailedItems()).isZero();
        assertThat(job.getProgressPercent()).isEqualByComparingTo("33.33");
    }

    @Test
    void emptyCompletionReportsOneHundredPercent() {
        UUID jobId = UUID.randomUUID();
        StockValuationCostJob job = StockValuationCostJob.queued("tenant", UUID.randomUUID(), null, "user");
        job.setId(jobId);
        when(jobRepository.findForUpdate(jobId)).thenReturn(Optional.of(job));

        StockValuationCostJobTransactionService service = new StockValuationCostJobTransactionService(
            jobRepository, resultRepository, historyRepository);
        service.saveTotalItems(jobId, 0);
        service.markCompleted(jobId);

        assertThat(job.getTotalItems()).isZero();
        assertThat(job.getProgressPercent()).isEqualByComparingTo("100.00");
    }
}
