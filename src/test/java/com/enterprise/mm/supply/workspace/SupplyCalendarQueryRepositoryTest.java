package com.enterprise.mm.supply.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AdvanceShippingNotice;
import com.enterprise.mm.entity.Contract;
import com.enterprise.mm.entity.ContractInvoice;
import com.enterprise.mm.entity.ContractPayment;
import com.enterprise.mm.entity.CustomsDeclaration;
import com.enterprise.mm.entity.DeliverySchedule;
import com.enterprise.mm.entity.ImportDelivery;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.supply.workspace.domain.SupplyAccessScope;
import com.enterprise.mm.supply.workspace.domain.SupplyCommitmentKey;
import com.enterprise.mm.supply.workspace.domain.SupplyCommitmentKeyCodec;
import com.enterprise.mm.supply.workspace.domain.SupplyWorkspaceQuery;
import com.enterprise.mm.supply.workspace.entity.SupplyWorkAssignment;
import com.enterprise.mm.supply.workspace.enums.SupplyAssignmentRole;
import com.enterprise.mm.supply.workspace.enums.SupplyCalendarLane;
import com.enterprise.mm.supply.workspace.enums.SupplyCommitmentKind;
import com.enterprise.mm.supply.workspace.enums.SupplyDateBasis;
import com.enterprise.mm.supply.workspace.enums.SupplyDeliverySource;
import com.enterprise.mm.supply.workspace.enums.SupplyDueDateSource;
import com.enterprise.mm.supply.workspace.enums.SupplyEscalationState;
import com.enterprise.mm.supply.workspace.enums.SupplyLens;
import com.enterprise.mm.supply.workspace.enums.SupplyQueueKey;
import com.enterprise.mm.supply.workspace.enums.SupplyRiskKey;
import com.enterprise.mm.supply.workspace.enums.SupplySeverity;
import com.enterprise.mm.supply.workspace.payload.SupplyCalendarDtos.CalendarEvent;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceRequest;
import com.enterprise.mm.supply.workspace.repository.SupplyCalendarQueryRepository;
import com.enterprise.mm.supply.workspace.service.SupplyWorkspaceFilterFactory;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
    "app.seed.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:supply_calendar;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"
})
@ActiveProfiles("test")
@Transactional
class SupplyCalendarQueryRepositoryTest {
    private static final String TENANT = "tenant-supply-calendar";
    private static final String OTHER_TENANT = "tenant-supply-calendar-other";
    private static final Instant AS_OF = Instant.parse("2026-07-14T20:30:00Z");
    private static final LocalDate FROM = LocalDate.of(2026, 7, 15);
    private static final LocalDate TO = LocalDate.of(2026, 7, 25);

    @Autowired private SupplyCalendarQueryRepository repository;
    @Autowired private EntityManager entityManager;
    @Autowired private SupplyCommitmentKeyCodec keyCodec;

