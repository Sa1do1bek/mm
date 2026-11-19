package com.enterprise.mm.supplier.po.service;

import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.error.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Builds the immutable PO+lines JSON snapshot (and its SHA-256 hash) captured by a
 * {@link com.enterprise.mm.entity.SupplierPoDispatch} at dispatch time. The snapshot
 * is the durable record of exactly what commercial terms were sent to the supplier
 * for a given PO version -- it is never re-derived from the live PO after dispatch.
 */
@Service
@RequiredArgsConstructor
public class SupplierPoSnapshotService {
    private final ObjectMapper objectMapper;

    public record Snapshot(String json, String hash) {
    }

    public Snapshot build(PurchaseOrder purchaseOrder, List<PurchaseOrderItem> items) {
        if (purchaseOrder == null) {
            throw new BusinessException("Purchase order is required to build a supplier dispatch snapshot");
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.put("purchaseOrderId", purchaseOrder.getId() == null ? null : purchaseOrder.getId().toString());
        root.put("purchaseOrderVersion", purchaseOrder.getVersion());
        root.put("code", purchaseOrder.getCode());
        root.put("supplierId", purchaseOrder.getSupplierId() == null ? null : purchaseOrder.getSupplierId().toString());
        root.put("contractId", purchaseOrder.getContractId() == null ? null : purchaseOrder.getContractId().toString());
        root.put("requestType", purchaseOrder.getRequestType() == null ? null : purchaseOrder.getRequestType().name());
        root.put("orderDate", purchaseOrder.getOrderDate() == null ? null : purchaseOrder.getOrderDate().toString());
        root.put("expectedDeliveryDate", purchaseOrder.getExpectedDeliveryDate() == null ? null : purchaseOrder.getExpectedDeliveryDate().toString());
        root.put("currencyCode", purchaseOrder.getCurrencyCode());
        root.put("exchangeRate", nvl(purchaseOrder.getExchangeRate()).toPlainString());
        root.put("totalAmount", nvl(purchaseOrder.getTotalAmount()).toPlainString());

        ArrayNode lines = root.putArray("items");
        items.stream()
            .sorted(Comparator.comparing(item -> item.getId() == null ? "" : item.getId().toString()))
            .forEach(item -> lines.add(lineNode(item)));

        String json = root.toString();
        return new Snapshot(json, sha256(json));
    }

    /** Immutable per-line snapshot captured on a {@code SupplierPoResponseLine} --
     * the ordered qty/price/date this particular line carried at dispatch time. */
    public String lineSnapshotJson(PurchaseOrderItem item) {
        return lineNode(item).toString();
    }

    private ObjectNode lineNode(PurchaseOrderItem item) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("purchaseOrderItemId", item.getId() == null ? null : item.getId().toString());
        node.put("lineType", item.getLineType() == null ? null : item.getLineType().name());
        node.put("materialId", item.getMaterialId() == null ? null : item.getMaterialId().toString());
        node.put("quantity", nvl(item.getQuantity()).toPlainString());
        node.put("unitPrice", nvl(item.getUnitPrice()).toPlainString());
        node.put("unit", item.getUnit());
        node.put("period", item.getPeriod());
        node.put("stage", item.getStage());
        node.put("serviceCategory", item.getServiceCategory());
        node.put("serviceDescription", item.getServiceDescription());
        return node;
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot hash supplier PO dispatch snapshot", exception);
        }
    }
}
