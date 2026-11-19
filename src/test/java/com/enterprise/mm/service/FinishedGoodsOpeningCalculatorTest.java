package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FinishedGoodsOpeningCalculatorTest {
    private static final UUID WAREHOUSE = UUID.randomUUID();
    private static final UUID MATERIAL = UUID.randomUUID();

    private FinishedGoodsOpeningCalculator.SourceRow row(String key, String account, String quantity, String value) {
        return new FinishedGoodsOpeningCalculator.SourceRow(key, "hash-" + key, account, "warehouse-1c",
            "00000037", "material-1c", "ton", WAREHOUSE, MATERIAL,
            new BigDecimal(quantity), new BigDecimal(value), false);
    }

    @Test
    void netsSignedAccountsWithExactQuantityAndBookValue() {
        var result = FinishedGoodsOpeningCalculator.calculate(List.of(
            row("a", "2812", "7212.3330360", "89139138603.85"),
            row("b", "2813", "-1764.0000000", "0.00")));
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().quantity()).isEqualByComparingTo("5448.3330360");
        assertThat(result.getFirst().amount()).isEqualByComparingTo("89139138603.85");
        assertThat(result.getFirst().status()).isEqualTo("LIVE_RECONCILIATION_REQUIRED");
        assertThat(result.getFirst().sourceKeys()).containsExactly("a", "b");
    }

    @Test
    void netsAllFourStockAccountsButNeverCommissionOrOutputAccounts() {
        var result = FinishedGoodsOpeningCalculator.calculate(List.of(
            row("a", "2811", "7632.32", "36413884048.88"), row("b", "2812", "-227.78", "0"),
            row("c", "2813", "-820.20", "0"), row("d", "2840", "-7632.32", "0")));
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().quantity()).isEqualByComparingTo("6584.34");
        assertThat(result.getFirst().sourceKeys()).containsExactly("a", "b", "c");
    }

    @Test
    void keepsNegativeAndValueOnlyPositionsOutOfAvailableStock() {
        assertThat(FinishedGoodsOpeningCalculator.calculate(List.of(row("a", "2810", "-1", "2")))
            .getFirst().status()).isEqualTo("NEGATIVE_REVIEW");
        assertThat(FinishedGoodsOpeningCalculator.calculate(List.of(row("a", "2810", "0", "2")))
            .getFirst().status()).isEqualTo("VALUE_ONLY_REVIEW");
        assertThat(FinishedGoodsOpeningCalculator.calculate(List.of(row("a", "2810", "2", "0")))
            .getFirst().status()).isEqualTo("ZERO_VALUE_REVIEW");
    }

    @Test
    void rejectsDuplicateSourceRows() {
        var row = row("a", "2810", "1", "1");
        assertThatThrownBy(() -> FinishedGoodsOpeningCalculator.calculate(List.of(row, row)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blocksConflictingMappingsAndUnverifiedDimensions() {
        var base = row("a", "2810", "1", "1");
        var conflict = new FinishedGoodsOpeningCalculator.SourceRow("b", "hash-b", "2811", "warehouse-1c",
            "00000037", "material-1c", "ton", WAREHOUSE, UUID.randomUUID(), BigDecimal.ONE, BigDecimal.ONE, false);
        assertThat(FinishedGoodsOpeningCalculator.calculate(List.of(base, conflict)).getFirst().status())
            .isEqualTo("MAPPING_REVIEW");
        var extra = new FinishedGoodsOpeningCalculator.SourceRow("x", "hash-x", "2810", "warehouse-1c",
            "00000037", "material-1c", "ton", WAREHOUSE, MATERIAL, BigDecimal.ONE, BigDecimal.ONE, true);
        assertThat(FinishedGoodsOpeningCalculator.calculate(List.of(extra)).getFirst().status())
            .isEqualTo("DIMENSIONS_REVIEW");
    }
}