    private UUID companyId;
    private UUID otherCompanyId;
    private Supplier supplier;
    private Contract contract;
    private Warehouse warehouse;
    private Warehouse otherWarehouse;
    private PurchaseOrder purchaseOrder;
    private SupplyWorkspaceFilterFactory filterFactory;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        otherCompanyId = UUID.randomUUID();
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(companyId);
        filterFactory = new SupplyWorkspaceFilterFactory(
            Clock.fixed(AS_OF, ZoneOffset.UTC), "Asia/Tashkent");
        supplier = supplier("SUP-CAL", "Calendar supplier", TENANT, companyId);
        contract = contract("CTR-CAL", "C-2026-100", supplier, TENANT, companyId);
        warehouse = persist(base(new Warehouse(), "WH-CAL", "Calendar warehouse", TENANT, companyId));
        otherWarehouse = persist(base(new Warehouse(), "WH-CAL-2", "Other warehouse", TENANT, companyId));
        purchaseOrder = po("PO-CAL", supplier, contract, TENANT, companyId);
        line(purchaseOrder, warehouse);
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void returnsFrozenAllDayWireIdentityFromOperationalDatesOnlyWithInclusiveExclusiveBounds() {
        DeliverySchedule schedule = schedule("SCH-100", purchaseOrder, contract, FROM, "PLANNED", "100", "25");
        schedule.setName("Human delivery schedule");
        schedule.setSource("SUPPLIER_COMMITMENT");

        PurchaseOrder unscheduledPo = po("PO-ASN-CAL", supplier, contract, TENANT, companyId);
        line(unscheduledPo, warehouse);
        AdvanceShippingNotice asn = asn("ASN-INTERNAL", "ASN-2026-100", unscheduledPo, contract,
            FROM.plusDays(1), "CONFIRMED", "80", "USD", "ROAD");
        ImportDelivery importDelivery = importDelivery(
            "IMP-INTERNAL", "SHIP-2026-100", purchaseOrder, FROM.plusDays(2), warehouse,
            "IN_TRANSIT", "120", "USD", "SEA");
        CustomsDeclaration customs = customs(
            "CUS-INTERNAL", "GTD-2026-100", purchaseOrder, importDelivery,
            FROM.plusDays(3), "SUBMITTED", "15", "USD");
        ContractInvoice invoice = invoice(
            "INV-INTERNAL", "INV-2026-100", purchaseOrder, contract,
            FROM.plusDays(4), "ISSUED", "200", "USD");
        invoice.setPaidAmount(new BigDecimal("50"));
        ContractPayment payment = payment(
            "PAY-INTERNAL", "PAY-2026-100", contract,
            FROM.plusDays(5), "POSTED", "60", "USD");
        payment.setContractInvoiceId(invoice.getId());

        schedule("SCH-TO", purchaseOrder, contract, TO, "PLANNED", "1", "0");
        payment("PAY-TO", "PAY-AT-TO", contract, TO, "POSTED", "1", "USD");
        schedule("SCH-NO-DATE", purchaseOrder, contract, null, "PLANNED", "1", "0");
        asn("ASN-NO-DATE", "ASN-NO-DATE", unscheduledPo, contract,
            null, "CONFIRMED", "1", "USD", "ROAD");
        importDelivery("IMP-NO-DATE", "IMP-NO-DATE", purchaseOrder, null, warehouse,
            "IN_TRANSIT", "1", "USD", "SEA");
        customs("CUS-NO-DATE", "CUS-NO-DATE", purchaseOrder, importDelivery,
            null, "SUBMITTED", "1", "USD");
        invoice("INV-NO-DATE", "INV-NO-DATE", purchaseOrder, contract,
            null, "ISSUED", "1", "USD");
        payment("PAY-NO-DATE", "PAY-NO-DATE", contract,
            null, "POSTED", "1", "USD");
        entityManager.flush();

        SupplyWorkspaceQuery query = query(request -> {});
        List<CalendarEvent> deliveryEvents = repository.deliveryEvents(query);
        List<CalendarEvent> asnEvents = repository.asnEvents(query);
        List<CalendarEvent> importEvents = repository.importAndCustomsEvents(query);
        List<CalendarEvent> invoiceEvents = repository.invoiceEvents(query);
        List<CalendarEvent> paymentEvents = repository.paymentEvents(query);

        assertThat(deliveryEvents).hasSize(1);
        assertFrozenEvent(
            deliveryEvents.getFirst(), SupplyCalendarLane.DELIVERY, SupplyDateBasis.PROMISED_DATE,
            "DELIVERY_SCHEDULE", schedule.getId(), FROM, "SCH-100", "delivery-schedules");
        assertThat(deliveryEvents.getFirst().commitmentKey())
            .isEqualTo("SCHEDULE:" + schedule.getId());
        assertThat(deliveryEvents.getFirst().commitmentTarget().query())
            .containsEntry("commitment", keyCodec.encode(
                new SupplyCommitmentKey(SupplyCommitmentKind.SCHEDULE, schedule.getId())))
            .containsEntry("tab", "SCHEDULE");
        assertThat(deliveryEvents.getFirst().money().originalAmount()).isEqualByComparingTo("75");
        assertThat(deliveryEvents.getFirst().documentName()).isEqualTo(
            "ru: Human delivery schedule\nen: Human delivery schedule\nuz: Human delivery schedule");
        assertThat(deliveryEvents.getFirst().source())
            .isEqualTo(SupplyDeliverySource.SUPPLIER_COMMITMENT);

        assertThat(asnEvents).hasSize(1);
        assertFrozenEvent(
            asnEvents.getFirst(), SupplyCalendarLane.ASN, SupplyDateBasis.EXPECTED_ARRIVAL_DATE,
            "ASN", asn.getId(), FROM.plusDays(1), "ASN-2026-100", "asn");
        assertThat(asnEvents.getFirst().commitmentKey()).isEqualTo("PO:" + unscheduledPo.getId());
        assertThat(asnEvents.getFirst().commitmentTarget().query())
            .containsEntry("tab", "ASN_IMPORT");

        assertThat(importEvents).hasSize(2);
        CalendarEvent importEvent = byDocument(importEvents, "IMPORT_DELIVERY");
        assertFrozenEvent(
            importEvent, SupplyCalendarLane.IMPORT_CUSTOMS, SupplyDateBasis.ETA,
            "IMPORT_DELIVERY", importDelivery.getId(), FROM.plusDays(2),
            "SHIP-2026-100", "import-deliveries");
        CalendarEvent customsEvent = byDocument(importEvents, "CUSTOMS_DECLARATION");
        assertFrozenEvent(
            customsEvent, SupplyCalendarLane.IMPORT_CUSTOMS, SupplyDateBasis.DECLARATION_DATE,
            "CUSTOMS_DECLARATION", customs.getId(), FROM.plusDays(3),
            "GTD-2026-100", "customs-declarations");

        assertThat(invoiceEvents).hasSize(1);
        assertFrozenEvent(
            invoiceEvents.getFirst(), SupplyCalendarLane.INVOICE, SupplyDateBasis.DUE_DATE,
            "CONTRACT_INVOICE", invoice.getId(), FROM.plusDays(4),
            "INV-2026-100", "contract-invoices");
        assertThat(invoiceEvents.getFirst().money().originalAmount()).isEqualByComparingTo("150");

        assertThat(paymentEvents).hasSize(1);
        assertFrozenEvent(
            paymentEvents.getFirst(), SupplyCalendarLane.PAYMENT, SupplyDateBasis.PAYMENT_DATE,
            "CONTRACT_PAYMENT", payment.getId(), FROM.plusDays(5),
            "PAY-2026-100", "contract-payments");

        assertThat(List.of(
            deliveryEvents.getFirst(), asnEvents.getFirst(), importEvent,
            customsEvent, invoiceEvents.getFirst(), paymentEvents.getFirst()))
            .allSatisfy(event -> {
                assertThat(event.allDay()).isTrue();
                assertThat(event.supplier().id()).isEqualTo(supplier.getId());
                assertThat(event.contract().id()).isEqualTo(contract.getId());
                assertThat(event.primaryTarget().recordId()).isEqualTo(event.documentId());
                assertThat(event.primaryTarget().query()).containsEntry("search", event.documentCode());
            });
        assertThat(List.of(deliveryEvents.getFirst(), asnEvents.getFirst(), importEvent,
            customsEvent, invoiceEvents.getFirst(), paymentEvents.getFirst()))
            .allSatisfy(event -> assertThat(event.purchaseOrder().id()).isNotNull());
        assertThat(paymentEvents.getFirst().purchaseOrder().id()).isEqualTo(purchaseOrder.getId());
        assertThat(paymentEvents.getFirst().contract().id()).isEqualTo(contract.getId());
    }

