package com.enterprise.mm.service.approval;

import com.enterprise.mm.entity.ApprovalOperationRequest;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalDetailItem;
import java.util.List;
import java.util.UUID;

/** Typed business command behind an operation-specific approval flow. */
public interface ApprovalOperationHandler {
    String moduleKey();
    UUID companyId(UUID targetId);
    /** Called only after the worklist has authorized the actor's assigned approval task. */
    default void requireDetailRead(UUID targetId) {
        companyId(targetId);
    }
    String displayName(UUID targetId);
    void validate(UUID targetId, Long expectedVersion, String payloadJson);
    void apply(ApprovalOperationRequest request);
    default List<ApprovalDetailItem> reviewItems(ApprovalOperationRequest request) {
        return List.of();
    }
}
