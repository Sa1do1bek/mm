package com.enterprise.mm.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.entity.StockValuationCostJob;
import com.enterprise.mm.enums.StockValuationCostJobStatus;
import com.enterprise.mm.config.StockValuationCostJobRecovery;
import com.enterprise.mm.repository.StockValuationCostJobRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StockValuationCostJobRecoveryTest {
    private static final LocalDateTime STARTUP = LocalDateTime.of(2026, 7, 10, 10, 0);

    @Mock
    private StockValuationCostJobRepository jobRepository;
    @Mock
    private StockValuationCostJobTransactionService transactionService;

    @Test
    void oldQueuedAndRunningJobsAreFailedAtStartup() {
        StockValuationCostJob queued = job(StockValuationCostJobStatus.QUEUED);
        StockValuationCostJob running = job(StockValuationCostJobStatus.RUNNING);
        when(jobRepository.findAbandoned(
            eq(List.of(StockValuationCostJobStatus.QUEUED, StockValuationCostJobStatus.RUNNING)), eq(STARTUP)))
            .thenReturn(List.of(queued, running));

        new StockValuationCostJobRecovery(jobRepository, transactionService, STARTUP).recover();

        verify(transactionService).markFailed(queued.getId(),
            "Application restarted while valuation job was queued or running");
        verify(transactionService).markFailed(running.getId(),
            "Application restarted while valuation job was queued or running");
    }

    @Test
    void recoveryOnlyQueriesOldActiveJobsAndLeavesTerminalOrNewJobsUntouched() {
        when(jobRepository.findAbandoned(any(), eq(STARTUP))).thenReturn(List.of());

        new StockValuationCostJobRecovery(jobRepository, transactionService, STARTUP).recover();

        verify(transactionService, org.mockito.Mockito.never()).markFailed(any(), any());
        verify(jobRepository).findAbandoned(
            eq(List.of(StockValuationCostJobStatus.QUEUED, StockValuationCostJobStatus.RUNNING)), eq(STARTUP));
    }

    private StockValuationCostJob job(StockValuationCostJobStatus status) {
        StockValuationCostJob job = StockValuationCostJob.queued("tenant", UUID.randomUUID(), null, "system");
        job.setId(UUID.randomUUID());
        job.setStatus(status);
        return job;
    }
}