    @Test
    void appliesNormalizedLaneFiltersWithoutCrossTenantCompanyOrWarehouseLeakage() {
        DeliverySchedule allowedSchedule = schedule(
            "SCH-ALLOWED", purchaseOrder, contract, FROM.plusDays(1), "PLANNED", "10", "0");
        AdvanceShippingNotice allowedAsn = asn(
            "ASN-ALLOWED", "ASN-ALLOWED", purchaseOrder, contract,
            FROM.plusDays(2), "CONFIRMED", "10", "USD", "ROAD");
        ImportDelivery allowedImport = importDelivery(
            "IMP-ALLOWED", "IMP-ALLOWED", purchaseOrder, FROM.plusDays(3), warehouse,
            "IN_TRANSIT", "10", "USD", "ROAD");
        CustomsDeclaration allowedCustoms = customs(
            "CUS-ALLOWED", "CUS-ALLOWED", purchaseOrder, allowedImport,
            FROM.plusDays(4), "SUBMITTED", "10", "USD");
        ContractInvoice allowedInvoice = invoice(
            "INV-ALLOWED", "INV-ALLOWED", purchaseOrder, contract,
            FROM.plusDays(5), "ISSUED", "10", "USD");
        ContractPayment allowedPayment = payment(
            "PAY-ALLOWED", "PAY-ALLOWED", contract,
            FROM.plusDays(6), "POSTED", "10", "USD");
        allowedPayment.setPurchaseOrderId(purchaseOrder.getId());

        PurchaseOrder deniedWarehousePo = po("PO-DENIED-WH", supplier, contract, TENANT, companyId);
        line(deniedWarehousePo, otherWarehouse);
        DeliverySchedule deniedSchedule = schedule(
            "SCH-DENIED-WH", deniedWarehousePo, contract, FROM.plusDays(1), "PLANNED", "10", "0");
        AdvanceShippingNotice deniedAsn = asn(
            "ASN-DENIED-WH", "ASN-DENIED-WH", deniedWarehousePo, contract,
            FROM.plusDays(2), "CONFIRMED", "10", "USD", "ROAD");
        ImportDelivery deniedImport = importDelivery(
            "IMP-DENIED-WH", "IMP-DENIED-WH", deniedWarehousePo, FROM.plusDays(3), otherWarehouse,
            "IN_TRANSIT", "10", "USD", "ROAD");
        CustomsDeclaration deniedCustoms = customs(
            "CUS-DENIED-WH", "CUS-DENIED-WH", deniedWarehousePo, deniedImport,
            FROM.plusDays(4), "SUBMITTED", "10", "USD");
        ContractInvoice deniedInvoice = invoice(
            "INV-DENIED-WH", "INV-DENIED-WH", deniedWarehousePo, contract,
            FROM.plusDays(5), "ISSUED", "10", "USD");

        LaneFixture otherCompany = fixture(
            "OTHER-COMPANY", TENANT, otherCompanyId, FROM.plusDays(1));
        LaneFixture otherTenant = fixture(
            "OTHER-TENANT", OTHER_TENANT, companyId, FROM.plusDays(1));
        entityManager.flush();

        SupplyWorkspaceQuery warehouseQuery = query(
            request -> {
                request.setSupplierId(supplier.getId());
                request.setContractId(contract.getId());
                request.setWarehouseId(warehouse.getId());
                request.setCurrencyCode(" usd ");
                request.setSearch(" allowed ");
            }, new SupplyAccessScope(
                TENANT, Set.of(companyId), Set.of(warehouse.getId()), false, true));

        assertThat(repository.deliveryEvents(warehouseQuery))
            .extracting(CalendarEvent::documentId).containsExactly(allowedSchedule.getId())
            .doesNotContain(deniedSchedule.getId(), otherCompany.scheduleId(), otherTenant.scheduleId());
        assertThat(repository.asnEvents(warehouseQuery))
            .extracting(CalendarEvent::documentId).containsExactly(allowedAsn.getId())
            .doesNotContain(deniedAsn.getId(), otherCompany.asnId(), otherTenant.asnId());
        assertThat(repository.importAndCustomsEvents(warehouseQuery))
            .extracting(CalendarEvent::documentId)
            .containsExactlyInAnyOrder(allowedImport.getId(), allowedCustoms.getId())
            .doesNotContain(deniedImport.getId(), deniedCustoms.getId(),
                otherCompany.importId(), otherCompany.customsId(),
                otherTenant.importId(), otherTenant.customsId());
        assertThat(repository.invoiceEvents(warehouseQuery))
            .extracting(CalendarEvent::documentId).containsExactly(allowedInvoice.getId())
            .doesNotContain(deniedInvoice.getId(), otherCompany.invoiceId(), otherTenant.invoiceId());

        List<CalendarEvent> scopedTargets = new java.util.ArrayList<>();
        scopedTargets.addAll(repository.deliveryEvents(warehouseQuery));
        scopedTargets.addAll(repository.asnEvents(warehouseQuery));
        scopedTargets.addAll(repository.importAndCustomsEvents(warehouseQuery));
        scopedTargets.addAll(repository.invoiceEvents(warehouseQuery));
        assertThat(scopedTargets).allSatisfy(event -> assertThat(event.primaryTarget().query())
            .containsEntry("companyId", companyId.toString())
            .containsEntry("warehouseId", warehouse.getId().toString()));

        assertThat(repository.paymentEvents(warehouseQuery)).isEmpty();

        SupplyWorkspaceQuery paymentQuery = query(request -> {
            request.setSupplierId(supplier.getId());
            request.setPurchaseOrderId(purchaseOrder.getId());
            request.setContractId(contract.getId());
            request.setCurrencyCode("usd");
            request.setStatus(" posted ");
            request.setSearch("pay-allowed");
        });
        assertThat(repository.paymentEvents(paymentQuery))
            .extracting(CalendarEvent::documentId).containsExactly(allowedPayment.getId());
    }

