package com.enterprise.mm.warehouse.packaging;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.UUID;

public final class ReturnablePackagingDtos {
    private ReturnablePackagingDtos() {}

    public record LotView(UUID id, String code, Long version, UUID supplierId, String supplierName,
        UUID materialId, String materialName, UUID contentMaterialId, String contentMaterialName,
        UUID goodsReceiptId, UUID goodsReceiptLineId, UUID warehouseId, String warehouseName, String ownership,
        String accountCode, BigDecimal unitCost, BigDecimal amount, BigDecimal receivedQuantity, BigDecimal filledQuantity,
        BigDecimal partialQuantity, BigDecimal emptyQuantity, BigDecimal damagedQuantity,
        BigDecimal reservedQuantity, BigDecimal dispatchedQuantity, BigDecimal confirmedQuantity,
        BigDecimal onHandQuantity, BigDecimal awaitingSupplierConfirmation, String sourceType,
        UUID manualDocumentId, UUID manualContentMovementId, LocalDate dueDate, String accountPostingMode,
        LocalDateTime createdAt, BigDecimal unknownQuantity, UUID legacyStockBalanceId,
        UUID receiptStockMovementId) {}

    public record ReturnView(UUID id, String code, Long version, UUID lotId, UUID supplierId,
        BigDecimal quantity, BigDecimal confirmedQuantity, BigDecimal recalledQuantity, String status, String documentNumber,
        String reason, LocalDateTime approvedAt, LocalDateTime dispatchedAt, LocalDateTime confirmedAt,
        String recallReason, LocalDateTime recalledAt, LocalDateTime createdAt, String supplierName) {}

    public record ReturnRegisterView(ReturnView document, LotView lot) {}

    public record LotFilters(String search, UUID supplierId, UUID materialId, UUID contentMaterialId,
        UUID warehouseId, String ownership, String sourceType, String state,
        LocalDate dueFrom, LocalDate dueTo, boolean onlyOnHand) {}
    public record ReturnFilters(String search, UUID supplierId, UUID materialId, UUID warehouseId,
        String ownership, String status) {}

    public record EventView(UUID id, String eventType, BigDecimal quantity, String fromState, String toState,
        UUID returnId, UUID stockMovementId, UUID postingBatchId, LocalDateTime createdAt, String createdBy,
        String reason, UUID contentMovementId) {}

    public record ChangeStateRequest(BigDecimal quantity, String fromState, String toState, Long expectedVersion,
        String reason, UUID contentMovementId) {
        public ChangeStateRequest(BigDecimal quantity, String fromState, String toState, Long expectedVersion) {
            this(quantity, fromState, toState, expectedVersion, null, null);
        }
    }
    public record CreateReturnRequest(UUID lotId, BigDecimal quantity, String reason, UUID supplierId) {
        public CreateReturnRequest(UUID lotId, BigDecimal quantity, String reason) {
            this(lotId, quantity, reason, null);
        }
    }
    public record VersionRequest(Long expectedVersion) {}
    public record DispatchRequest(Long expectedVersion, String documentNumber) {}
    public record ConfirmRequest(Long expectedVersion, BigDecimal acceptedQuantity) {}
    public record RecallRequest(Long expectedVersion, String reason, BigDecimal quantity) {
        public RecallRequest(Long expectedVersion, String reason) { this(expectedVersion, reason, null); }
    }
}
