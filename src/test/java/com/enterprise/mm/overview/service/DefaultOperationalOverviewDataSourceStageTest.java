package com.enterprise.mm.overview.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DefaultOperationalOverviewDataSourceStageTest {

    @Test
    void sourcingStageUsesOnlyTheApprovedUnsourcedCohortExposedByItsDrilldown() {
        var stage = DefaultOperationalOverviewDataSource.sourcingStage(9L);

        assertThat(stage.label()).isEqualTo("Одобрено без источника");
        assertThat(stage.count()).isEqualTo(9L);
        assertThat(stage.queueKey()).isEqualTo("APPROVED_UNSOURCED");
        assertThat(stage.drilldownUrl())
            .isEqualTo("/app/procurement-command?queue=APPROVED_UNSOURCED");
    }

    @Test
    void receivingStageUsesTheSameSentAndPartiallyReceivedCohortAsGoodsIncome() {
        var stage = DefaultOperationalOverviewDataSource.receivingStage(13L);

        assertThat(stage.count()).isEqualTo(13L);
        assertThat(stage.queueKey()).isEqualTo("RECEIVING");
        assertThat(stage.drilldownUrl()).isEqualTo(
            "/app/purchase-orders?status=SENT&status=PARTIALLY_RECEIVED");
    }
}
