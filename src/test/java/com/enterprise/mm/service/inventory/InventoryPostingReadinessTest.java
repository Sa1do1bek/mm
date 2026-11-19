package com.enterprise.mm.service.inventory;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
class InventoryPostingReadinessTest {
    @Test void approvalOfAnOlderOrUnknownObservationCannotBePosted() {
        assertThat(InventoryPostingReadinessService.revisionsMatch(8L,7L)).isFalse();
        assertThat(InventoryPostingReadinessService.revisionsMatch(8L,null)).isFalse();
        assertThat(InventoryPostingReadinessService.revisionsMatch(8L,8L)).isTrue();
    }
}
