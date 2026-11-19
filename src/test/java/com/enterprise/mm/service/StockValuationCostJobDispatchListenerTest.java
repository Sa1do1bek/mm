package com.enterprise.mm.service;

import static org.mockito.Mockito.verify;

import com.enterprise.mm.event.StockValuationCostJobCreatedEvent;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;

class StockValuationCostJobDispatchListenerTest {
    @Test
    void listenerRunsWorkerAfterCommit() throws NoSuchMethodException {
        TransactionalEventListener annotation = StockValuationCostJobDispatchListener.class
            .getDeclaredMethod("onJobCreated", StockValuationCostJobCreatedEvent.class)
            .getAnnotation(TransactionalEventListener.class);

        assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
    }

    @Test
    void rejectedWorkerSubmissionMarksJobFailed() {
        StockValuationCostJobWorker worker = mock(StockValuationCostJobWorker.class);
        StockValuationCostJobTransactionService transactionService = mock(StockValuationCostJobTransactionService.class);
        StockValuationCostJobDispatchListener listener = new StockValuationCostJobDispatchListener(worker, transactionService);
        UUID jobId = UUID.randomUUID();
        doThrow(new TaskRejectedException("executor full")).when(worker).run(jobId);

        listener.onJobCreated(new StockValuationCostJobCreatedEvent(jobId));

        verify(transactionService).markFailed(jobId,
            "Valuation job could not be started because worker capacity is full");
    }

    @Test
    void missingWorkerJobDoesNotRequireFailureMutation() {
        StockValuationCostJobWorker worker = mock(StockValuationCostJobWorker.class);
        StockValuationCostJobTransactionService transactionService = mock(StockValuationCostJobTransactionService.class);
        StockValuationCostJobDispatchListener listener = new StockValuationCostJobDispatchListener(worker, transactionService);
        UUID jobId = UUID.randomUUID();

        listener.onJobCreated(new StockValuationCostJobCreatedEvent(jobId));

        verify(worker).run(jobId);
        verifyNoInteractions(transactionService);
    }
}
