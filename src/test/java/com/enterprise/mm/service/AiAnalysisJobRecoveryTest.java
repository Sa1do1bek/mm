package com.enterprise.mm.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.config.AiAnalysisJobRecovery;
import com.enterprise.mm.entity.AiAnalysisJob;
import com.enterprise.mm.enums.AiAnalysisJobStatus;
import com.enterprise.mm.enums.AiAnalysisType;
import com.enterprise.mm.repository.AiAnalysisJobRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskRejectedException;

@ExtendWith(MockitoExtension.class)
class AiAnalysisJobRecoveryTest {
    private static final LocalDateTime STARTUP = LocalDateTime.of(2026, 8, 14, 11, 0);

    @Mock
    private AiAnalysisJobRepository jobRepository;
    @Mock
    private AiAnalysisJobWorker worker;
    @Mock
    private AiAnalysisJobTransactionService transactionService;

    @Test
    void startupRedispatchesOldQueuedJobsAndFailsOldRunningJobs() {
        AiAnalysisJob queued = job(AiAnalysisJobStatus.QUEUED);
        AiAnalysisJob running = job(AiAnalysisJobStatus.RUNNING);
        when(jobRepository.findAbandoned(
            eq(List.of(AiAnalysisJobStatus.QUEUED, AiAnalysisJobStatus.RUNNING)),
            eq(STARTUP)
        )).thenReturn(List.of(queued, running));

        new AiAnalysisJobRecovery(jobRepository, worker, transactionService, STARTUP).recover();

        verify(worker).run(queued.getId());
        verify(transactionService).markFailed(
            running.getId(),
            "AI_JOB_ABANDONED_ON_RESTART",
            "Application restarted while AI analysis job was running"
        );
    }

    @Test
    void startupRedispatchesQueuedMxikJobsToDedicatedWorkerQueue() {
        AiAnalysisJob queued = job(AiAnalysisJobStatus.QUEUED);
        queued.setAnalysisType(AiAnalysisType.MATERIAL_MXIK_MATCH_BATCH);
        when(jobRepository.findAbandoned(
            eq(List.of(AiAnalysisJobStatus.QUEUED, AiAnalysisJobStatus.RUNNING)),
            eq(STARTUP)
        )).thenReturn(List.of(queued));

        new AiAnalysisJobRecovery(jobRepository, worker, transactionService, STARTUP).recover();

        verify(worker).runMxik(queued.getId());
        verify(worker, never()).run(any());
    }

    @Test
    void recoveryOnlyQueriesOldActiveJobsAndLeavesTerminalOrNewJobsUntouched() {
        when(jobRepository.findAbandoned(any(), eq(STARTUP))).thenReturn(List.of());

        new AiAnalysisJobRecovery(jobRepository, worker, transactionService, STARTUP).recover();

        verify(worker, never()).run(any());
        verify(transactionService, never()).markFailed(any(), any(), any());
        verify(jobRepository).findAbandoned(
            eq(List.of(AiAnalysisJobStatus.QUEUED, AiAnalysisJobStatus.RUNNING)),
            eq(STARTUP)
        );
    }

    @Test
    void rejectedRecoveredQueuedJobIsFailedInsteadOfRemainingQueued() {
        AiAnalysisJob queued = job(AiAnalysisJobStatus.QUEUED);
        when(jobRepository.findAbandoned(any(), eq(STARTUP))).thenReturn(List.of(queued));
        doThrow(new TaskRejectedException("executor full")).when(worker).run(queued.getId());

        new AiAnalysisJobRecovery(jobRepository, worker, transactionService, STARTUP).recover();

        verify(transactionService).markFailed(
            queued.getId(),
            "AI_JOB_DISPATCH_REJECTED",
            "AI analysis job could not be restarted because worker capacity is full"
        );
    }

    private static AiAnalysisJob job(AiAnalysisJobStatus status) {
        AiAnalysisJob job = AiAnalysisJob.queued(
            "tenant-a",
            UUID.randomUUID(),
            AiAnalysisType.RFQ_QUOTATION_COMPARISON,
            "RFQ",
            UUID.randomUUID(),
            "request-hash",
            "uz",
            "buyer",
            "{}"
        );
        job.setId(UUID.randomUUID());
        job.setStatus(status);
        return job;
    }
}
