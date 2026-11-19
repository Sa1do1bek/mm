package com.enterprise.mm.supply.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.DeliverySchedule;
import com.enterprise.mm.entity.AdvanceShippingNotice;
import com.enterprise.mm.entity.CustomsDeclaration;
import com.enterprise.mm.entity.ImportDelivery;
import com.enterprise.mm.entity.IntegrationEvent;
import com.enterprise.mm.entity.LandedLogisticsEvent;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.supply.workspace.domain.SupplyAccessScope;
import com.enterprise.mm.supply.workspace.domain.SupplyCommitmentKey;
import com.enterprise.mm.supply.workspace.domain.SupplyWorkspaceQuery;
import com.enterprise.mm.supply.workspace.enums.SupplyCommitmentKind;
import com.enterprise.mm.supply.workspace.enums.SupplyCommitmentSort;
import com.enterprise.mm.supply.workspace.enums.SupplyDataQuality;
import com.enterprise.mm.supply.workspace.enums.SupplyQueueKey;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceRequest;
import com.enterprise.mm.supply.workspace.entity.SupplyWorkAssignment;
import com.enterprise.mm.supply.workspace.enums.SupplyAssignmentRole;
import com.enterprise.mm.supply.workspace.enums.SupplyDueDateSource;
import com.enterprise.mm.supply.workspace.enums.SupplyEscalationState;
import com.enterprise.mm.supply.workspace.enums.SupplySeverity;
import com.enterprise.mm.supply.workspace.repository.SupplyCommitmentQueryRepository;
import com.enterprise.mm.supply.workspace.repository.SupplyCommitmentRecord;
import com.enterprise.mm.supply.workspace.service.SupplyWorkspaceFilterFactory;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
    "app.seed.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:supply_commitment;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"
})
@ActiveProfiles("test")
@Transactional
class SupplyCommitmentQueryRepositoryTest {
    private static final String TENANT = "tenant-supply-workspace";
    private static final String OTHER_TENANT = "tenant-supply-workspace-other";
    private static final Instant AS_OF = Instant.parse("2026-07-15T08:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 15);

    @Autowired private SupplyCommitmentQueryRepository repository;
    @Autowired private EntityManager entityManager;

    private UUID companyId;
    private UUID otherCompanyId;
    private Supplier supplier;
    private Warehouse warehouse;
    private Warehouse otherWarehouse;
    private SupplyWorkspaceFilterFactory filterFactory;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        otherCompanyId = UUID.randomUUID();
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(companyId);
        filterFactory = new SupplyWorkspaceFilterFactory(Clock.fixed(AS_OF, ZoneOffset.UTC), "Asia/Tashkent");
        supplier = supplier("SUP-SW", "Supply Workspace Supplier", TENANT, companyId);
        warehouse = persist(base(new Warehouse(), "WH-SW", "Workspace warehouse", TENANT, companyId));
        otherWarehouse = persist(base(new Warehouse(), "WH-SW-2", "Other warehouse", TENANT, companyId));
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void selectsActiveSchedulesAndOnlyFallsBackToPoWhenNoActiveScheduleExists() {
        PurchaseOrder active = po("PO-ACTIVE", TODAY.plusDays(4), "SENT", RequestType.GOODS, TENANT, companyId, supplier);
        line(active, "EA", "10", "2", "1", warehouse);
        DeliverySchedule activeSchedule = schedule(active, TODAY.plusDays(3), "PLANNED", "10", "2", "100", "20", TENANT, companyId);

        PurchaseOrder terminal = po("PO-TERMINAL", TODAY.plusDays(5), "PARTIALLY_RECEIVED", RequestType.GOODS, TENANT, companyId, supplier);
        line(terminal, "EA", "5", "1", "3", warehouse);
        schedule(terminal, TODAY.plusDays(2), "RECEIVED", "5", "5", "15", "15", TENANT, companyId);

        PurchaseOrder cancelled = po("PO-CANCELLED-SCHEDULE", TODAY.plusDays(5), "SENT", RequestType.GOODS, TENANT, companyId, supplier);
        line(cancelled, "EA", "5", "1", "3", warehouse);
        DeliverySchedule cancelledSchedule = schedule(
            cancelled, TODAY.plusDays(2), "CANCELLED", "5", "0", "15", "0", TENANT, companyId);

        PurchaseOrder unknown = po("PO-UNKNOWN", TODAY.plusDays(6), "PARTIALLY_COMPLETED", RequestType.GOODS, TENANT, companyId, supplier);
        line(unknown, "EA", "4", "1", "4", warehouse);
        DeliverySchedule unknownSchedule = schedule(unknown, TODAY.plusDays(7), null, "4", "0", "16", "0", TENANT, companyId);

        PurchaseOrder deletedSchedulePo = po("PO-DELETED-SCHEDULE", TODAY.plusDays(8), "SENT", RequestType.GOODS, TENANT, companyId, supplier);
        line(deletedSchedulePo, "EA", "2", "0", "5", warehouse);
        DeliverySchedule deleted = schedule(deletedSchedulePo, TODAY.plusDays(1), "PLANNED", "2", "0", "10", "0", TENANT, companyId);
        deleted.softDelete();

        PurchaseOrder crossCompanySchedulePo = po("PO-CROSS-SCHEDULE", TODAY.plusDays(9), "SENT", RequestType.GOODS, TENANT, companyId, supplier);
        line(crossCompanySchedulePo, "EA", "2", "0", "5", warehouse);
        schedule(crossCompanySchedulePo, TODAY.plusDays(1), "PLANNED", "2", "0", "10", "0", TENANT, otherCompanyId);
        entityManager.flush();

        List<SupplyCommitmentRecord> records = repository.search(query(request -> {}), Pageable.unpaged()).getContent();

        assertThat(records).extracting(SupplyCommitmentRecord::key)
            .contains(
                new SupplyCommitmentKey(SupplyCommitmentKind.SCHEDULE, activeSchedule.getId()),
                new SupplyCommitmentKey(SupplyCommitmentKind.PO, terminal.getId()),
                new SupplyCommitmentKey(SupplyCommitmentKind.PO, cancelled.getId()),
                new SupplyCommitmentKey(SupplyCommitmentKind.SCHEDULE, unknownSchedule.getId()),
                new SupplyCommitmentKey(SupplyCommitmentKind.PO, deletedSchedulePo.getId()),
                new SupplyCommitmentKey(SupplyCommitmentKind.PO, crossCompanySchedulePo.getId()))
            .doesNotContain(
                new SupplyCommitmentKey(SupplyCommitmentKind.PO, active.getId()),
                new SupplyCommitmentKey(SupplyCommitmentKind.PO, unknown.getId()),
                new SupplyCommitmentKey(SupplyCommitmentKind.SCHEDULE, cancelledSchedule.getId()));
    }

    @Test
    void enforcesScopeLifecycleGoodsAndOperationalDateWithoutCreatedAtFallback() {
        PurchaseOrder match = po("PO-SCOPE-MATCH", TODAY.plusDays(2), "SENT", RequestType.GOODS, TENANT, companyId, supplier);
        line(match, "EA", "3", "0", "10", warehouse);

        PurchaseOrder deleted = po("PO-SCOPE-DELETED", TODAY.plusDays(2), "SENT", RequestType.GOODS, TENANT, companyId, supplier);
        line(deleted, "EA", "3", "0", "10", warehouse);
        deleted.softDelete();

        PurchaseOrder service = po("PO-SCOPE-SERVICE", TODAY.plusDays(2), "SENT", RequestType.SERVICE, TENANT, companyId, supplier);
        line(service, "EA", "3", "0", "10", warehouse);

        PurchaseOrder draft = po("PO-SCOPE-DRAFT", TODAY.plusDays(2), "APPROVED", RequestType.GOODS, TENANT, companyId, supplier);
        line(draft, "EA", "3", "0", "10", warehouse);

        Supplier otherCompanySupplier = supplier("SUP-SW-OC", "Other company supplier", TENANT, otherCompanyId);
        PurchaseOrder otherCompany = po("PO-SCOPE-COMPANY", TODAY.plusDays(2), "SENT", RequestType.GOODS, TENANT, otherCompanyId, otherCompanySupplier);
        line(otherCompany, "EA", "3", "0", "10", null);

        Supplier otherTenantSupplier = supplier("SUP-SW-OT", "Other tenant supplier", OTHER_TENANT, companyId);
        PurchaseOrder otherTenant = po("PO-SCOPE-TENANT", TODAY.plusDays(2), "SENT", RequestType.GOODS, OTHER_TENANT, companyId, otherTenantSupplier);
        line(otherTenant, "EA", "3", "0", "10", null);

        PurchaseOrder noOperationalDate = po("PO-SCOPE-NO-DATE", null, "SENT", RequestType.GOODS, TENANT, companyId, supplier);
        line(noOperationalDate, "EA", "3", "0", "10", warehouse);

        PurchaseOrder scheduled = po("PO-SCOPE-SCHEDULE-DATE", TODAY.plusDays(2), "SENT", RequestType.GOODS, TENANT, companyId, supplier);
        line(scheduled, "EA", "3", "0", "10", warehouse);
        schedule(scheduled, TODAY.plusDays(20), "PLANNED", "3", "0", "30", "0", TENANT, companyId);
        entityManager.flush();

        SupplyWorkspaceQuery ranged = query(request -> {
            request.setFrom(TODAY);
            request.setTo(TODAY.plusDays(10));
        });
        List<SupplyCommitmentRecord> records = repository.search(ranged, Pageable.unpaged()).getContent();

        assertThat(records).extracting(SupplyCommitmentRecord::purchaseOrderCode)
            .containsExactly("PO-SCOPE-MATCH");
        assertThat(records.getFirst().operationalDate()).isEqualTo(match.getExpectedDeliveryDate());
        assertThat(repository.count(ranged)).isEqualTo(1);
    }

    @Test
    void keepsMoneyByCurrencyAndQuantityByUomWithoutCrossUnitTotals() {
        PurchaseOrder usd = po("PO-USD", TODAY.plusDays(2), "SENT", RequestType.GOODS, TENANT, companyId, supplier);
        usd.setCurrencyCode("usd");
        line(usd, "EA", "10", "3", "2", warehouse);
        line(usd, "EA", "5", "1", "4", warehouse);

        PurchaseOrder mixed = po("PO-MIXED-UOM", TODAY.plusDays(3), "SENT", RequestType.GOODS, TENANT, companyId, supplier);
        mixed.setCurrencyCode("UZS");
        line(mixed, "KG", "8", "2", "5", warehouse);
        line(mixed, "L", "6", "1", "7", warehouse);

        PurchaseOrder missingUom = po("PO-MISSING-UOM", TODAY.plusDays(3), "SENT", RequestType.GOODS, TENANT, companyId, supplier);
        missingUom.setCurrencyCode("UZS");
        line(missingUom, null, "2", "0", "3", warehouse);

        PurchaseOrder incomplete = po("PO-INCOMPLETE", TODAY.plusDays(3), "SENT", RequestType.GOODS, TENANT, companyId, supplier);
        incomplete.setCurrencyCode("UZS");
        PurchaseOrderItem incompleteLine = line(incomplete, "BOX", "1", "0", "3", warehouse);
        incompleteLine.setQuantity(null);

        PurchaseOrder scheduledPo = po("PO-HEADER-SCHEDULE", TODAY.plusDays(4), "SENT", RequestType.GOODS, TENANT, companyId, supplier);
        scheduledPo.setCurrencyCode("USD");
        line(scheduledPo, "EA", "20", "0", "10", warehouse);
        schedule(scheduledPo, TODAY.plusDays(4), "PARTIALLY_RECEIVED", "10", "2", "100", "20", TENANT, companyId);
        entityManager.flush();

        List<SupplyCommitmentRecord> records = repository.search(query(request -> {}), Pageable.unpaged()).getContent();
        SupplyCommitmentRecord usdRecord = byCode(records, "PO-USD");
        SupplyCommitmentRecord mixedRecord = byCode(records, "PO-MIXED-UOM");
        SupplyCommitmentRecord missingUomRecord = byCode(records, "PO-MISSING-UOM");
        SupplyCommitmentRecord incompleteRecord = byCode(records, "PO-INCOMPLETE");
        SupplyCommitmentRecord scheduledRecord = byCode(records, "PO-HEADER-SCHEDULE");

        assertThat(usdRecord.openQuantity()).isEqualByComparingTo("11");
        assertThat(usdRecord.plannedQuantity()).isEqualByComparingTo("15");
        assertThat(usdRecord.acceptedQuantity()).isEqualByComparingTo("4");
        assertThat(usdRecord.hasOpenQuantity()).isTrue();
        assertThat(usdRecord.uomCode()).isEqualTo("EA");
        assertThat(usdRecord.openAmount()).isEqualByComparingTo("30");
        assertThat(usdRecord.currencyCode()).isEqualTo("USD");
        assertThat(usdRecord.dataQuality()).isEqualTo(SupplyDataQuality.AUTHORITATIVE);

        assertThat(mixedRecord.openQuantity()).isNull();
        assertThat(mixedRecord.plannedQuantity()).isNull();
        assertThat(mixedRecord.acceptedQuantity()).isNull();
        assertThat(mixedRecord.hasOpenQuantity()).isTrue();
        assertThat(mixedRecord.uomCode()).isNull();
        assertThat(mixedRecord.openAmount()).isEqualByComparingTo("65");
        assertThat(mixedRecord.dataQuality()).isEqualTo(SupplyDataQuality.PARTIAL);

        assertThat(missingUomRecord.openQuantity()).isNull();
        assertThat(missingUomRecord.uomCode()).isNull();
        assertThat(missingUomRecord.dataQuality()).isEqualTo(SupplyDataQuality.PARTIAL);

        assertThat(incompleteRecord.openQuantity()).isNull();
        assertThat(incompleteRecord.openAmount()).isNull();
        assertThat(incompleteRecord.dataQuality()).isEqualTo(SupplyDataQuality.PARTIAL);

        assertThat(scheduledRecord.openQuantity()).isEqualByComparingTo("8");
        assertThat(scheduledRecord.plannedQuantity()).isEqualByComparingTo("10");
        assertThat(scheduledRecord.acceptedQuantity()).isEqualByComparingTo("2");
        assertThat(scheduledRecord.hasOpenQuantity()).isTrue();
        assertThat(scheduledRecord.uomCode()).isNull();
        assertThat(scheduledRecord.openAmount()).isEqualByComparingTo("80");
        assertThat(scheduledRecord.dataQuality()).isEqualTo(SupplyDataQuality.HEADER_ONLY);

        List<SupplyCommitmentRecord.MoneyValue> exposure = repository.openExposureByCurrency(query(request -> {}));
        assertThat(exposure).extracting(SupplyCommitmentRecord.MoneyValue::currencyCode)
            .containsExactly("USD", "UZS");
        assertThat(exposure.get(0).amount()).isEqualByComparingTo("110");
        assertThat(exposure.get(1).amount()).isNull();

        List<SupplyCommitmentRecord.QuantityValue> quantity = repository.openQuantityByUom(query(request -> {}));
        assertThat(quantity).extracting(SupplyCommitmentRecord.QuantityValue::uomCode)
            .containsExactly("BOX", "EA", "KG", "L");
        assertThat(quantity.get(0).value()).isNull();
        assertThat(quantity.get(1).value()).isEqualByComparingTo("11");
        assertThat(quantity.get(2).value()).isEqualByComparingTo("6");
        assertThat(quantity.get(3).value()).isEqualByComparingTo("5");
    }

    @Test
    void searchCountPagingAmountSortAndUnpagedUseOneStablePredicateContract() {
        for (int index = 0; index < 4; index++) {
            PurchaseOrder po = po("PO-PARITY-" + index, TODAY.minusDays(1), "SENT", RequestType.GOODS, TENANT, companyId, supplier);
            po.setCurrencyCode("USD");
            line(po, "EA", Integer.toString(index + 2), "0", Integer.toString(index + 1), warehouse);
        }
        PurchaseOrder nonmatch = po("PO-NONMATCH", TODAY.minusDays(1), "SENT", RequestType.GOODS, TENANT, companyId, supplier);
        nonmatch.setCurrencyCode("UZS");
        line(nonmatch, "EA", "99", "0", "99", warehouse);
        entityManager.flush();

        SupplyWorkspaceQuery query = query(request -> {
            request.setSearch("  parity  ");
            request.setCurrencyCode("usd");
            request.setSort(SupplyCommitmentSort.OPEN_AMOUNT);
        });
        List<SupplyCommitmentRecord> all = repository.search(query, Pageable.unpaged()).getContent();
        List<SupplyCommitmentRecord> paged = new ArrayList<>();
        paged.addAll(repository.search(query, PageRequest.of(0, 2)).getContent());
        paged.addAll(repository.search(query, PageRequest.of(1, 2)).getContent());

        assertThat(repository.count(query)).isEqualTo(4);
        assertThat(all).hasSize(4);
        assertThat(paged).extracting(SupplyCommitmentRecord::key)
            .containsExactlyElementsOf(all.stream().map(SupplyCommitmentRecord::key).toList());
        assertThat(all).extracting(SupplyCommitmentRecord::openAmount)
            .isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test
    void warehouseScopeAndFindScopedFailClosedAcrossTenantCompanyAndNoAccess() {
        PurchaseOrder allowed = po("PO-WH-ALLOWED", TODAY.plusDays(1), "SENT", RequestType.GOODS, TENANT, companyId, supplier);
        line(allowed, "EA", "2", "0", "5", warehouse);
        line(allowed, "EA", "3", "0", "7", otherWarehouse);
        PurchaseOrder denied = po("PO-WH-DENIED", TODAY.plusDays(1), "SENT", RequestType.GOODS, TENANT, companyId, supplier);
        line(denied, "EA", "2", "0", "5", otherWarehouse);
        PurchaseOrder scheduledPo = po("PO-WH-SCHEDULE", TODAY.plusDays(1), "SENT", RequestType.GOODS, TENANT, companyId, supplier);
        line(scheduledPo, "EA", "2", "0", "5", warehouse);
        line(scheduledPo, "EA", "3", "0", "7", otherWarehouse);
        schedule(scheduledPo, TODAY.plusDays(1), "PLANNED", "5", "0", "31", "0", TENANT, companyId);
        entityManager.flush();

        SupplyAccessScope allowedScope = new SupplyAccessScope(
            TENANT, Set.of(companyId), Set.of(warehouse.getId()), false, true);
        SupplyWorkspaceQuery allowedQuery = filterFactory.normalize(new SupplyWorkspaceRequest(), allowedScope);
        List<SupplyCommitmentRecord> scopedRecords = repository.search(allowedQuery, Pageable.unpaged()).getContent();
        assertThat(scopedRecords)
            .extracting(SupplyCommitmentRecord::purchaseOrderCode)
            .contains("PO-WH-ALLOWED", "PO-WH-SCHEDULE")
            .doesNotContain("PO-WH-DENIED");
        SupplyCommitmentRecord allowedRecord = byCode(scopedRecords, "PO-WH-ALLOWED");
        assertThat(allowedRecord.openQuantity()).isEqualByComparingTo("2");
        assertThat(allowedRecord.openAmount()).isEqualByComparingTo("10");
        assertThat(allowedRecord.dataQuality()).isEqualTo(SupplyDataQuality.PARTIAL);
        SupplyCommitmentRecord scheduledRecord = byCode(scopedRecords, "PO-WH-SCHEDULE");
        assertThat(scheduledRecord.openQuantity()).isNull();
        assertThat(scheduledRecord.openAmount()).isNull();
        assertThat(scheduledRecord.dataQuality()).isEqualTo(SupplyDataQuality.UNAVAILABLE);
        assertThat(repository.openQuantityByUom(allowedQuery).getFirst().value()).isEqualByComparingTo("2");
        assertThat(repository.openExposureByCurrency(allowedQuery).getFirst().amount()).isNull();

        SupplyAccessScope noWarehouse = new SupplyAccessScope(
            TENANT, Set.of(companyId), Set.of(), false, true);
        SupplyWorkspaceQuery noAccessQuery = filterFactory.normalize(new SupplyWorkspaceRequest(), noWarehouse);
        assertThat(repository.search(noAccessQuery, Pageable.unpaged())).isEmpty();
        assertThat(repository.count(noAccessQuery)).isZero();

        SupplyCommitmentKey key = new SupplyCommitmentKey(SupplyCommitmentKind.PO, allowed.getId());
        assertThat(repository.findScoped(key, allowedScope, AS_OF)).isPresent();
        assertThat(repository.findScoped(
            key, new SupplyAccessScope(TENANT, Set.of(otherCompanyId), Set.of(), false, false), AS_OF)).isEmpty();
        assertThat(repository.findScoped(
            key, new SupplyAccessScope(OTHER_TENANT, Set.of(companyId), Set.of(), false, false), AS_OF)).isEmpty();
        assertThat(repository.findScoped(key, noWarehouse, AS_OF)).isEmpty();
    }

    @Test
    void namedIntegrationQueueIsSupportedByTheCanonicalPredicateContract() {
        SupplyWorkspaceQuery namedQueue = query(request -> request.setQueue(SupplyQueueKey.INTEGRATION_FAILED));

        assertThat(repository.count(namedQueue)).isZero();
    }

    @Test
    void overdueQueueRequiresDemonstrableOpenQuantityAndExcludesUnknownRatherThanTreatingItAsZero() {
        PurchaseOrder open = po(
            "PO-OVERDUE-OPEN", TODAY.minusDays(2), "SENT", RequestType.GOODS, TENANT, companyId, supplier);
        line(open, "EA", "10", "4", "2", warehouse);
        PurchaseOrder complete = po(
            "PO-OVERDUE-COMPLETE", TODAY.minusDays(3), "SENT", RequestType.GOODS, TENANT, companyId, supplier);
        line(complete, "EA", "10", "10", "2", warehouse);
        PurchaseOrder unknown = po(
            "PO-OVERDUE-UNKNOWN", TODAY.minusDays(4), "SENT", RequestType.GOODS, TENANT, companyId, supplier);
        PurchaseOrderItem unknownLine = line(unknown, "EA", "1", "0", "2", warehouse);
        unknownLine.setQuantity(null);
        entityManager.flush();

        SupplyWorkspaceQuery overdue = query(request -> request.setQueue(SupplyQueueKey.OVERDUE_COMMITMENTS));
        assertThat(repository.search(overdue, Pageable.unpaged()).getContent())
            .extracting(SupplyCommitmentRecord::purchaseOrderCode)
            .containsExactly("PO-OVERDUE-OPEN");
        assertThat(repository.count(overdue)).isEqualTo(1);

        SupplyCommitmentRecord unknownRecord = byCode(
            repository.search(query(request -> {}), Pageable.unpaged()).getContent(), "PO-OVERDUE-UNKNOWN");
        assertThat(unknownRecord.hasOpenQuantity()).isNull();
        assertThat(unknownRecord.dataQuality()).isEqualTo(SupplyDataQuality.PARTIAL);
    }

    @Test
    void activeImportEtaRiskIsDerivedAtAsOfAndTracksAllUnresolvedExceptions() {
        PurchaseOrder etaPo = po(
            "PO-IMPORT-ETA", TODAY.plusDays(5), "SENT", RequestType.GOODS, TENANT, companyId, supplier);
        line(etaPo, "EA", "2", "0", "3", warehouse);
        ImportDelivery eta = importDelivery(etaPo, "IN_TRANSIT", TODAY.plusDays(1), TODAY.plusDays(2));
        eta.setRiskFlag(false);

        PurchaseOrder exceptionPo = po(
            "PO-IMPORT-EXCEPTION", TODAY.plusDays(5), "SENT", RequestType.GOODS, TENANT, companyId, supplier);
        line(exceptionPo, "EA", "2", "0", "3", warehouse);
        ImportDelivery exception = importDelivery(exceptionPo, "BOOKED", TODAY.plusDays(10), null);
        logisticsException(exception, LocalDateTime.of(2026, 7, 14, 9, 0), LocalDateTime.of(2026, 7, 15, 7, 0));
        logisticsException(exception, LocalDateTime.of(2026, 7, 15, 8, 0), null);

        PurchaseOrder resolvedLaterPo = po(
            "PO-IMPORT-RESOLVED-LATER", TODAY.plusDays(5), "SENT", RequestType.GOODS, TENANT, companyId, supplier);
        line(resolvedLaterPo, "EA", "2", "0", "3", warehouse);
        ImportDelivery resolvedLater = importDelivery(resolvedLaterPo, "BOOKED", TODAY.plusDays(10), null);
        logisticsException(
            resolvedLater, LocalDateTime.of(2026, 7, 14, 9, 0), LocalDateTime.of(2026, 7, 16, 9, 0));

        PurchaseOrder inactivePo = po(
            "PO-IMPORT-INACTIVE", TODAY.plusDays(5), "SENT", RequestType.GOODS, TENANT, companyId, supplier);
        line(inactivePo, "EA", "2", "0", "3", warehouse);
        importDelivery(inactivePo, "DELIVERED", TODAY.minusDays(3), null).setRiskFlag(true);

        ImportDelivery unlinked = importDelivery(null, "AT_CUSTOMS", TODAY.minusDays(1), null);
        unlinked.setRiskFlag(false);
        importDelivery(null, "CLOSED", TODAY.minusDays(1), null).setRiskFlag(true);
        entityManager.flush();

        SupplyWorkspaceQuery currentRisk = query(request -> request.setQueue(SupplyQueueKey.ACTIVE_IMPORT_ETA_RISK));
        assertThat(repository.search(currentRisk, Pageable.unpaged()).getContent())
            .extracting(SupplyCommitmentRecord::purchaseOrderCode)
            .containsExactlyInAnyOrder("PO-IMPORT-EXCEPTION", "PO-IMPORT-RESOLVED-LATER");
        assertThat(repository.countUnlinkedImportRisks(query(request -> {}))).isEqualTo(1);

        SupplyWorkspaceQuery historicalRisk = query(request -> {
            request.setAsOf(Instant.parse("2026-07-13T08:00:00Z"));
            request.setQueue(SupplyQueueKey.ACTIVE_IMPORT_ETA_RISK);
        });
        assertThat(repository.search(historicalRisk, Pageable.unpaged()).getContent()).isEmpty();

        Instant future = Instant.parse("2026-07-18T08:00:00Z");
        SupplyWorkspaceQuery futureRisk = query(request -> {
            request.setAsOf(future);
            request.setQueue(SupplyQueueKey.ACTIVE_IMPORT_ETA_RISK);
        });
        assertThat(repository.search(futureRisk, Pageable.unpaged()).getContent())
            .extracting(SupplyCommitmentRecord::purchaseOrderCode)
            .contains("PO-IMPORT-EXCEPTION")
            .doesNotContain("PO-IMPORT-ETA", "PO-IMPORT-INACTIVE", "PO-IMPORT-RESOLVED-LATER");

        eta.setActualArrivalDate(null);
        entityManager.flush();
        assertThat(repository.search(futureRisk, Pageable.unpaged()).getContent())
            .extracting(SupplyCommitmentRecord::purchaseOrderCode)
            .contains("PO-IMPORT-ETA");
    }

    @Test
    void releasedReplacementDeclarationSupersedesRejectedHistoryAndMissingReleaseBlocks() {
        PurchaseOrder releasedPo = po(
            "PO-CUSTOMS-RELEASED", TODAY.plusDays(2), "SENT", RequestType.GOODS, TENANT, companyId, supplier);
        line(releasedPo, "EA", "2", "0", "3", warehouse);
        ImportDelivery releasedImport = importDelivery(releasedPo, "AT_CUSTOMS", TODAY.plusDays(1), null);
        customs(releasedPo, releasedImport, "REJECTED");
        customs(releasedPo, releasedImport, "RELEASED");

        PurchaseOrder blockedPo = po(
            "PO-CUSTOMS-BLOCKED", TODAY.plusDays(2), "SENT", RequestType.GOODS, TENANT, companyId, supplier);
        line(blockedPo, "EA", "2", "0", "3", warehouse);
        ImportDelivery blockedImport = importDelivery(blockedPo, "CLEARED", TODAY.plusDays(1), null);
        customs(blockedPo, blockedImport, "REJECTED");

        PurchaseOrder bookedPo = po(
            "PO-CUSTOMS-BOOKED", TODAY.plusDays(2), "SENT", RequestType.GOODS, TENANT, companyId, supplier);
        line(bookedPo, "EA", "2", "0", "3", warehouse);
        importDelivery(bookedPo, "BOOKED", TODAY.plusDays(1), null);

        PurchaseOrder inTransitPo = po(
            "PO-CUSTOMS-IN-TRANSIT", TODAY.plusDays(2), "SENT", RequestType.GOODS, TENANT, companyId, supplier);
        line(inTransitPo, "EA", "2", "0", "3", warehouse);
        importDelivery(inTransitPo, "IN_TRANSIT", TODAY.plusDays(1), null);

        PurchaseOrder rejectedInTransitPo = po(
            "PO-CUSTOMS-IN-TRANSIT-REJECTED", TODAY.plusDays(2), "SENT", RequestType.GOODS, TENANT, companyId, supplier);
        line(rejectedInTransitPo, "EA", "2", "0", "3", warehouse);
        ImportDelivery rejectedInTransit = importDelivery(
            rejectedInTransitPo, "IN_TRANSIT", TODAY.plusDays(1), null);
        customs(rejectedInTransitPo, rejectedInTransit, "REJECTED");
        entityManager.flush();

        SupplyWorkspaceQuery blocked = query(request -> request.setQueue(SupplyQueueKey.CUSTOMS_DOCUMENT_BLOCKED));
        assertThat(repository.search(blocked, Pageable.unpaged()).getContent())
            .extracting(SupplyCommitmentRecord::purchaseOrderCode)
            .containsExactlyInAnyOrder("PO-CUSTOMS-BLOCKED", "PO-CUSTOMS-IN-TRANSIT-REJECTED")
            .doesNotContain("PO-CUSTOMS-RELEASED", "PO-CUSTOMS-BOOKED", "PO-CUSTOMS-IN-TRANSIT");
        assertThat(repository.count(blocked)).isEqualTo(2);
    }

    @Test
    void integrationQueueIncludesFailedDeadAndOnlyActionableRetries() {
        PurchaseOrder futureRetryPo = integrationPo("PO-INT-FUTURE");
        integration(futureRetryPo, "RETRY", LocalDateTime.of(2026, 7, 16, 13, 0));
        PurchaseOrder dueRetryPo = integrationPo("PO-INT-DUE");
        integration(dueRetryPo, "RETRY", LocalDateTime.of(2026, 7, 15, 12, 0));
        PurchaseOrder noDateRetryPo = integrationPo("PO-INT-NO-DATE");
        integration(noDateRetryPo, "RETRY", null);
        PurchaseOrder failedPo = integrationPo("PO-INT-FAILED");
        integration(failedPo, "FAILED", LocalDateTime.of(2026, 7, 20, 12, 0));
        PurchaseOrder deadPo = integrationPo("PO-INT-DEAD");
        integration(deadPo, "DEAD", null);
        PurchaseOrder donePo = integrationPo("PO-INT-DONE");
        integration(donePo, "COMPLETED", null);
        entityManager.flush();

        SupplyWorkspaceQuery failed = query(request -> request.setQueue(SupplyQueueKey.INTEGRATION_FAILED));
        assertThat(repository.search(failed, Pageable.unpaged()).getContent())
            .extracting(SupplyCommitmentRecord::purchaseOrderCode)
            .containsExactlyInAnyOrder("PO-INT-DUE", "PO-INT-NO-DATE", "PO-INT-FAILED", "PO-INT-DEAD")
            .doesNotContain("PO-INT-FUTURE", "PO-INT-DONE");
        assertThat(repository.count(failed)).isEqualTo(4);
    }

    @Test
    void timestampPredicatesConvertInstantAtOperationalZoneAcrossUtcDayBoundary() {
        Instant boundaryAsOf = Instant.parse("2026-07-15T20:30:00Z"); // 2026-07-16 01:30 Asia/Tashkent
        PurchaseOrder retryDue = integrationPo("PO-OFFSET-RETRY-DUE");
        integration(retryDue, "RETRY", LocalDateTime.of(2026, 7, 16, 1, 15));
        PurchaseOrder retryFuture = integrationPo("PO-OFFSET-RETRY-FUTURE");
        integration(retryFuture, "RETRY", LocalDateTime.of(2026, 7, 16, 1, 45));

        PurchaseOrder eventDue = integrationPo("PO-OFFSET-EVENT-DUE");
        ImportDelivery dueDelivery = importDelivery(eventDue, "BOOKED", TODAY.plusDays(10), null);
        logisticsException(dueDelivery, LocalDateTime.of(2026, 7, 16, 1, 15), null);
        PurchaseOrder eventFuture = integrationPo("PO-OFFSET-EVENT-FUTURE");
        ImportDelivery futureDelivery = importDelivery(eventFuture, "BOOKED", TODAY.plusDays(10), null);
        logisticsException(futureDelivery, LocalDateTime.of(2026, 7, 16, 1, 45), null);
        entityManager.flush();

        SupplyWorkspaceQuery integrationQueue = query(request -> {
            request.setAsOf(boundaryAsOf);
            request.setQueue(SupplyQueueKey.INTEGRATION_FAILED);
        });
        assertThat(repository.search(integrationQueue, Pageable.unpaged()).getContent())
            .extracting(SupplyCommitmentRecord::purchaseOrderCode)
            .containsExactly("PO-OFFSET-RETRY-DUE");

        SupplyWorkspaceQuery importQueue = query(request -> {
            request.setAsOf(boundaryAsOf);
            request.setQueue(SupplyQueueKey.ACTIVE_IMPORT_ETA_RISK);
        });
        assertThat(repository.search(importQueue, Pageable.unpaged()).getContent())
            .extracting(SupplyCommitmentRecord::purchaseOrderCode)
            .containsExactly("PO-OFFSET-EVENT-DUE");
    }

    @Test
    void asnAndUnassignedNamedQueuesUseRealRepositoryCohorts() {
        PurchaseOrder missingAsn = po(
            "PO-ASN-MISSING", TODAY.plusDays(3), "SENT", RequestType.GOODS, TENANT, companyId, supplier);
        line(missingAsn, "EA", "2", "0", "3", warehouse);
        PurchaseOrder confirmedAsn = po(
            "PO-ASN-CONFIRMED", TODAY.plusDays(3), "SENT", RequestType.GOODS, TENANT, companyId, supplier);
        line(confirmedAsn, "EA", "2", "0", "3", warehouse);
        asn(confirmedAsn, "CONFIRMED");
        assignment(confirmedAsn, "buyer.assigned");
        entityManager.flush();

        SupplyWorkspaceQuery asnMissing = query(
            request -> request.setQueue(SupplyQueueKey.ARRIVING_7D_WITHOUT_CONFIRMED_ASN));
        assertThat(repository.search(asnMissing, Pageable.unpaged()).getContent())
            .extracting(SupplyCommitmentRecord::purchaseOrderCode)
            .containsExactly("PO-ASN-MISSING");

        SupplyWorkspaceQuery unassigned = query(request -> request.setQueue(SupplyQueueKey.UNASSIGNED));
        assertThat(repository.search(unassigned, Pageable.unpaged()).getContent())
            .extracting(SupplyCommitmentRecord::purchaseOrderCode)
            .containsExactly("PO-ASN-MISSING");
        assertThat(repository.count(unassigned)).isEqualTo(1);
    }

    @Test
    void nullAssignmentDueDateSourceDoesNotBreakCommitmentsEndpointMapping() {
        PurchaseOrder assignedPo = po(
            "PO-NULL-DUE-SOURCE", TODAY.plusDays(2), "SENT", RequestType.GOODS, TENANT, companyId, supplier);
        line(assignedPo, "EA", "5", "1", "4", warehouse);
        SupplyWorkAssignment assignment = assignment(assignedPo, "buyer.null-source");
        assignment.setDueDateSource(null);
        entityManager.flush();

        List<SupplyCommitmentRecord> records = repository.search(query(request -> {}), Pageable.unpaged()).getContent();

        SupplyCommitmentRecord record = byCode(records, "PO-NULL-DUE-SOURCE");
        assertThat(record.ownerUsername()).isEqualTo("buyer.null-source");
        assertThat(record.assignmentDueDateSource()).isNull();
    }

    private SupplyCommitmentRecord byCode(List<SupplyCommitmentRecord> records, String code) {
        return records.stream().filter(record -> code.equals(record.purchaseOrderCode())).findFirst().orElseThrow();
    }

    private SupplyWorkspaceQuery query(Consumer<SupplyWorkspaceRequest> customize) {
        SupplyWorkspaceRequest request = new SupplyWorkspaceRequest();
        request.setAsOf(AS_OF);
        customize.accept(request);
        return filterFactory.normalize(request, new SupplyAccessScope(
            TENANT, Set.of(companyId), Set.of(), false, false));
    }

    private Supplier supplier(String code, String name, String tenantId, UUID supplierCompanyId) {
        Supplier entity = base(new Supplier(), code, name, tenantId, supplierCompanyId);
        entity.setLegalName(name);
        return persist(entity);
    }

    private PurchaseOrder po(
        String code,
        LocalDate expectedDate,
        String status,
        RequestType requestType,
        String tenantId,
        UUID poCompanyId,
        Supplier poSupplier
    ) {
        PurchaseOrder entity = base(new PurchaseOrder(), code, code, tenantId, poCompanyId);
        entity.setStatus(status);
        entity.setSupplierId(poSupplier.getId());
        entity.setExpectedDeliveryDate(expectedDate);
        entity.setOrderDate(TODAY.minusDays(10));
        entity.setRequestType(requestType);
        entity.setCurrencyCode("UZS");
        entity.setTotalAmount(BigDecimal.ZERO);
        return persist(entity);
    }

    private PurchaseOrderItem line(
        PurchaseOrder po,
        String unit,
        String quantity,
        String received,
        String unitPrice,
        Warehouse receiptWarehouse
    ) {
        PurchaseOrderItem entity = base(
            new PurchaseOrderItem(), "LINE-" + UUID.randomUUID(), "PO line", po.getTenantId(), po.getCompanyId());
        entity.setPurchaseOrderId(po.getId());
        entity.setLineType(ProcurementLineType.MATERIAL);
        entity.setUnit(unit);
        entity.setQuantity(new BigDecimal(quantity));
        entity.setReceivedQuantity(new BigDecimal(received));
        entity.setUnitPrice(new BigDecimal(unitPrice));
        entity.setReceiptWarehouseId(receiptWarehouse == null ? null : receiptWarehouse.getId());
        return persist(entity);
    }

    private DeliverySchedule schedule(
        PurchaseOrder po,
        LocalDate date,
        String status,
        String plannedQuantity,
        String receivedQuantity,
        String plannedAmount,
        String receivedAmount,
        String tenantId,
        UUID scheduleCompanyId
    ) {
        DeliverySchedule entity = base(
            new DeliverySchedule(), "SCH-" + UUID.randomUUID(), "Delivery schedule", tenantId, scheduleCompanyId);
        entity.setStatus(status);
        entity.setPurchaseOrderId(po.getId());
        entity.setScheduleDate(date);
        entity.setPlannedQuantity(new BigDecimal(plannedQuantity));
        entity.setReceivedQuantity(new BigDecimal(receivedQuantity));
        entity.setPlannedAmount(new BigDecimal(plannedAmount));
        entity.setReceivedAmount(new BigDecimal(receivedAmount));
        return persist(entity);
    }

    private ImportDelivery importDelivery(
        PurchaseOrder po,
        String status,
        LocalDate eta,
        LocalDate actualArrival
    ) {
        ImportDelivery entity = base(
            new ImportDelivery(), "IMP-" + UUID.randomUUID(), "Import delivery", TENANT, companyId);
        entity.setStatus(status);
        entity.setSupplierId(supplier.getId());
        entity.setPurchaseOrderId(po == null ? null : po.getId());
        entity.setEta(eta);
        entity.setActualArrivalDate(actualArrival);
        entity.setImportFlag(true);
        return persist(entity);
    }

    private LandedLogisticsEvent logisticsException(
        ImportDelivery delivery,
        LocalDateTime eventDate,
        LocalDateTime resolvedAt
    ) {
        LandedLogisticsEvent entity = base(
            new LandedLogisticsEvent(), "EVT-" + UUID.randomUUID(), "Import exception", TENANT, companyId);
        entity.setImportDeliveryId(delivery.getId());
        entity.setEventType("DELAY_EXCEPTION");
        entity.setEventDate(eventDate);
        entity.setExceptionFlag(resolvedAt == null);
        entity.setResolvedAt(resolvedAt);
        return persist(entity);
    }

    private CustomsDeclaration customs(PurchaseOrder po, ImportDelivery delivery, String status) {
        CustomsDeclaration entity = base(
            new CustomsDeclaration(), "CUS-" + UUID.randomUUID(), "Customs declaration", TENANT, companyId);
        entity.setStatus(status);
        entity.setPurchaseOrderId(po.getId());
        entity.setImportDeliveryId(delivery.getId());
        entity.setSupplierId(supplier.getId());
        if ("RELEASED".equals(status)) {
            entity.setReleasedAt(LocalDateTime.of(2026, 7, 15, 9, 0));
        }
        return persist(entity);
    }

    private PurchaseOrder integrationPo(String code) {
        PurchaseOrder po = po(code, TODAY.plusDays(2), "SENT", RequestType.GOODS, TENANT, companyId, supplier);
        line(po, "EA", "2", "0", "3", warehouse);
        return po;
    }

    private IntegrationEvent integration(PurchaseOrder po, String status, LocalDateTime nextRetryAt) {
        IntegrationEvent entity = base(
            new IntegrationEvent(), "INT-" + UUID.randomUUID(), "Integration event", TENANT, companyId);
        entity.setStatus(status);
        entity.setAggregateType("PURCHASE_ORDER");
        entity.setAggregateId(po.getId());
        entity.setEventType("SUPPLY_SYNC");
        entity.setIdempotencyKey(UUID.randomUUID().toString());
        entity.setNextRetryAt(nextRetryAt);
        return persist(entity);
    }

    private AdvanceShippingNotice asn(PurchaseOrder po, String status) {
        AdvanceShippingNotice entity = base(
            new AdvanceShippingNotice(), "ASN-" + UUID.randomUUID(), "ASN", TENANT, companyId);
        entity.setStatus(status);
        entity.setSupplierId(supplier.getId());
        entity.setPurchaseOrderId(po.getId());
        return persist(entity);
    }

    private SupplyWorkAssignment assignment(PurchaseOrder po, String username) {
        SupplyWorkAssignment entity = base(
            new SupplyWorkAssignment(), "SWA-" + UUID.randomUUID(), "Assignment", TENANT, companyId);
        entity.setCommitmentKey("PO:" + po.getId());
        entity.setCommitmentKind(SupplyCommitmentKind.PO);
        entity.setOwnerUserId(UUID.randomUUID());
        entity.setOwnerUsername(username);
        entity.setOwnerRole(SupplyAssignmentRole.PROCUREMENT_SPECIALIST);
        entity.setDueDateSource(SupplyDueDateSource.POLICY);
        entity.setSeverity(SupplySeverity.WARNING);
        entity.setEscalationState(SupplyEscalationState.NONE);
        return persist(entity);
    }

    private <T extends BusinessEntity> T base(
        T entity,
        String code,
        String name,
        String tenantId,
        UUID entityCompanyId
    ) {
        entity.setTenantId(tenantId);
        entity.setCompanyId(entityCompanyId);
        entity.setCode(code);
        entity.setName(name);
        entity.setStatus("ACTIVE");
        return entity;
    }

    private <T> T persist(T entity) {
        entityManager.persist(entity);
        entityManager.flush();
        return entity;
    }
}
