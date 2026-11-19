package com.enterprise.mm.supply.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.supply.workspace.enums.SupplyDataQuality;
import com.enterprise.mm.supply.workspace.repository.SupplyCommitmentRecord;
import com.enterprise.mm.supply.workspace.service.SupplyMoneyService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class SupplyMoneyServiceTest {
    private final SupplyMoneyService service = new SupplyMoneyService();

    @Test
    void preservesOriginalCurrenciesAndNeverBuildsACrossCurrencyTotal() {
        SupplyMoneyService.MoneyBreakdown result = service.group(List.of(
            new SupplyCommitmentRecord.MoneyValue("UZS", new BigDecimal("1200000.00")),
            new SupplyCommitmentRecord.MoneyValue("USD", new BigDecimal("125.50"))
        ));

        assertThat(result.values()).extracting(value -> value.currency())
            .containsExactly("USD", "UZS");
        assertThat(result.values()).extracting(value -> value.originalAmount())
            .containsExactly(new BigDecimal("125.50"), new BigDecimal("1200000.00"));
        assertThat(result.values()).allSatisfy(value -> {
            assertThat(value.baseAmount()).isNull();
            assertThat(value.baseCurrency()).isNull();
            assertThat(value.conversionAvailable()).isFalse();
        });
        assertThat(result.availability()).isEqualTo(SupplyDataQuality.AUTHORITATIVE);
        assertThat(result.reasons()).isEmpty();
    }

    @Test
    void missingCurrencyOrIncompleteAmountIsAnExplicitLimitationRatherThanZero() {
        SupplyMoneyService.MoneyBreakdown result = service.group(java.util.Arrays.asList(
            new SupplyCommitmentRecord.MoneyValue(null, new BigDecimal("10")),
            new SupplyCommitmentRecord.MoneyValue("USD", null)
        ));

        assertThat(result.values()).hasSize(2);
        assertThat(result.values()).anySatisfy(value -> {
            assertThat(value.currency()).isNull();
            assertThat(value.originalAmount()).isEqualByComparingTo("10");
        });
        assertThat(result.values()).anySatisfy(value -> {
            assertThat(value.currency()).isEqualTo("USD");
            assertThat(value.originalAmount()).isNull();
        });
        assertThat(result.availability()).isEqualTo(SupplyDataQuality.PARTIAL);
        assertThat(result.reasons()).extracting(reason -> reason.code())
            .containsExactlyInAnyOrder("MISSING_CURRENCY", "INCOMPLETE_OPEN_AMOUNT");
    }

    @Test
    void invalidPersistedCurrencyIsExplicitlyPartial() {
        SupplyMoneyService.MoneyBreakdown result = service.group(List.of(
            new SupplyCommitmentRecord.MoneyValue("ZZZ", new BigDecimal("10"))));

        assertThat(result.values().getFirst().currency()).isEqualTo("ZZZ");
        assertThat(result.availability()).isEqualTo(SupplyDataQuality.PARTIAL);
        assertThat(result.reasons()).extracting(reason -> reason.code())
            .containsExactly("INVALID_CURRENCY");
    }

    @Test
    void workspaceDecimalsSerializeAsLosslessJsonStrings() throws Exception {
        var value = new com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.MoneyValue(
            new BigDecimal("12345678901234567890.123400"),
            "USD",
            new BigDecimal("987.6500"),
            "UZS",
            new BigDecimal("12600.125000"),
            LocalDate.of(2026, 7, 15),
            "CBU",
            true);
        var quantity = new com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.QuantityValue(
            new BigDecimal("0.000100"), "EA");
        com.fasterxml.jackson.databind.ObjectMapper mapper =
            new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();

        var moneyJson = mapper.readTree(mapper.writeValueAsString(value));
        var quantityJson = mapper.readTree(mapper.writeValueAsString(quantity));

        assertThat(moneyJson.get("originalAmount").isTextual()).isTrue();
        assertThat(moneyJson.get("originalAmount").asText())
            .isEqualTo("12345678901234567890.123400");
        assertThat(moneyJson.get("baseAmount").asText()).isEqualTo("987.6500");
        assertThat(moneyJson.get("rate").asText()).isEqualTo("12600.125000");
        assertThat(quantityJson.get("value").isTextual()).isTrue();
        assertThat(quantityJson.get("value").asText()).isEqualTo("0.000100");
    }
}
