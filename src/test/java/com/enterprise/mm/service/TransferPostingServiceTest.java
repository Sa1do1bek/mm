package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TransferPostingServiceTest {
    @Test void dispatchContextMustIdentifyTheAuthorizedPlanLine() {
        assertThatIllegalArgumentException().isThrownBy(() -> new TransferPostingContext(UUID.randomUUID(), UUID.randomUUID(), null, TransferPostingContext.Kind.DISPATCH));
    }
    @Test void clearanceContextCannotOmitShipmentAllocation() {
        assertThatIllegalArgumentException().isThrownBy(() -> new TransferPostingContext(UUID.randomUUID(), UUID.randomUUID(), null, TransferPostingContext.Kind.GOOD_RECEIPT));
    }

    @Test void transferQuantityHonorsSevenPlaceSnapshotAndLegacyPrecision() {
        var exact = new java.math.BigDecimal("0.1234567");
        assertThatCode(()->TransferPostingService.validateQuantity(exact,7)).doesNotThrowAnyException();
        assertThatThrownBy(()->TransferPostingService.validateQuantity(exact,4)).hasMessageContaining("allowed decimal precision");
        assertThatThrownBy(()->TransferPostingService.validateQuantity(new java.math.BigDecimal("0.5"),0)).hasMessageContaining("allowed decimal precision");
        assertThatThrownBy(()->TransferPostingService.validateQuantity(new java.math.BigDecimal("0.12345678"),7)).hasMessageContaining("allowed decimal precision");
    }

}
