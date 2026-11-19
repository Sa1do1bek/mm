package com.enterprise.mm;

import static org.assertj.core.api.Assertions.*;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.enums.RoleName;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.*;
import com.enterprise.mm.repository.*;
import com.enterprise.mm.service.*;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "app.seed.enabled=false")
@ActiveProfiles("test")
@Transactional
class WarehouseAssignmentIsolationTest {
    @Autowired CompanyRepository companies;
    @Autowired WarehouseRepository warehouses;
    @Autowired EmployeeRepository employees;
    @Autowired UserRepository users;
    @Autowired StorageLocationRepository locations;
    @Autowired MaterialRepository materials;
    @Autowired StockBalanceRepository stockBalances;
    @Autowired StockRiskAlertService stockRiskService;
    @Autowired com.enterprise.mm.warehouse.execution.service.WarehouseExecutionWorkspaceService executionService;
    @Autowired UnitOfMeasureRepository units;
    @Autowired SupplierRepository suppliers;
    @Autowired QualityInspectionRepository inspections;
    @Autowired LaboratoryTestRepository laboratoryTests;
    @Autowired QualityNonconformanceRepository nonconformances;
    @Autowired QualityClaimRepository claims;
    @Autowired WarehouseService warehouseService;
    @Autowired StorageLocationService locationService;
    @Autowired LaboratoryTestService laboratoryService;
    @Autowired QualityNonconformanceService nonconformanceService;
    @Autowired QualityClaimService claimService;
    private Warehouse own;
    private Warehouse other;

    @BeforeEach void fixtures() {
        TenantContext.setTenant("warehouse-assignment-isolation");
        Company company = companies.saveAndFlush(base(new Company(), "Company"));
        CompanyContext.setCompany(company.getId());
        own = warehouses.saveAndFlush(base(new Warehouse(), "Assigned warehouse"));
        other = warehouses.saveAndFlush(base(new Warehouse(), "Other warehouse"));
        Employee employee = base(new Employee(), "Assigned warehouse head");
        employee.setWarehouse(own); employee = employees.saveAndFlush(employee);
        User user = base(new User(), "Warehouse test user");
        user.setUsername("warehouse-isolation-user"); user.setPasswordHash("unused-test-password-hash");
        user.setEmployee(employee); user.setRoles(Set.of(RoleName.WAREHOUSE_MANAGER));
        users.saveAndFlush(user);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                user.getUsername(), "unused", List.of(new SimpleGrantedAuthority("ROLE_WAREHOUSE_MANAGER"))));
    }

    @AfterEach void clear() { SecurityContextHolder.clearContext(); TenantContext.clear(); CompanyContext.clear(); }

    @Test void warehouseListCountsAndDirectReadsRespectTheAssignment() {
        var page = warehouseService.search(WarehouseSearchRequest.empty(), Pageable.ofSize(1));
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).extracting(WarehouseResponse::getId).containsExactly(own.getId());
        assertThatThrownBy(() -> warehouseService.get(other.getId())).isInstanceOf(NotFoundException.class);
    }

    @Test void anotherWarehousesZoneCannotBeRenamedBeforeAResponseScopeCheck() {
        StorageLocation zone = base(new StorageLocation(), "Other warehouse zone");
        zone.setWarehouseId(other.getId()); zone = locations.saveAndFlush(zone);
        StorageLocationUpdateRequest request = new StorageLocationUpdateRequest();
        request.setName("Unauthorized rename"); request.setWarehouseId(other.getId());
        var id = zone.getId();
        String originalName = zone.getName();
        assertThatThrownBy(() -> locationService.update(id, request)).isInstanceOf(NotFoundException.class);
        assertThat(locations.findById(id).orElseThrow().getName()).isEqualTo(originalName);
    }

    @Test void laboratoryDiscrepancyAndClaimListsCountOnlyTheAssignedWarehouse() {
        UnitOfMeasure unit = base(new UnitOfMeasure(), "Each");
        unit.setMeasurementNameShort("EA"); unit = units.saveAndFlush(unit);
        Material material = base(new Material(), "Fixture material");
        material.setUnitOfMeasureId(unit.getId()); material = materials.saveAndFlush(material);
        Supplier supplier = suppliers.saveAndFlush(base(new Supplier(), "Fixture supplier"));
        for (Warehouse warehouse : List.of(own, other)) {
            QualityInspection inspection = base(new QualityInspection(), warehouse.getName() + " inspection");
            inspection.setWarehouseId(warehouse.getId()); inspection.setMaterialId(material.getId());
            inspection = inspections.saveAndFlush(inspection);
            LaboratoryTest lab = base(new LaboratoryTest(), warehouse.getName() + " test");
            lab.setQualityInspectionId(inspection.getId()); lab.setMaterialId(material.getId()); lab.setTestType("CHEMICAL");
            laboratoryTests.saveAndFlush(lab);
            QualityNonconformance discrepancy = base(new QualityNonconformance(), warehouse.getName() + " discrepancy");
            discrepancy.setQualityInspectionId(inspection.getId()); discrepancy.setMaterialId(material.getId());
            nonconformances.saveAndFlush(discrepancy);
            QualityClaim claim = base(new QualityClaim(), warehouse.getName() + " claim");
            claim.setSupplierId(supplier.getId()); claim.setQualityInspectionId(inspection.getId());
            claims.saveAndFlush(claim);
        }
        assertThat(laboratoryService.search(LaboratoryTestSearchRequest.empty(), Pageable.ofSize(1)).getTotalElements()).isEqualTo(1);
        assertThat(nonconformanceService.search(QualityNonconformanceSearchRequest.empty(), Pageable.ofSize(1)).getTotalElements()).isEqualTo(1);
        assertThat(claimService.search(QualityClaimSearchRequest.empty(), Pageable.ofSize(1)).getTotalElements()).isEqualTo(1);
    }

    @Test void warehouseRiskCalculationsUseOnlyAssignedStock() {
        UnitOfMeasure unit = units.saveAndFlush(base(new UnitOfMeasure(), "Risk unit"));
        for (Warehouse warehouse : List.of(own, other)) {
            Material material = base(new Material(), warehouse.getName() + " risk material");
            material.setUnitOfMeasureId(unit.getId()); material = materials.saveAndFlush(material);
            StockBalance balance = base(new StockBalance(), "Risk balance");
            balance.setWarehouse(warehouse); balance.setMaterial(material);
            balance.setQuantity(java.math.BigDecimal.TEN);
            balance.setAvailableQuantity(java.math.BigDecimal.TEN);
            stockBalances.saveAndFlush(balance);
        }
        assertThat(stockRiskService.search(90, true, null, null, null, null, null, Pageable.ofSize(50)).getContent())
                .hasSize(1)
                .allSatisfy(row -> assertThat(row.warehouseId()).isEqualTo(own.getId()));
        assertThatThrownBy(() -> stockRiskService.search(90, true, null, null, other.getId(), null, null, Pageable.ofSize(50)))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test void executionOverviewCarriesTheAssignedWarehouseIntoEveryQueue() {
        assertThat(executionService.overview(own.getId()).queues()).hasSize(4)
                .allSatisfy(queue -> assertThat(queue.appliedFilters().warehouseId()).isEqualTo(own.getId()));
        assertThatThrownBy(() -> executionService.overview(other.getId())).isInstanceOf(NotFoundException.class);
    }

    private <T extends BusinessEntity> T base(T entity, String name) {
        entity.setName(name); entity.setCode(java.util.UUID.randomUUID().toString());
        entity.setStatus("ACTIVE"); return entity;
    }
}
