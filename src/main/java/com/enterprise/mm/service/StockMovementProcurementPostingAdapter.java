package com.enterprise.mm.service;

import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.payload.StockMovementResponse;
import com.enterprise.mm.procurement.port.ProcurementStockPostingPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StockMovementProcurementPostingAdapter implements ProcurementStockPostingPort {
    private final StockMovementService stockMovementService;
    private final StockAccountResolver stockAccountResolver;

    @Override
    public PostedReceipt postAcceptedReceipt(AcceptedReceipt receipt) {
        StockMovement movement = new StockMovement();
        movement.setCode(receipt.code());
        movement.setName(receipt.name());
        movement.setStatus("DRAFT");
        movement.setMaterialId(receipt.materialId());
        movement.setWarehouseId(receipt.warehouseId());
        movement.setStorageLocationId(receipt.storageLocationId());
        movement.setStorageBinId(receipt.storageBinId());
        movement.setBatchNumber(receipt.batchNumber());
        movement.setMovementType(MovementType.RECEIPT);
        movement.setQuantity(receipt.quantity());
        movement.setUnitCost(receipt.unitCost());
        movement.setSourceCurrencyCodeSnapshot(receipt.sourceCurrencyCode());
        movement.setSourceExchangeRateSnapshot(receipt.sourceExchangeRate());
        movement.setSourceUnitPriceSnapshot(receipt.sourceUnitPrice());
        stockAccountResolver.findForMaterial(receipt.materialId())
            .ifPresent(account -> movement.setAccountCodeSnapshot(account.accountCode()));
        movement.setReferenceType("PurchaseOrder");
        movement.setReferenceId(receipt.purchaseOrderId());
        StockMovementResponse posted = stockMovementService.postPurchasingReceipt(movement);
        return new PostedReceipt(posted.getId(), posted.getStockBatchId(), posted.getMovementDate());
    }
}
