package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FinishedGoodsOpeningLiveReviewTest {
    private final FinishedGoodsOpeningLiveReview.Evidence clean =
        new FinishedGoodsOpeningLiveReview.Evidence(true, true, true, false, false, false, false);

    @Test
    void allowsOnlyAValidUntouchedPositionToProceed() {
        assertThat(FinishedGoodsOpeningLiveReview.classify("LIVE_RECONCILIATION_REQUIRED", clean))
            .isEqualTo("ELIGIBLE");
        assertThat(FinishedGoodsOpeningLiveReview.classify("NEGATIVE_REVIEW", clean))
            .isEqualTo("NEGATIVE_REVIEW");
    }

    @Test
    void blocksMissingOrIncompatibleMasterAndUnit() {
        assertThat(FinishedGoodsOpeningLiveReview.classify("LIVE_RECONCILIATION_REQUIRED",
            new FinishedGoodsOpeningLiveReview.Evidence(false, true, true, false, false, false, false)))
            .isEqualTo("MASTER_REVIEW");
        assertThat(FinishedGoodsOpeningLiveReview.classify("LIVE_RECONCILIATION_REQUIRED",
            new FinishedGoodsOpeningLiveReview.Evidence(true, false, true, false, false, false, false)))
            .isEqualTo("UOM_REVIEW");
    }

    @Test
    void blocksExistingPhysicalStockOrPriorNativeMovement() {
        assertThat(FinishedGoodsOpeningLiveReview.classify("LIVE_RECONCILIATION_REQUIRED",
            new FinishedGoodsOpeningLiveReview.Evidence(true, true, true, true, false, false, false)))
            .isEqualTo("EXISTING_STOCK_REVIEW");
        assertThat(FinishedGoodsOpeningLiveReview.classify("LIVE_RECONCILIATION_REQUIRED",
            new FinishedGoodsOpeningLiveReview.Evidence(true, true, true, false, true, false, false)))
            .isEqualTo("EXISTING_MOVEMENT_REVIEW");
    }

    @Test
    void blocksControlledStockDuplicateTargetAndOpeningInProgress() {
        assertThat(FinishedGoodsOpeningLiveReview.classify("LIVE_RECONCILIATION_REQUIRED",
            new FinishedGoodsOpeningLiveReview.Evidence(true, true, false, false, false, false, false)))
            .isEqualTo("CONTROLLED_STOCK_REVIEW");
        assertThat(FinishedGoodsOpeningLiveReview.classify("LIVE_RECONCILIATION_REQUIRED",
            new FinishedGoodsOpeningLiveReview.Evidence(true, true, true, false, false, true, false)))
            .isEqualTo("DUPLICATE_TARGET_REVIEW");
        assertThat(FinishedGoodsOpeningLiveReview.classify("LIVE_RECONCILIATION_REQUIRED",
            new FinishedGoodsOpeningLiveReview.Evidence(true, true, true, false, false, false, true)))
            .isEqualTo("OPENING_IN_PROGRESS");
    }
}
