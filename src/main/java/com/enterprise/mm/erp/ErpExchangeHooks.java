package com.enterprise.mm.erp;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
public final class ErpExchangeHooks {
    private ErpExchangeHooks() {}
    public static void accept(ErpExchangeService exchange, String kind, UUID documentId, Long version, LocalDate date, String operation, UUID source, UUID destination, List<ErpExchangeService.Line> lines) {
        if (exchange != null) exchange.acceptWarehouseDocument(kind, documentId, version == null ? 0L : version, date, operation, source, destination, lines);
    }
    public static void requireSupportedReceipt(boolean exchangeEnabled, java.math.BigDecimal damagedQuantity) {
        if (exchangeEnabled && damagedQuantity != null && damagedQuantity.signum() > 0)
            throw new com.enterprise.mm.error.BusinessException("Damaged transfer receipt requires an explicit 1C quarantine mapping", "ERP_QUARANTINE_MAPPING_REQUIRED");
    }
}
