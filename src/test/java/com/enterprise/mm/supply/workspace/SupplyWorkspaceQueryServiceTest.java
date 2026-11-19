package com.enterprise.mm.supply.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.enterprise.mm.supply.workspace.domain.SupplyCommitmentKey;
import com.enterprise.mm.supply.workspace.domain.SupplyCommitmentKeyCodec;
import com.enterprise.mm.supply.workspace.domain.SupplyWorkspaceQuery;
import com.enterprise.mm.supply.workspace.enums.SupplyCommitmentKind;
import com.enterprise.mm.supply.workspace.enums.SupplyCommitmentSort;
import com.enterprise.mm.supply.workspace.enums.SupplyDataQuality;
import com.enterprise.mm.supply.workspace.enums.SupplyLens;
import com.enterprise.mm.supply.workspace.enums.SupplyQueueKey;
import com.enterprise.mm.supply.workspace.enums.SupplySeverity;
import com.enterprise.mm.supply.workspace.enums.SupplySourceState;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.SupplyCommitmentPageResponse;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.SupplyOverviewResponse;
import com.enterprise.mm.supply.workspace.repository.SupplyCommitmentQueryRepository;
import com.enterprise.mm.supply.workspace.repository.SupplyCommitmentRecord;
import com.enterprise.mm.supply.workspace.service.SupplyActionPolicy;
import com.enterprise.mm.supply.workspace.service.SupplyMoneyService;
import com.enterprise.mm.supply.workspace.service.SupplySlaPolicy;
import com.enterprise.mm.supply.workspace.service.SupplyWorkspaceAccessPolicy;
import com.enterprise.mm.supply.workspace.service.SupplyWorkspaceQueryService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

class SupplyWorkspaceQueryServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-15T08:00:00Z");
    private static final UUID COMPANY_ID = UUID.fromString("7e4a23f0-e615-45d1-8ac6-106599fc0f93");
    private static final UUID SCHEDULE_ID = UUID.fromString("31e2e424-40a7-4314-8216-b9d893e1d0fb");

    @Test
    void mapsCanonicalAndEncodedKeysAndKeepsLegacyScheduleReadinessExplicitlyPartial() {
        SupplyCommitmentQueryRepository repository = mock(SupplyCommitmentQueryRepository.class);
        SupplyCommitmentRecord legacySchedule = record(
            new SupplyCommitmentKey(SupplyCommitmentKind.SCHEDULE, SCHEDULE_ID),
            LocalDate.of(2026, 7, 18), SupplyDataQuality.HEADER_ONLY,
            false, true, false, false, false, true);
        when(repository.search(any(), any())).thenReturn(new PageImpl<>(
            List.of(legacySchedule), PageRequest.of(0, 20), 1));

        SupplyCommitmentPageResponse response = service(repository).commitments(query(), PageRequest.of(0, 20));

        assertThat(response.items()).hasSize(1);
        var row = response.items().getFirst();
        assertThat(row.commitmentKey()).isEqualTo("SCHEDULE:" + SCHEDULE_ID);
        assertThat(row.encodedCommitmentKey())
            .isEqualTo(new SupplyCommitmentKeyCodec().encode(legacySchedule.key()));
        assertThat(row.dataQuality()).isEqualTo(SupplyDataQuality.HEADER_ONLY);
        assertThat(row.asnReadiness().availability()).isEqualTo(SupplyDataQuality.PARTIAL);
        assertThat(row.asnReadiness().reasons()).extracting(reason -> reason.code())
            .containsExactly("HEADER_ONLY_SCHEDULE_ALLOCATION");
        assertThat(row.destinationWarehouse()).isNull();
        assertThat(row.blockerCode()).isEqualTo("ARRIVING_7D_WITHOUT_CONFIRMED_ASN");
        assertThat(row.blockerMessageKey()).isEqualTo("supply.workspace.blocker.asnMissing");
        assertThat(row.limitations()).extracting(reason -> reason.code())
            .contains("DESTINATION_WAREHOUSE_NOT_RESOLVED");
        assertThat(row.quantities().open().value()).isEqualByComparingTo("8");
        assertThat(row.quantities().open().uomCode()).isNull();
        assertThat(row.quantities().planned().value()).isEqualByComparingTo("10");
        assertThat(row.quantities().accepted().value()).isEqualByComparingTo("2");
        assertThat(row.allowedActions()).extracting(action -> action.key())
            .containsExactly("OPEN_COMMITMENT");
        assertThat(response.totalElements()).isEqualTo(1);
        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.sourceFreshness().getFirst().state()).isEqualTo(SupplySourceState.FRESH);
    }

    @Test
    void failedMetricAndUnlinkedImportCoverageAreReportedAsPartialAndNeverAsZero() {
        SupplyCommitmentQueryRepository repository = mock(SupplyCommitmentQueryRepository.class);
        when(repository.count(any())).thenAnswer(invocation -> {
            SupplyWorkspaceQuery counted = invocation.getArgument(0);
            if (counted.queue() == SupplyQueueKey.INTEGRATION_FAILED) {
                throw new DataRetrievalFailureException("integration source unavailable");
            }
            return 2L;
        });
        when(repository.openExposureByCurrency(any())).thenReturn(List.of(
            new SupplyCommitmentRecord.MoneyValue("USD", new BigDecimal("15")),
            new SupplyCommitmentRecord.MoneyValue("UZS", new BigDecimal("250000"))));
        when(repository.openQuantityByUom(any())).thenReturn(List.of());
        when(repository.agendaByDay(any(), any(), any())).thenReturn(List.of());
        when(repository.countUnlinkedImportRisks(any())).thenReturn(1L);

        SupplyOverviewResponse response = service(repository).overview(query());

        var failed = response.metrics().stream()
            .filter(metric -> metric.key() == SupplyQueueKey.INTEGRATION_FAILED)
            .findFirst().orElseThrow();
        assertThat(failed.count()).isNull();
        assertThat(failed.availability()).isEqualTo(SupplyDataQuality.UNAVAILABLE);
        assertThat(response.partialErrors()).extracting(error -> error.code())
            .contains("SUPPLY_METRIC_SOURCE_FAILED", "UNLINKED_IMPORT_DELIVERY");
        assertThat(response.sourceFreshness().getFirst().state()).isEqualTo(SupplySourceState.PARTIAL);
        var overdue = response.metrics().stream()
            .filter(metric -> metric.key() == SupplyQueueKey.OVERDUE_COMMITMENTS)
            .findFirst().orElseThrow();
        assertThat(overdue.moneyByCurrency()).extracting(value -> value.currency())
            .containsExactly("USD", "UZS");
        assertThat(overdue.moneyByCurrency()).extracting(value -> value.originalAmount())
            .containsExactly(new BigDecimal("15"), new BigDecimal("250000"));
        assertThat(overdue.availability()).isEqualTo(SupplyDataQuality.PARTIAL);
        assertThat(overdue.reasons()).extracting(reason -> reason.code())
            .contains("UNKNOWN_OPEN_QUANTITY_EXCLUDED");
        var customs = response.metrics().stream()
            .filter(metric -> metric.key() == SupplyQueueKey.CUSTOMS_DOCUMENT_BLOCKED)
            .findFirst().orElseThrow();
        assertThat(customs.availability()).isEqualTo(SupplyDataQuality.PARTIAL);
        assertThat(customs.reasons()).extracting(reason -> reason.code())
            .contains("DOCUMENT_CHECKLIST_NOT_MODELED");
        assertThat(response.queues().stream()
            .filter(queue -> queue.key() == SupplyQueueKey.CUSTOMS_DOCUMENT_BLOCKED)
            .findFirst().orElseThrow().availability()).isEqualTo(SupplyDataQuality.PARTIAL);
    }

    @Test
    void namedPredicateQueriesAndDeterministicSortContractAreAvailableForEveryQueue() {
        org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc =
            mock(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), any(org.springframework.jdbc.core.namedparam.MapSqlParameterSource.class),
            org.mockito.ArgumentMatchers.eq(Long.class))).thenReturn(0L);
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.namedparam.MapSqlParameterSource.class),
            org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<SupplyCommitmentRecord>>any()))
            .thenReturn(List.of());
        SupplyCommitmentQueryRepository repository = new SupplyCommitmentQueryRepository(jdbc, "Asia/Tashkent");

        for (SupplyQueueKey key : SupplyQueueKey.values()) {
            assertThat(repository.count(query().withQueue(key))).isZero();
        }
        repository.search(query(), PageRequest.of(0, 10));

        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.atLeastOnce()).query(
            sql.capture(), any(org.springframework.jdbc.core.namedparam.MapSqlParameterSource.class),
            org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<SupplyCommitmentRecord>>any());
        String searchSql = sql.getAllValues().getLast();
        assertThat(searchSql)
            .contains("severity_rank desc")
            .contains("is_overdue_commitment")
            .contains("operational_date asc")
            .contains("canonical_key asc")
            .doesNotContain("open_amount desc");
    }

    private SupplyWorkspaceQueryService service(SupplyCommitmentQueryRepository repository) {
        return new SupplyWorkspaceQueryService(
            repository,
            new SupplyCommitmentKeyCodec(),
            mock(SupplyWorkspaceAccessPolicy.class),
            new SupplyMoneyService(),
            new SupplySlaPolicy(),
            new SupplyActionPolicy(),
            Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private SupplyWorkspaceQuery query() {
        ZoneId timeZone = ZoneId.of("Asia/Tashkent");
        return new SupplyWorkspaceQuery(
            "tenant-supply", Set.of(COMPANY_ID), Set.of(), false,
            NOW, LocalDate.of(2026, 7, 15), timeZone,
            null, null, SupplyLens.TEAM, null, COMPANY_ID, null, null, null, null,
            null, null, null, null, null, null, SupplyCommitmentSort.DEFAULT);
    }

    private SupplyCommitmentRecord record(
        SupplyCommitmentKey key,
        LocalDate operationalDate,
        SupplyDataQuality dataQuality,
        boolean overdue,
        boolean arrivingWithoutAsn,
        boolean importRisk,
        boolean customsBlocked,
        boolean integrationFailed,
        boolean unassigned
    ) {
        return new SupplyCommitmentRecord(
            key, COMPANY_ID,
            UUID.fromString("0ac44d0b-43c6-48e2-a873-858e47e2fcce"), "PO-100", "Bearings PO",
            UUID.fromString("9ebd985a-7346-40e4-98ca-4024649d3554"), "SUP-1", "Bearing supplier",
            null, operationalDate, "PLANNED", "USD", new BigDecimal("100"),
            new BigDecimal("8"), null, dataQuality, SupplySeverity.WARNING,
            overdue, arrivingWithoutAsn, importRisk, customsBlocked, integrationFailed, unassigned,
            null, null, null, null, null,
            null, null, null, null, null,
            new BigDecimal("10"), new BigDecimal("2"), true);
    }
}