    @Test
    void executesSupportedRiskAndQueueFiltersAgainstSourceFacts() {
        DeliverySchedule overdueSchedule = schedule(
            "SCH-RISK-OVERDUE", purchaseOrder, contract, FROM.minusDays(1), "PLANNED", "10", "0");
        ImportDelivery riskyImport = importDelivery(
            "IMP-RISK", "IMP-RISK", purchaseOrder, FROM.plusDays(1), warehouse,
            "IN_TRANSIT", "10", "USD", "ROAD");
        riskyImport.setRiskFlag(true);
        CustomsDeclaration rejectedCustoms = customs(
            "CUS-RISK", "CUS-RISK", purchaseOrder, riskyImport,
            FROM.plusDays(2), "REJECTED", "10", "USD");
        ContractInvoice overdueInvoice = invoice(
            "INV-RISK", "INV-RISK", purchaseOrder, contract,
            FROM.minusDays(1), "ISSUED", "10", "USD");
        ContractPayment overduePayment = payment(
            "PAY-RISK", "PAY-RISK", contract,
            FROM.minusDays(1), "DRAFT", "10", "USD");
        overduePayment.setPurchaseOrderId(purchaseOrder.getId());
        entityManager.flush();

        assertThat(repository.deliveryEvents(riskQuery(
            request -> request.setRisk(SupplyRiskKey.OVERDUE))))
            .extracting(CalendarEvent::documentId).containsExactly(overdueSchedule.getId());
        assertThat(repository.deliveryEvents(riskQuery(
            request -> request.setQueue(SupplyQueueKey.OVERDUE_COMMITMENTS))))
            .extracting(CalendarEvent::documentId).containsExactly(overdueSchedule.getId());
        assertThat(repository.importAndCustomsEvents(riskQuery(
            request -> request.setRisk(SupplyRiskKey.IMPORT_ETA_RISK))))
            .extracting(CalendarEvent::documentId).containsExactly(riskyImport.getId());
        assertThat(repository.importAndCustomsEvents(riskQuery(
            request -> request.setRisk(SupplyRiskKey.CUSTOMS_BLOCKED))))
            .extracting(CalendarEvent::documentId).containsExactly(rejectedCustoms.getId());
        assertThat(repository.invoiceEvents(riskQuery(
            request -> request.setRisk(SupplyRiskKey.OVERDUE))))
            .extracting(CalendarEvent::documentId).containsExactly(overdueInvoice.getId());
        assertThat(repository.paymentEvents(riskQuery(
            request -> request.setRisk(SupplyRiskKey.OVERDUE))))
            .extracting(CalendarEvent::documentId).containsExactly(overduePayment.getId());
    }

