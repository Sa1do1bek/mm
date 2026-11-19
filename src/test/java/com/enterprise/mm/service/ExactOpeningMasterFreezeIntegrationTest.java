package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.enums.MeasurementUnitType;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.*;
import com.enterprise.mm.security.AccessScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

@SpringBootTest
@ActiveProfiles("test")
class ExactOpeningMasterFreezeIntegrationTest {
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired WarehouseService warehouses;
    @Autowired StorageLocationService locations;
    @Autowired StorageBinService bins;
    @Autowired MaterialService materials;
    @Autowired UnitOfMeasureService units;
    @Autowired MaterialGroupService groups;
    @Autowired CompanyService companies;
    @Autowired StockBatchService batches;
    @Autowired StockBalanceService balances;
    @Autowired StockMovementService movements;
    @Autowired ImportService excelImports;
    @Autowired WarehouseIdentificationService identification;
    @Autowired InventoryOptimizationRecommendationService optimization;
    @Autowired InboundIntegrationSyncService inbound;
    @Autowired LandedCostAllocationService landedCosts;
    @Autowired ObjectMapper objectMapper;

    private String tenant;
    private UUID companyId, warehouseId, locationId, binId, materialId, groupId, unitId, batchId, balanceId, importId;

    @BeforeEach
    void setUp() {
        tenant = "opening-freeze-" + UUID.randomUUID();
        TenantContext.setTenant(tenant);
        var authentication = new UsernamePasswordAuthenticationToken("global-freeze-admin", null,
            List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        authentication.setDetails(new AccessScope.RequestScope(true));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        transactions.executeWithoutResult(status -> {
            // Company CRUD must guard its own id; company_id may hold the original default context.
            Company company = base(new Company(), "FREEZE-COMPANY", CompanyContext.DEFAULT_COMPANY_ID);
            entityManager.persist(company); companyId = company.getId(); CompanyContext.setCompany(companyId);
            MaterialGroup group = base(new MaterialGroup(), "FREEZE-GROUP", companyId);
            entityManager.persist(group); groupId = group.getId();
            UnitOfMeasure unit = base(new UnitOfMeasure(), "FREEZE-KG", companyId);
            unit.setMeasurementNameShort("kg"); unit.setMeasurementNameLong("Kilogram"); unit.setUnitType(MeasurementUnitType.WEIGHT);
            entityManager.persist(unit); unitId = unit.getId();
            Warehouse warehouse = base(new Warehouse(), "FREEZE-WAREHOUSE", companyId);
            entityManager.persist(warehouse); warehouseId = warehouse.getId();
            StorageLocation location = base(new StorageLocation(), "FREEZE-LOCATION", companyId);
            location.setWarehouseId(warehouseId); entityManager.persist(location); locationId = location.getId();
            StorageBin bin = base(new StorageBin(), "FREEZE-BIN", companyId);
            bin.setWarehouseId(warehouseId); bin.setStorageLocationId(locationId); bin.setBinCode("FREEZE-BIN");
            entityManager.persist(bin); binId = bin.getId();
        });
        MaterialCreateRequest request = new MaterialCreateRequest();
        request.setCode("FREEZE-MATERIAL"); request.setName("Opening stock source material");
        request.setMaterialGroupId(groupId); request.setUnitOfMeasureId(unitId); request.setMaterialType(MaterialType.TMB);
        materialId = materials.create(request).getId();
        transactions.executeWithoutResult(status -> {
            StockBatch batch = base(new StockBatch(), "FREEZE-STOCK-BATCH", companyId);
            batch.setMaterialId(materialId); batch.setWarehouseId(warehouseId); batch.setQuantity(BigDecimal.ZERO);
            batch.setUnitCost(BigDecimal.ZERO); entityManager.persist(batch); batchId = batch.getId();
            StockBalance balance = base(new StockBalance(), "FREEZE-BALANCE", companyId);
            balance.setMaterialId(materialId); balance.setWarehouseId(warehouseId); balance.setStorageLocationId(locationId);
            balance.setStockBatchId(batchId); balance.setQuantity(BigDecimal.ZERO); balance.setAvailableQuantity(BigDecimal.ZERO);
            balance.setReservedQuantity(BigDecimal.ZERO); balance.setQuarantineQuantity(BigDecimal.ZERO); balance.setTotalValue(BigDecimal.ZERO);
            entityManager.persist(balance); balanceId = balance.getId();
            ExactOpeningImport run = new ExactOpeningImport(); importId = UUID.randomUUID();
            run.setId(importId); run.setTenantId(tenant); run.setCompanyId(companyId);
            run.setSourceManifestSha256("a".repeat(64)); run.setPreparedManifestSha256("b".repeat(64));
            run.setSourceOrganizationId("c".repeat(32)); run.setSourceEvidence("Freeze boundary test");
            run.setPostingDate(LocalDate.of(2026, 10, 8)); run.setExpectedRows(1);
            run.setExpectedQuantity(BigDecimal.ONE); run.setExpectedValue(BigDecimal.ZERO); entityManager.persist(run);
        });
    }

    @AfterEach
    void tearDown() {
        AccessScope.clearRequestedCompanyScope(); SecurityContextHolder.clearContext(); TenantContext.clear(); CompanyContext.clear();
    }

    @Test
    void globalAdminCannotEscapeFreezeByChangingTheCompanyHeaderOnUpdatesAndDeletes() {
        CompanyContext.setCompany(UUID.randomUUID());
        MaterialUpdateRequest material = new MaterialUpdateRequest(); material.setUnitOfMeasureId(unitId);
        blocked(() -> warehouses.update(warehouseId, new WarehouseUpdateRequest()));
        blocked(() -> warehouses.softDelete(warehouseId));
        blocked(() -> locations.update(locationId, new StorageLocationUpdateRequest()));
        blocked(() -> locations.softDelete(locationId));
        blocked(() -> bins.update(binId, new StorageBinUpdateRequest()));
        blocked(() -> bins.softDelete(binId));
        blocked(() -> materials.update(materialId, material));
        blocked(() -> materials.softDelete(materialId));
        blocked(() -> units.update(unitId, new UnitOfMeasureUpdateRequest(), Locale.ENGLISH));
        blocked(() -> units.softDelete(unitId));
        blocked(() -> groups.update(groupId, new MaterialGroupUpdateRequest()));
        blocked(() -> groups.softDelete(groupId));
        blocked(() -> batches.update(batchId, new StockBatchUpdateRequest()));
        blocked(() -> batches.softDelete(batchId));
        blocked(() -> balances.update(balanceId, new StockBalanceUpdateRequest()));
        blocked(() -> balances.softDelete(balanceId));
        blocked(() -> companies.update(companyId, new CompanyUpdateRequest()));
        blocked(() -> companies.softDelete(companyId));
        assertThat(warehouses.get(warehouseId).getId()).isEqualTo(warehouseId);
        assertThat(materials.get(materialId).getId()).isEqualTo(materialId);
    }

    @Test
    void restoresCannotReopenOrChangeFrozenRecords() {
        transactions.executeWithoutResult(status -> {
            for (var pair : List.of(new Object[]{Warehouse.class, warehouseId}, new Object[]{StorageLocation.class, locationId},
                new Object[]{StorageBin.class, binId}, new Object[]{Material.class, materialId}, new Object[]{UnitOfMeasure.class, unitId},
                new Object[]{StockBatch.class, batchId}, new Object[]{StockBalance.class, balanceId}, new Object[]{Company.class, companyId},
                new Object[]{MaterialGroup.class, groupId})) {
                ((BusinessEntity) entityManager.find((Class<?>) pair[0], pair[1])).softDelete();
            }
        });
        CompanyContext.setCompany(UUID.randomUUID());
        blocked(() -> warehouses.restore(warehouseId)); blocked(() -> locations.restore(locationId));
        blocked(() -> bins.restore(binId)); blocked(() -> materials.restore(materialId));
        blocked(() -> units.restore(unitId, Locale.ENGLISH)); blocked(() -> batches.restore(batchId));
        blocked(() -> balances.restore(balanceId)); blocked(() -> companies.restore(companyId)); blocked(() -> groups.restore(groupId));
    }

    @Test
    void lifecycleAndConditionalReadSideEffectsCannotChangeTheFrozenMaterial() {
        var request = new MaterialLifecycleRequest(null, "Lifecycle change during opening");
        blocked(() -> materials.markObsolete(materialId, request)); blocked(() -> materials.assignReplacement(materialId, request));
        blocked(() -> materials.prohibit(materialId, request)); blocked(() -> materials.reactivate(materialId, request));
        jdbc.update("update materials set shelf_life_days=1, created_at=? where id=?", LocalDateTime.now().minusDays(2), materialId);
        blocked(() -> materials.get(materialId));
        assertThat(jdbc.queryForObject("select status from materials where id=?", String.class, materialId)).isEqualTo("ACTIVE");
    }

    @Test
    void zeroManualBatchesBalancesAndDraftMovementsAreBlocked() {
        StockBatchCreateRequest batch = new StockBatchCreateRequest(); batch.setMaterialId(materialId); batch.setWarehouseId(warehouseId);
        batch.setName("Manual zero batch"); batch.setQuantity(BigDecimal.ZERO); batch.setUnitCost(BigDecimal.ZERO);
        StockBalanceCreateRequest balance = new StockBalanceCreateRequest(); balance.setMaterialId(materialId);
        balance.setWarehouseId(warehouseId); balance.setName("Manual zero balance"); balance.setQuantity(BigDecimal.ZERO);
        StockMovementCreateRequest movement = new StockMovementCreateRequest(); movement.setMaterialId(materialId);
        movement.setWarehouseId(warehouseId); movement.setQuantity(BigDecimal.ONE);
        blocked(() -> batches.create(batch)); blocked(() -> balances.create(balance)); blocked(() -> movements.create(movement));
    }

    @Test
    void unrelatedCompanyMetadataCanChangeEvenWhenHeaderPointsToFrozenCompany() {
        UUID otherWarehouse = transactions.execute(status -> {
            Company other = base(new Company(), "OTHER-COMPANY", CompanyContext.DEFAULT_COMPANY_ID); entityManager.persist(other);
            Warehouse warehouse = base(new Warehouse(), "OTHER-WAREHOUSE", other.getId()); entityManager.persist(warehouse);
            return warehouse.getId();
        });
        warehouses.softDelete(otherWarehouse);
        Boolean deleted = transactions.execute(status -> entityManager.find(Warehouse.class, otherWarehouse).isDeleted());
        assertThat(deleted).isTrue();
    }

    @Test
    void existingMutationIsAllowedAfterOpeningCompletion() {
        transactions.executeWithoutResult(status -> entityManager.find(ExactOpeningImport.class, importId).setStatus("COMPLETED"));
        warehouses.softDelete(warehouseId);
        Boolean deleted = transactions.execute(status -> entityManager.find(Warehouse.class, warehouseId).isDeleted());
        assertThat(deleted).isTrue();
    }

    @Test
    void otherMasterWriteEntrypointsCannotBypassTheFreeze() throws Exception {
        byte[] workbookBytes;
        try (var workbook = new XSSFWorkbook(); var stream = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("materials");
            sheet.createRow(0).createCell(0).setCellValue("code"); sheet.getRow(0).createCell(1).setCellValue("name");
            sheet.createRow(1).createCell(0).setCellValue("FREEZE-MATERIAL"); sheet.getRow(1).createCell(1).setCellValue("Renamed by Excel");
            workbook.write(stream); workbookBytes = stream.toByteArray();
        }
        var file = new MockMultipartFile("file", "materials.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbookBytes);
        blocked(() -> excelImports.importMaterials(file));
        blocked(() -> identification.generate("MATERIAL", materialId, "BARCODE"));
        blocked(() -> identification.generate("STOCK_BATCH", batchId, "BARCODE"));
        blocked(() -> identification.generate("STORAGE_LOCATION", locationId, "BARCODE"));
        UUID recommendationId = transactions.execute(status -> {
            var recommendation = base(new InventoryOptimizationRecommendation(), "FREEZE-EOQ", companyId);
            recommendation.setMaterialId(materialId); recommendation.setRecommendedSafetyStock(BigDecimal.TEN);
            entityManager.persist(recommendation); return recommendation.getId();
        });
        CompanyContext.setCompany(UUID.randomUUID());
        blocked(() -> optimization.applyToMaterial(recommendationId));
        assertThat(jdbc.queryForObject("select name from materials where id=?", String.class, materialId)).doesNotContain("Renamed");
        assertThat(jdbc.queryForObject("select barcode from materials where id=?", String.class, materialId)).isNull();
        assertThat(jdbc.queryForObject("select safety_stock from materials where id=?", BigDecimal.class, materialId)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(jdbc.queryForObject("select status from inventory_optimization_recommendations where id=?", String.class, recommendationId)).isEqualTo("ACTIVE");
    }

    @Test
    void inboundWmsStockOverwriteGuardsActualChosenCompanyBeforeAnyWrites() {
        // WMS chooses these records by source code. A different current-company header must not bypass their freeze.
        CompanyContext.setCompany(UUID.randomUUID());
        var row = objectMapper.createObjectNode().put("materialCode", "FREEZE-MATERIAL")
            .put("warehouseCode", "FREEZE-WAREHOUSE").put("quantity", "99").put("availableQuantity", "99").put("totalValue", "123");
        Object inboundTarget = AopTestUtils.getUltimateTargetObject(inbound);
        blocked(() -> transactions.executeWithoutResult(status -> {
            ReflectionTestUtils.invokeMethod(inboundTarget, "stockBalance", row);
        }));
        assertThat(jdbc.queryForObject("select count(*) from stock_balances where tenant_id=?", Integer.class, tenant)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select quantity from stock_balances where id=?", BigDecimal.class, balanceId)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(jdbc.queryForObject("select total_value from stock_balances where id=?", BigDecimal.class, balanceId)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void landedCostPostingCannotChangeBookValueDuringOpeningImport() {
        UUID documentId = transactions.execute(status -> {
            var document = base(new LandedCostAllocation(), "FREEZE-LANDED-COST", companyId);
            document.setStatus("DRAFT"); document.setImportDeliveryId(UUID.randomUUID());
            document.setDocumentDate(LocalDate.of(2026, 10, 8)); entityManager.persist(document);
            var line = base(new LandedCostAllocationLine(), "FREEZE-LANDED-LINE", companyId);
            line.setAllocationDocumentId(document.getId()); line.setMaterialId(materialId); line.setWarehouseId(warehouseId);
            line.setStorageLocationId(locationId); line.setStockBatchId(batchId); line.setQuantity(BigDecimal.ONE);
            line.setAllocatedAmount(BigDecimal.ONE); line.setAllocatedBaseAmount(BigDecimal.ONE); entityManager.persist(line);
            return document.getId();
        });
        CompanyContext.setCompany(UUID.randomUUID());
        blocked(() -> landedCosts.post(documentId));
        assertThat(jdbc.queryForObject("select status from landed_cost_allocations where id=?", String.class, documentId)).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("select total_value from stock_balances where id=?", BigDecimal.class, balanceId)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(jdbc.queryForObject("select count(*) from stock_cost_movements where tenant_id=?", Integer.class, tenant)).isZero();
    }

    private <T extends BusinessEntity> T base(T entity, String code, UUID company) {
        entity.setTenantId(tenant); entity.setCompanyId(company); entity.setCode(code); entity.setName(code); entity.setStatus("ACTIVE");
        return entity;
    }

    private void blocked(ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(BusinessException.class)
            .hasMessageContaining("blocked until its opening import is reconciled");
    }
}
