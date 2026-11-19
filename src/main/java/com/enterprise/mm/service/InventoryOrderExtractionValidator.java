package com.enterprise.mm.service;

import com.enterprise.mm.service.InventoryAiDraftService.AnalysisRequest;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Structural boundary for the versioned order extraction payload; ERP matching is separate. */
@Component
public class InventoryOrderExtractionValidator {
    private static final Set<String> TASK_TYPES = Set.of("STOCK_COUNT", "FIXED_ASSET_CHECK", "FINANCIAL_CHECK", "CONSTRUCTION_CHECK", "UNKNOWN");
    private static final Set<String> KINDS = Set.of("LOCAL", "REGIONAL", "FINANCIAL", "CONSTRUCTION", "OTHER");
    private static final Set<String> OBJECT_KINDS = Set.of("ORG_UNIT", "DEPARTMENT", "WAREHOUSE", "REGION", "ACCOUNT_CATEGORY", "CONSTRUCTION_OBJECT", "OTHER");

    public void validate(JsonNode order, JsonNode commissions, AnalysisRequest request) {
        if (!order.isObject() || !order.path("schemaVersion").isIntegralNumber()
            || order.path("schemaVersion").asInt() != 1 || !order.path("header").isObject()) invalid();
        JsonNode header = order.path("header");
        for (String key : Set.of("orderTitle", "orderNumber", "orderDate", "stockAsOfDate",
            "plannedStartDate", "plannedEndDate", "accountingSubmissionDeadline")) {
            JsonNode value = header.path(key);
            if (value.isMissingNode()) invalid();
            if (!value.isNull()) {
                if (!value.isObject() || !value.path("value").isTextual()
                    || value.path("value").asText().isBlank() || value.path("value").asText().length() > InventoryAiResponseLimits.expanded(240)
                    || !value.path("confidence").isNumber()
                    || value.path("confidence").asDouble() < 0 || value.path("confidence").asDouble() > 1) invalid();
                citations(value.path("sources"), request, 10, true);
                if (key.endsWith("Date") || key.equals("plannedStartDate") || key.equals("plannedEndDate")
                    || key.equals("accountingSubmissionDeadline")) date(value.path("value").asText());
            }
        }
        String start = header.path("plannedStartDate").path("value").asText(null);
        String end = header.path("plannedEndDate").path("value").asText(null);
        if (start != null && end != null && LocalDate.parse(end).isBefore(LocalDate.parse(start))) invalid();
        Set<String> commissionKeys = new HashSet<>();
        for (JsonNode commission : commissions.path("commissions")) commissionKeys.add(commission.path("sourceKey").asText());
        reference(order.path("centralCommissionSourceKey"), commissionKeys);
        JsonNode workItems = order.path("workItems");
        if (!workItems.isArray() || workItems.size() > InventoryAiResponseLimits.expanded(200)) invalid();
        Set<String> itemKeys = new HashSet<>();
        for (JsonNode item : workItems) {
            if (!item.isObject() || !itemKeys.add(required(item.path("sourceKey"), 160))
                || !KINDS.contains(item.path("kind").asText())) invalid();
            optional(item.path("annexNumber"), 40);
            optional(item.path("itemNumber"), 40);
            JsonNode objects = item.path("objectMentions");
            if (!objects.isArray() || objects.size() > InventoryAiResponseLimits.expanded(30)) invalid();
            for (JsonNode object : objects) {
                if (!object.isObject() || !OBJECT_KINDS.contains(object.path("kind").asText())) invalid();
                required(object.path("name"), 240);
                citations(object.path("sources"), request, 10, true);
            }
            JsonNode types = item.path("taskTypes");
            if (!types.isArray() || types.isEmpty() || types.size() > InventoryAiResponseLimits.expanded(5)) invalid();
            for (JsonNode type : types) if (!TASK_TYPES.contains(type.asText())) invalid();
            reference(item.path("workingCommissionSourceKey"), commissionKeys);
            JsonNode candidates = item.path("commissionCandidateSourceKeys");
            if (!candidates.isArray() || candidates.size() > InventoryAiResponseLimits.expanded(20)) invalid();
            for (JsonNode candidate : candidates) reference(candidate, commissionKeys);
            if (!Set.of("COMPLETE", "PARTIAL", "UNKNOWN").contains(item.path("rosterCompleteness").asText())) invalid();
            citations(item.path("sources"), request, 20, true);
            strings(item.path("warnings"), 20, 500);
        }
        JsonNode directions = order.path("approvalDirections");
        if (!directions.isArray() || directions.size() > InventoryAiResponseLimits.expanded(20)) invalid();
        for (JsonNode direction : directions) {
            if (!direction.isObject() || !TASK_TYPES.contains(direction.path("taskType").asText())) invalid();
            optional(direction.path("approverName"), 240);
            reference(direction.path("approverCommissionSourceKey"), commissionKeys);
            citations(direction.path("sources"), request, 10, true);
        }
        JsonNode consents = order.path("consentSignatories");
        if (!consents.isArray() || consents.size() > InventoryAiResponseLimits.expanded(100)) invalid();
        for (JsonNode consent : consents) {
            if (!consent.isObject()) invalid();
            required(consent.path("name"), 240);
            optional(consent.path("position"), 240);
            citations(consent.path("sources"), request, 10, true);
        }
        JsonNode coverage = order.path("coverage");
        if (!coverage.isArray() || coverage.size() > InventoryAiResponseLimits.expanded(30)) invalid();
        for (JsonNode part : coverage) {
            if (!part.isObject() || !Set.of("COMPLETE", "PARTIAL", "UNKNOWN").contains(part.path("status").asText())) invalid();
            optional(part.path("annexNumber"), 40);
            JsonNode expected = part.path("expectedItemCount");
            if (!expected.isNull() && (!expected.isIntegralNumber() || expected.asInt() < 0)) invalid();
            if (!part.path("extractedItemCount").isIntegralNumber() || part.path("extractedItemCount").asInt() < 0) invalid();
            strings(part.path("missingItemNumbers"), 200, 40);
            citations(part.path("sources"), request, 20, false);
        }
        strings(order.path("warnings"), 100, 500);
    }

    private void reference(JsonNode value, Set<String> keys) {
        if (value.isMissingNode() || value.isNull()) return;
        if (!value.isTextual() || !keys.contains(value.asText())) invalid();
    }
    private void optional(JsonNode value, int max) {
        if (!value.isMissingNode() && !value.isNull()) required(value, max);
    }
    private String required(JsonNode value, int max) {
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > InventoryAiResponseLimits.expanded(max)) invalid();
        return value.asText();
    }
    private void strings(JsonNode values, int max, int length) {
        if (!values.isArray() || values.size() > InventoryAiResponseLimits.expanded(max)) invalid();
        for (JsonNode value : values) required(value, length);
    }
    private void citations(JsonNode values, AnalysisRequest request, int max, boolean required) {
        if (!values.isArray() || values.size() > InventoryAiResponseLimits.expanded(max) || required && values.isEmpty()) invalid();
        for (JsonNode source : values) {
            if (!source.isObject() || !source.path("page").isIntegralNumber() || source.path("page").asInt() < 1
                || request.documents().stream().noneMatch(file -> file.fileId().toString().equals(source.path("fileId").asText()))) invalid();
            required(source.path("quote"), 400);
        }
    }
    private void date(String value) {
        try { LocalDate.parse(value); }
        catch (DateTimeParseException ex) { invalid(); }
    }
    private void invalid() { throw new IllegalArgumentException("MM AI inventory order extraction response is invalid"); }
}
