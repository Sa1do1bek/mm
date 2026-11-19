package com.enterprise.mm.service.inventory;

import com.enterprise.mm.enums.InventoryLineStatus;
import com.enterprise.mm.payload.*;
import org.springframework.beans.BeanUtils;

/** Pure DTO projection. Blind access uses an allowlist so future review fields cannot leak. */
public final class InventoryReadPolicy {
    private InventoryReadPolicy() {}
    public static InventoryItemResponse project(InventoryItemResponse source, boolean visible) {
        if (source == null) return null;
        var row = new InventoryItemResponse();
        if (visible) { BeanUtils.copyProperties(source, row); return row; }
        row.setId(source.getId()); row.setTenantId(source.getTenantId()); row.setCompanyId(source.getCompanyId());
        row.setCode(source.getCode()); row.setName(source.getName()); row.setVersion(source.getVersion());
        row.setInventoryDocumentId(source.getInventoryDocumentId()); row.setInventoryDocumentCode(source.getInventoryDocumentCode()); row.setInventoryDocumentName(source.getInventoryDocumentName());
        row.setMaterialId(source.getMaterialId()); row.setMaterialCode(source.getMaterialCode()); row.setMaterialName(source.getMaterialName());
        row.setWarehouseId(source.getWarehouseId()); row.setWarehouseCode(source.getWarehouseCode()); row.setWarehouseName(source.getWarehouseName());
        row.setStorageLocationId(source.getStorageLocationId()); row.setStorageLocationCode(source.getStorageLocationCode()); row.setStorageLocationName(source.getStorageLocationName());
        row.setStorageBinId(source.getStorageBinId()); row.setStorageBinCode(source.getStorageBinCode()); row.setStorageBinName(source.getStorageBinName());
        row.setStockBatchId(source.getStockBatchId()); row.setStockBatchCode(source.getStockBatchCode()); row.setStockBatchName(source.getStockBatchName());
        row.setBatchNumber(source.getBatchNumber()); row.setSerialNumber(source.getSerialNumber()); row.setExpiryDate(source.getExpiryDate());
        row.setUnitCode(source.getUnitCode()); row.setUnitLabel(source.getUnitLabel()); row.setQuantityScale(source.getQuantityScale());
        row.setCountedQuantity(source.getCountedQuantity()); row.setCountedBy(source.getCountedBy()); row.setCountedAt(source.getCountedAt());
        row.setCountComment(source.getCountComment()); row.setFoundStock(source.isFoundStock());
        row.setCountEvidenceCount(source.getCountEvidenceCount());
        // Manual explanation tasks and the counter's response are actionable without exposing review calculations/reasons.
        row.setExplanationStatus(source.getExplanationStatus()); row.setExplanationRequestedAt(source.getExplanationRequestedAt());
        row.setExplanationText(source.getExplanationText()); row.setExplanationProvidedAt(source.getExplanationProvidedAt());
        row.setRecountRequestedAt(source.getRecountRequestedAt());
        row.setCountRevision(source.getCountRevision()); row.setCountState(source.getCountedQuantity() == null ? "UNCOUNTED" : "COUNTED");
        row.setLineStatus(neutral(source.getCountedQuantity())); row.setStatus(row.getCountState());
        return row;
    }
    public static InventoryLineHistoryResponse project(InventoryLineHistoryResponse source, boolean visible) {
        var row = new InventoryLineHistoryResponse();
        if (visible) { BeanUtils.copyProperties(source, row); return row; }
        row.setId(source.getId()); row.setInventoryDocumentId(source.getInventoryDocumentId()); row.setInventoryItemId(source.getInventoryItemId());
        row.setOldCountRevision(source.getOldCountRevision()); row.setNewCountRevision(source.getNewCountRevision());
        row.setChangedBy(source.getChangedBy()); row.setChangedAt(source.getChangedAt());
        row.setOldCountedQuantity(source.getOldCountedQuantity()); row.setNewCountedQuantity(source.getNewCountedQuantity());
        row.setOldStatus(neutral(source.getOldCountedQuantity())); row.setNewStatus(neutral(source.getNewCountedQuantity()));
        row.setAction(switch (source.getAction()) { case "COUNT", "CLEAR_COUNT", "REQUEST_RECOUNT" -> source.getAction(); default -> "REVIEW_UPDATED"; });
        return row;
    }
    private static InventoryLineStatus neutral(java.math.BigDecimal quantity) { return quantity == null ? InventoryLineStatus.NOT_COUNTED : InventoryLineStatus.COUNTED; }
}