    @Test
    void emitsCommitmentTargetsOnlyWhenTheCalendarDocumentBelongsToTheCanonicalOpenCohort() {
        DeliverySchedule active = schedule(
            "SCH-CANONICAL", purchaseOrder, contract, FROM.plusDays(1), "PLANNED", "10", "0");
        DeliverySchedule received = schedule(
            "SCH-RECEIVED", purchaseOrder, contract, FROM.plusDays(2), "RECEIVED", "10", "10");

        PurchaseOrder completedPo = po("PO-COMPLETED", supplier, contract, TENANT, companyId);
        completedPo.setStatus("COMPLETED");
        line(completedPo, warehouse);
        AdvanceShippingNotice completedAsn = asn(
            "ASN-COMPLETED", "ASN-COMPLETED", completedPo, contract,
            FROM.plusDays(3), "CONFIRMED", "10", "USD", "ROAD");
        entityManager.flush();

        CalendarEvent activeEvent = repository.deliveryEvents(query(request -> {})).stream()
            .filter(event -> event.documentId().equals(active.getId())).findFirst().orElseThrow();
        CalendarEvent receivedEvent = repository.deliveryEvents(query(request -> {})).stream()
            .filter(event -> event.documentId().equals(received.getId())).findFirst().orElseThrow();
        CalendarEvent completedAsnEvent = repository.asnEvents(query(request -> {})).stream()
            .filter(event -> event.documentId().equals(completedAsn.getId())).findFirst().orElseThrow();

        assertThat(activeEvent.commitmentKey()).startsWith("SCHEDULE:");
        assertThat(activeEvent.commitmentTarget()).isNotNull();
        assertThat(receivedEvent.commitmentKey()).isNull();
        assertThat(receivedEvent.commitmentTarget()).isNull();
        assertThat(completedAsnEvent.commitmentKey()).isNull();
        assertThat(completedAsnEvent.commitmentTarget()).isNull();
    }

    @Test
    void derivesOverdueAgainstTheConfiguredOperationalTimezoneWithoutShiftingDateOnlyEvents() {
        DeliverySchedule yesterday = schedule(
            "SCH-YESTERDAY", purchaseOrder, contract, FROM.minusDays(1), "PLANNED", "10", "0");
        DeliverySchedule today = schedule(
            "SCH-TODAY", purchaseOrder, contract, FROM, "PLANNED", "10", "0");

        SupplyWorkspaceRequest request = new SupplyWorkspaceRequest();
        request.setAsOf(AS_OF);
        request.setFrom(FROM.minusDays(1));
        request.setTo(FROM.plusDays(1));
        SupplyWorkspaceQuery query = filterFactory.normalize(request, new SupplyAccessScope(
            TENANT, Set.of(companyId), Set.of(), false, false));

        assertThat(query.asOfDate()).isEqualTo(FROM);
        assertThat(repository.deliveryEvents(query))
            .filteredOn(event -> event.documentId().equals(yesterday.getId()))
            .extracting(CalendarEvent::dateTime, CalendarEvent::risk)
            .containsExactly(org.assertj.core.groups.Tuple.tuple(FROM.minusDays(1), SupplyRiskKey.OVERDUE));
        assertThat(repository.deliveryEvents(query))
            .filteredOn(event -> event.documentId().equals(today.getId()))
            .extracting(CalendarEvent::dateTime, CalendarEvent::risk)
            .containsExactly(org.assertj.core.groups.Tuple.tuple(FROM, null));
    }

