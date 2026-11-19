package com.enterprise.mm.service.approval;

import com.enterprise.mm.error.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@Slf4j
public class ApprovalOperationApplyListener {
    private final ApprovalOperationExecutionService execution;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onApproved(ApprovalOperationEvent event) {
        try {
            execution.apply(event.requestId());
        } catch (RuntimeException failure) {
            log.error("Approved operation {} could not be applied", event.requestId(), failure);
            execution.recordFailure(event.requestId(), failure instanceof BusinessException
                ? failure.getMessage() : "Operation could not be applied; contact support");
        }
    }
}
