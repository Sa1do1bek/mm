package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.enterprise.mm.event.AiAnalysisJobCreatedEvent;
import com.enterprise.mm.enums.AiAnalysisType;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

class AiAnalysisJobDispatchListenerTest {
    @Test
    void listenerRunsWorkerAfterCommit() throws NoSuchMethodException {
        TransactionalEventListener annotation = AiAnalysisJobDispatchListener.class
            .getDeclaredMethod("onJobCreated", AiAnalysisJobCreatedEvent.class)
            .getAnnotation(TransactionalEventListener.class);

        assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
    }

    @Test
    void rejectedWorkerSubmissionMarksJobFailed() {
        AiAnalysisJobWorker worker = mock(AiAnalysisJobWorker.class);
        AiAnalysisJobTransactionService transactionService = mock(AiAnalysisJobTransactionService.class);
        AiAnalysisJobDispatchListener listener = new AiAnalysisJobDispatchListener(worker, transactionService);
        UUID jobId = UUID.randomUUID();
        doThrow(new TaskRejectedException("executor full")).when(worker).run(jobId);

        listener.onJobCreated(new AiAnalysisJobCreatedEvent(jobId, AiAnalysisType.PRICE_INTELLIGENCE));

        verify(worker).run(jobId);
        verify(transactionService).markFailed(
            jobId,
            "AI_JOB_DISPATCH_REJECTED",
            "AI analysis job could not be started because worker capacity is full"
        );
        verifyNoMoreInteractions(worker);
    }

    @Test
    void mxikJobsUseDedicatedWorkerQueue() {
        AiAnalysisJobWorker worker = mock(AiAnalysisJobWorker.class);
        AiAnalysisJobTransactionService transactionService = mock(AiAnalysisJobTransactionService.class);
        AiAnalysisJobDispatchListener listener = new AiAnalysisJobDispatchListener(worker, transactionService);
        UUID jobId = UUID.randomUUID();

        listener.onJobCreated(new AiAnalysisJobCreatedEvent(jobId, AiAnalysisType.MATERIAL_MXIK_MATCH_BATCH));

        verify(worker).runMxik(jobId);
        verifyNoMoreInteractions(worker, transactionService);
    }
}
