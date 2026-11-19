package com.enterprise.mm.supply.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AdvanceShippingNotice;
import com.enterprise.mm.entity.Contract;
import com.enterprise.mm.entity.ImportDelivery;
import com.enterprise.mm.entity.LandedCostComponent;
import com.enterprise.mm.entity.LandedLogisticsEvent;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.supply.workspace.domain.SupplyAccessScope;
import com.enterprise.mm.supply.workspace.domain.SupplyWorkspaceQuery;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceRequest;
import com.enterprise.mm.supply.workspace.repository.SupplyPerformanceQueryRepository;
import com.enterprise.mm.supply.workspace.repository.SupplyPerformanceQueryRepository.DurationObservation;
import com.enterprise.mm.supply.workspace.repository.SupplyPerformanceQueryRepository.LandedCostObservation;
import com.enterprise.mm.supply.workspace.service.SupplyWorkspaceFilterFactory;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
    "app.seed.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:supply_performance;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"
})
@ActiveProfiles("test")
@Transactional
class SupplyPerformanceQueryRepositoryTest {
    private static final String TENANT = "tenant-supply-performance-repository";
    private static final String OTHER_TENANT = "tenant-supply-performance-other";
    private static final Instant AS_OF = Instant.parse("2026-07-12T07:00:00Z");
    private static final LocalDate FROM = LocalDate.of(2026, 7, 6);
    private static final LocalDate TO = LocalDate.of(2026, 7, 13);

    @Autowired private SupplyPerformanceQueryRepository repository;
    @Autowired private EntityManager entityManager;

    private SupplyWorkspaceFilterFactory filterFactory;
    private UUID companyId;
    private UUID otherCompanyId;
    private Warehouse warehouse;
    private Warehouse otherWarehouse;
    private Supplier supplier;
    private Contract contract;
    private PurchaseOrder purchaseOrder;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        otherCompanyId = UUID.randomUUID();
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(companyId);
        filterFactory = new SupplyWorkspaceFilterFactory(
            Clock.fixed(AS_OF, ZoneOffset.UTC), "Asia/Tashkent");
        warehouse = persist(base(new Warehouse(), "WH-ALLOWED", "Allowed warehouse", TENANT, companyId));
        otherWarehouse = persist(base(new Warehouse(), "WH-DENIED", "Denied warehouse", TENANT, companyId));
        supplier = supplier("SUP-ALLOWED", "Allowed supplier", TENANT, companyId);
        contract = contract("CTR-ALLOWED", supplier, TENANT, companyId);
        purchaseOrder = purchaseOrder("PO-ALLOWED", supplier, contract, TENANT, companyId);
        purchaseOrderLine(purchaseOrder, warehouse);
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void returnsOnlyTenantCompanyAndWarehouseScopedFactsUsingOperationalCompletionDates() {
        AdvanceShippingNotice allowedAsn = asn(
            "ASN-ALLOWED", purchaseOrder, contract,
            FROM.minusDays(1).atTime(8, 0), FROM.atTime(10, 0), "USD", "ROAD");
        ImportDelivery allowedImport = importDelivery(
            "IMP-ALLOWED", purchaseOrder, warehouse, FROM.plusDays(2), "CLOSED", "USD", "ROAD");
        LandedLogisticsEvent allowedException = exception(
            "EVT-ALLOWED", allowedImport, FROM.minusDays(1).atTime(9, 0), FROM.plusDays(1).atTime(9, 0));

        Fixture deniedWarehouse = fixture("DENIED-WH", TENANT, companyId, otherWarehouse);
        Fixture deniedCompany = fixture("DENIED-COMPANY", TENANT, otherCompanyId, null);
        Fixture deniedTenant = fixture("DENIED-TENANT", OTHER_TENANT, companyId, null);
        entityManager.flush();

        SupplyWorkspaceQuery query = query(new SupplyAccessScope(
            TENANT, Set.of(companyId), Set.of(warehouse.getId()), false, true), false);

        assertThat(repository.asnConfirmationObservations(query))
            .extracting(DurationObservation::sourceId)
            .containsExactly(allowedAsn.getId())
            .doesNotContain(deniedWarehouse.asnId(), deniedCompany.asnId(), deniedTenant.asnId());
        assertThat(repository.exceptionResolutionObservations(query))
            .extracting(DurationObservation::sourceId)
            .containsExactly(allowedException.getId())
            .doesNotContain(deniedWarehouse.exceptionId(), deniedCompany.exceptionId(), deniedTenant.exceptionId());
        assertThat(repository.landedCostUpliftObservations(query))
            .extracting(LandedCostObservation::sourceId)
            .containsExactly(allowedImport.getId())
            .doesNotContain(deniedWarehouse.importId(), deniedCompany.importId(), deniedTenant.importId());
    }

