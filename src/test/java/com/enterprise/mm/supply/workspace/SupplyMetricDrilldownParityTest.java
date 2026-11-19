package com.enterprise.mm.supply.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.supply.workspace.domain.SupplyWorkspaceQuery;
import com.enterprise.mm.supply.workspace.enums.SupplyCommitmentSort;
import com.enterprise.mm.supply.workspace.enums.SupplyLens;
import com.enterprise.mm.supply.workspace.enums.SupplyQueueKey;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.SupplyOverviewResponse;
import com.enterprise.mm.supply.workspace.repository.SupplyCommitmentQueryRepository;
import com.enterprise.mm.supply.workspace.service.SupplyActionPolicy;
import com.enterprise.mm.supply.workspace.service.SupplyMoneyService;
import com.enterprise.mm.supply.workspace.service.SupplySlaPolicy;
import com.enterprise.mm.supply.workspace.service.SupplyWorkspaceAccessPolicy;
import com.enterprise.mm.supply.workspace.service.SupplyWorkspaceQueryService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SupplyMetricDrilldownParityTest {
    private static final Instant NOW = Instant.parse("2026-07-15T08:00:00Z");

    @Test
    void everyMetricCountAndQueueSummaryUsesItsSerializedNamedDrilldownPredicate() {
        SupplyCommitmentQueryRepository repository = mock(SupplyCommitmentQueryRepository.class);
        when(repository.count(any())).thenAnswer(invocation -> {
            SupplyWorkspaceQuery query = invocation.getArgument(0);
            return (long) query.queue().ordinal() + 1;
        });
        when(repository.openExposureByCurrency(any())).thenReturn(List.of());
        when(repository.openQuantityByUom(any())).thenReturn(List.of());
        when(repository.agendaByDay(any(), any(), any())).thenReturn(List.of());
        when(repository.countUnlinkedImportRisks(any())).thenReturn(0L);
        SupplyWorkspaceQuery query = query();

        SupplyOverviewResponse response = service(repository).overview(query);

        assertThat(response.metrics()).hasSize(5);
        response.metrics().forEach(metric -> {
            assertThat(metric.count()).isEqualTo((long) metric.key().ordinal() + 1);
            assertThat(metric.drilldown().queue()).isEqualTo(metric.key());
            assertThat(metric.drilldown().filters().queue()).isEqualTo(metric.key());
            assertThat(metric.drilldown().filters())
                .isEqualTo(SupplyWorkspaceQueryService.appliedFilters(query.withQueue(metric.key())));
            assertThat(metric.comparison().available()).isFalse();
            assertThat(metric.comparison().reason().code())
                .isEqualTo("NO_REPRODUCIBLE_HISTORICAL_COHORT");
        });
        assertThat(response.queues()).extracting(summary -> summary.key())
            .containsExactly(SupplyQueueKey.values());
        assertThat(response.queues()).allSatisfy(summary -> {
            assertThat(summary.count()).isEqualTo((long) summary.key().ordinal() + 1);
            assertThat(summary.drilldown().filters().queue()).isEqualTo(summary.key());
        });

        ArgumentCaptor<SupplyWorkspaceQuery> counted = ArgumentCaptor.forClass(SupplyWorkspaceQuery.class);
        verify(repository, times(SupplyQueueKey.values().length)).count(counted.capture());
        assertThat(counted.getAllValues()).extracting(SupplyWorkspaceQuery::queue)
            .containsExactly(SupplyQueueKey.values());
    }

    private SupplyWorkspaceQueryService service(SupplyCommitmentQueryRepository repository) {
        SupplyWorkspaceAccessPolicy accessPolicy = mock(SupplyWorkspaceAccessPolicy.class);
        return new SupplyWorkspaceQueryService(
            repository,
            new com.enterprise.mm.supply.workspace.domain.SupplyCommitmentKeyCodec(),
            accessPolicy,
            new SupplyMoneyService(),
            new SupplySlaPolicy(),
            new SupplyActionPolicy(),
            Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private SupplyWorkspaceQuery query() {
        UUID companyId = UUID.fromString("3ac84dd3-0045-4a41-a795-e27d31a348e0");
        ZoneId timeZone = ZoneId.of("Asia/Tashkent");
        return new SupplyWorkspaceQuery(
            "tenant-supply", Set.of(companyId), Set.of(), false,
            NOW, LocalDate.of(2026, 7, 15), timeZone,
            LocalDate.of(2026, 7, 15), LocalDate.of(2026, 8, 1),
            SupplyLens.TEAM, null, companyId, null, null, null, null,
            null, null, null, null, null, "bearing", SupplyCommitmentSort.DEFAULT);
    }
}
