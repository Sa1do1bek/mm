package com.enterprise.mm.service.approval;

import com.enterprise.mm.entity.ApprovalFlowStep;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalDetailItem;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalTaskDecisionRequest;
import com.enterprise.mm.payload.ApprovalDtos.DemandStockReviewResponse;
import java.util.List;
import java.util.UUID;

public interface ApprovalModuleAdapter {
    String moduleKey();
    /** Additional operation-specific keys handled by the same adapter. */
    default List<String> additionalModuleKeys() {
        return List.of();
    }
    /** If present, the task actor must differ from this requester. */
    default String requesterUsername(UUID entityId) {
        return null;
    }
    UUID companyId(UUID entityId);
    default UUID departmentId(UUID entityId) {
        return null;
    }
    default boolean usesRequestType() {
        return false;
    }
    default RequestType requestType(UUID entityId) {
        return null;
    }
    String displayName(UUID entityId);
    /** Gate sensitive source details before the generic worklist assembler loads history or files. */
    default void requireDetailRead(UUID entityId) {}
    void validateCanSubmit(UUID entityId);
    void markSubmitted(UUID entityId);
    default DemandStockReviewResponse demandStockReview(UUID entityId, ApprovalFlowStep step) {
        return null;
    }

    /**
     * Document lines for the approval worklist detail "sourceLines" tab.
     * Return {@code null} when this module does not support line context.
     * Return an empty list when the document exists but has no lines.
     */
    default List<ApprovalDetailItem> documentLines(UUID entityId) {
        return null;
    }

    /** Module-owned evidence; null retains the ordinary document attachment resolver. */
    default List<ApprovalDetailItem> evidence(UUID entityId) {
        return null;
    }

    /** Locks revision-sensitive documents and rejects a task from a superseded approval cycle. */
    default void lockApprovalRevision(UUID entityId, UUID approvalInstanceId, UUID taskId) {
    }

    default void beforeApproveTask(UUID entityId, ApprovalFlowStep step, ApprovalTaskDecisionRequest request) {
    }
    default Boolean isApprovalTaskActive(UUID entityId) {
        return Boolean.TRUE;
    }
    void markApproved(UUID entityId, String comment);
    void markRejected(UUID entityId, String comment);

    default void onSubmitted(UUID entityId) {
        markSubmitted(entityId);
    }

    default void onApproved(UUID entityId, String comment) {
        markApproved(entityId, comment);
    }

    default void onRejected(UUID entityId, String comment) {
        markRejected(entityId, comment);
    }

    default void onCancelled(UUID entityId, String comment) {
    }

    /**
     * Move the underlying document into the editable return-for-correction state.
     * Implementations must be idempotent because the approval engine can retry a
     * callback after a transaction boundary or stale task recovery.
     */
    default void onReturned(UUID entityId, String comment) {
    }
}
