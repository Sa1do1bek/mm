package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.error.BusinessException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class StockBalanceMutationServiceTest {

    private final StockBalanceMutationService service = new StockBalanceMutationService();

    /**
     * TODO "Freshness (balanceAt)": documents the fix — every mutation method now
     * stamps balanceAt, so InventoryAvailabilityQueryRepository's staleBalance filter
     * and overview().staleDataCount() reflect real movement recency instead of a
     * timestamp that was never touched by the real posting engine.
     */
    @Test
    void everyMutationMethodStampsBalanceAtWithTheCurrentTime() {
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);

        StockBalance reserveBalance = balance("10", "10", "0", "50");
        service.reserve(reserveBalance, BigDecimal.valueOf(4));
        assertThat(reserveBalance.getBalanceAt()).isNotNull().isAfter(before);

        StockBalance releaseBalance = balance("10", "6", "4", "50");
        service.releaseReservation(releaseBalance, BigDecimal.valueOf(3));
        assertThat(releaseBalance.getBalanceAt()).isNotNull().isAfter(before);

        StockBalance increaseBalance = balance("5", "5", "0", "20");
        service.increaseOnHand(increaseBalance, BigDecimal.valueOf(2), BigDecimal.valueOf(3));
        assertThat(increaseBalance.getBalanceAt()).isNotNull().isAfter(before);

        StockBalance quarantineBalance = balance("10", "10", "0", "50");
        service.moveAvailableToQuarantine(quarantineBalance, BigDecimal.valueOf(2));
        assertThat(quarantineBalance.getBalanceAt()).isNotNull().isAfter(before);

        StockBalance decreaseBalance = balance("2", "1", "1", "20");
        service.decreaseOnHand(decreaseBalance, BigDecimal.valueOf(1), BigDecimal.TEN, false);
        assertThat(decreaseBalance.getBalanceAt()).isNotNull().isAfter(before);

        StockBalance deltaBalance = balance("7", "4", "2", "70");
        service.applyExactDelta(deltaBalance, new BigDecimal("-1"), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        assertThat(deltaBalance.getBalanceAt()).isNotNull().isAfter(before);
    }

    @Test
    void reserveMovesQuantityFromAvailableToReservedWithoutChangingOnHand() {
        StockBalance balance = balance("10", "10", "0", "50");

        service.reserve(balance, BigDecimal.valueOf(4));

        assertThat(balance.getQuantity()).isEqualByComparingTo("10");
        assertThat(balance.getAvailableQuantity()).isEqualByComparingTo("6");
        assertThat(balance.getReservedQuantity()).isEqualByComparingTo("4");
        assertThat(balance.getTotalValue()).isEqualByComparingTo("50");
    }

    @Test
    void reserveRejectsWhenAvailableIsInsufficient() {
        StockBalance balance = balance("10", "2", "0", "50");

        assertThatThrownBy(() -> service.reserve(balance, BigDecimal.valueOf(3)))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Not enough available stock to reserve");
    }

    @Test
    void releaseMovesQuantityBackToAvailableWithoutChangingOnHand() {
        StockBalance balance = balance("10", "6", "4", "50");

        service.releaseReservation(balance, BigDecimal.valueOf(3));

        assertThat(balance.getQuantity()).isEqualByComparingTo("10");
        assertThat(balance.getAvailableQuantity()).isEqualByComparingTo("9");
        assertThat(balance.getReservedQuantity()).isEqualByComparingTo("1");
        assertThat(balance.getTotalValue()).isEqualByComparingTo("50");
    }

    @Test
    void increaseOnHandUpdatesQuantityAvailableAndValue() {
        StockBalance balance = balance("5", "5", "0", "20");
        balance.setQuarantineQuantity(new BigDecimal("1"));

        StockBalanceMutationService.BalanceMutation mutation =
            service.increaseOnHand(balance, BigDecimal.valueOf(2), BigDecimal.valueOf(3));

        assertThat(balance.getQuantity()).isEqualByComparingTo("7");
        assertThat(balance.getAvailableQuantity()).isEqualByComparingTo("7");
        assertThat(balance.getTotalValue()).isEqualByComparingTo("26");
        assertThat(mutation.before()).isEqualTo(new StockBalanceMutationService.BalanceSnapshot(
            new BigDecimal("5"), new BigDecimal("5"), BigDecimal.ZERO,
            BigDecimal.ONE, new BigDecimal("20")));
        assertThat(mutation.after()).isEqualTo(new StockBalanceMutationService.BalanceSnapshot(
            new BigDecimal("7"), new BigDecimal("7"), BigDecimal.ZERO,
            BigDecimal.ONE, new BigDecimal("26")));
        assertThat(mutation.onHandDelta()).isEqualByComparingTo("2");
        assertThat(mutation.availableDelta()).isEqualByComparingTo("2");
        assertThat(mutation.reservedDelta()).isEqualByComparingTo("0");
        assertThat(mutation.quarantineDelta()).isEqualByComparingTo("0");
        assertThat(mutation.totalValueDelta()).isEqualByComparingTo("6");
    }

    @Test
    void decreaseOnHandRejectsNegativeAvailableWhenNotAllowed() {
        StockBalance balance = balance("2", "1", "1", "20");

        assertThatThrownBy(() -> service.decreaseOnHand(balance, BigDecimal.valueOf(2), BigDecimal.TEN, false))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Insufficient available stock");
    }

    @Test
    void decreaseOnHandAllowsNegativeWhenExplicitlyEnabled() {
        StockBalance balance = balance("2", "1", "1", "20");

        StockBalanceMutationService.BalanceMutation mutation =
            service.decreaseOnHand(balance, BigDecimal.valueOf(2), BigDecimal.TEN, true);

        assertThat(balance.getQuantity()).isEqualByComparingTo("0");
        assertThat(balance.getAvailableQuantity()).isEqualByComparingTo("-1");
        assertThat(balance.getReservedQuantity()).isEqualByComparingTo("1");
        assertThat(balance.getTotalValue()).isEqualByComparingTo("10");
        assertThat(mutation.onHandDelta()).isEqualByComparingTo("-2");
        assertThat(mutation.availableDelta()).isEqualByComparingTo("-2");
        assertThat(mutation.reservedDelta()).isZero();
        assertThat(mutation.quarantineDelta()).isZero();
        assertThat(mutation.totalValueDelta()).isEqualByComparingTo("-10");
    }

    @Test
    void exactReversalAppliesEveryPersistedQuantityBucketAndValueDelta() {
        StockBalance balance = balance("7", "4", "2", "70");
        balance.setQuarantineQuantity(BigDecimal.ONE);

        StockBalanceMutationService.BalanceMutation mutation = service.applyExactDelta(
            balance,
            new BigDecimal("-2"),
            new BigDecimal("-1"),
            new BigDecimal("-1"),
            BigDecimal.ZERO,
            new BigDecimal("-20")
        );

        assertThat(balance.getQuantity()).isEqualByComparingTo("5");
        assertThat(balance.getAvailableQuantity()).isEqualByComparingTo("3");
        assertThat(balance.getReservedQuantity()).isEqualByComparingTo("1");
        assertThat(balance.getQuarantineQuantity()).isEqualByComparingTo("1");
        assertThat(balance.getTotalValue()).isEqualByComparingTo("50");
        assertThat(mutation.onHandDelta()).isEqualByComparingTo("-2");
        assertThat(mutation.availableDelta()).isEqualByComparingTo("-1");
        assertThat(mutation.reservedDelta()).isEqualByComparingTo("-1");
        assertThat(mutation.totalValueDelta()).isEqualByComparingTo("-20");
    }

    @Test
    void exactReversalRejectsAnyNegativeResultInsteadOfClampingIt() {
        StockBalance balance = balance("1", "1", "0", "5");

        assertThatThrownBy(() -> service.applyExactDelta(
            balance,
            new BigDecimal("-2"),
            new BigDecimal("-2"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            new BigDecimal("-10")
        ))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("exact reversal", "negative");

        assertThat(balance.getQuantity()).isEqualByComparingTo("1");
        assertThat(balance.getTotalValue()).isEqualByComparingTo("5");
    }

    @Test
    void decreaseOnHandZeroQuantityEnforcesZeroTotalValueResidualPolicy() {
        StockBalance balance = balance("1", "1", "0", "10.0001");
        // Consume full quantity 1 with consumedCost 10.0000 (0.0001 fraction would otherwise remain)
        service.decreaseOnHand(balance, BigDecimal.ONE, new BigDecimal("10.0000"), false);
        assertThat(balance.getQuantity()).isEqualByComparingTo("0");
        assertThat(balance.getAvailableQuantity()).isEqualByComparingTo("0");
        assertThat(balance.getTotalValue()).isEqualByComparingTo("0");
    }

    private StockBalance balance(String quantity, String available, String reserved, String totalValue) {
        StockBalance balance = new StockBalance();
        balance.setQuantity(new BigDecimal(quantity));
        balance.setAvailableQuantity(new BigDecimal(available));
        balance.setReservedQuantity(new BigDecimal(reserved));
        balance.setTotalValue(new BigDecimal(totalValue));
        return balance;
    }
}
