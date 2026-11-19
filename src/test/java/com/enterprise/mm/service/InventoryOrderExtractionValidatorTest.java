package com.enterprise.mm.service;

import com.enterprise.mm.service.InventoryAiDraftService.AnalysisRequest;
import com.enterprise.mm.service.InventoryAiDraftService.FileSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InventoryOrderExtractionValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final InventoryOrderExtractionValidator validator = new InventoryOrderExtractionValidator();
    private final UUID fileId = UUID.randomUUID();
    private final AnalysisRequest request = new AnalysisRequest(UUID.randomUUID(),
        List.of(new FileSnapshot(fileId, "order.pdf", "application/pdf", "sha")), 1, 1);

    @Test void keepsNinetyNineBandsAndDistinctDates() {
        ObjectNode order = order();
        ArrayNode items = (ArrayNode) order.path("workItems");
        for (int i = 1; i <= 99; i++) items.add(item(i));
        assertDoesNotThrow(() -> validator.validate(order, commissions(), request));
    }

    @Test void rejectsDuplicateBandAndWrongFileEvidence() {
        ObjectNode order = order();
        ArrayNode items = (ArrayNode) order.path("workItems");
        items.add(item(1)); items.add(item(1));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(order, commissions(), request));
        items.remove(1);
        ((ObjectNode) items.get(0).path("sources").get(0)).put("fileId", UUID.randomUUID().toString());
        assertThrows(IllegalArgumentException.class, () -> validator.validate(order, commissions(), request));
    }

    @Test void acceptsExpandedOrderCitationLength() {
        ObjectNode order = order();
        ObjectNode citation = (ObjectNode) order.path("header").path("orderNumber").path("sources").get(0);
        citation.put("quote", "x".repeat(448));
        assertDoesNotThrow(() -> validator.validate(order, commissions(), request));
        citation.put("quote", "x".repeat(1200));
        assertDoesNotThrow(() -> validator.validate(order, commissions(), request));
        citation.put("quote", "x".repeat(1201));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(order, commissions(), request));
    }

    private ObjectNode order() {
        ObjectNode order = mapper.createObjectNode(); order.put("schemaVersion", 1);
        ObjectNode header = order.putObject("header");
        header.set("orderTitle", field("Annual inventory")); header.set("orderNumber", field("1749"));
        header.set("orderDate", field("2026-10-16")); header.set("stockAsOfDate", field("2026-10-01"));
        header.set("plannedStartDate", field("2026-10-20")); header.set("plannedEndDate", field("2026-11-20"));
        header.set("accountingSubmissionDeadline", field("2026-11-25"));
        order.putNull("centralCommissionSourceKey"); order.putArray("workItems"); order.putArray("approvalDirections");
        order.putArray("consentSignatories"); order.putArray("coverage"); order.putArray("warnings");
        return order;
    }

    private ObjectNode item(int number) {
        ObjectNode item = mapper.createObjectNode(); item.put("sourceKey", "band:" + number);
        item.put("annexNumber", "2"); item.put("itemNumber", String.valueOf(number)); item.put("kind", "LOCAL");
        ObjectNode department = item.putArray("objectMentions").addObject();
        department.put("kind", "DEPARTMENT"); department.put("name", "008-sex"); department.putArray("sources").add(source());
        item.putArray("taskTypes").add("STOCK_COUNT"); item.put("workingCommissionSourceKey", "commission:1");
        item.putArray("commissionCandidateSourceKeys"); item.put("rosterCompleteness", "PARTIAL");
        item.putArray("sources").add(source()); item.putArray("warnings"); return item;
    }

    private ObjectNode commissions() {
        ObjectNode result = mapper.createObjectNode(); result.putArray("commissions").addObject().put("sourceKey", "commission:1");
        return result;
    }

    private ObjectNode field(String value) {
        ObjectNode field = mapper.createObjectNode(); field.put("value", value); field.put("confidence", 1);
        field.putArray("sources").add(source()); return field;
    }

    private ObjectNode source() {
        ObjectNode source = mapper.createObjectNode(); source.put("fileId", fileId.toString());
        source.put("page", 2); source.put("quote", "Document quote"); return source;
    }
}
