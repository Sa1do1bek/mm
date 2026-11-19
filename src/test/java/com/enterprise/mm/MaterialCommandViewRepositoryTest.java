package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.InventoryAccountingSettings;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.MaterialReservation;
import com.enterprise.mm.entity.MaterialQualityIssue;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.entity.AuditLog;
import com.enterprise.mm.entity.IntegrationEvent;
import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.payload.MaterialCommandViewDtos.AvailabilityFilter;
import com.enterprise.mm.payload.MaterialCommandViewDtos.AvailabilityRow;
import com.enterprise.mm.payload.MaterialCommandViewDtos.DemandSupplyFilter;
import com.enterprise.mm.payload.MaterialCommandViewDtos.DemandSupplyKind;
import com.enterprise.mm.payload.MaterialCommandViewDtos.DemandSupplyRow;
import com.enterprise.mm.payload.MaterialCommandViewDtos.MovementFilter;
import com.enterprise.mm.payload.MaterialCommandViewDtos.MovementRow;
import com.enterprise.mm.payload.MaterialCommandViewDtos.SectionKey;
import com.enterprise.mm.payload.MaterialCommandViewDtos.SupplierFilter;
import com.enterprise.mm.payload.MaterialCommandViewDtos.SupplierScopeStatus;
import com.enterprise.mm.payload.MaterialCommandViewDtos.QualityCompatibilityFilter;
import com.enterprise.mm.payload.MaterialCommandViewDtos.QualityCompatibilityKind;
import com.enterprise.mm.payload.MaterialCommandViewDtos.RelationshipFilter;
import com.enterprise.mm.payload.MaterialCommandViewDtos.RelationshipType;
import com.enterprise.mm.repository.InventoryAccountingSettingsRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.MaterialReservationRepository;
import com.enterprise.mm.repository.MaterialQualityIssueRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.PurchaseRequestItemRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.StockMovementRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.repository.AuditLogRepository;
import com.enterprise.mm.repository.IntegrationEventRepository;
import com.enterprise.mm.service.MaterialDemandSupplyQueryService;
import com.enterprise.mm.service.MaterialSupplierQueryService;
import com.enterprise.mm.service.MaterialQualityQueryService;
import com.enterprise.mm.service.MaterialRelationshipQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.enterprise.mm.service.MaterialCommandViewScopeGuard.MaterialScope;
import com.enterprise.mm.service.MaterialInventoryQueryService;
import com.enterprise.mm.service.MaterialInventoryQueryService.InventorySnapshot;
import com.enterprise.mm.warehouse.movements.MovementQuerySupport;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalTime;
import java.util.List;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MaterialCommandViewRepositoryTest {
    private static final String TENANT_ID = "default";
    private static final UUID COMPANY_ID = CompanyContext.DEFAULT_COMPANY_ID;

    @Autowired MaterialRepository materialRepository;
    @Autowired com.enterprise.mm.repository.UnitOfMeasureRepository unitRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired StockBalanceRepository stockBalanceRepository;
    @Autowired StockMovementRepository stockMovementRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired InventoryAccountingSettingsRepository accountingSettingsRepository;
    @Autowired MaterialInventoryQueryService inventory;
    @Autowired PurchaseRequestRepository purchaseRequestRepository;
    @Autowired PurchaseRequestItemRepository purchaseRequestItemRepository;
    @Autowired PurchaseOrderRepository purchaseOrderRepository;
    @Autowired PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Autowired MaterialReservationRepository materialReservationRepository;
    @Autowired SupplierRepository supplierRepository;
    @Autowired MaterialDemandSupplyQueryService demandSupply;
    @Autowired MaterialQualityIssueRepository materialQualityIssueRepository;
    @Autowired MaterialSupplierQueryService suppliers;
    @Autowired MaterialQualityQueryService quality;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired IntegrationEventRepository integrationEventRepository;
    @Autowired MaterialRelationshipQueryService relationships;

    private Material material;
    private Warehouse warehouse;
    private MaterialScope scope;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(TENANT_ID);
        CompanyContext.setCompany(COMPANY_ID);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            "admin",
            "n/a",
            java.util.List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        material = material("M360-MAT-" + suffix(), COMPANY_ID);
        warehouse = warehouse("M360-WH-" + suffix(), COMPANY_ID);
        scope = new MaterialScope(TENANT_ID, COMPANY_ID, material.getId(), material);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void inventoryAggregateExcludesOtherCompanyAndUsesBaseCurrency() {
        accountingSettings("UZS");
        stockBalance(COMPANY_ID, material.getId(), warehouse.getId(), "10", "7", "2", "1", "120");
        stockBalance(UUID.randomUUID(), material.getId(), warehouse.getId(), "99", "99", "0", "0", "999");

        InventorySnapshot snapshot = inventory.aggregate(scope);

        assertThat(snapshot.onHand()).isEqualByComparingTo("10");
        assertThat(snapshot.available()).isEqualByComparingTo("7");
        assertThat(snapshot.reserved()).isEqualByComparingTo("2");
        assertThat(snapshot.quarantine()).isEqualByComparingTo("1");
        assertThat(snapshot.totalValue()).isEqualByComparingTo("120");
        assertThat(snapshot.currencyCode()).isEqualTo("UZS");
        assertThat(snapshot.warehouseCount()).isEqualTo(1);
    }

    @Test
    void availabilityPositiveOnlyAndWarehouseFilterAreAppliedInSql() {
        accountingSettings("UZS");
        StockBalance expected = stockBalance(
            COMPANY_ID, material.getId(), warehouse.getId(), "4", "4", "0", "0", "12.3400");
        stockBalance(COMPANY_ID, material.getId(), warehouse.getId(), "0", "0", "0", "0", "0");
        Warehouse otherWarehouse = warehouse("M360-WH-OTHER-" + suffix(), COMPANY_ID);
        stockBalance(COMPANY_ID, material.getId(), otherWarehouse.getId(), "8", "8", "0", "0", "80");

        Page<AvailabilityRow> page = inventory.availability(
            scope,
            new AvailabilityFilter(warehouse.getId(), null, null, true),
            PageRequest.of(0, 25));

        assertThat(page).extracting(AvailabilityRow::id).containsExactly(expected.getId());
        assertThat(new BigDecimal(page.getContent().getFirst().availableQuantity())).isEqualByComparingTo("4");
        assertThat(page).extracting(AvailabilityRow::totalValue).containsExactly("12.3400");
    }

    @Test
    void movementFiltersUseDirectMaterialAndStablePostedOrder() {
        var unit = demandUnit("1C-UOM-" + UUID.randomUUID().toString().replace("-", ""), "шт.", COMPANY_ID);
        material.setUnitOfMeasureId(unit.getId()); materialRepository.saveAndFlush(material);
        LocalDate movementDate = LocalDate.of(2026, 7, 12);
        StockMovement older = movement(material.getId(), warehouse.getId(), movementDate,
            LocalDateTime.of(2026, 7, 12, 9, 0), MovementType.RECEIPT);
        StockMovement newer = movement(material.getId(), warehouse.getId(), movementDate,
            LocalDateTime.of(2026, 7, 12, 10, 0), MovementType.RECEIPT);
        Material otherMaterial = material("M360-MAT-OTHER-" + suffix(), COMPANY_ID);
        movement(otherMaterial.getId(), warehouse.getId(), movementDate,
            LocalDateTime.of(2026, 7, 12, 11, 0), MovementType.RECEIPT);

        Page<MovementRow> page = inventory.movements(
            scope,
            new MovementFilter(
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31),
                warehouse.getId(),
                MovementType.RECEIPT,
                null),
            PageRequest.of(0, 25));

        assertThat(page).extracting(MovementRow::id).containsExactly(newer.getId(), older.getId());
        assertThat(page).allSatisfy(row -> {
            assertThat(row.unitOfMeasureCode()).isEqualTo(unit.getCode());
            assertThat(row.unitOfMeasureShortName()).isEqualTo("шт.");
        });
        var warehouseRows = stockMovementRepository.findWarehouse360Movements(TENANT_ID, COMPANY_ID, warehouse.getId(),
            List.of("POSTED"), LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), PageRequest.of(0, 25));
        assertThat(warehouseRows).filteredOn(row -> row.getMaterialId().equals(material.getId())).hasSize(2)
            .allSatisfy(row -> assertThat(row.getUnitOfMeasureShortName()).isEqualTo("шт."));
    }

    @Test
    void demandSupplyExcludesTerminalAndNonMaterialLines() {
        PurchaseOrder included = purchaseOrder("SENT", LocalDate.of(2026, 7, 15));
        purchaseOrderItem(included, material.getId(), ProcurementLineType.MATERIAL, "10", "4");
        PurchaseOrder terminal = purchaseOrder("RECEIVED", LocalDate.of(2026, 7, 16));
        purchaseOrderItem(terminal, material.getId(), ProcurementLineType.MATERIAL, "8", "8");
        PurchaseOrder serviceOrder = purchaseOrder("SENT", LocalDate.of(2026, 7, 17));
        purchaseOrderItem(serviceOrder, null, ProcurementLineType.SERVICE, "5", "0");
        flushDemandFixtures();

        Page<DemandSupplyRow> page = demandSupply.page(
            scope,
            DemandSupplyFilter.empty(),
            Set.of(SectionKey.PURCHASE_ORDERS),
            PageRequest.of(0, 25));

        assertThat(page).singleElement().satisfies(row -> {
            assertThat(row.kind()).isEqualTo(DemandSupplyKind.PURCHASE_ORDER_SUPPLY);
            assertThat(new BigDecimal(row.openQuantity())).isEqualByComparingTo("6");
        });
    }

    @Test
    void demandSupplyReturnsShortNamesForDocumentUnitsAndReservationStockUnit() {
        var stockUnit = demandUnit("AMM-STOCK-" + suffix(), "шт.", COMPANY_ID);
        var orderUnit = demandUnit("AMM-BOX-" + suffix(), "кор.", COMPANY_ID);
        material.setUnitOfMeasureId(stockUnit.getId()); materialRepository.saveAndFlush(material);
        var request = purchaseRequestDemand(LocalDate.of(2026, 7, 12)); request.setUnit(stockUnit.getCode());
        var order = purchaseOrderItem(purchaseOrder("SENT", LocalDate.of(2026, 7, 13)),
            material.getId(), ProcurementLineType.MATERIAL, "10", "4"); order.setUnit(orderUnit.getCode());
        var reserved = reservation(LocalDate.of(2026, 7, 14));
        flushDemandFixtures();

        var rows = demandSupply.page(scope, DemandSupplyFilter.empty(), allDemandSections(), PageRequest.of(0, 25));
        assertThat(rows.getTotalElements()).isEqualTo(3);
        assertThat(rows).filteredOn(r -> r.id().equals(request.getId())).singleElement().satisfies(row -> {
            assertThat(row.unitOfMeasureCode()).isEqualTo(stockUnit.getCode());
            assertThat(row.unitOfMeasureShortName()).isEqualTo("шт.");
            var json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().valueToTree(row);
            assertThat(json.path("unitOfMeasureShortName").asText()).isEqualTo("шт.");
        });
        assertThat(rows).filteredOn(r -> r.id().equals(order.getId())).singleElement()
            .satisfies(row -> assertThat(row.unitOfMeasureShortName()).isEqualTo("кор."));
        assertThat(rows).filteredOn(r -> r.id().equals(reserved.getId())).singleElement()
            .satisfies(row -> assertThat(row.unitOfMeasureShortName()).isEqualTo("шт."));
    }

    @Test
    void demandSupplyDoesNotLeakForeignUnitOrMislabelUnknownDocumentUnit() {
        var stockUnit = demandUnit("AMM-STOCK-" + suffix(), "шт.", COMPANY_ID);
        material.setUnitOfMeasureId(stockUnit.getId()); materialRepository.saveAndFlush(material);
        String foreignCode = "AMM-FOREIGN-" + suffix();
        demandUnit(foreignCode, "foreign", UUID.randomUUID());
        var request = purchaseRequestDemand(LocalDate.of(2026, 7, 12)); request.setUnit(foreignCode);
        flushDemandFixtures();
        assertThat(demandSupply.page(scope, DemandSupplyFilter.empty(), allDemandSections(), PageRequest.of(0, 25)))
            .singleElement().satisfies(row -> {
                assertThat(row.unitOfMeasureCode()).isEqualTo(foreignCode);
                assertThat(row.unitOfMeasureShortName()).isNull();
            });
    }

    private com.enterprise.mm.entity.UnitOfMeasure demandUnit(String code, String shortName, UUID company) {
        var unit = new com.enterprise.mm.entity.UnitOfMeasure(); unit.setCode(code); unit.setName("Unit " + code);
        unit.setStatus("ACTIVE"); unit.setCompanyId(company); unit.setMeasurementNameShort(shortName);
        return unitRepository.saveAndFlush(unit);
    }

    @Test
    void overdueSupplyRiskExecutesWithoutWarehouseScope() {
        PurchaseOrder overdue = purchaseOrder("SENT", LocalDate.of(2026, 7, 10));
        purchaseOrderItem(overdue, material.getId(), ProcurementLineType.MATERIAL, "10", "4");
        flushDemandFixtures();

        boolean exists = demandSupply.overdueSupplyExists(
            scope, Set.of(SectionKey.PURCHASE_ORDERS), LocalDate.of(2026, 7, 13));

        assertThat(exists).isTrue();
    }

    @Test
    void demandSupplyUsesOneGlobalDateOrderAndPaginationAcrossSources() {
        PurchaseOrder order = purchaseOrder("SENT", LocalDate.of(2026, 7, 10));
        PurchaseOrderItem poItem = purchaseOrderItem(
            order, material.getId(), ProcurementLineType.MATERIAL, "10", "4");
        MaterialReservation reservation = reservation(LocalDate.of(2026, 7, 12));
        PurchaseRequestItem requestItem = purchaseRequestDemand(LocalDate.of(2026, 7, 11));
        flushDemandFixtures();

        Page<DemandSupplyRow> first = demandSupply.page(
            scope, DemandSupplyFilter.empty(), allDemandSections(), PageRequest.of(0, 2));
        Page<DemandSupplyRow> second = demandSupply.page(
            scope, DemandSupplyFilter.empty(), allDemandSections(), PageRequest.of(1, 2));

        assertThat(first).extracting(DemandSupplyRow::id)
            .containsExactly(reservation.getId(), requestItem.getId());
        assertThat(second).extracting(DemandSupplyRow::id).containsExactly(poItem.getId());
        assertThat(first.getTotalElements()).isEqualTo(3);
    }

    @Test
    void demandSupplyIncludesPartialAndFullTenderPurchaseRequests() {
        PurchaseRequestItem partial = purchaseRequestDemand(
            "PARTIALLY_SENT_TO_TENDER", LocalDate.of(2026, 7, 11));
        PurchaseRequestItem full = purchaseRequestDemand(
            "FULLY_SENT_TO_TENDER", LocalDate.of(2026, 7, 12));
        flushDemandFixtures();

        Page<DemandSupplyRow> page = demandSupply.page(
            scope,
            DemandSupplyFilter.empty(),
            Set.of(SectionKey.PURCHASE_REQUESTS),
            PageRequest.of(0, 25));

        assertThat(page).extracting(DemandSupplyRow::id)
            .containsExactly(full.getId(), partial.getId());
        assertThat(page).extracting(DemandSupplyRow::documentStatus)
            .containsExactly("FULLY_SENT_TO_TENDER", "PARTIALLY_SENT_TO_TENDER");
    }

    @Test
    void deniedProcurementSourcesAreNotCountedOrReturned() {
        purchaseRequestDemand(LocalDate.of(2026, 7, 11));
        MaterialReservation reservation = reservation(LocalDate.of(2026, 7, 12));
        flushDemandFixtures();

        Page<DemandSupplyRow> page = demandSupply.page(
            scope,
            DemandSupplyFilter.empty(),
            Set.of(SectionKey.RESERVATIONS),
            PageRequest.of(0, 25));

        assertThat(page).extracting(DemandSupplyRow::id).containsExactly(reservation.getId());
        assertThat(page).allMatch(row -> row.kind() == DemandSupplyKind.RESERVATION_DEMAND);
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    void supplierHistoryIsTransactionalAndDoesNotInferAccreditation() {
        PurchaseOrder order = purchaseOrder("SENT", LocalDate.of(2026, 7, 12));
        purchaseOrderItem(order, material.getId(), ProcurementLineType.MATERIAL, "4", "0");
        flushDemandFixtures();

        var page = suppliers.page(scope, new SupplierFilter(null, null, null), PageRequest.of(0, 25));

        assertThat(page).singleElement().satisfies(row -> {
            assertThat(row.sourceType()).isEqualTo("PURCHASE_ORDER");
            assertThat(row.scopeStatus()).isEqualTo(SupplierScopeStatus.UNKNOWN);
        });
    }

    @Test
    void qualityReturnsOnlyExplicitStoredFacts() {
        MaterialQualityIssue issue = new MaterialQualityIssue();
        issue.setCode("M360-QI-" + suffix());
        issue.setName("Explicit issue");
        issue.setStatus("OPEN");
        issue.setCompanyId(COMPANY_ID);
        issue.setMaterialId(material.getId());
        issue.setIssueType("DUPLICATE_CODE");
        issue.setSeverity("HIGH");
        issue = materialQualityIssueRepository.saveAndFlush(issue);

        var page = quality.page(scope, new QualityCompatibilityFilter(null, null), PageRequest.of(0, 25));

        assertThat(page).extracting(row -> row.id()).containsExactly(issue.getId());
        assertThat(page).extracting(row -> row.kind()).containsExactly(QualityCompatibilityKind.QUALITY_ISSUE);
    }

    @Test
    void qualityIssuesUseOccurrenceTimestampBeforeIdForStableOrdering() {
        MaterialQualityIssue older = qualityIssue(
            "Older issue", LocalDateTime.of(2026, 7, 10, 9, 0));
        MaterialQualityIssue newer = qualityIssue(
            "Newer issue", LocalDateTime.of(2026, 7, 12, 9, 0));

        var page = quality.page(scope,
            new QualityCompatibilityFilter(QualityCompatibilityKind.QUALITY_ISSUE, null),
            PageRequest.of(0, 25));

        assertThat(page).extracting(row -> row.id()).containsExactly(newer.getId(), older.getId());
    }

    @Test
    void relationshipsUseOnlyDirectKeysAndDoNotExposeRawPayloads() throws Exception {
        AuditLog audit = new AuditLog();
        audit.setCode("M360-AUD-" + suffix()); audit.setName("Material updated"); audit.setStatus("SUCCESS");
        audit.setCompanyId(COMPANY_ID); audit.setEntityType("Material"); audit.setEntityId(material.getId());
        audit.setAction("UPDATE"); audit.setDetails("secret details"); audit.setOccurredAt(LocalDateTime.now());
        audit = auditLogRepository.saveAndFlush(audit);
        IntegrationEvent event = new IntegrationEvent();
        event.setCode("M360-INT-" + suffix()); event.setName("Material sync"); event.setStatus("FAILED");
        event.setCompanyId(COMPANY_ID); event.setAggregateType("Material"); event.setAggregateId(material.getId());
        event.setEventType("MATERIAL_SYNC"); event.setPayload("{\"price\":999}");
        event.setIdempotencyKey("M360-" + suffix()); event = integrationEventRepository.saveAndFlush(event);

        var page = relationships.page(scope, RelationshipFilter.empty(),
            Set.of(SectionKey.AUDIT, SectionKey.INTEGRATIONS), PageRequest.of(0, 25));
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(page.getContent());

        assertThat(page).extracting(row -> row.id()).containsExactlyInAnyOrder(audit.getId(), event.getId());
        assertThat(page).extracting(row -> row.type())
            .containsExactlyInAnyOrder(RelationshipType.AUDIT_EVENT, RelationshipType.INTEGRATION_EVENT);
        assertThat(json).doesNotContain("secret details", "\"price\"", "payload");
    }

    private Material material(String code, UUID companyId) {
        Material entity = new Material();
        entity.setCode(code);
        entity.setName(code);
        entity.setStatus("ACTIVE");
        entity.setCompanyId(companyId);
        return materialRepository.save(entity);
    }

    private MaterialQualityIssue qualityIssue(String name, LocalDateTime occurredAt) {
        MaterialQualityIssue issue = new MaterialQualityIssue();
        issue.setCode("M360-QI-" + suffix());
        issue.setName(name);
        issue.setStatus("OPEN");
        issue.setCompanyId(COMPANY_ID);
        issue.setMaterialId(material.getId());
        issue.setIssueType("DUPLICATE_CODE");
        issue.setSeverity("HIGH");
        issue.setCreatedAt(occurredAt);
        issue.setUpdatedAt(occurredAt);
        return materialQualityIssueRepository.saveAndFlush(issue);
    }

    private Warehouse warehouse(String code, UUID companyId) {
        Warehouse entity = new Warehouse();
        entity.setCode(code);
        entity.setName(code);
        entity.setStatus("ACTIVE");
        entity.setCompanyId(companyId);
        return warehouseRepository.save(entity);
    }

    private void accountingSettings(String currencyCode) {
        InventoryAccountingSettings settings = accountingSettingsRepository
            .findByTenantIdAndCompanyIdAndIsDeletedFalse(TENANT_ID, COMPANY_ID)
            .orElseGet(() -> {
                InventoryAccountingSettings created = new InventoryAccountingSettings();
                created.setCode("M360-SETTINGS-" + suffix());
                created.setName(created.getCode());
                created.setStatus("ACTIVE");
                created.setCompanyId(COMPANY_ID);
                return created;
            });
        settings.setBaseCurrencyCode(currencyCode);
        accountingSettingsRepository.save(settings);
    }

    private StockBalance stockBalance(
        UUID companyId,
        UUID materialId,
        UUID warehouseId,
        String onHand,
        String available,
        String reserved,
        String quarantine,
        String totalValue
    ) {
        StockBalance entity = new StockBalance();
        entity.setCode("M360-BAL-" + suffix());
        entity.setName(entity.getCode());
        entity.setStatus("ACTIVE");
        entity.setCompanyId(companyId);
        entity.setMaterialId(materialId);
        entity.setWarehouseId(warehouseId);
        entity.setQuantity(new BigDecimal(onHand));
        entity.setAvailableQuantity(new BigDecimal(available));
        entity.setReservedQuantity(new BigDecimal(reserved));
        entity.setQuarantineQuantity(new BigDecimal(quarantine));
        entity.setTotalValue(new BigDecimal(totalValue));
        entity.setBalanceAt(LocalDateTime.of(2026, 7, 13, 8, 0));
        return stockBalanceRepository.save(entity);
    }

    private StockMovement movement(
        UUID materialId,
        UUID warehouseId,
        LocalDate movementDate,
        LocalDateTime postedAt,
        MovementType movementType
    ) {
        StockMovement entity = new StockMovement();
        entity.setCode("M360-MOV-" + suffix());
        entity.setName(entity.getCode());
        entity.setStatus("POSTED");
        entity.setCompanyId(COMPANY_ID);
        entity.setMaterialId(materialId);
        entity.setWarehouseId(warehouseId);
        entity.setMovementDate(movementDate);
        entity.setPostedAt(postedAt);
        entity.setMovementType(movementType);
        entity.setQuantity(new BigDecimal("3.5000"));
        return stockMovementRepository.save(entity);
    }

    private PurchaseRequestItem purchaseRequestDemand(LocalDate requiredDate) {
        return purchaseRequestDemand("APPROVED", requiredDate);
    }

    private PurchaseRequestItem purchaseRequestDemand(String status, LocalDate requiredDate) {
        PurchaseRequest request = new PurchaseRequest();
        request.setCode("M360-PR-" + suffix());
        request.setName(request.getCode());
        request.setStatus(status);
        request.setCompanyId(COMPANY_ID);
        request.setRequiredDate(requiredDate);
        request = purchaseRequestRepository.save(request);

        PurchaseRequestItem item = new PurchaseRequestItem();
        item.setCode("M360-PRI-" + suffix());
        item.setName(item.getCode());
        item.setStatus("ACTIVE");
        item.setCompanyId(COMPANY_ID);
        item.setPurchaseRequestId(request.getId());
        item.setLineType(ProcurementLineType.MATERIAL);
        item.setMaterialId(material.getId());
        item.setQuantity(new BigDecimal("5.0000"));
        item.setUnit("EA");
        return purchaseRequestItemRepository.save(item);
    }

    private MaterialReservation reservation(LocalDate requiredDate) {
        MaterialReservation entity = new MaterialReservation();
        entity.setCode("M360-RES-" + suffix());
        entity.setName(entity.getCode());
        entity.setStatus("ACTIVE");
        entity.setCompanyId(COMPANY_ID);
        entity.setMaterialId(material.getId());
        entity.setWarehouseId(warehouse.getId());
        entity.setReservationType("MANUAL");
        entity.setPriority("NORMAL");
        entity.setQuantity(new BigDecimal("7.0000"));
        entity.setReservedQuantity(new BigDecimal("7.0000"));
        entity.setReleasedQuantity(new BigDecimal("2.0000"));
        entity.setRequiredDate(requiredDate);
        return materialReservationRepository.save(entity);
    }

    private PurchaseOrder purchaseOrder(String status, LocalDate expectedDeliveryDate) {
        Supplier supplier = new Supplier();
        supplier.setCode("M360-SUP-" + suffix());
        supplier.setName(supplier.getCode());
        supplier.setStatus("ACTIVE");
        supplier.setCompanyId(COMPANY_ID);
        supplier = supplierRepository.save(supplier);

        PurchaseOrder order = new PurchaseOrder();
        order.setCode("M360-PO-" + suffix());
        order.setName(order.getCode());
        order.setStatus(status);
        order.setCompanyId(COMPANY_ID);
        order.setSupplierId(supplier.getId());
        order.setOrderDate(expectedDeliveryDate.minusDays(3));
        order.setExpectedDeliveryDate(expectedDeliveryDate);
        return purchaseOrderRepository.save(order);
    }

    private PurchaseOrderItem purchaseOrderItem(
        PurchaseOrder order,
        UUID materialId,
        ProcurementLineType lineType,
        String quantity,
        String receivedQuantity
    ) {
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setCode("M360-POI-" + suffix());
        item.setName(item.getCode());
        item.setStatus("ACTIVE");
        item.setCompanyId(COMPANY_ID);
        item.setPurchaseOrderId(order.getId());
        item.setLineType(lineType);
        item.setMaterialId(materialId);
        item.setQuantity(new BigDecimal(quantity));
        item.setReceivedQuantity(new BigDecimal(receivedQuantity));
        item.setUnit("EA");
        item.setReceiptWarehouseId(warehouse.getId());
        return purchaseOrderItemRepository.save(item);
    }

    private Set<SectionKey> allDemandSections() {
        return Set.of(SectionKey.PURCHASE_REQUESTS, SectionKey.PURCHASE_ORDERS, SectionKey.RESERVATIONS);
    }

    private void flushDemandFixtures() {
        supplierRepository.flush();
        purchaseRequestRepository.flush();
        purchaseRequestItemRepository.flush();
        purchaseOrderRepository.flush();
        purchaseOrderItemRepository.flush();
        materialReservationRepository.flush();
    }

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
    @Test void movementQueryAcceptsEveryPublicSortAndUsesEffectiveDateInclusiveRange() {
        var old = exportMovementFixture("Z",LocalDate.of(2026,10,1));
        var recent = exportMovementFixture("A",LocalDate.of(2026,10,22));
        var fallback = exportMovementFixture("B",null);
        jdbc.update("update stock_movements set created_at=? where id=?",LocalDateTime.of(2026,10,23,12,0),fallback.getId());
        for(String field : MovementQuerySupport.SQL_SORTS.keySet()) for(String direction : List.of("asc","desc")) {
            Page<StockMovement> result = exportMovementSearch(field+","+direction,null,null);
            assertThat(result.getTotalElements()).as(field+","+direction).isEqualTo(3);
        }
        assertThat(exportMovementSearch("movementDate,asc",null,null).getContent()).extracting(StockMovement::getId)
            .containsExactly(old.getId(),recent.getId(),fallback.getId());
        assertThat(exportMovementSearch("code,asc",LocalDate.of(2026,10,22),LocalDate.of(2026,10,22)).getContent())
            .extracting(StockMovement::getId).containsExactly(recent.getId());
        assertThat(exportMovementSearch("code,asc",null,LocalDate.of(2026,10,1)).getContent()).extracting(StockMovement::getId).containsExactly(old.getId());
        assertThat(stockMovementRepository.findQueryRows(TenantContext.currentTenant(),UUID.randomUUID(),List.of(old.getId()))).isEmpty();
        assertThat(stockMovementRepository.findQueryRows(TenantContext.currentTenant(),COMPANY_ID,List.of(old.getId()))).hasSize(1);
    }
    private Page<StockMovement> exportMovementSearch(String sort,LocalDate from,LocalDate to) {
        return stockMovementRepository.search(TenantContext.currentTenant(),COMPANY_ID,false,"",from==null?null:from.atStartOfDay(),to==null?null:to.atTime(LocalTime.MAX),
            null,true,null,null,true,null,null,null,null,
            PageRequest.of(0,20,MovementQuerySupport.jpaSort(MovementQuerySupport.sort(List.of(sort)))));
    }
    private StockMovement exportMovementFixture(String code,LocalDate date) {
        var e = new StockMovement(); e.setTenantId(TenantContext.currentTenant()); e.setCompanyId(COMPANY_ID);
        e.setMaterialId(material.getId()); e.setWarehouseId(warehouse.getId()); e.setCode(code); e.setName(code); e.setStatus("POSTED"); e.setMovementType(MovementType.RECEIPT); e.setMovementDate(date);
        e.setCreatedAt(LocalDateTime.of(2026,10,23,12,0)); e.setQuantity(BigDecimal.ONE);
        return stockMovementRepository.saveAndFlush(e);
    }

}
