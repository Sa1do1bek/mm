package com.enterprise.mm.supply.workspace.repository;

import com.enterprise.mm.supply.workspace.domain.SupplyCommitmentKey;
import com.enterprise.mm.supply.workspace.enums.SupplyDataQuality;
import com.enterprise.mm.supply.workspace.enums.SupplyDueDateSource;
import com.enterprise.mm.supply.workspace.enums.SupplyEscalationState;
import com.enterprise.mm.supply.workspace.enums.SupplySeverity;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record SupplyCommitmentRecord(
    SupplyCommitmentKey key,
    UUID companyId,
    UUID purchaseOrderId,
    String purchaseOrderCode,
    String purchaseOrderName,
    UUID supplierId,
    String supplierCode,
    String supplierName,
    UUID contractId,
    LocalDate operationalDate,
    String status,
    String currencyCode,
    BigDecimal openAmount,
    BigDecimal openQuantity,
    String uomCode,
    SupplyDataQuality dataQuality,
    SupplySeverity severity,
    boolean isOverdueCommitment,
    boolean isArriving7dWithoutConfirmedAsn,
    boolean isActiveImportEtaRisk,
    boolean isCustomsDocumentBlocked,
    boolean isIntegrationFailed,
    boolean isUnassigned,
    UUID ownerUserId,
    String ownerUsername,
    String ownerRole,
    LocalDate assignmentDueDate,
    Long assignmentVersion,
    SupplyDueDateSource assignmentDueDateSource,
    SupplySeverity assignmentSeverity,
    SupplyEscalationState assignmentEscalationState,
    Integer assignmentEscalationLevel,
    String assignmentReason,
    BigDecimal plannedQuantity,
    BigDecimal acceptedQuantity,
    Boolean hasOpenQuantity
) {
    public SupplyCommitmentRecord(
        SupplyCommitmentKey key,
        UUID companyId,
        UUID purchaseOrderId,
        String purchaseOrderCode,
        String purchaseOrderName,
        UUID supplierId,
        String supplierCode,
        String supplierName,
        UUID contractId,
        LocalDate operationalDate,
        String status,
        String currencyCode,
        BigDecimal openAmount,
        BigDecimal openQuantity,
        String uomCode,
        SupplyDataQuality dataQuality,
        SupplySeverity severity,
        boolean isOverdueCommitment,
        boolean isArriving7dWithoutConfirmedAsn,
        boolean isActiveImportEtaRisk,
        boolean isCustomsDocumentBlocked,
        boolean isIntegrationFailed,
        boolean isUnassigned,
        UUID ownerUserId,
        String ownerUsername,
        String ownerRole,
        LocalDate assignmentDueDate,
        Long assignmentVersion
    ) {
        this(
            key, companyId, purchaseOrderId, purchaseOrderCode, purchaseOrderName,
            supplierId, supplierCode, supplierName, contractId, operationalDate,
            status, currencyCode, openAmount, openQuantity, uomCode, dataQuality,
            severity, isOverdueCommitment, isArriving7dWithoutConfirmedAsn,
            isActiveImportEtaRisk, isCustomsDocumentBlocked, isIntegrationFailed,
            isUnassigned, ownerUserId, ownerUsername, ownerRole, assignmentDueDate,
            assignmentVersion, null, null, null, null, null, null, null, null);
    }

    public record MoneyValue(String currencyCode, BigDecimal amount) {
    }

    public record QuantityValue(String uomCode, BigDecimal value) {
    }

    public record AgendaDayCount(LocalDate date, long count, SupplySeverity highestSeverity) {
    }
}
