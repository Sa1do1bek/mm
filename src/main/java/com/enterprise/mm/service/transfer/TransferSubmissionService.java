package com.enterprise.mm.service.transfer;

import com.enterprise.mm.payload.TransferDocumentResponse;
import com.enterprise.mm.service.IdempotencyService;
import com.enterprise.mm.service.TransferDocumentService;
import com.enterprise.mm.service.approval.ApprovalOperationSubmissionService;
import com.enterprise.mm.service.approval.TransferApproveOperationHandler;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TransferSubmissionService {
    private final TransferDocumentService documents;
    private final TransferActionPolicy policy;
    private final ApprovalOperationSubmissionService approvals;
    private final IdempotencyService idempotency;

    @Transactional
    public TransferDocumentResponse submit(UUID id, String key) {
        // Authorize and serialize retries before replaying a previously completed submission.
        policy.lockAuthorized(id, TransferActionPolicy.Action.SUBMIT);
        return idempotency.executeRequired("TRANSFER_SUBMIT", id, key, id,
            TransferDocumentResponse.class, () -> {
                var submitted = documents.submit(id);
                approvals.submit(TransferApproveOperationHandler.MODULE_KEY, id,
                    submitted.getVersion(), "{}", key);
                return submitted;
            });
    }
}
