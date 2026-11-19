package com.enterprise.mm.service.transfer;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class TransferValueSplitTest {
    private BigDecimal n(String value) { return new BigDecimal(value); }
    @Test void finalClearanceUsesResidualValue() {
        var first = TransferValueSplit.take(n("3"), n("0.01"), n("1"));
        var second = TransferValueSplit.take(n("2"), n("0.01").subtract(first), n("1"));
        var last = TransferValueSplit.take(n("1"), n("0.01").subtract(first).subtract(second), n("1"));
        assertThat(first).isEqualByComparingTo("0.0033");
        assertThat(second).isEqualByComparingTo("0.0034");
        assertThat(last).isEqualByComparingTo("0.0033");
        assertThat(first.add(second).add(last)).isEqualByComparingTo("0.01");
        assertThat(TransferValueSplit.take(n("1"), n("0.0034"), n("1"))).isEqualByComparingTo("0.0034");
    }
    @Test void supportsZeroValueStockAndFractionalQuantities() {
        assertThat(TransferValueSplit.take(n("0.125"), n("2.50"), n("0.025"))).isEqualByComparingTo("0.5000");
        assertThat(TransferValueSplit.take(n("1"), n("0"), n("0.5"))).isZero();
    }
    @Test void rejectsInvalidClearanceBeforeDividing() {
        for (String quantity : new String[] {"0", "-1", "4"}) {
            assertThatIllegalArgumentException().isThrownBy(() -> TransferValueSplit.take(n("3"), n("10"), n(quantity)));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> TransferValueSplit.take(n("0"), n("0"), n("1")));
        assertThatIllegalArgumentException().isThrownBy(() -> TransferValueSplit.take(n("3"), n("-1"), n("1")));
        assertThatIllegalArgumentException().isThrownBy(() -> TransferValueSplit.take(n("3"), null, n("1")));
    }
}
