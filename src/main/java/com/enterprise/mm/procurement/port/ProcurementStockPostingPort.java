package com.enterprise.mm.procurement.port;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public interface ProcurementStockPostingPort {

    PostedReceipt postAcceptedReceipt(AcceptedReceipt receipt);

    record AcceptedReceipt(
        String code,
        String name,
        UUID materialId,
        UUID warehouseId,
        UUID storageLocationId,
        UUID storageBinId,
        String batchNumber,
        BigDecimal quantity,
        BigDecimal unitCost,
        UUID purchaseOrderId,
        String sourceCurrencyCode,
        BigDecimal sourceExchangeRate,
        BigDecimal sourceUnitPrice
    ) {
        public AcceptedReceipt(String code, String name, UUID materialId, UUID warehouseId,
            UUID storageLocationId, UUID storageBinId, String batchNumber, BigDecimal quantity,
            BigDecimal unitCost, UUID purchaseOrderId, String sourceCurrencyCode, BigDecimal sourceExchangeRate) {
            this(code, name, materialId, warehouseId, storageLocationId, storageBinId, batchNumber,
                quantity, unitCost, purchaseOrderId, sourceCurrencyCode, sourceExchangeRate, null);
        }
        public AcceptedReceipt(
            String code,
            String name,
            UUID materialId,
            UUID warehouseId,
            UUID storageLocationId,
            UUID storageBinId,
            String batchNumber,
            BigDecimal quantity,
            BigDecimal unitCost,
            UUID purchaseOrderId
        ) {
            this(code, name, materialId, warehouseId, storageLocationId, storageBinId, batchNumber,
                quantity, unitCost, purchaseOrderId, "UZS", BigDecimal.ONE);
        }
    }

    record PostedReceipt(UUID stockMovementId, UUID stockBatchId, LocalDate movementDate) {
        public PostedReceipt(UUID stockMovementId, LocalDate movementDate) {
            this(stockMovementId, null, movementDate);
        }
    }
}