    @Test
    void includesFromExcludesToAndKeepsIncompleteDurationsForHonestDenominators() {
        AdvanceShippingNotice completesAtFrom = asn(
            "ASN-COMPLETE-FROM", purchaseOrder, contract,
            FROM.minusDays(2).atTime(8, 0), FROM.atStartOfDay(), "USD", "ROAD");
        AdvanceShippingNotice incompleteAtFrom = asn(
            "ASN-INCOMPLETE", purchaseOrder, contract,
            FROM.atStartOfDay(), null, "USD", "ROAD");
        AdvanceShippingNotice completesAtTo = asn(
            "ASN-COMPLETE-TO", purchaseOrder, contract,
            TO.minusDays(1).atTime(8, 0), TO.atStartOfDay(), "USD", "ROAD");

        ImportDelivery importDelivery = importDelivery(
            "IMP-BOUNDARY", purchaseOrder, warehouse, FROM, "CLOSED", "USD", "ROAD");
        LandedLogisticsEvent resolvedAtFrom = exception(
            "EVT-RESOLVED-FROM", importDelivery,
            FROM.minusDays(2).atTime(8, 0), FROM.atStartOfDay());
        LandedLogisticsEvent unresolvedAtFrom = exception(
            "EVT-UNRESOLVED", importDelivery, FROM.atStartOfDay(), null);
        LandedLogisticsEvent resolvedAtTo = exception(
            "EVT-RESOLVED-TO", importDelivery,
            TO.minusDays(1).atTime(8, 0), TO.atStartOfDay());
        ImportDelivery atTo = importDelivery(
            "IMP-AT-TO", purchaseOrder, warehouse, TO, "CLOSED", "USD", "ROAD");
        entityManager.flush();

        SupplyWorkspaceQuery query = query(new SupplyAccessScope(
            TENANT, Set.of(companyId), Set.of(), false, false));

        assertThat(repository.asnConfirmationObservations(query))
            .extracting(DurationObservation::sourceId)
            .containsExactly(completesAtFrom.getId(), incompleteAtFrom.getId())
            .doesNotContain(completesAtTo.getId());
        assertThat(repository.asnConfirmationObservations(query))
            .filteredOn(observation -> observation.sourceId().equals(incompleteAtFrom.getId()))
            .singleElement().extracting(DurationObservation::completedAt).isNull();

        assertThat(repository.exceptionResolutionObservations(query))
            .extracting(DurationObservation::sourceId)
            .containsExactly(resolvedAtFrom.getId(), unresolvedAtFrom.getId())
            .doesNotContain(resolvedAtTo.getId());
        assertThat(repository.landedCostUpliftObservations(query))
            .extracting(LandedCostObservation::sourceId)
            .contains(importDelivery.getId())
            .doesNotContain(atTo.getId());
    }

