package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.entity.StockValuationCostJob;
import com.enterprise.mm.enums.StockValuationCostJobPhase;
import com.enterprise.mm.enums.StockValuationCostJobStatus;
import com.enterprise.mm.repository.StockValuationCostJobRepository;
import com.enterprise.mm.repository.StockValuationCostJobResultRepository;
import com.enterprise.mm.repository.StockValuationHistoryRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StockValuationCostJobTransactionServiceTest {
    @Mock
    private StockValuationCostJobRepository repository;
    @Mock
    private StockValuationCostJobResultRepository resultRepository;
    @Mock
    private StockValuationHistoryRepository historyRepository;

    @Test
    void stateMutationsUseLockedJobAndApplyMinimalLifecycle() {
        UUID jobId = UUID.randomUUID();
        StockValuationCostJob job = StockValuationCostJob.queued("tenant", UUID.randomUUID(), null, "user");
        job.setId(jobId);
        when(repository.findForUpdate(jobId)).thenReturn(Optional.of(job));
        StockValuationCostJobTransactionService service = new StockValuationCostJobTransactionService(
            repository, resultRepository, historyRepository);

        service.markRunning(jobId);
        assertThat(job.getStatus()).isEqualTo(StockValuationCostJobStatus.RUNNING);
        assertThat(job.getPhase()).isEqualTo(StockValuationCostJobPhase.PREPARING);
        assertThat(job.getStartedAt()).isNotNull();

        service.markCompleted(jobId);
        assertThat(job.getStatus()).isEqualTo(StockValuationCostJobStatus.COMPLETED);
        assertThat(job.getPhase()).isEqualTo(StockValuationCostJobPhase.DONE);
        assertThat(job.getFinishedAt()).isNotNull();

        service.markFailed(jobId, "rejected");
        assertThat(job.getStatus()).isEqualTo(StockValuationCostJobStatus.FAILED);
        assertThat(job.getErrorMessage()).isEqualTo("rejected");
        verify(repository, org.mockito.Mockito.times(3)).findForUpdate(jobId);
    }
}
