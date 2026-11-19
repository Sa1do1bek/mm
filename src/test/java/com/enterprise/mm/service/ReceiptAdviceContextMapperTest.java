package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.*;

import com.enterprise.mm.payload.WarehouseReceiptContextDtos.Context;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class ReceiptAdviceContextMapperTest {
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
        .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    ReceiptAdviceContextMapper transport = new ReceiptAdviceContextMapper(mapper);
    Context fixture() throws Exception {
        try (var in = getClass().getResourceAsStream("/receipt-advice/receipt-suggestions-mm-mixed-request.json")) {
            return mapper.treeToValue(mapper.readTree(in).get("backendContext"), Context.class);
        }
    }
    @Test void actualMmDtoPreservesGroupAndContractReadinessForTheNewContract() throws Exception {
        var context = fixture();
        var wire = transport.map(context, "uz");
        assertThat(wire.path("positions").path("content").get(0).path("kind").asText()).isEqualTo("MATERIAL_GROUP");
        assertThat(wire.path("positions").path("content").get(1).path("materials").get(0)
            .path("receiptSelection").path("decisionSource").asText()).isEqualTo("CONTRACT_READINESS");
        assertThat(context.positions().content().getFirst().materials()).hasSize(2);
    }
    @Test void localizesBoundsAndDoesNotModifyTheAuthoritativeSnapshot() throws Exception {
        var c = fixture();
        var original = new Context(c.contractId(), c.contractNumber(), c.warehouseId(),
            "ru: Электрический склад\nen: Electric warehouse\nuz: Elektr ombori", "x".repeat(3000), c.profileVersion(), c.positions());
        var wire = transport.map(original, "uz");
        assertThat(wire.path("warehouseName").asText()).isEqualTo("Elektr ombori");
        assertThat(wire.path("warehousePurpose").asText()).hasSize(2000);
        assertThat(original.warehousePurpose()).hasSize(3000);
        assertThat(original.warehouseName()).contains("ru:");
    }
    @Test void acceptsOptionalNullDefaultsButRejectsModifiedOriginalValues() throws Exception {
        var wire = transport.map(fixture(), "uz");
        var echo = wire.deepCopy();
        ((ObjectNode) echo).putNull("optionalField");
        assertThat(transport.echoes(wire, echo)).isTrue();
        ((ObjectNode) echo).put("profileVersion", 99);
        assertThat(transport.echoes(wire, echo)).isFalse();
    }
}
