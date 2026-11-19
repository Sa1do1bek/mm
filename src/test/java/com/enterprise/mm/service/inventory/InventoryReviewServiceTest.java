package com.enterprise.mm.service.inventory;

import static org.assertj.core.api.Assertions.*;
import com.enterprise.mm.enums.*;
import com.enterprise.mm.error.BadRequestException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class InventoryReviewServiceTest {
    @Test void investigationPostponementAndSourceCorrectionDoNotAuthorizeStockPosting() {
        assertThat(InventoryReviewService.isPostingDecision(InventoryDiscrepancyDecisionType.START_INVESTIGATION)).isFalse();
        assertThat(InventoryReviewService.isPostingDecision(InventoryDiscrepancyDecisionType.POSTPONE)).isFalse();
        assertThat(InventoryReviewService.isPostingDecision(InventoryDiscrepancyDecisionType.CORRECT_SOURCE_OPERATION)).isFalse();
        assertThat(InventoryReviewService.isPostingDecision(InventoryDiscrepancyDecisionType.WRITE_OFF_SHORTAGE)).isTrue();
        assertThat(InventoryReviewService.isPostingDecision(InventoryDiscrepancyDecisionType.POST_SURPLUS)).isTrue();
    }
    @Test void aPostingDecisionMustMatchTheObservedSign() {
        assertThatThrownBy(()->InventoryReviewService.validateDecisionSign(InventoryDiscrepancyDecisionType.WRITE_OFF_SHORTAGE,BigDecimal.ONE)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(()->InventoryReviewService.validateDecisionSign(InventoryDiscrepancyDecisionType.POST_SURPLUS,BigDecimal.ONE.negate())).isInstanceOf(BadRequestException.class);
        assertThatCode(()->InventoryReviewService.validateDecisionSign(InventoryDiscrepancyDecisionType.WRITE_OFF_SHORTAGE,BigDecimal.ONE.negate())).doesNotThrowAnyException();
    }
}
