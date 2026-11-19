package com.enterprise.mm.service;

import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.enums.SerialTrackingPolicy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** Shared nonserialized worker position eligibility; commands still lock and check quantity. */
public final class StockPositionPolicy {
    private StockPositionPolicy() {}
    public static String blockedReason(StockBalance balance) {
        return blockedReason(balance, false);
    }
    public static String blockedReason(StockBalance balance, boolean serializedSelectionSupported) {
        var batch = balance.getStockBatch(); var material = balance.getMaterial();
        if (inactive(material) || inactive(balance.getWarehouse()) || inactive(balance.getStorageLocation()) || inactive(balance.getStorageBin())) return "inactive";
        if (balance.isDeleted() || material == null || material.isDeleted() || batch == null || batch.isDeleted()
            || List.of("INACTIVE", "CANCELLED", "BLOCKED").contains(String.valueOf(balance.getStatus()))
            || List.of("INACTIVE", "CANCELLED", "BLOCKED").contains(String.valueOf(batch.getStatus()))) return "inactive";
        if (!Objects.equals(batch.getMaterialId(), balance.getMaterialId())
            || !Objects.equals(batch.getWarehouseId(), balance.getWarehouseId())
            || material.getUnitOfMeasureId() == null) return "incompatible";
        LocalDate expiry = batch.getExpiresOn() == null ? batch.getExpirationDate() : batch.getExpiresOn();
        if (expiry != null && expiry.isBefore(LocalDate.now())) return "expired";
        if (!serializedSelectionSupported && material.getSerialTrackingPolicy() == SerialTrackingPolicy.REQUIRED) return "capability";
        return null;
    }
    private static boolean inactive(com.enterprise.mm.common.BusinessEntity entity) {
        return entity != null && (entity.isDeleted() || List.of("INACTIVE", "CANCELLED", "BLOCKED").contains(String.valueOf(entity.getStatus())));
    }
    public static String unavailableReason(StockBalance balance, BigDecimal executable) {
        String reason = blockedReason(balance);
        if (reason != null) return reason;
        if (executable.signum() <= 0) return balance.getQuarantineQuantity() != null
            && balance.getQuarantineQuantity().signum() > 0 ? "hold" : "exhausted";
        return null;
    }
}
