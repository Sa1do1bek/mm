package com.enterprise.mm.payload;

import com.enterprise.mm.enums.RfqAggregationMode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class RfqAggregationDtos {
    private RfqAggregationDtos() {
    }

    public record AggregationPreviewFilter(
        RfqAggregationMode groupBy,
        UUID materialId,
        UUID materialGroupId,
        UUID departmentId,
        LocalDate requiredDateFrom,
        LocalDate requiredDateTo,
        String search) {
    }

    public record CreateAggregatedRfqRequest(
        @NotNull RfqAggregationMode groupBy,
        @NotBlank String groupKey,
        @NotEmpty List<UUID> purchaseRequestItemIds,
        @NotNull LocalDate dueDate,
        @NotBlank String tenderProcedureType,
        @NotBlank String selectionRule,
        @Min(1) Integer minQuotationsRequired,
        String responsibleBuyer,
        String responseInstructions,
        String packageLanguage) {
        public CreateAggregatedRfqRequest(
            RfqAggregationMode groupBy,
            String groupKey,
            List<UUID> purchaseRequestItemIds,
            LocalDate dueDate,
            String tenderProcedureType,
            String selectionRule,
            Integer minQuotationsRequired
        ) {
            this(
                groupBy,
                groupKey,
                purchaseRequestItemIds,
                dueDate,
                tenderProcedureType,
                selectionRule,
                minQuotationsRequired,
                null,
                null,
                "ru"
            );
        }
    }

    public record CreateCampaignRfqRequest(
        LocalDate dueDate,
        String responsibleBuyer,
        String responseInstructions,
        String packageLanguage,
        List<UUID> invitedSupplierIds,
        UUID commissionId,
        RfqAggregationMode groupBy,
        String groupKey,
        List<UUID> purchaseRequestItemIds,
        String selectionRule,
        Integer minQuotationsRequired
    ) {
        public boolean hasAggregationSelection() {
            return purchaseRequestItemIds != null && !purchaseRequestItemIds.isEmpty();
        }
    }

    public record AwardContractRequest(String reason) {
    }

    public record RfqAggregationPreviewGroup(
        String groupKey,
        RfqAggregationMode groupBy,
        UUID companyId,
        String currencyCode,
        UUID materialGroupId,
        String materialGroupCode,
        String materialGroupName,
        BigDecimal totalQuantity,
        BigDecimal estimatedAmount,
        int purchaseRequestCount,
        int itemCount,
        LocalDate earliestRequiredDate,
        LocalDate latestRequiredDate,
        List<Reference> departments,
        List<Reference> budgetItems,
        List<RfqAggregationPreviewLine> lines,
        List<RfqAggregationSourceRow> sources) {
    }

    public record RfqAggregationPreviewLine(
        UUID materialId,
        String materialCode,
        String materialName,
        String unit,
        String normalizedUnitKey,
        BigDecimal quantity,
        BigDecimal estimatedAmount,
        int sourceItemCount,
        List<RfqAggregationSourceRow> sources) {
    }

    public record RfqAggregationSourceRow(
        UUID purchaseRequestId,
        UUID purchaseRequestItemId,
        String purchaseRequestCode,
        String purchaseRequestName,
        String purchaseRequestItemCode,
        UUID departmentId,
        String departmentCode,
        String departmentName,
        UUID budgetItemId,
        String budgetItemCode,
        String budgetItemName,
        UUID materialId,
        String materialCode,
        String materialName,
        BigDecimal quantity,
        BigDecimal estimatedPrice,
        BigDecimal estimatedAmount,
        String unit,
        String normalizedUnitKey,
        LocalDate requiredDate,
        String currencyCode) {
    }

    public record CreateAggregatedRfqResponse(
        UUID rfqId,
        int rfqItemCount,
        int sourcePurchaseRequestCount,
        int sourceItemCount,
        List<CreatedRfqLine> lines,
        List<AffectedPurchaseRequestStatus> affectedPurchaseRequests) {
    }

    public record CreatedRfqLine(
        UUID rfqItemId,
        UUID materialId,
        String unit,
        String normalizedUnitKey,
        BigDecimal quantity,
        int sourceItemCount) {
    }

    public record AffectedPurchaseRequestStatus(
        UUID purchaseRequestId,
        String previousStatus,
        String currentStatus) {
    }

    public record RfqSourcesResponse(
        UUID rfqId,
        boolean aggregatedTender,
        boolean legacySourceUnavailable,
        int sourcePurchaseRequestCount,
        int sourceItemCount,
        List<RfqSourceLine> lines) {
    }

    public record RfqSourceLine(
        UUID rfqItemId,
        UUID materialId,
        String materialCode,
        String materialName,
        String unit,
        BigDecimal quantity,
        List<RfqSourceAllocation> sources) {
    }

    public record RfqSourceAllocation(
        UUID sourceId,
        UUID purchaseRequestId,
        UUID purchaseRequestItemId,
        BigDecimal allocatedQuantity,
        LocalDate sourceRequiredDate,
        UUID sourceDepartmentId,
        UUID sourceBudgetItemId,
        String sourceCurrencyCode,
        String sourceUnit,
        String status,
        LocalDateTime allocatedAt,
        LocalDateTime releasedAt) {
    }

    public record CancelRfqResponse(
        UUID rfqId,
        int releasedSourceCount,
        List<AffectedPurchaseRequestStatus> affectedPurchaseRequests) {
    }

    public record AwardContractResponse(
        UUID rfqId,
        UUID quotationId,
        UUID supplierId,
        UUID contractId,
        int contractSpecificationCount) {
    }

    public record RfqAggregationDocumentScope(
        String tenantId,
        UUID companyId,
        boolean documentFilteringRequired,
        Set<String> ownPrincipals,
        Set<String> teamPrincipals,
        Set<UUID> allowedDepartmentIds) {
    }

    public record RfqAggregationGroupKeyRow(
        String groupKey,
        RfqAggregationMode groupBy,
        UUID companyId,
        String currencyCode,
        UUID materialId,
        String materialCode,
        String materialName,
        String unit,
        String normalizedUnitKey,
        UUID materialGroupId,
        String materialGroupCode,
        String materialGroupName,
        BigDecimal totalQuantity,
        BigDecimal estimatedAmount,
        long purchaseRequestCount,
        long itemCount,
        LocalDate earliestRequiredDate,
        LocalDate latestRequiredDate) {
    }

    public record RfqAggregationCandidateRow(
        String groupKey,
        UUID purchaseRequestId,
        UUID purchaseRequestItemId,
        String purchaseRequestCode,
        String purchaseRequestName,
        String purchaseRequestItemCode,
        UUID companyId,
        String currencyCode,
        UUID departmentId,
        String departmentCode,
        String departmentName,
        UUID budgetItemId,
        String budgetItemCode,
        String budgetItemName,
        LocalDate requiredDate,
        UUID materialId,
        String materialCode,
        String materialName,
        UUID materialGroupId,
        String materialGroupCode,
        String materialGroupName,
        String unit,
        String normalizedUnitKey,
        BigDecimal quantity,
        BigDecimal estimatedPrice) {
    }

    public record Reference(UUID id, String code, String name) {
    }
}
