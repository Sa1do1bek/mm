package com.enterprise.mm.supply.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.supply.workspace.domain.SupplyWorkspaceQuery;
import com.enterprise.mm.supply.workspace.enums.SupplyAvailability;
import com.enterprise.mm.supply.workspace.enums.SupplyCalendarLane;
import com.enterprise.mm.supply.workspace.enums.SupplyCommitmentSort;
import com.enterprise.mm.supply.workspace.enums.SupplyDateBasis;
import com.enterprise.mm.supply.workspace.enums.SupplyLens;
import com.enterprise.mm.supply.workspace.enums.SupplyRiskKey;
import com.enterprise.mm.supply.workspace.enums.SupplySeverity;
import com.enterprise.mm.supply.workspace.enums.SupplySourceState;
import com.enterprise.mm.supply.workspace.payload.SupplyCalendarDtos.CalendarEvent;
import com.enterprise.mm.supply.workspace.payload.SupplyCalendarDtos.SupplyCalendarResponse;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.DeepLinkTarget;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.EntityRef;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.MoneyValue;
import com.enterprise.mm.supply.workspace.repository.SupplyCalendarQueryRepository;
import com.enterprise.mm.supply.workspace.service.SupplyCalendarService;
import com.enterprise.mm.supply.workspace.service.SupplyWorkspaceAccessPolicy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataRetrievalFailureException;

class SupplyCalendarServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-15T08:00:00Z");
    private static final LocalDate FROM = LocalDate.of(2026, 7, 1);
    private static final LocalDate TO = LocalDate.of(2026, 8, 1);
    private static final UUID COMPANY_ID = UUID.fromString("7e4a23f0-e615-45d1-8ac6-106599fc0f93");

    @Test
    void requiresACompletePositiveCalendarRangeOfAtMostOneHundredDays() {
        SupplyCalendarService service = service(mock(SupplyCalendarQueryRepository.class));

        assertThatThrownBy(() -> service.calendar(query(null, TO), EnumSet.allOf(SupplyCalendarLane.class)))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("SUPPLY_CALENDAR_RANGE_REQUIRED");
        assertThatThrownBy(() -> service.calendar(query(FROM, null), EnumSet.allOf(SupplyCalendarLane.class)))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("SUPPLY_CALENDAR_RANGE_REQUIRED");
        assertThatThrownBy(() -> service.calendar(query(FROM, FROM), EnumSet.allOf(SupplyCalendarLane.class)))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("SUPPLY_CALENDAR_RANGE_INVALID");
        assertThatThrownBy(() -> service.calendar(
            query(FROM, FROM.plusDays(101)), EnumSet.allOf(SupplyCalendarLane.class)))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("SUPPLY_CALENDAR_RANGE_TOO_LARGE");

        SupplyCalendarQueryRepository repository = mock(SupplyCalendarQueryRepository.class);
        healthy(repository);
        assertThat(service(repository).calendar(
            query(FROM, FROM.plusDays(100)), EnumSet.allOf(SupplyCalendarLane.class)).events()).isEmpty();
    }

    @Test
    void keepsHealthyLanesVisibleAndUsesNullCountWhenOneIndependentLaneFails() {
        SupplyCalendarQueryRepository repository = mock(SupplyCalendarQueryRepository.class);
        CalendarEvent delivery = event(
            SupplyCalendarLane.DELIVERY, "DELIVERY_SCHEDULE", FROM.plusDays(2),
            new BigDecimal("100"), "USD", SupplyRiskKey.OVERDUE);
        when(repository.deliveryEvents(any())).thenReturn(List.of(delivery));
        when(repository.asnEvents(any())).thenThrow(
            new DataRetrievalFailureException("ASN source unavailable"));
        when(repository.importAndCustomsEvents(any())).thenReturn(List.of());
        when(repository.invoiceEvents(any())).thenReturn(List.of());
        when(repository.paymentEvents(any())).thenReturn(List.of());

        SupplyCalendarResponse response = service(repository).calendar(
            query(FROM, TO), EnumSet.allOf(SupplyCalendarLane.class));

        assertThat(response.events()).containsExactly(delivery);
        assertThat(response.laneSummaries()).hasSize(5);
        assertThat(response.laneSummaries().stream()
            .filter(summary -> summary.lane() == SupplyCalendarLane.DELIVERY)
            .findFirst().orElseThrow().eventCount()).isEqualTo(1);
        var failed = response.laneSummaries().stream()
            .filter(summary -> summary.lane() == SupplyCalendarLane.ASN)
            .findFirst().orElseThrow();
        assertThat(failed.eventCount()).isNull();
        assertThat(failed.availability()).isEqualTo(SupplyAvailability.UNAVAILABLE);
        assertThat(failed.reasons()).extracting(reason -> reason.code())
            .containsExactly("CALENDAR_SOURCE_FAILED");
        assertThat(response.partialErrors()).singleElement().satisfies(error -> {
            assertThat(error.source()).isEqualTo("ASN");
            assertThat(error.code()).isEqualTo("CALENDAR_SOURCE_FAILED");
            assertThat(error.retryable()).isTrue();
        });
        assertThat(response.sourceFreshness().stream()
            .filter(source -> source.source().equals("ASN"))
            .findFirst().orElseThrow().state()).isEqualTo(SupplySourceState.FAILED);
        assertThat(response.sourceFreshness().stream()
            .filter(source -> source.source().equals("DELIVERY"))
            .findFirst().orElseThrow().state()).isEqualTo(SupplySourceState.FRESH);
    }

    @Test
    void keepsMoneySummariesInsideTheirLaneAndOriginalCurrency() {
        SupplyCalendarQueryRepository repository = mock(SupplyCalendarQueryRepository.class);
        when(repository.deliveryEvents(any())).thenReturn(List.of(
            event(SupplyCalendarLane.DELIVERY, "DELIVERY_SCHEDULE", FROM, new BigDecimal("100"), "USD", null),
            event(SupplyCalendarLane.DELIVERY, "DELIVERY_SCHEDULE", FROM.plusDays(1), new BigDecimal("50"), "UZS", null)));
        when(repository.asnEvents(any())).thenReturn(List.of());
        when(repository.importAndCustomsEvents(any())).thenReturn(List.of(
            event(SupplyCalendarLane.IMPORT_CUSTOMS, "IMPORT_DELIVERY", FROM, new BigDecimal("70"), "USD", null),
            event(SupplyCalendarLane.IMPORT_CUSTOMS, "CUSTOMS_DECLARATION", FROM.plusDays(1), new BigDecimal("30"), "USD", null)));
        when(repository.invoiceEvents(any())).thenReturn(List.of(
            event(SupplyCalendarLane.INVOICE, "CONTRACT_INVOICE", FROM, new BigDecimal("200"), "USD", null)));
        when(repository.paymentEvents(any())).thenReturn(List.of(
            event(SupplyCalendarLane.PAYMENT, "CONTRACT_PAYMENT", FROM, new BigDecimal("300"), "USD", null)));

        SupplyCalendarResponse response = service(repository).calendar(
            query(FROM, TO), EnumSet.allOf(SupplyCalendarLane.class));

        assertLaneMoney(response, SupplyCalendarLane.DELIVERY, "USD", "100");
        assertLaneMoney(response, SupplyCalendarLane.DELIVERY, "UZS", "50");
        assertLaneMoney(response, SupplyCalendarLane.IMPORT_CUSTOMS, "USD", "100");
        assertLaneMoney(response, SupplyCalendarLane.INVOICE, "USD", "200");
        assertLaneMoney(response, SupplyCalendarLane.PAYMENT, "USD", "300");
        assertThat(response.laneSummaries())
            .flatExtracting(summary -> summary.moneyByCurrency())
            .noneMatch(money -> money.originalAmount().compareTo(new BigDecimal("700")) == 0);
    }

    @Test
    void reportsUnsupportedActiveFiltersAndDoesNotPretendThatLaneWasFiltered() {
        SupplyCalendarQueryRepository repository = mock(SupplyCalendarQueryRepository.class);
        healthy(repository);
        SupplyWorkspaceQuery filtered = withFilters(
            query(FROM, TO), SupplyLens.MY_WORK, UUID.randomUUID(), UUID.randomUUID(), "SEA");

        SupplyCalendarResponse response = service(repository).calendar(
            filtered, EnumSet.allOf(SupplyCalendarLane.class));

        var delivery = support(response, SupplyCalendarLane.DELIVERY);
        assertThat(delivery.supportedFilters()).contains("lens", "ownerUserId", "warehouseId");
        assertThat(delivery.unsupportedActiveFilters()).contains("transportMode");

        var payment = support(response, SupplyCalendarLane.PAYMENT);
        assertThat(payment.supportedFilters()).contains("purchaseOrderId", "risk");
        assertThat(payment.supportedFilters()).doesNotContain("warehouseId", "queue");
        assertThat(payment.unsupportedActiveFilters())
            .containsExactly("lens", "ownerUserId", "warehouseId", "transportMode");
        var paymentSummary = response.laneSummaries().stream()
            .filter(summary -> summary.lane() == SupplyCalendarLane.PAYMENT)
            .findFirst().orElseThrow();
        assertThat(paymentSummary.eventCount()).isNull();
        assertThat(paymentSummary.availability()).isEqualTo(SupplyAvailability.UNAVAILABLE);
        assertThat(paymentSummary.reasons()).extracting(reason -> reason.code())
            .containsExactly("UNSUPPORTED_ACTIVE_FILTER");
    }

    @Test
    void preservesAppliedScopeFiltersDateOnlyValuesAndExecutableTargetsInResponseTimezone() {
        SupplyCalendarQueryRepository repository = mock(SupplyCalendarQueryRepository.class);
        CalendarEvent payment = event(
            SupplyCalendarLane.PAYMENT, "CONTRACT_PAYMENT", FROM,
            new BigDecimal("10.5000"), "UZS", null);
        when(repository.paymentEvents(any())).thenReturn(List.of(payment));
        when(repository.deliveryEvents(any())).thenReturn(List.of());
        when(repository.asnEvents(any())).thenReturn(List.of());
        when(repository.importAndCustomsEvents(any())).thenReturn(List.of());
        when(repository.invoiceEvents(any())).thenReturn(List.of());

        SupplyCalendarResponse response = service(repository).calendar(
            query(FROM, TO), Set.of(SupplyCalendarLane.PAYMENT));

        assertThat(response.appliedScope().tenantId()).isEqualTo("tenant-supply");
        assertThat(response.appliedScope().companyIds()).containsExactly(COMPANY_ID);
        assertThat(response.appliedFilters().from()).isEqualTo(FROM);
        assertThat(response.appliedFilters().to()).isEqualTo(TO);
        assertThat(response.events()).singleElement().satisfies(event -> {
            assertThat(event.dateTime()).isEqualTo(FROM);
            assertThat(event.allDay()).isTrue();
            assertThat(event.primaryTarget().moduleKey()).isEqualTo("contract-payments");
            assertThat(event.primaryTarget().recordId()).isEqualTo(event.documentId());
        });
        assertThat(response.timeZone()).isEqualTo("Asia/Tashkent");
        assertThat(response.generatedAt()).isEqualTo(NOW);
        assertThat(response.definitionVersion()).isEqualTo("supply-workspace.v1");
    }

    private SupplyCalendarService service(SupplyCalendarQueryRepository repository) {
        SupplyWorkspaceAccessPolicy accessPolicy = mock(SupplyWorkspaceAccessPolicy.class);
        when(accessPolicy.bindAuthenticatedIdentity(any())).thenAnswer(invocation -> invocation.getArgument(0));
        return new SupplyCalendarService(repository, accessPolicy, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void healthy(SupplyCalendarQueryRepository repository) {
        when(repository.deliveryEvents(any())).thenReturn(List.of());
        when(repository.asnEvents(any())).thenReturn(List.of());
        when(repository.importAndCustomsEvents(any())).thenReturn(List.of());
        when(repository.invoiceEvents(any())).thenReturn(List.of());
        when(repository.paymentEvents(any())).thenReturn(List.of());
    }

    private CalendarEvent event(
        SupplyCalendarLane lane,
        String documentType,
        LocalDate date,
        BigDecimal amount,
        String currency,
        SupplyRiskKey risk
    ) {
        UUID id = UUID.randomUUID();
        String moduleKey = switch (documentType) {
            case "DELIVERY_SCHEDULE" -> "delivery-schedules";
            case "ASN" -> "asn";
            case "IMPORT_DELIVERY" -> "import-deliveries";
            case "CUSTOMS_DECLARATION" -> "customs-declarations";
            case "CONTRACT_INVOICE" -> "contract-invoices";
            case "CONTRACT_PAYMENT" -> "contract-payments";
            default -> throw new IllegalArgumentException(documentType);
        };
        SupplyDateBasis dateBasis = switch (documentType) {
            case "DELIVERY_SCHEDULE" -> SupplyDateBasis.PROMISED_DATE;
            case "ASN" -> SupplyDateBasis.EXPECTED_ARRIVAL_DATE;
            case "IMPORT_DELIVERY" -> SupplyDateBasis.ETA;
            case "CUSTOMS_DECLARATION" -> SupplyDateBasis.DECLARATION_DATE;
            case "CONTRACT_INVOICE" -> SupplyDateBasis.DUE_DATE;
            case "CONTRACT_PAYMENT" -> SupplyDateBasis.PAYMENT_DATE;
            default -> throw new IllegalArgumentException(documentType);
        };
        String code = documentType + "-100";
        return new CalendarEvent(
            documentType + ":" + id + ":" + date,
            null,
            lane,
            date,
            true,
            dateBasis,
            documentType,
            id,
            code,
            documentType + " human name",
            null,
            new EntityRef(UUID.randomUUID(), "SUP-1", "Supplier", "ACTIVE"),
            null,
            new EntityRef(UUID.randomUUID(), "CTR-1", "Contract", "ACTIVE"),
            money(amount, currency),
            "ACTIVE",
            risk,
            null,
            null,
            new DeepLinkTarget(moduleKey, id, null, Map.of("search", code)),
            null);
    }

    private MoneyValue money(BigDecimal amount, String currency) {
        return new MoneyValue(amount, currency, null, null, null, null, null, false);
    }

    private SupplyWorkspaceQuery query(LocalDate from, LocalDate to) {
        return new SupplyWorkspaceQuery(
            "tenant-supply", Set.of(COMPANY_ID), Set.of(), false,
            NOW, LocalDate.of(2026, 7, 15), ZoneId.of("Asia/Tashkent"),
            from, to, SupplyLens.TEAM, null, COMPANY_ID, null, null, null, null,
            null, null, null, null, null, null, SupplyCommitmentSort.DEFAULT);
    }

    private SupplyWorkspaceQuery withFilters(
        SupplyWorkspaceQuery query,
        SupplyLens lens,
        UUID ownerUserId,
        UUID warehouseId,
        String transportMode
    ) {
        return new SupplyWorkspaceQuery(
            query.tenantId(), query.authorizedCompanyIds(), Set.of(warehouseId), true,
            query.asOf(), query.asOfDate(), query.timeZone(), query.from(), query.to(),
            lens, ownerUserId, query.companyId(), query.supplierId(), query.purchaseOrderId(),
            query.contractId(), warehouseId, transportMode, query.currencyCode(), query.status(),
            query.risk(), query.queue(), query.search(), query.sort(),
            UUID.randomUUID(), "calendar-user");
    }

    private com.enterprise.mm.supply.workspace.payload.SupplyCalendarDtos.SectionFilterSupport support(
        SupplyCalendarResponse response,
        SupplyCalendarLane lane
    ) {
        return response.filterSupport().stream()
            .filter(support -> support.lane() == lane)
            .findFirst().orElseThrow();
    }

    private void assertLaneMoney(
        SupplyCalendarResponse response,
        SupplyCalendarLane lane,
        String currency,
        String amount
    ) {
        assertThat(response.laneSummaries().stream()
            .filter(summary -> summary.lane() == lane)
            .findFirst().orElseThrow().moneyByCurrency())
            .filteredOn(money -> currency.equals(money.currency()))
            .singleElement()
            .extracting(MoneyValue::originalAmount)
            .isEqualTo(new BigDecimal(amount));
    }
}
