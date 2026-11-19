package com.enterprise.mm.warehouse.custody;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public class CustodyCommands {

    public record CustodyReceiptCommand(
        String commandId,
        UUID materialId,
        UUID warehouseId,
        UUID ownerId,
        BigDecimal quantity,
        String baseUnitCode,
        LocalDate receiptDate,
        String sourceReference
    ) {}

    public record CustodyDispatchCommand(
        String commandId,
        UUID materialId,
        UUID warehouseId,
        UUID ownerId,
        BigDecimal quantity,
        String baseUnitCode,
        LocalDate dispatchDate,
        String sourceReference
    ) {}

    public record CustodyOwnershipTransferCommand(
        String commandId,
        UUID materialId,
        UUID warehouseId,
        UUID sourceOwnerId,
        BigDecimal quantity,
        BigDecimal unitSettlementPrice,
        String targetAccountCode,
        String baseUnitCode,
        LocalDate settlementDate,
        String sourceReference
    ) {}

    public record CustodyReceiptResult(
        String commandId,
        UUID materialId,
        UUID warehouseId,
        UUID ownerId,
        BigDecimal receiptQuantity,
        String accountCode,
        String stockCategory,
        BigDecimal financialAssetValue,
        LocalDate receiptDate,
        List<String> allowedActions
    ) {
        public CustodyReceiptResult(
            String commandId,
            UUID materialId,
            UUID warehouseId,
            UUID ownerId,
            BigDecimal receiptQuantity,
            String accountCode,
            String stockCategory,
            BigDecimal financialAssetValue,
            LocalDate receiptDate
        ) {
            this(commandId, materialId, warehouseId, ownerId, receiptQuantity, accountCode, stockCategory, financialAssetValue, receiptDate, List.of("DISPATCH", "TRANSFER_OWNERSHIP", "VIEW_STATEMENT"));
        }
    }

    public record CustodyDispatchResult(
        String commandId,
        UUID materialId,
        UUID warehouseId,
        UUID ownerId,
        BigDecimal dispatchedQuantity,
        String accountCode,
        String stockCategory,
        LocalDate dispatchDate,
        List<String> allowedActions
    ) {
        public CustodyDispatchResult(
            String commandId,
            UUID materialId,
            UUID warehouseId,
            UUID ownerId,
            BigDecimal dispatchedQuantity,
            String accountCode,
            String stockCategory,
            LocalDate dispatchDate
        ) {
            this(commandId, materialId, warehouseId, ownerId, dispatchedQuantity, accountCode, stockCategory, dispatchDate, List.of("VIEW_STATEMENT"));
        }
    }

    public record CustodyOwnershipTransferResult(
        String commandId,
        UUID materialId,
        UUID warehouseId,
        UUID sourceOwnerId,
        BigDecimal transferredQuantity,
        BigDecimal unitSettlementPrice,
        BigDecimal totalSettlementValue,
        String sourceAccountCode,
        String targetAccountCode,
        LocalDate settlementDate,
        List<String> allowedActions
    ) {
        public CustodyOwnershipTransferResult(
            String commandId,
            UUID materialId,
            UUID warehouseId,
            UUID sourceOwnerId,
            BigDecimal transferredQuantity,
            BigDecimal unitSettlementPrice,
            BigDecimal totalSettlementValue,
            String sourceAccountCode,
            String targetAccountCode,
            LocalDate settlementDate
        ) {
            this(commandId, materialId, warehouseId, sourceOwnerId, transferredQuantity, unitSettlementPrice, totalSettlementValue, sourceAccountCode, targetAccountCode, settlementDate, List.of("VIEW_STATEMENT"));
        }
    }

    public record ActivateCustodyCommand(
        String sourceReferenceId,
        String sourceHash,
        UUID materialId,
        UUID warehouseId,
        UUID ownerId,
        BigDecimal quantity,
        BigDecimal referenceAmount,
        String currencyCode,
        String baseUnitCode,
        LocalDate activationDate
    ) {}

    public record CustodyActivationResult(
        String sourceReferenceId,
        String sourceHash,
        UUID materialId,
        UUID warehouseId,
        UUID ownerId,
        BigDecimal activatedQuantity,
        BigDecimal referenceAmount,
        String accountCode,
        String stockCategory,
        boolean isIdempotentReplay,
        LocalDate activationDate
    ) {}

    public record CustodyStatementQuery(
        UUID ownerId,
        UUID warehouseId,
        UUID materialId,
        LocalDate fromDate,
        LocalDate toDate
    ) {}

    public record CustodyStatementPosition(
        UUID materialId,
        UUID warehouseId,
        String accountCode,
        String stockCategory,
        BigDecimal closingQuantity,
        BigDecimal companyAssetValue,
        BigDecimal referenceAmount,
        BigDecimal reconciledDelta
    ) {}

    public record CustodyStatementReport(
        String agreementNumber,
        UUID ownerId,
        String counterpartyName,
        List<CustodyStatementPosition> positions
    ) {}
}
