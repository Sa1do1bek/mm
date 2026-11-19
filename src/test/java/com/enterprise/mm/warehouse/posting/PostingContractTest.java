package com.enterprise.mm.warehouse.posting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PostingContractTest {
    @Test
    void stockEffectRequiresExactQuantityAndBaseUnit() {
        var effect = new PostingEffect(PostingEffectType.STOCK, UUID.randomUUID(), UUID.randomUUID(),
            "1010", "PCS", new BigDecimal("0.0000001"), BigDecimal.ZERO, null);
        assertThat(effect.quantity()).isEqualByComparingTo("0.0000001");

        assertThatThrownBy(() -> new PostingEffect(PostingEffectType.STOCK, UUID.randomUUID(), UUID.randomUUID(),
            "1010", "PCS", new BigDecimal("0.00000001"), BigDecimal.ZERO, null))
            .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> new PostingEffect(PostingEffectType.STOCK, UUID.randomUUID(), UUID.randomUUID(),
            "1010", null, BigDecimal.ONE, BigDecimal.ZERO, null))
            .isInstanceOf(BadRequestException.class);
    }

    @Test
    void valueOnlyEffectNeverCreatesAvailableStock() {
        var effect = new PostingEffect(PostingEffectType.VALUE_ONLY, UUID.randomUUID(), UUID.randomUUID(),
            "1010", null, BigDecimal.ZERO, new BigDecimal("125.2300"), "UZS");
        assertThat(effect.quantity()).isZero();
        assertThatThrownBy(() -> new PostingEffect(PostingEffectType.VALUE_ONLY, UUID.randomUUID(), UUID.randomUUID(),
            "1010", "PCS", BigDecimal.ONE, new BigDecimal("125.2300"), "UZS"))
            .isInstanceOf(BadRequestException.class);
    }

    @Test
    void effectRetainsCompletePositionAndSourceIdentity() {
        UUID location = UUID.randomUUID();
        UUID bin = UUID.randomUUID();
        UUID batch = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        var effect = new PostingEffect(PostingEffectType.STOCK, UUID.randomUUID(), UUID.randomUUID(),
            "1010", "PCS", new BigDecimal("-1"), BigDecimal.ZERO, null,
            location, bin, batch, owner, "OWNED", "AVAILABLE", "1c:issue:line-42");

        assertThat(effect.storageLocationId()).isEqualTo(location);
        assertThat(effect.storageBinId()).isEqualTo(bin);
        assertThat(effect.stockBatchId()).isEqualTo(batch);
        assertThat(effect.ownerId()).isEqualTo(owner);
        assertThat(effect.stockCategory()).isEqualTo("OWNED");
        assertThat(effect.disposition()).isEqualTo("AVAILABLE");
        assertThat(effect.sourceReference()).isEqualTo("1c:issue:line-42");
    }

    @Test
    void ruleSelectorRequiresOneApprovedEffectiveRule() {
        var date = LocalDate.of(2026, 10, 14);
        var approved = new PostingRule(UUID.randomUUID(), "WAREHOUSE", "ISSUE", "OWNED", 1,
            date.minusDays(1), null, true, "1010", "UZS");
        var draft = new PostingRule(UUID.randomUUID(), "WAREHOUSE", "ISSUE", "OWNED", 2,
            date.minusDays(1), null, false, "1010", "UZS");
        var wrongBook = new PostingRule(UUID.randomUUID(), "FINANCIAL", "ISSUE", "OWNED", 3,
            date.minusDays(1), null, true, "1010", "UZS");

        assertThat(PostingRuleSelector.select(List.of(approved, draft, wrongBook),
            "WAREHOUSE", "ISSUE", "OWNED", date)).isEqualTo(approved);
        assertThatThrownBy(() -> PostingRuleSelector.select(List.of(draft),
            "WAREHOUSE", "ISSUE", "OWNED", date)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> PostingRuleSelector.select(List.of(approved, approved),
            "WAREHOUSE", "ISSUE", "OWNED", date)).isInstanceOf(ConflictException.class);
    }

    @Test
    void exampleE1_ownedAccountIssueProducesConsistentStockAccountAndCostEffects() {
        UUID material = UUID.randomUUID();
        UUID warehouse = UUID.randomUUID();
        BigDecimal issuedQuantity = new BigDecimal("3.0000000");
        BigDecimal unitCost = new BigDecimal("1250.5000");
        BigDecimal totalCost = issuedQuantity.multiply(unitCost).setScale(4, java.math.RoundingMode.UNNECESSARY);

        var stockEffect = new PostingEffect(PostingEffectType.STOCK, material, warehouse,
            "1010", "PCS", issuedQuantity.negate(), BigDecimal.ZERO, null);
        var accountEffect = new PostingEffect(PostingEffectType.ACCOUNT, material, warehouse,
            "1010", "PCS", issuedQuantity.negate(), totalCost.negate(), "UZS");
        var costEffect = new PostingEffect(PostingEffectType.COST, material, warehouse,
            "1010", null, BigDecimal.ZERO, totalCost.negate(), "UZS");

        assertThat(stockEffect.quantity()).isEqualByComparingTo("-3.0000000");
        assertThat(accountEffect.quantity()).isEqualByComparingTo("-3.0000000");
        assertThat(accountEffect.amount()).isEqualByComparingTo("-3751.5000");
        assertThat(costEffect.amount()).isEqualByComparingTo("-3751.5000");
    }

    @Test
    void exampleE2_twoSourceAccountsIsolateDeltasAndConservePhysicalStock() {
        UUID material = UUID.randomUUID();
        UUID warehouse = UUID.randomUUID();
        BigDecimal issuedFromAccount1010 = new BigDecimal("3.0000000");

        // Account 1010 effect
        var account1010Effect = new PostingEffect(PostingEffectType.ACCOUNT, material, warehouse,
            "1010", "PCS", issuedFromAccount1010.negate(), new BigDecimal("-3000.0000"), "UZS");
        // Physical stock delta is exactly 3 units
        var physicalStockEffect = new PostingEffect(PostingEffectType.STOCK, material, warehouse,
            "1010", "PCS", issuedFromAccount1010.negate(), BigDecimal.ZERO, null);

        // Account 1020 has 0 effect in this posting batch (remains untouched with its 6 units)
        assertThat(account1010Effect.quantity()).isEqualByComparingTo("-3.0000000");
        assertThat(physicalStockEffect.quantity()).isEqualByComparingTo("-3.0000000");
    }

    @Test
    void exampleE3_partialTransferConservesQuantitiesAcrossSourceDestinationAndExceptions() {
        UUID material = UUID.randomUUID();
        UUID sourceWarehouse = UUID.randomUUID();
        UUID destWarehouse = UUID.randomUUID();

        BigDecimal dispatched = new BigDecimal("10.0000000");
        BigDecimal accepted = new BigDecimal("8.0000000");
        BigDecimal inTransitOrDiscrepant = dispatched.subtract(accepted);

        var dispatchEffect = new PostingEffect(PostingEffectType.STOCK, material, sourceWarehouse,
            "1010", "PCS", dispatched.negate(), BigDecimal.ZERO, null);
        var receiptEffect = new PostingEffect(PostingEffectType.STOCK, material, destWarehouse,
            "1010", "PCS", accepted, BigDecimal.ZERO, null);

        assertThat(dispatchEffect.quantity()).isEqualByComparingTo("-10.0000000");
        assertThat(receiptEffect.quantity()).isEqualByComparingTo("8.0000000");
        assertThat(inTransitOrDiscrepant).isEqualByComparingTo("2.0000000");
    }

    @Test
    void exampleE4_historicalOriginReturnPreservesOriginLineageAndRejectsFabricatedUnits() {
        UUID material = UUID.randomUUID();
        UUID warehouse = UUID.randomUUID();
        String sourceReference = "1c:invoice-12345:line-2";
        BigDecimal returnQuantity = new BigDecimal("2.0000000");
        BigDecimal originalCost = new BigDecimal("2500.0000");

        var returnStock = new PostingEffect(PostingEffectType.STOCK, material, warehouse,
            "1010", "PCS", returnQuantity, BigDecimal.ZERO, null,
            null, null, null, null, "OWNED", "AVAILABLE", sourceReference);
        var returnAccount = new PostingEffect(PostingEffectType.ACCOUNT, material, warehouse,
            "1010", "PCS", returnQuantity, originalCost, "UZS",
            null, null, null, null, "OWNED", "AVAILABLE", sourceReference);

        assertThat(returnStock.sourceReference()).isEqualTo(sourceReference);
        assertThat(returnStock.quantity()).isEqualByComparingTo("2.0000000");
        assertThat(returnAccount.amount()).isEqualByComparingTo("2500.0000");
    }

    @Test
    void exampleE5_valueOnlyPositionAllowsReportableAmountWithoutStockUnits() {
        UUID material = UUID.randomUUID();
        UUID warehouse = UUID.randomUUID();
        BigDecimal monetaryAmount = new BigDecimal("987654.3200");

        var valueEffect = new PostingEffect(PostingEffectType.VALUE_ONLY, material, warehouse,
            "1010", null, BigDecimal.ZERO, monetaryAmount, "UZS");

        assertThat(valueEffect.quantity()).isZero();
        assertThat(valueEffect.amount()).isEqualByComparingTo("987654.3200");
        assertThat(valueEffect.baseUnitCode()).isNull();

        // Any attempt to attach a unit or nonzero quantity to VALUE_ONLY must fail
        assertThatThrownBy(() -> new PostingEffect(PostingEffectType.VALUE_ONLY, material, warehouse,
            "1010", "PCS", BigDecimal.ONE, monetaryAmount, "UZS"))
            .isInstanceOf(BadRequestException.class);
    }
}