    @Test
    void exposesCurrencyAndPostingEvidenceInsteadOfSilentlyMixingLandedCosts() {
        ImportDelivery importDelivery = importDelivery(
            "IMP-EVIDENCE", purchaseOrder, warehouse, FROM.plusDays(2), "CLOSED", "USD", "ROAD");
        component("COST-POSTED-USD", importDelivery, "USD", "POSTED", true);
        component("COST-POSTED-EUR", importDelivery, "EUR", "POSTED", true);
        component("COST-DRAFT-USD", importDelivery, "USD", "DRAFT", true);
        component("COST-IGNORED-EUR", importDelivery, "EUR", "DRAFT", false);
        entityManager.flush();

        LandedCostObservation observation = repository.landedCostUpliftObservations(query(
            new SupplyAccessScope(TENANT, Set.of(companyId), Set.of(), false, false)))
            .stream().filter(candidate -> candidate.sourceId().equals(importDelivery.getId()))
            .findFirst().orElseThrow();

        assertThat(observation.currencyCode()).isEqualTo("USD");
        assertThat(observation.currencyConsistent()).isFalse();
        assertThat(observation.postedCostComplete()).isFalse();
        assertThat(observation.baseGoodsAmount()).isEqualByComparingTo("100");
        assertThat(observation.landedCostAmount()).isEqualByComparingTo("125");
    }

    private SupplyWorkspaceQuery query(SupplyAccessScope scope) {
        return query(scope, true);
    }

    private SupplyWorkspaceQuery query(SupplyAccessScope scope, boolean applyBusinessFilters) {
        SupplyWorkspaceRequest request = new SupplyWorkspaceRequest();
        request.setAsOf(AS_OF);
        request.setFrom(FROM);
        request.setTo(TO);
        if (applyBusinessFilters) {
            request.setSupplierId(supplier.getId());
            request.setTransportMode("road");
            request.setCurrencyCode("usd");
        }
        return filterFactory.normalize(request, scope);
    }

    private Fixture fixture(String suffix, String tenantId, UUID fixtureCompanyId, Warehouse fixtureWarehouse) {
        Warehouse actualWarehouse = fixtureWarehouse == null
            ? persist(base(new Warehouse(), "WH-" + suffix, suffix, tenantId, fixtureCompanyId))
            : fixtureWarehouse;
        Supplier fixtureSupplier = supplier("SUP-" + suffix, suffix, tenantId, fixtureCompanyId);
        Contract fixtureContract = contract("CTR-" + suffix, fixtureSupplier, tenantId, fixtureCompanyId);
        PurchaseOrder fixturePo = purchaseOrder("PO-" + suffix, fixtureSupplier, fixtureContract, tenantId, fixtureCompanyId);
        purchaseOrderLine(fixturePo, actualWarehouse);
        AdvanceShippingNotice fixtureAsn = asn(
            "ASN-" + suffix, fixturePo, fixtureContract,
            FROM.atTime(8, 0), FROM.atTime(10, 0), "USD", "ROAD");
        ImportDelivery fixtureImport = importDelivery(
            "IMP-" + suffix, fixturePo, actualWarehouse, FROM.plusDays(2), "CLOSED", "USD", "ROAD");
        LandedLogisticsEvent fixtureException = exception(
            "EVT-" + suffix, fixtureImport, FROM.atTime(8, 0), FROM.atTime(10, 0));
        return new Fixture(fixtureAsn.getId(), fixtureImport.getId(), fixtureException.getId());
    }

    private Supplier supplier(String code, String name, String tenantId, UUID entityCompanyId) {
        Supplier entity = base(new Supplier(), code, name, tenantId, entityCompanyId);
        entity.setLegalName(name);
        return persist(entity);
    }

    private Contract contract(String code, Supplier entitySupplier, String tenantId, UUID entityCompanyId) {
        Contract entity = base(new Contract(), code, code, tenantId, entityCompanyId);
        entity.setContractNumber(code);
        entity.setSupplierId(entitySupplier.getId());
        entity.setCurrencyCode("USD");
        return persist(entity);
    }

