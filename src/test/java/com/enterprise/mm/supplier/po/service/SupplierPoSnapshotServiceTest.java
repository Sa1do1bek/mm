package com.enterprise.mm.supplier.po.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.enums.ProcurementLineType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SupplierPoSnapshotServiceTest {
    private final SupplierPoSnapshotService service = new SupplierPoSnapshotService(new ObjectMapper());

    @Test
    void sameInputProducesSameHashAndJson() {
        PurchaseOrder po = purchaseOrder();
        List<PurchaseOrderItem> items = List.of(item(po.getId(), new BigDecimal("10"), new BigDecimal("5.5")));

        SupplierPoSnapshotService.Snapshot first = service.build(po, items);
        SupplierPoSnapshotService.Snapshot second = service.build(po, items);

        assertThat(first.hash()).isEqualTo(second.hash());
        assertThat(first.json()).isEqualTo(second.json());
        assertThat(first.hash()).hasSize(64);
    }

    @Test
    void differentQuantityProducesDifferentHash() {
        PurchaseOrder po = purchaseOrder();
        List<PurchaseOrderItem> originalItems = List.of(item(po.getId(), new BigDecimal("10"), new BigDecimal("5.5")));
        List<PurchaseOrderItem> changedItems = List.of(item(po.getId(), new BigDecimal("12"), new BigDecimal("5.5")));

        SupplierPoSnapshotService.Snapshot original = service.build(po, originalItems);
        SupplierPoSnapshotService.Snapshot changed = service.build(po, changedItems);

        assertThat(original.hash()).isNotEqualTo(changed.hash());
    }

    private PurchaseOrder purchaseOrder() {
        PurchaseOrder po = new PurchaseOrder();
        po.setId(UUID.randomUUID());
        po.setCode("PO-1");
        po.setVersion(3L);
        po.setSupplierId(UUID.randomUUID());
        po.setCurrencyCode("UZS");
        po.setExchangeRate(BigDecimal.ONE);
        po.setTotalAmount(new BigDecimal("55.00"));
        return po;
    }

    private PurchaseOrderItem item(UUID purchaseOrderId, BigDecimal quantity, BigDecimal unitPrice) {
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setId(UUID.randomUUID());
        item.setPurchaseOrderId(purchaseOrderId);
        item.setLineType(ProcurementLineType.MATERIAL);
        item.setMaterialId(UUID.randomUUID());
        item.setQuantity(quantity);
        item.setUnitPrice(unitPrice);
        return item;
    }
}
