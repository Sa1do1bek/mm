package com.enterprise.mm.service;

import com.enterprise.mm.event.AiAnalysisJobCreatedEvent;
import com.enterprise.mm.enums.AiAnalysisType;
import java.util.concurrent.RejectedExecutionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@Slf4j
public class AiAnalysisJobDispatchListener {
    public static final String DISPATCH_REJECTED_CODE = "AI_JOB_DISPATCH_REJECTED";
    static final String DISPATCH_REJECTED_MESSAGE =
        "AI analysis job could not be started because worker capacity is full";

    private final AiAnalysisJobWorker worker;
    private final AiAnalysisJobTransactionService transactionService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onJobCreated(AiAnalysisJobCreatedEvent event) {
        try {
            if (isMxik(event.analysisType())) {
                worker.runMxik(event.jobId());
            } else {
                worker.run(event.jobId());
            }
        } catch (RejectedExecutionException ex) {
            log.warn("AI analysis executor rejected job dispatch: jobId={}", event.jobId());
            transactionService.markFailed(event.jobId(), DISPATCH_REJECTED_CODE, DISPATCH_REJECTED_MESSAGE);
        }
    }

    private boolean isMxik(AiAnalysisType analysisType) {
        return analysisType == AiAnalysisType.MATERIAL_MXIK_MATCH
            || analysisType == AiAnalysisType.MATERIAL_MXIK_MATCH_BATCH;
    }
}