    private PurchaseOrder purchaseOrder(
        String code,
        Supplier entitySupplier,
        Contract entityContract,
        String tenantId,
        UUID entityCompanyId
    ) {
        PurchaseOrder entity = base(new PurchaseOrder(), code, code, tenantId, entityCompanyId);
        entity.setStatus("SENT");
        entity.setSupplierId(entitySupplier.getId());
        entity.setContractId(entityContract.getId());
        entity.setOrderDate(FROM.minusDays(30));
        entity.setExpectedDeliveryDate(TO.plusDays(1));
        entity.setRequestType(RequestType.GOODS);
        entity.setCurrencyCode("USD");
        entity.setTotalAmount(new BigDecimal("100"));
        return persist(entity);
    }

    private PurchaseOrderItem purchaseOrderLine(PurchaseOrder po, Warehouse receiptWarehouse) {
        PurchaseOrderItem entity = base(
            new PurchaseOrderItem(), "LINE-" + UUID.randomUUID(), "Performance line",
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

    private AdvanceShippingNotice asn(
        String code,
        PurchaseOrder po,
        Contract entityContract,
        LocalDateTime sentAt,
        LocalDateTime confirmedAt,
        String currency,
        String transportMode
    ) {
        AdvanceShippingNotice entity = base(
            new AdvanceShippingNotice(), code, code, po.getTenantId(), po.getCompanyId());
        entity.setAsnNumber(code);
        entity.setSupplierId(po.getSupplierId());
        entity.setPurchaseOrderId(po.getId());
        entity.setContractId(entityContract.getId());
        entity.setSentAt(sentAt);
        entity.setConfirmedAt(confirmedAt);
        entity.setCurrencyCode(currency);
        entity.setTransportMode(transportMode);
        entity.setStatus(confirmedAt == null ? "SENT" : "CONFIRMED");
        return persist(entity);
    }

    private ImportDelivery importDelivery(
        String code,
        PurchaseOrder po,
        Warehouse destinationWarehouse,
        LocalDate actualArrival,
        String status,
        String currency,
        String transportMode
    ) {
        ImportDelivery entity = base(
            new ImportDelivery(), code, code, po.getTenantId(), po.getCompanyId());
        entity.setShipmentNumber(code);
        entity.setSupplierId(po.getSupplierId());
        entity.setPurchaseOrderId(po.getId());
        entity.setDestinationWarehouseId(destinationWarehouse.getId());
        entity.setActualArrivalDate(actualArrival);
        entity.setStatus(status);
        entity.setBaseGoodsAmount(new BigDecimal("100"));
        entity.setLandedCostAmount(new BigDecimal("125"));
        entity.setCurrencyCode(currency);
        entity.setTransportMode(transportMode);
        return persist(entity);
    }

    private LandedLogisticsEvent exception(
        String code,
        ImportDelivery importDelivery,
        LocalDateTime eventDate,
        LocalDateTime resolvedAt
    ) {
        LandedLogisticsEvent entity = base(
            new LandedLogisticsEvent(), code, code,
            importDelivery.getTenantId(), importDelivery.getCompanyId());
        entity.setImportDeliveryId(importDelivery.getId());
        entity.setEventType("DELAY");
        entity.setEventDate(eventDate);
        entity.setResolvedAt(resolvedAt);
        entity.setExceptionFlag(true);
        return persist(entity);
    }

    private LandedCostComponent component(
        String code,
        ImportDelivery importDelivery,
        String currency,
        String status,
        boolean included
    ) {
        LandedCostComponent entity = base(
            new LandedCostComponent(), code, code,
            importDelivery.getTenantId(), importDelivery.getCompanyId());
        entity.setImportDeliveryId(importDelivery.getId());
        entity.setComponentType("FREIGHT");
        entity.setAmount(BigDecimal.TEN);
        entity.setCurrencyCode(currency);
        entity.setStatus(status);
        entity.setIncludedInInventoryCost(included);
        entity.setPostedAt("POSTED".equals(status) ? FROM.atStartOfDay() : null);
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

    private record Fixture(UUID asnId, UUID importId, UUID exceptionId) {
    }
}
