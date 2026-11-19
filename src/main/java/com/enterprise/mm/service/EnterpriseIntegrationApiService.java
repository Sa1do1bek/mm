package com.enterprise.mm.service;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.payload.EnterpriseIntegrationDtos.EnterpriseIntegrationBatchRequest;
import com.enterprise.mm.payload.EnterpriseIntegrationDtos.EnterpriseIntegrationPushResult;
import com.enterprise.mm.payload.EnterpriseIntegrationDtos.EnterpriseIntegrationRequest;
import com.enterprise.mm.payload.ExternalIntegrationDtos.IntegrationSendRequest;
import com.enterprise.mm.payload.IntegrationEventResponse;
import com.enterprise.mm.mapper.IntegrationEventMapper;
import com.enterprise.mm.enums.ExternalIntegrationSystem;
import com.enterprise.mm.entity.IntegrationEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EnterpriseIntegrationApiService {
    private final ExternalIntegrationService externalIntegrationService;
    private final IntegrationDispatchService dispatchService;
    private final IntegrationEventMapper eventMapper;
    private final ObjectMapper objectMapper;

    @Transactional
    public IntegrationEventResponse queue(ExternalIntegrationSystem system, String eventType, EnterpriseIntegrationRequest request, String headerIdempotencyKey) {
        EnterpriseIntegrationRequest safeRequest = request == null ? emptyRequest() : request;
        String normalizedEvent = normalizeEventType(system, eventType);
        Object payload = payload(system, normalizedEvent, safeRequest);
        IntegrationSendRequest sendRequest = new IntegrationSendRequest(
            normalizedEvent,
            safeRequest.aggregateId(),
            payload,
            safeRequest.idempotencyKey()
        );
        IntegrationEventResponse queued = externalIntegrationService.enqueue(system, sendRequest, firstNonBlank(headerIdempotencyKey, idempotencyKey(system, normalizedEvent, safeRequest)));
        if (Boolean.TRUE.equals(safeRequest.dispatchNow())) {
            IntegrationEvent dispatched = dispatchService.dispatch(queued.getId());
            return eventMapper.toResponse(dispatched);
        }
        return queued;
    }
    @Transactional
    public EnterpriseIntegrationPushResult queueBatch(
        ExternalIntegrationSystem system,
        String eventType,
        EnterpriseIntegrationBatchRequest request,
        String headerIdempotencyKey
    ) {
        String normalizedEvent = normalizeEventType(system, eventType);
        List<UUID> eventIds = new ArrayList<>();
        int index = 0;
        for (EnterpriseIntegrationRequest item : request.items()) {
            EnterpriseIntegrationRequest effective = copyDispatchFlag(item, request.dispatchNow());
            String itemKey = batchIdempotencyKey(headerIdempotencyKey, request.idempotencyKey(), normalizedEvent, index);
            eventIds.add(queue(system, normalizedEvent, effective, itemKey).getId());
            index++;
        }
        return new EnterpriseIntegrationPushResult(system.name(), normalizedEvent, eventIds.size(), eventIds);
    }
    private Object payload(ExternalIntegrationSystem system, String eventType, EnterpriseIntegrationRequest request) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("sourceSystem", "MM");
        payload.put("sourceModule", "MATERIAL_MANAGEMENT");
        payload.put("targetSystem", system.name());
        payload.put("eventType", eventType);
        payload.put("targetPath", firstNonBlank(request.targetPath(), defaultTargetPath(system, eventType)));
        payload.put("createdAt", LocalDateTime.now().toString());
        payload.put("document", document(request));
        payload.put("counterparty", counterparty(request));
        payload.put("budget", budget(request));
        payload.put("inventory", inventory(request));
        payload.put("raw", request.payload() == null ? Map.of() : objectMapper.valueToTree(request.payload()));
        return payload;
    }
    private Map<String, Object> document(EnterpriseIntegrationRequest request) {
        LinkedHashMap<String, Object> document = new LinkedHashMap<>();
        document.put("externalDocumentId", request.externalDocumentId());
        document.put("documentNumber", request.documentNumber());
        document.put("documentDate", request.documentDate());
        document.put("documentType", request.documentType());
        document.put("status", request.status());
        document.put("amount", request.amount());
        document.put("currencyCode", request.currencyCode());
        document.put("contractNumber", request.contractNumber());
        document.put("purchaseOrderNumber", request.purchaseOrderNumber());
        return document;
    }
    private Map<String, Object> counterparty(EnterpriseIntegrationRequest request) {
        LinkedHashMap<String, Object> counterparty = new LinkedHashMap<>();
        counterparty.put("supplierTin", request.supplierTin());
        counterparty.put("supplierName", request.supplierName());
        return counterparty;
    }
    private Map<String, Object> budget(EnterpriseIntegrationRequest request) {
        LinkedHashMap<String, Object> budget = new LinkedHashMap<>();
        budget.put("budgetCode", request.budgetCode());
        budget.put("costCenter", request.costCenter());
        return budget;
    }
    private Map<String, Object> inventory(EnterpriseIntegrationRequest request) {
        LinkedHashMap<String, Object> inventory = new LinkedHashMap<>();
        inventory.put("warehouseCode", request.warehouseCode());
        inventory.put("storageLocationCode", request.storageLocationCode());
        inventory.put("materialCode", request.materialCode());
        inventory.put("quantity", request.quantity());
        inventory.put("unitCode", request.unitCode());
        return inventory;
    }
    private String defaultTargetPath(ExternalIntegrationSystem system, String eventType) {
        return switch (system) {
            case FI, ONE_C -> "/api/v1/basic/finmodel/integrations/entries";
            case TREASURY -> switch (eventType) {
                case "TREASURY.PAYMENT_REQUEST", "TREASURY.PAYMENT_REGISTER" -> "/api/v1/basic/treasury/payments/requests";
                case "TREASURY.PO_COMMITMENT", "TREASURY.CONTRACT_PAYMENT_SCHEDULE" -> "/api/v1/basic/treasury/commitments";
                default -> "/api/v1/basic/treasury/payments/requests";
            };
            case BUDGETING -> switch (eventType) {
                case "BUDGETING.BUDGET_LINE_SYNC" -> "/api/v2/investments/budgets/lines/save";
                default -> "/api/v1/basic/treasury/commitments";
            };
            case PRODUCTION -> switch (eventType) {
                case "PRODUCTION.PRODUCTION_ORDER" -> "/api/v1/integrations/erp/production-program/import";
                case "PRODUCTION.STOCK_ISSUE_POSTED", "PRODUCTION.MATERIAL_RESERVATION" -> "/api/v1/materials/inputs";
                case "PRODUCTION.PRODUCTION_RECEIPT" -> "/api/v1/integrations/erp/confirmed-production";
                default -> "/api/v1/integrations/erp/confirmed-production";
            };
            case HR -> switch (eventType) {
                case "HR.EMPLOYEE_SYNC" -> "/api/integrations/mm/employees";
                case "HR.DEPARTMENT_SYNC" -> "/api/organization/departments";
                case "HR.POSITION_SYNC" -> "/api/organization/positions";
                case "HR.TIMESHEET_SYNC" -> "/api/timesheets/entries";
                default -> "/api/data-exchange/jobs";
            };
            case EDO -> "/api/documents/outgoing";
            case WMS -> switch (eventType) {
                case "WMS.TRANSFER_RECEIPT" -> "/api/v1/integrations/wms/transfers/receipts/import";
                default -> "/api/v1/integrations/wms/transfers/confirm";
            };
            case SCADA -> "/api/v1/integrations/scada/events/import";
            case ETTN -> "/api/documents/outgoing";
            case EXCHANGE -> switch (eventType) {
                case "EXCHANGE.QUOTATION_RECEIVED", "EXCHANGE.BID_SYNC" -> "/api/v1/basic/treasury/tenders";
                default -> "/api/v1/basic/treasury/tenders";
            };
            default -> "/events";
        };
    }
    private EnterpriseIntegrationRequest copyDispatchFlag(EnterpriseIntegrationRequest request, Boolean batchDispatchNow) {
        if (batchDispatchNow == null || request.dispatchNow() != null) {
            return request;
        }
        return new EnterpriseIntegrationRequest(
            request.aggregateId(),
            request.externalDocumentId(),
            request.documentNumber(),
            request.documentDate(),
            request.documentType(),
            request.status(),
            request.amount(),
            request.currencyCode(),
            request.supplierTin(),
            request.supplierName(),
            request.contractNumber(),
            request.purchaseOrderNumber(),
            request.budgetCode(),
            request.costCenter(),
            request.warehouseCode(),
            request.storageLocationCode(),
            request.materialCode(),
            request.quantity(),
            request.unitCode(),
            request.targetPath(),
            request.payload(),
            request.idempotencyKey(),
            batchDispatchNow
        );
    }
    private String normalizeEventType(ExternalIntegrationSystem system, String eventType) {
        String normalized = eventType.trim().toUpperCase().replace('-', '_').replace(' ', '_');
        return normalized.startsWith(system.name() + ".") ? normalized : system.name() + "." + normalized;
    }
    private String idempotencyKey(ExternalIntegrationSystem system, String eventType, EnterpriseIntegrationRequest request) {
        String sourceId = firstNonBlank(request.idempotencyKey(), firstNonBlank(request.externalDocumentId(), request.documentNumber()));
        if (sourceId == null || sourceId.isBlank()) {
            return null;
        }
        return system.name() + ":" + eventType + ":" + sourceId;
    }
    private String batchIdempotencyKey(String headerKey, String bodyKey, String eventType, int index) {
        String base = firstNonBlank(headerKey, bodyKey);
        return base == null || base.isBlank() ? null : base + ":" + eventType + ":" + index;
    }
    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second != null && !second.isBlank() ? second : null;
    }
    private EnterpriseIntegrationRequest emptyRequest() {
        return new EnterpriseIntegrationRequest(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }
}

