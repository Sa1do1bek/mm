package com.enterprise.mm.payload;

import java.util.UUID;

public record ApprovalOperationResponse(
    UUID operationRequestId,
    String moduleKey,
    UUID targetEntityId,
    String status,
    UUID approvalInstanceId,
    String requesterUsername,
    String failureReason
) {}
