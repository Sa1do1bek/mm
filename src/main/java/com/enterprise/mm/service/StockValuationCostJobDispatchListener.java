package com.enterprise.mm.service;

import com.enterprise.mm.event.StockValuationCostJobCreatedEvent;
import java.util.concurrent.RejectedExecutionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@Slf4j
public class StockValuationCostJobDispatchListener {
    private static final String REJECTION_MESSAGE =
        "Valuation job could not be started because worker capacity is full";

    private final StockValuationCostJobWorker worker;
    private final StockValuationCostJobTransactionService transactionService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onJobCreated(StockValuationCostJobCreatedEvent event) {
        try {
            worker.run(event.jobId());
        } catch (RejectedExecutionException ex) {
            log.warn("Valuation job executor rejected job dispatch: jobId={}", event.jobId());
            transactionService.markFailed(event.jobId(), REJECTION_MESSAGE);
        }
    }
}
