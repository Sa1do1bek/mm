package com.enterprise.mm.payload;

import com.enterprise.mm.enums.ApprovalAssigneeType;
import com.enterprise.mm.enums.ApprovalFlowType;
import com.enterprise.mm.enums.ApprovalHistoryAction;
import com.enterprise.mm.enums.ApprovalInstanceStatus;
import com.enterprise.mm.enums.ApprovalStepType;
import com.enterprise.mm.enums.ApprovalTaskStatus;
import com.enterprise.mm.enums.ApprovalRequestType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ApprovalDtos {
    private ApprovalDtos() {
    }

    public record ApprovalFlowRequest(
        UUID companyId,
        @Size(max = 80) String code,
        @NotBlank @Size(max = 240) String name,
        @NotBlank @Size(max = 80) String moduleKey,
        ApprovalFlowType flowType,
        UUID departmentId,
        ApprovalRequestType requestType,
        Boolean active,
        @Size(max = 50) String status,
        String description,
        List<@Valid ApprovalFlowStepRequest> steps,
        List<@Valid ApprovalFlowParallelAssigneeRequest> parallelAssignees
    ) {
    }

    public record ApprovalFlowResponse(
        UUID id,
        UUID companyId,
        String code,
        String name,
        String nameUz,
        String nameRu,
        String nameEn,
        String moduleKey,
        ApprovalFlowType flowType,
        UUID departmentId,
        ApprovalRequestType requestType,
        boolean active,
        String status,
        String description,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<ApprovalFlowStepResponse> steps,
        List<ApprovalFlowParallelAssigneeResponse> parallelAssignees
    ) {
    }

    public record ApprovalFlowParallelAssigneeRequest(
        UUID assigneeUserId,
        @Size(max = 120) String assigneeUsername
    ) {
    }

    public record ApprovalFlowParallelAssigneeResponse(
        UUID id,
        UUID approvalFlowId,
        UUID assigneeUserId,
        String assigneeUsername,
        String code,
        String name
    ) {
    }

    public record ApprovalFlowStepRequest(
        @Size(max = 80) String code,
        @Size(max = 240) String name,
        @NotNull Integer stepOrder,
        ApprovalStepType stepType,
        UUID warehouseId,
        UUID departmentId,
        @NotNull ApprovalAssigneeType assigneeType,
        @Size(max = 80) String assigneeRole,
        UUID assigneeUserId,
        @Size(max = 120) String assigneeUsername,
        Boolean canReturn,
        Boolean isApprover,
        Boolean commentRequired,
        String description
    ) {
    }

    public record ApprovalFlowStepResponse(
        UUID id,
        UUID approvalFlowId,
        Integer stepOrder,
        ApprovalStepType stepType,
        UUID warehouseId,
        UUID departmentId,
        ApprovalAssigneeType assigneeType,
        String assigneeRole,
        UUID assigneeUserId,
        String assigneeUsername,
        boolean canReturn,
        boolean isApprover,
        boolean commentRequired,
        String code,
        String name,
        String description
    ) {
    }

    public record ApprovalFlowHierarchyTemplateResponse(
        UUID departmentId,
        List<ApprovalFlowStepRequest> steps
    ) {
    }

    public record ApprovalTaskDecisionRequest(
        String comment,
        Integer returnToStepOrder,
        List<DemandStockReviewLineDecision> demandStockReviewLines
    ) {
        public ApprovalTaskDecisionRequest(String comment, Integer returnToStepOrder) {
            this(comment, returnToStepOrder, null);
        }
    }

    public record DemandStockReviewLineDecision(
        UUID demandPlanItemId,
        BigDecimal approvedQuantity,
        String changeReason,
        BigDecimal stockFulfillmentQuantity
    ) {
        public DemandStockReviewLineDecision(UUID demandPlanItemId, BigDecimal approvedQuantity, String changeReason) {
            this(demandPlanItemId, approvedQuantity, changeReason, null);
        }
    }

    public record DemandStockReviewResponse(
        UUID warehouseId,
        String warehouseName,
        List<DemandStockReviewLineResponse> lines
    ) {
    }

    public record DemandStockReviewLineResponse(
        UUID demandPlanItemId,
        UUID materialId,
        String materialName,
        String unitOfMeasureShortName,
        BigDecimal requestedQuantity,
        BigDecimal approvedQuantity,
        BigDecimal availableStock,
        BigDecimal differenceQuantity,
        BigDecimal recommendedApprovedQuantity,
        BigDecimal stockFulfillmentQuantity,
        String changeReason
    ) {
        public DemandStockReviewLineResponse(UUID demandPlanItemId, UUID materialId, String materialName,
            String unitOfMeasureShortName, BigDecimal requestedQuantity, BigDecimal approvedQuantity,
            BigDecimal availableStock, BigDecimal differenceQuantity, BigDecimal recommendedApprovedQuantity, String changeReason) {
            this(demandPlanItemId, materialId, materialName, unitOfMeasureShortName, requestedQuantity, approvedQuantity,
                availableStock, differenceQuantity, recommendedApprovedQuantity, BigDecimal.ZERO, changeReason);
        }
    }

    public record ApprovalTaskResponse(
        UUID id,
        UUID approvalInstanceId,
        String moduleKey,
        UUID entityId,
        String entityName,
        Integer stepOrder,
        ApprovalStepType stepType,
        DemandStockReviewResponse demandStockReview,
        ApprovalAssigneeType assigneeType,
        String assigneeRole,
        UUID assigneeUserId,
        String assigneeFullname,
        ApprovalTaskStatus taskStatus,
        String actedBy,
        LocalDateTime actedAt,
        String commentText,
        LocalDateTime createdAt,
        Integer totalSteps,
        List<ApprovalStepProgressView> steps,
        boolean commentRequired,
        boolean canReturn,
        boolean isApprover,
        LocalDateTime dueAt,
        long ageHours,
        String ageBucket,
        String dueState,
        String severity,
        BigDecimal sourceValue,
        String currencyCode,
        String nextAction,
        List<String> allowedActions,
        int completedSteps,
        String sourceDataStatus
    ) {
    }

    public record ApprovalStepProgressView(
        Integer stepOrder,
        ApprovalTaskStatus status,
        ApprovalAssigneeType assigneeType,
        String assigneeUsername,
        String assigneeName,
        String assigneeRole,
        String actedBy,
        String actedByName,
        LocalDateTime actedAt,
        String commentText,
        boolean isApprover
    ) {
    }


    public record ApprovalTaskSummaryResponse(
        long pendingCount,
        long overdueCount,
        long dueSoonCount,
        List<ApprovalAgeBucketCount> ageBuckets,
        LocalDateTime generatedAt,
        int slaHours
    ) {
        public ApprovalTaskSummaryResponse(long pendingCount) {
            this(pendingCount, 0, 0, List.of(), LocalDateTime.now(), 48);
        }
    }

    public record ApprovalAgeBucketCount(
        String key,
        String label,
        long count
    ) {
    }

    public record ApprovalWorklistFilter(
        UUID taskId,
        UUID entityId,
        String search,
        String moduleKey,
        String status,
        LocalDateTime createdFrom,
        LocalDateTime createdTo,
        String dueState,
        String ageBucket
    ) {
    }

    public record ApprovalTaskDetailResponse(
        ApprovalTaskResponse task,
        ApprovalDetailSection source,
        ApprovalDetailSection sourceLines,
        ApprovalDetailSection evidence,
        ApprovalDetailSection workflow,
        ApprovalDetailSection history,
        ApprovalDetailSection relatedDocuments,
        ApprovalDetailSection integrationImpact,
        ApprovalDetailSection auditTrail
    ) {
    }

    public record ApprovalDetailSection(
        String key,
        String status,
        String message,
        List<ApprovalDetailItem> items
    ) {
        public ApprovalDetailSection {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record ApprovalDetailItem(
        String id,
        String label,
        Map<String, Object> values
    ) {
        public ApprovalDetailItem {
            values = values == null ? Map.of() : Map.copyOf(values);
        }
    }

    public record ApprovalInstanceResponse(
        UUID id,
        UUID approvalFlowId,
        String approvalFlowName,
        String moduleKey,
        UUID entityId,
        String entityName,
        ApprovalInstanceStatus approvalStatus,
        Integer currentStepOrder,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        List<ApprovalTaskResponse> tasks
    ) {
    }

    public record ApprovalFlowPreviewResponse(
        String approvalFlowName,
        List<ApprovalStepProgressView> steps
    ) {
    }

    public record ApprovalHistoryResponse(
        UUID id,
        UUID approvalInstanceId,
        ApprovalHistoryAction action,
        String actor,
        Integer fromStepOrder,
        Integer toStepOrder,
        String commentText,
        LocalDateTime actionAt
    ) {
    }
}
