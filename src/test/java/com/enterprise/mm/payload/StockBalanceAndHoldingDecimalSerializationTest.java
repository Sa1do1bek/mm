package com.enterprise.mm.payload;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.warehouse.holding.HoldingCommands.HoldingStatementPosition;
import com.enterprise.mm.warehouse.holding.HoldingCommands.HoldingStatementReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StockBalanceAndHoldingDecimalSerializationTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("StockBalanceResponse serializes BigDecimal quantities and values as decimal strings")
    void stockBalanceResponseSerializesDecimalsAsStrings() throws Exception {
        StockBalanceResponse response = new StockBalanceResponse();
        response.setId(UUID.randomUUID());
        response.setQuantity(new BigDecimal("123.4500"));
        response.setAvailableQuantity(new BigDecimal("50.0000"));
        response.setExecutableQuantity(new BigDecimal("50.0000"));
        response.setReservedQuantity(new BigDecimal("73.4500"));
        response.setQuarantineQuantity(BigDecimal.ZERO);
        response.setTotalValue(new BigDecimal("1234567.8900"));

        String json = objectMapper.writeValueAsString(response);
        JsonNode root = objectMapper.readTree(json);

        // Verify JSON type is textual (string), preserving browser precision
        assertThat(root.get("quantity").isTextual()).isTrue();
        assertThat(root.get("quantity").asText()).isEqualTo("123.4500");

        assertThat(root.get("availableQuantity").isTextual()).isTrue();
        assertThat(root.get("availableQuantity").asText()).isEqualTo("50.0000");

        assertThat(root.get("executableQuantity").isTextual()).isTrue();
        assertThat(root.get("executableQuantity").asText()).isEqualTo("50.0000");

        assertThat(root.get("reservedQuantity").isTextual()).isTrue();
        assertThat(root.get("reservedQuantity").asText()).isEqualTo("73.4500");

        assertThat(root.get("quarantineQuantity").isTextual()).isTrue();
        assertThat(root.get("quarantineQuantity").asText()).isEqualTo("0");

        assertThat(root.get("totalValue").isTextual()).isTrue();
        assertThat(root.get("totalValue").asText()).isEqualTo("1234567.8900");
    }

    @Test
    @DisplayName("HoldingStatementReport and HoldingStatementPosition serialize BigDecimals as decimal strings")
    void holdingStatementSerializesDecimalsAsStrings() throws Exception {
        UUID holdingId = UUID.randomUUID();
        UUID matId = UUID.randomUUID();
        UUID deptId = UUID.randomUUID();

        var position = new HoldingStatementPosition(
            holdingId,
            matId,
            deptId,
            null,
            "014",
            "PCS",
            BigDecimal.ZERO,
            new BigDecimal("10.0000000"),
            new BigDecimal("2.0000000"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            new BigDecimal("8.0000000"),
            BigDecimal.ZERO,
            true
        );

        var report = new HoldingStatementReport(
            1,
            1,
            0,
            new BigDecimal("10.0000000"),
            BigDecimal.ZERO,
            new BigDecimal("2.0000000"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            true,
            List.of(position)
        );

        String json = objectMapper.writeValueAsString(report);
        JsonNode root = objectMapper.readTree(json);

        assertThat(root.get("totalActiveQuantity").isTextual()).isTrue();
        assertThat(root.get("totalActiveQuantity").asText()).isEqualTo("10.0000000");

        assertThat(root.get("totalConsumedQuantity").isTextual()).isTrue();
        assertThat(root.get("totalConsumedQuantity").asText()).isEqualTo("2.0000000");

        JsonNode posNode = root.get("positions").get(0);
        assertThat(posNode.get("activeQuantity").isTextual()).isTrue();
        assertThat(posNode.get("activeQuantity").asText()).isEqualTo("10.0000000");

        assertThat(posNode.get("consumedQuantity").isTextual()).isTrue();
        assertThat(posNode.get("consumedQuantity").asText()).isEqualTo("2.0000000");

        assertThat(posNode.get("calculatedClosingQuantity").isTextual()).isTrue();
        assertThat(posNode.get("calculatedClosingQuantity").asText()).isEqualTo("8.0000000");

        // Verify deserialization back to object
        HoldingStatementReport deserialized = objectMapper.readValue(json, HoldingStatementReport.class);
        assertThat(deserialized.totalActiveQuantity()).isEqualByComparingTo("10.0000000");
    }
}
