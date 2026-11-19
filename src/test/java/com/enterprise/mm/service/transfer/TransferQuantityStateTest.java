package com.enterprise.mm.service.transfer;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class TransferQuantityStateTest {
    private BigDecimal n(String value) { return new BigDecimal(value); }

    @Test void reportedLossDoesNotClearTransitUntilPosted() {
        var state = new TransferQuantityState(n("100"), n("100"), n("0"), n("98"), n("0"), n("0"));
        assertThat(state.stillToDispatch()).isEqualByComparingTo("0");
        assertThat(state.unresolvedTransit()).isEqualByComparingTo("2");
    }
    @Test void receiptDoesNotHideUndispatchedRemainder() {
        var state = new TransferQuantityState(n("100"), n("40"), n("10"), n("35"), n("3"), n("2"));
        assertThat(state.stillToDispatch()).isEqualByComparingTo("50");
        assertThat(state.unresolvedTransit()).isEqualByComparingTo("0");
    }
    @Test void rejectsOverClearance() {
        assertThatIllegalArgumentException().isThrownBy(() ->
            new TransferQuantityState(n("100"), n("40"), n("0"), n("39"), n("2"), n("0")));
    }
    @Test void rejectsOverDispatchIncludingCancelledPlan() {
        assertThatIllegalArgumentException().isThrownBy(() ->
            new TransferQuantityState(n("100"), n("90"), n("11"), n("0"), n("0"), n("0")));
    }
    @Test void rejectsNegativeAndMissingFacts() {
        assertThatIllegalArgumentException().isThrownBy(() ->
            new TransferQuantityState(n("100"), n("0"), n("-1"), n("0"), n("0"), n("0")));
        assertThatIllegalArgumentException().isThrownBy(() ->
            new TransferQuantityState(n("100"), null, n("0"), n("0"), n("0"), n("0")));
    }
}