    @Test
    void myWorkDeliveryLaneMatchesAllOwnerIdsAndOnlyNullIdLegacyUsername() {
        UUID firstOwnerId = UUID.randomUUID();
        UUID secondOwnerId = UUID.randomUUID();
        DeliverySchedule first = schedule(
            "SCH-MY-FIRST", purchaseOrder, contract, FROM.plusDays(1), "PLANNED", "10", "0");
        DeliverySchedule second = schedule(
            "SCH-MY-SECOND", purchaseOrder, contract, FROM.plusDays(2), "PLANNED", "10", "0");
        DeliverySchedule legacy = schedule(
            "SCH-MY-LEGACY", purchaseOrder, contract, FROM.plusDays(3), "PLANNED", "10", "0");
        DeliverySchedule foreignId = schedule(
            "SCH-MY-FOREIGN-ID", purchaseOrder, contract, FROM.plusDays(4), "PLANNED", "10", "0");
        assignment(first, firstOwnerId, "buyer.shared");
        assignment(second, secondOwnerId, "buyer.shared");
        assignment(legacy, null, "buyer.shared");
        assignment(foreignId, UUID.randomUUID(), "buyer.shared");
        entityManager.flush();

        SupplyWorkspaceQuery myWork = query(request -> request.setLens(SupplyLens.MY_WORK))
            .withAuthenticatedPrincipals(
                Set.of(firstOwnerId, secondOwnerId), "buyer.shared");

        assertThat(repository.deliveryEvents(myWork))
            .extracting(CalendarEvent::documentId)
            .containsExactlyInAnyOrder(first.getId(), second.getId(), legacy.getId())
            .doesNotContain(foreignId.getId());
    }

    private void assertFrozenEvent(
        CalendarEvent event,
        SupplyCalendarLane lane,
        SupplyDateBasis dateBasis,
        String documentType,
        UUID documentId,
        LocalDate date,
        String documentCode,
        String moduleKey
    ) {
        assertThat(event.id()).isEqualTo(documentType + ":" + documentId + ":" + date);
        assertThat(event.lane()).isEqualTo(lane);
        assertThat(event.dateTime()).isEqualTo(date);
        assertThat(event.allDay()).isTrue();
        assertThat(event.dateBasis()).isEqualTo(dateBasis);
        assertThat(event.documentType()).isEqualTo(documentType);
        assertThat(event.documentId()).isEqualTo(documentId);
        assertThat(event.documentCode()).isEqualTo(documentCode);
        assertThat(event.primaryTarget().moduleKey()).isEqualTo(moduleKey);
    }

    private CalendarEvent byDocument(List<CalendarEvent> events, String documentType) {
        return events.stream().filter(event -> documentType.equals(event.documentType()))
            .findFirst().orElseThrow();
    }

    private SupplyWorkspaceQuery query(Consumer<SupplyWorkspaceRequest> customize) {
        return query(customize, new SupplyAccessScope(
            TENANT, Set.of(companyId), Set.of(), false, false));
    }

    private SupplyWorkspaceQuery query(
        Consumer<SupplyWorkspaceRequest> customize,
        SupplyAccessScope scope
    ) {
        SupplyWorkspaceRequest request = new SupplyWorkspaceRequest();
        request.setAsOf(AS_OF);
        request.setFrom(FROM);
        request.setTo(TO);
        customize.accept(request);
        return filterFactory.normalize(request, scope);
    }

    private SupplyWorkspaceQuery riskQuery(Consumer<SupplyWorkspaceRequest> customize) {
        SupplyWorkspaceRequest request = new SupplyWorkspaceRequest();
        request.setAsOf(AS_OF);
        request.setFrom(FROM.minusDays(2));
        request.setTo(TO);
        customize.accept(request);
        return filterFactory.normalize(request, new SupplyAccessScope(
            TENANT, Set.of(companyId), Set.of(), false, false));
    }

