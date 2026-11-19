package com.enterprise.mm.inventory.policy;
import com.enterprise.mm.enums.InventoryStatus;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class InventoryFreezePolicyTest {
    @Test void approvalAndReturnForCorrectionKeepTheFreeze() {
        assertThat(InventoryFreezePolicy.holdsFreeze(InventoryStatus.APPROVED,true)).isTrue();
        assertThat(InventoryFreezePolicy.holdsFreeze(InventoryStatus.REJECTED,true)).isTrue();
        assertThat(InventoryFreezePolicy.holdsFreeze(InventoryStatus.ADJUSTMENT_POSTED,true)).isFalse();
        assertThat(InventoryFreezePolicy.holdsFreeze(InventoryStatus.CANCELLED,true)).isFalse();
        assertThat(InventoryFreezePolicy.holdsFreeze(InventoryStatus.APPROVED,false)).isFalse();
    }
}
