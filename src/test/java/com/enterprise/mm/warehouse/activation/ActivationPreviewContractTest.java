package com.enterprise.mm.warehouse.activation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ActivationPreviewContractTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Dual-contract serialization: ActivationPreviewResult includes both FE and BE fields")
    void resultSerializesBothFrontendAndBackendFields() throws Exception {
        UUID matId = UUID.randomUUID();
        UUID whId = UUID.randomUUID();

        var eligibleCandidate = new ActivationPreviewCandidate(
            "src-1",
            "10.01",
            "Raw Materials",
            matId,
            "MAT-1",
            "Material 1",
            whId,
            "WH-1",
            "Main Warehouse",
            "OWNED",
            "KG",
            new BigDecimal("100.0000000"),
            new BigDecimal("500000.0000"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            new BigDecimal("100.0000000"),
            new BigDecimal("500000.0000"),
            SourcePositionClassification.ELIGIBLE_DELTA,
            "hash-1",
            null
        );

        var blockedCandidate = new ActivationPreviewCandidate(
            "src-2",
            "10.02",
            "Other Materials",
            matId,
            "MAT-2",
            "Material 2",
            whId,
            "WH-1",
            "Main Warehouse",
            "OWNED",
            "KG",
            new BigDecimal("10.0000000"),
            new BigDecimal("20000.0000"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            SourcePositionClassification.UNRESOLVED,
            "hash-2",
            "Unresolved material or warehouse master binding"
        );

        var previewResult = new ActivationPreviewResult(
            2L,
            1L,
            0L,
            0L,
            0L,
            0L,
            1L,
            new BigDecimal("110.0000000"),
            new BigDecimal("520000.0000"),
            new BigDecimal("100.0000000"),
            new BigDecimal("500000.0000"),
            List.of(eligibleCandidate, blockedCandidate),
            0,
            10
        );

        String json = objectMapper.writeValueAsString(previewResult);
        JsonNode root = objectMapper.readTree(json);

        // Verify root-level BE fields preserved
        assertThat(root.has("items")).isTrue();
        assertThat(root.get("totalCount").asLong()).isEqualTo(2L);
        assertThat(root.get("eligibleCount").asLong()).isEqualTo(1L);

        // Verify root-level FE fields present
        assertThat(root.has("candidates")).isTrue();
        assertThat(root.get("candidates").isArray()).isTrue();
        assertThat(root.get("candidates").size()).isEqualTo(2);
        assertThat(root.get("totalCandidates").asLong()).isEqualTo(2L);
        assertThat(root.get("ineligibleCount").asLong()).isEqualTo(1L);

        // Verify candidate 1 (ELIGIBLE_DELTA)
        JsonNode item1 = root.get("candidates").get(0);
        assertThat(item1.get("eligible").asBoolean()).isTrue();
        assertThat(item1.get("warnings")).isEmpty();
        assertThat(item1.get("quantity").isTextual()).isTrue();
        assertThat(item1.get("totalValue").isTextual()).isTrue();
        assertThat(item1.get("unitCost").isTextual()).isTrue();
        assertThat(new BigDecimal(item1.get("quantity").asText())).isEqualByComparingTo("100.0000000");
        assertThat(new BigDecimal(item1.get("totalValue").asText())).isEqualByComparingTo("500000.0000");
        assertThat(new BigDecimal(item1.get("unitCost").asText())).isEqualByComparingTo("5000.0000");
        // Verify candidate 1 BE fields preserved
        assertThat(item1.get("classification").asText()).isEqualTo("ELIGIBLE_DELTA");
        assertThat(item1.get("sourceKey").asText()).isEqualTo("src-1");

        // Verify candidate 2 (UNRESOLVED)
        JsonNode item2 = root.get("candidates").get(1);
        assertThat(item2.get("eligible").asBoolean()).isFalse();
        assertThat(item2.get("warnings").size()).isEqualTo(1);
        assertThat(item2.get("warnings").get(0).asText()).isEqualTo("Unresolved material or warehouse master binding");
        assertThat(new BigDecimal(item2.get("quantity").asText())).isEqualByComparingTo("10.0000000");
        assertThat(new BigDecimal(item2.get("totalValue").asText())).isEqualByComparingTo("20000.0000");
        assertThat(new BigDecimal(item2.get("unitCost").asText())).isEqualByComparingTo("2000.0000");

        // Verify deserialization works cleanly
        ActivationPreviewResult deserialized = objectMapper.readValue(json, ActivationPreviewResult.class);
        assertThat(deserialized.totalCount()).isEqualTo(2L);
        assertThat(deserialized.items()).hasSize(2);
    }
}