    private LaneFixture fixture(String prefix, String tenantId, UUID fixtureCompanyId, LocalDate firstDate) {
        Supplier fixtureSupplier = supplier(
            "SUP-" + prefix, prefix + " supplier", tenantId, fixtureCompanyId);
        Contract fixtureContract = contract(
            "CTR-" + prefix, "C-" + prefix, fixtureSupplier, tenantId, fixtureCompanyId);
        Warehouse fixtureWarehouse = persist(base(
            new Warehouse(), "WH-" + prefix, prefix + " warehouse", tenantId, fixtureCompanyId));
        PurchaseOrder fixturePo = po(
            "PO-" + prefix, fixtureSupplier, fixtureContract, tenantId, fixtureCompanyId);
        line(fixturePo, fixtureWarehouse);
        DeliverySchedule fixtureSchedule = schedule(
            "SCH-" + prefix, fixturePo, fixtureContract, firstDate, "PLANNED", "10", "0");
        AdvanceShippingNotice fixtureAsn = asn(
            "ASN-" + prefix, "ASN-" + prefix, fixturePo, fixtureContract,
            firstDate.plusDays(1), "CONFIRMED", "10", "USD", "ROAD");
        ImportDelivery fixtureImport = importDelivery(
            "IMP-" + prefix, "IMP-" + prefix, fixturePo, firstDate.plusDays(2), fixtureWarehouse,
            "IN_TRANSIT", "10", "USD", "ROAD");
        CustomsDeclaration fixtureCustoms = customs(
            "CUS-" + prefix, "CUS-" + prefix, fixturePo, fixtureImport,
            firstDate.plusDays(3), "SUBMITTED", "10", "USD");
        ContractInvoice fixtureInvoice = invoice(
            "INV-" + prefix, "INV-" + prefix, fixturePo, fixtureContract,
            firstDate.plusDays(4), "ISSUED", "10", "USD");
        ContractPayment fixturePayment = payment(
            "PAY-" + prefix, "PAY-" + prefix, fixtureContract,
            firstDate.plusDays(5), "POSTED", "10", "USD");
        return new LaneFixture(
            fixtureSchedule.getId(), fixtureAsn.getId(), fixtureImport.getId(), fixtureCustoms.getId(),
            fixtureInvoice.getId(), fixturePayment.getId());
    }

    private Supplier supplier(String code, String name, String tenantId, UUID entityCompanyId) {
        Supplier entity = base(new Supplier(), code, name, tenantId, entityCompanyId);
        entity.setLegalName(name);
        return persist(entity);
    }

    private Contract contract(
        String code,
        String number,
        Supplier contractSupplier,
        String tenantId,
        UUID entityCompanyId
    ) {
        Contract entity = base(new Contract(), code, number, tenantId, entityCompanyId);
        entity.setContractNumber(number);
        entity.setSupplierId(contractSupplier.getId());
        entity.setCurrencyCode("USD");
        return persist(entity);
    }

    private PurchaseOrder po(
        String code,
        Supplier poSupplier,
        Contract poContract,
        String tenantId,
        UUID entityCompanyId
    ) {
        PurchaseOrder entity = base(new PurchaseOrder(), code, code, tenantId, entityCompanyId);
        entity.setStatus("SENT");
        entity.setSupplierId(poSupplier.getId());
        entity.setContractId(poContract.getId());
        entity.setOrderDate(FROM.minusDays(10));
        entity.setExpectedDeliveryDate(FROM.plusDays(10));
        entity.setRequestType(RequestType.GOODS);
        entity.setCurrencyCode("USD");
        entity.setTotalAmount(new BigDecimal("100"));
        return persist(entity);
    }

    private PurchaseOrderItem line(PurchaseOrder po, Warehouse receiptWarehouse) {
        PurchaseOrderItem entity = base(
            new PurchaseOrderItem(), "LINE-" + UUID.randomUUID(), "Calendar line",
            po.getTenantId(), po.getCompanyId());
        entity.setPurchaseOrderId(po.getId());
        entity.setLineType(ProcurementLineType.MATERIAL);
        entity.setUnit("EA");
        entity.setQuantity(BigDecimal.TEN);
        entity.setReceivedQuantity(BigDecimal.ZERO);
        entity.setUnitPrice(BigDecimal.TEN);
        entity.setReceiptWarehouseId(receiptWarehouse.getId());
        return persist(entity);
    }

    private DeliverySchedule schedule(
        String code,
        PurchaseOrder po,
        Contract scheduleContract,
        LocalDate date,
        String status,
        String planned,
        String received
    ) {
        DeliverySchedule entity = base(
            new DeliverySchedule(), code, code, po.getTenantId(), po.getCompanyId());
        entity.setStatus(status);
        entity.setPurchaseOrderId(po.getId());
        entity.setContractId(scheduleContract.getId());
        entity.setScheduleDate(date);
        entity.setPlannedQuantity(new BigDecimal(planned));
        entity.setReceivedQuantity(new BigDecimal(received));
        entity.setPlannedAmount(new BigDecimal(planned));
        entity.setReceivedAmount(new BigDecimal(received));
        return persist(entity);
    }

