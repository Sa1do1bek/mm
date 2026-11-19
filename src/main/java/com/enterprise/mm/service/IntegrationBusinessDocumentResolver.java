package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.IntegrationEvent;
import com.enterprise.mm.payload.IntegrationHealthWorkspaceDtos.BusinessDocumentReference;
import com.enterprise.mm.security.AccessScope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class IntegrationBusinessDocumentResolver {
    private static final String ENTITY_PACKAGE = "com.enterprise.mm.entity.";
    private static final Map<String, Descriptor> DESCRIPTORS = descriptors();
    private static final Map<String, String> PAYLOAD_ID_TYPES = payloadIdTypes();

    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;

    public IntegrationBusinessDocumentResolver(EntityManager entityManager, ObjectMapper objectMapper) {
        this.entityManager = entityManager;
        this.objectMapper = objectMapper;
    }

    public BusinessDocumentReference resolve(IntegrationEvent event) {
        Candidate candidate = directCandidate(event);
        if (candidate == null) candidate = payloadCandidate(event.getPayload());
        if (candidate == null) {
            return new BusinessDocumentReference(
                humanize(event.getAggregateType()),
                event.getAggregateId(),
                null,
                "Document identity unavailable",
                null,
                null,
                null,
                false
            );
        }
        Descriptor descriptor = DESCRIPTORS.get(candidate.type());
        BusinessEntity entity = descriptor == null ? null : find(descriptor.className(), candidate.id());
        String code = entity == null ? candidate.code() : entity.getCode();
        String name = entity == null ? null : entity.getName();
        String moduleKey = descriptor == null ? null : descriptor.moduleKey();
        boolean identityAvailable = candidate.id() != null && (code != null && !code.isBlank());
        return new BusinessDocumentReference(
            humanize(candidate.type()),
            candidate.id(),
            code,
            name == null || name.isBlank() ? humanize(candidate.type()) : name,
            moduleKey,
            deepLink(moduleKey, candidate.id()),
            code,
            identityAvailable
        );
    }

    private Candidate directCandidate(IntegrationEvent event) {
        String type = canonicalType(event.getAggregateType());
        if (type != null && event.getAggregateId() != null && DESCRIPTORS.containsKey(type)) {
            return new Candidate(type, event.getAggregateId(), null);
        }
        if (event.getAggregateId() != null && isPurchaseOrderBoundEvent(event.getEventType())) {
            return new Candidate("PurchaseOrder", event.getAggregateId(), null);
        }
        return null;
    }

    private boolean isPurchaseOrderBoundEvent(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return false;
        }
        String normalized = eventType.toUpperCase(java.util.Locale.ROOT);
        return normalized.contains("PO_")
            || normalized.contains("PURCHASE_ORDER")
            || normalized.contains("GOODS_RECEIVED")
            || normalized.contains("PO_SENT")
            || normalized.contains("PO_COMMITMENT")
            || normalized.contains("ACCRUAL_REQUIRED");
    }

    private String deepLink(String moduleKey, UUID id) {
        if (moduleKey == null || moduleKey.isBlank() || id == null) {
            return null;
        }
        return "/app/" + moduleKey + "/" + id;
    }

    private Candidate payloadCandidate(String payload) {
        if (payload == null || payload.isBlank()) return null;
        try {
            return findCandidate(objectMapper.readTree(payload), 0);
        } catch (Exception exception) {
            return null;
        }
    }

    private Candidate findCandidate(JsonNode node, int depth) {
        if (node == null || depth > 10) return null;
        if (node.isObject()) {
            for (Map.Entry<String, String> mapping : PAYLOAD_ID_TYPES.entrySet()) {
                JsonNode value = node.get(mapping.getKey());
                UUID id = uuid(value);
                if (id != null) {
                    String codeKey = mapping.getKey().replaceFirst("Id$", "Code");
                    return new Candidate(mapping.getValue(), id, text(node.get(codeKey)));
                }
            }
            var fields = node.fields();
            while (fields.hasNext()) {
                Candidate nested = findCandidate(fields.next().getValue(), depth + 1);
                if (nested != null) return nested;
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                Candidate nested = findCandidate(item, depth + 1);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private BusinessEntity find(String className, UUID id) {
        if (id == null) return null;
        try {
            Object value = entityManager.find(Class.forName(ENTITY_PACKAGE + className), id);
            if (!(value instanceof BusinessEntity entity)
                || entity.isDeleted()
                || !TenantContext.currentTenant().equals(entity.getTenantId())
                || !AccessScope.canAccessCompany(entity.getCompanyId())) {
                return null;
            }
            return entity;
        } catch (ClassNotFoundException exception) {
            return null;
        }
    }

    private UUID uuid(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.isTextual() ? value.asText() : value.isIntegralNumber() ? null : value.asText(null);
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String text(JsonNode value) {
        return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private String canonicalType(String value) {
        if (value == null) return null;
        return DESCRIPTORS.keySet().stream().filter(key -> key.equalsIgnoreCase(value)).findFirst().orElse(null);
    }

    private String humanize(String value) {
        if (value == null || value.isBlank()) return "Business document";
        return value.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replace('_', ' ').trim();
    }

    private static Map<String, Descriptor> descriptors() {
        Map<String, Descriptor> map = new LinkedHashMap<>();
        map.put("PurchaseOrder", new Descriptor("PurchaseOrder", "purchase-orders"));
        map.put("PurchaseRequest", new Descriptor("PurchaseRequest", "purchase-requests"));
        map.put("RFQ", new Descriptor("RFQ", "rfq"));
        map.put("GoodsReceipt", new Descriptor("GoodsReceipt", "goods-receipt"));
        map.put("ServiceAcceptanceAct", new Descriptor("ServiceAcceptanceAct", "service-acceptance-acts"));
        map.put("ServiceOrder", new Descriptor("ServiceOrder", "services"));
        map.put("ServiceRequest", new Descriptor("ServiceRequest", "service-requests"));
        map.put("WorkCompletionAct", new Descriptor("WorkCompletionAct", "work-completion-acts"));
        map.put("DemandPlan", new Descriptor("DemandPlan", "demand-plans"));
        map.put("ProcurementPlan", new Descriptor("ProcurementPlan", "procurement-plans"));
        map.put("VmiStockAgreement", new Descriptor("VmiStockAgreement", "vmi-stock-agreements"));
        map.put("QualityClaim", new Descriptor("QualityClaim", "quality-claims"));
        map.put("AdvanceShippingNotice", new Descriptor("AdvanceShippingNotice", "advance-shipping-notices"));
        map.put("LaboratoryTest", new Descriptor("LaboratoryTest", "laboratory-tests"));
        map.put("NonMovingStockCase", new Descriptor("NonMovingStockCase", "non-moving-stock-cases"));
        map.put("ProcurementCategoryStrategy", new Descriptor("ProcurementCategoryStrategy", "category-strategies"));
        map.put("LandedCostAllocation", new Descriptor("LandedCostAllocation", "landed-cost-allocations"));
        map.put("ProcurementCampaign", new Descriptor("ProcurementCampaign", "procurement-campaigns"));
        map.put("CustomsDeclaration", new Descriptor("CustomsDeclaration", "customs-declarations"));
        map.put("SupplierQuotation", new Descriptor("SupplierQuotation", "quotations"));
        map.put("InventoryFxDifference", new Descriptor("InventoryFxDifference", "inventory-fx-differences"));
        map.put("MaterialReservation", new Descriptor("MaterialReservation", "material-reservations"));
        map.put("InventoryAccountingReconciliation", new Descriptor("InventoryAccountingReconciliation", "inventory-accounting-reconciliations"));
        map.put("InventoryPeriodClose", new Descriptor("InventoryPeriodClose", "inventory-period-closes"));
        map.put("DemandPlanningCycle", new Descriptor("DemandPlanningCycle", "demand-planning-cycles"));
        map.put("ContractInvoice", new Descriptor("ContractInvoice", "contract-invoices"));
        map.put("ContractPayment", new Descriptor("ContractPayment", "contract-payments"));
        return Map.copyOf(map);
    }

    private static Map<String, String> payloadIdTypes() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("purchaseOrderId", "PurchaseOrder");
        map.put("goodsReceiptId", "GoodsReceipt");
        map.put("serviceAcceptanceActId", "ServiceAcceptanceAct");
        map.put("serviceOrderId", "ServiceOrder");
        map.put("serviceRequestId", "ServiceRequest");
        map.put("purchaseRequestId", "PurchaseRequest");
        map.put("rfqId", "RFQ");
        map.put("inventoryPeriodCloseId", "InventoryPeriodClose");
        map.put("nonMovingStockCaseId", "NonMovingStockCase");
        map.put("reservationId", "MaterialReservation");
        map.put("demandPlanId", "DemandPlan");
        map.put("supplierQuotationId", "SupplierQuotation");
        map.put("laboratoryTestId", "LaboratoryTest");
        map.put("asnId", "AdvanceShippingNotice");
        map.put("contractInvoiceId", "ContractInvoice");
        map.put("contractPaymentId", "ContractPayment");
        return Map.copyOf(map);
    }

    private record Descriptor(String className, String moduleKey) {
    }

    private record Candidate(String type, UUID id, String code) {
    }
}