    private SupplyWorkAssignment assignment(
        DeliverySchedule schedule,
        UUID ownerUserId,
        String ownerUsername
    ) {
        SupplyWorkAssignment entity = base(
            new SupplyWorkAssignment(), "SWA-" + UUID.randomUUID(), "Calendar assignment",
            schedule.getTenantId(), schedule.getCompanyId());
        entity.setCommitmentKey("SCHEDULE:" + schedule.getId());
        entity.setCommitmentKind(SupplyCommitmentKind.SCHEDULE);
        entity.setOwnerUserId(ownerUserId);
        entity.setOwnerUsername(ownerUsername);
        entity.setOwnerRole(SupplyAssignmentRole.PROCUREMENT_SPECIALIST);
        entity.setDueDateSource(SupplyDueDateSource.POLICY);
        entity.setSeverity(SupplySeverity.WARNING);
        entity.setEscalationState(SupplyEscalationState.NONE);
        return persist(entity);
    }

    private AdvanceShippingNotice asn(
        String code,
        String number,
        PurchaseOrder po,
        Contract asnContract,
        LocalDate expectedArrival,
        String status,
        String amount,
        String currency,
        String transportMode
    ) {
        AdvanceShippingNotice entity = base(
            new AdvanceShippingNotice(), code, code, po.getTenantId(), po.getCompanyId());
        entity.setAsnNumber(number);
        entity.setSupplierId(po.getSupplierId());
        entity.setPurchaseOrderId(po.getId());
        entity.setContractId(asnContract.getId());
        entity.setExpectedArrivalDate(expectedArrival);
        entity.setStatus(status);
        entity.setTotalAmount(new BigDecimal(amount));
        entity.setCurrencyCode(currency);
        entity.setTransportMode(transportMode);
        return persist(entity);
    }

    private ImportDelivery importDelivery(
        String code,
        String shipmentNumber,
        PurchaseOrder po,
        LocalDate eta,
        Warehouse destinationWarehouse,
        String status,
        String amount,
        String currency,
        String transportMode
    ) {
        ImportDelivery entity = base(
            new ImportDelivery(), code, code, po.getTenantId(), po.getCompanyId());
        entity.setShipmentNumber(shipmentNumber);
        entity.setSupplierId(po.getSupplierId());
        entity.setPurchaseOrderId(po.getId());
        entity.setEta(eta);
        entity.setDestinationWarehouseId(destinationWarehouse.getId());
        entity.setStatus(status);
        entity.setLandedCostAmount(new BigDecimal(amount));
        entity.setCurrencyCode(currency);
        entity.setTransportMode(transportMode);
        entity.setImportFlag(true);
        return persist(entity);
    }

    private CustomsDeclaration customs(
        String code,
        String declarationNumber,
        PurchaseOrder po,
        ImportDelivery importDelivery,
        LocalDate declarationDate,
        String status,
        String amount,
        String currency
    ) {
        CustomsDeclaration entity = base(
            new CustomsDeclaration(), code, code, po.getTenantId(), po.getCompanyId());
        entity.setDeclarationNumber(declarationNumber);
        entity.setImportDeliveryId(importDelivery.getId());
        entity.setPurchaseOrderId(po.getId());
        entity.setSupplierId(po.getSupplierId());
        entity.setDeclarationDate(declarationDate);
        entity.setStatus(status);
        entity.setTotalCustomsAmount(new BigDecimal(amount));
        entity.setCurrencyCode(currency);
        return persist(entity);
    }

    private ContractInvoice invoice(
        String code,
        String invoiceNumber,
        PurchaseOrder po,
        Contract invoiceContract,
        LocalDate dueDate,
        String status,
        String amount,
        String currency
    ) {
        ContractInvoice entity = base(
            new ContractInvoice(), code, code, po.getTenantId(), po.getCompanyId());
        entity.setInvoiceNumber(invoiceNumber);
        entity.setContractId(invoiceContract.getId());
        entity.setPurchaseOrderId(po.getId());
        entity.setSupplierId(po.getSupplierId());
        entity.setInvoiceDate(dueDate == null ? null : dueDate.minusDays(5));
        entity.setDueDate(dueDate);
        entity.setStatus(status);
        entity.setAmount(new BigDecimal(amount));
        entity.setCurrencyCode(currency);
        return persist(entity);
    }

    private ContractPayment payment(
        String code,
        String paymentNumber,
        Contract paymentContract,
        LocalDate paymentDate,
        String status,
        String amount,
        String currency
    ) {
        ContractPayment entity = base(
            new ContractPayment(), code, code, paymentContract.getTenantId(), paymentContract.getCompanyId());
        entity.setPaymentNumber(paymentNumber);
        entity.setContractId(paymentContract.getId());
        entity.setSupplierId(paymentContract.getSupplierId());
        entity.setPaymentDate(paymentDate);
        entity.setStatus(status);
        entity.setAmount(new BigDecimal(amount));
        entity.setCurrencyCode(currency);
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

    private record LaneFixture(
        UUID scheduleId,
        UUID asnId,
        UUID importId,
        UUID customsId,
        UUID invoiceId,
        UUID paymentId
    ) {
    }
}
