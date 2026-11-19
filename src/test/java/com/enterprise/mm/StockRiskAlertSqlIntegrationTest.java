package com.enterprise.mm;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.enums.RoleName;
import com.enterprise.mm.enums.StockRiskSeverity;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.payload.StockRiskAlertResponse;
import com.enterprise.mm.repository.*;
import com.enterprise.mm.service.StockRiskAlertService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the real native SQL of StockRiskAlertQueryRepository and checks that the business rules
 * (severity, days until minimum stock, consumption window) and the SQL-side filter / sort / paging behave as
 * the previous in-memory implementation did.
 *
 * Data set (all in the user's assigned warehouse, 90 day window, ISSUE + POSTED movements count as consumption):
 *   A-CRIT : min 10, available 10, no usage            -> CRITICAL
 *   B-HIGH : available 50,  900 issued (10/day)        -> 5 days left     -> HIGH
 *   C-MED  : available 200, 900 issued (10/day)        -> 20 days left    -> MEDIUM
 *   D-LOW  : reorder point 100, available 80           -> LOW
 *   E-OK   : available 1000                            -> OK
 *   F-OLD  : available 1000, 900 issued 120 days ago   -> OK (outside window)
 *   G-IGN  : available 1000, RECEIPT / DRAFT / CANCELLED issue movements -> OK (not consumption)
 */
@SpringBootTest(properties = "app.seed.enabled=false")
@ActiveProfiles("test")
@Transactional
class StockRiskAlertSqlIntegrationTest {
    @Autowired CompanyRepository companies;
    @Autowired WarehouseRepository warehouses;
    @Autowired EmployeeRepository employees;
    @Autowired UserRepository users;
    @Autowired UnitOfMeasureRepository units;
    @Autowired MaterialRepository materials;
    @Autowired StockBalanceRepository stockBalances;
    @Autowired StockMovementRepository stockMovements;
    @Autowired StockRiskAlertService service;

    private Warehouse own;
    private UnitOfMeasure unit;

    @BeforeEach
    void fixtures() {
        TenantContext.setTenant("stock-risk-sql-it");
        Company company = companies.saveAndFlush(base(new Company(), "Company"));
        CompanyContext.setCompany(company.getId());
        own = warehouses.saveAndFlush(base(new Warehouse(), "Assigned warehouse"));
        Employee employee = base(new Employee(), "Assigned warehouse head");
        employee.setWarehouse(own);
        employee = employees.saveAndFlush(employee);
        User user = base(new User(), "Risk test user");
        user.setUsername("stock-risk-sql-user");
        user.setPasswordHash("unused-test-password-hash");
        user.setEmployee(employee);
        user.setRoles(Set.of(RoleName.WAREHOUSE_MANAGER));
        users.saveAndFlush(user);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            user.getUsername(), "unused", List.of(new SimpleGrantedAuthority("ROLE_WAREHOUSE_MANAGER"))));

        unit = units.saveAndFlush(base(new UnitOfMeasure(), "Risk unit"));
        LocalDateTime recent = LocalDateTime.now().minusDays(1);

        material("A-CRIT", 10, 0, 10);

        Material high = material("B-HIGH", 0, 0, 50);
        movement(high, MovementType.ISSUE, "POSTED", 900, recent);

        Material medium = material("C-MED", 0, 0, 200);
        movement(medium, MovementType.ISSUE, "POSTED", 900, recent);

        material("D-LOW", 0, 100, 80);
        material("E-OK", 0, 0, 1000);

        Material old = material("F-OLD", 0, 0, 1000);
        movement(old, MovementType.ISSUE, "POSTED", 900, LocalDateTime.now().minusDays(120));

        Material ignored = material("G-IGN", 0, 0, 1000);
        movement(ignored, MovementType.RECEIPT, "POSTED", 900, recent);
        movement(ignored, MovementType.ISSUE, "DRAFT", 900, recent);
        movement(ignored, MovementType.ISSUE, "CANCELLED", 900, recent);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void defaultOrderIsSeverityThenSoonestThenCode() {
        Page<StockRiskAlertResponse> page = run(true, null, null, PageRequest.of(0, 50));

        assertThat(codes(page)).containsExactly("A-CRIT", "B-HIGH", "C-MED", "D-LOW", "E-OK", "F-OLD", "G-IGN");
        assertThat(page.getTotalElements()).isEqualTo(7);
    }

    @Test
    void healthyMaterialsAreHiddenUnlessRequested() {
        Page<StockRiskAlertResponse> page = run(false, null, null, PageRequest.of(0, 50));

        assertThat(codes(page)).containsExactly("A-CRIT", "B-HIGH", "C-MED", "D-LOW");
        assertThat(page.getTotalElements()).isEqualTo(4);
        assertThat(page.getContent()).allSatisfy(row -> assertThat(row.risk()).isTrue());
    }

    @Test
    void severityAndDaysUntilMinimumStockFollowTheBusinessRules() {
        Page<StockRiskAlertResponse> page = run(true, null, null, PageRequest.of(0, 50));

        StockRiskAlertResponse critical = byCode(page, "A-CRIT");
        assertThat(critical.severity()).isEqualTo(StockRiskSeverity.CRITICAL);
        assertThat(critical.daysUntilMinStock()).isNull();
        assertThat(critical.warehouseId()).isEqualTo(own.getId());

        StockRiskAlertResponse high = byCode(page, "B-HIGH");
        assertThat(high.severity()).isEqualTo(StockRiskSeverity.HIGH);
        assertThat(high.availableQuantity()).isEqualByComparingTo("50");
        assertThat(high.consumptionQuantity()).isEqualByComparingTo("900");
        assertThat(high.averageDailyUsage()).isEqualByComparingTo("10");
        assertThat(high.daysUntilMinStock()).isEqualTo(5);
        assertThat(high.daysUntilOutOfStock()).isEqualTo(5);
        assertThat(high.periodDays()).isEqualTo(90);

        StockRiskAlertResponse medium = byCode(page, "C-MED");
        assertThat(medium.severity()).isEqualTo(StockRiskSeverity.MEDIUM);
        assertThat(medium.daysUntilMinStock()).isEqualTo(20);
        assertThat(medium.daysUntilOutOfStock()).isEqualTo(20);

        StockRiskAlertResponse low = byCode(page, "D-LOW");
        assertThat(low.severity()).isEqualTo(StockRiskSeverity.LOW);
        assertThat(low.message()).isEqualTo("Available stock is at or below reorder point.");

        assertThat(byCode(page, "E-OK").severity()).isEqualTo(StockRiskSeverity.OK);
    }

    @Test
    void onlyPostedIssuesInsideThePeriodCountAsConsumption() {
        Page<StockRiskAlertResponse> page = run(true, null, null, PageRequest.of(0, 50));

        for (String code : List.of("E-OK", "F-OLD", "G-IGN")) {
            StockRiskAlertResponse row = byCode(page, code);
            assertThat(row.consumptionQuantity()).as(code).isEqualByComparingTo("0");
            assertThat(row.severity()).as(code).isEqualTo(StockRiskSeverity.OK);
            assertThat(row.daysUntilMinStock()).as(code).isNull();
        }
    }

    @Test
    void paginationReturnsTheRequestedSliceAndTheRealTotal() {
        Page<StockRiskAlertResponse> first = run(true, null, null, PageRequest.of(0, 3));
        Page<StockRiskAlertResponse> second = run(true, null, null, PageRequest.of(1, 3));
        Page<StockRiskAlertResponse> third = run(true, null, null, PageRequest.of(2, 3));

        assertThat(codes(first)).containsExactly("A-CRIT", "B-HIGH", "C-MED");
        assertThat(codes(second)).containsExactly("D-LOW", "E-OK", "F-OLD");
        assertThat(codes(third)).containsExactly("G-IGN");
        assertThat(first.getTotalElements()).isEqualTo(7);
        assertThat(first.getTotalPages()).isEqualTo(3);
        assertThat(third.getTotalElements()).isEqualTo(7);
    }

    @Test
    void pageBeyondLastRetainsTheFilteredTotal() {
        Page<StockRiskAlertResponse> page = run(false, List.of("HIGH", "MEDIUM"), null, PageRequest.of(10, 3));
        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getTotalPages()).isEqualTo(1);
    }

    @Test
    void largePageOffsetDoesNotOverflowToANegativeSqlOffset() {
        Page<StockRiskAlertResponse> page = run(true, null, null, PageRequest.of(Integer.MAX_VALUE, 20));
        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isEqualTo(7);
    }

    @Test
    void severityFilterKeepsOnlyRequestedSeverities() {
        Page<StockRiskAlertResponse> page = run(false, List.of("HIGH", "MEDIUM"), null, PageRequest.of(0, 50));

        assertThat(codes(page)).containsExactly("B-HIGH", "C-MED");
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    void healthyFilterAndSeverityFilterAreBothApplied() {
        Page<StockRiskAlertResponse> page = run(false, List.of("OK"), null, PageRequest.of(0, 50));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
    }

    @Test
    void searchMatchesCodeOrNameCaseInsensitivelyAndTreatsWildcardsLiterally() {
        assertThat(codes(run(true, null, "c-me", PageRequest.of(0, 50)))).containsExactly("C-MED");
        assertThat(codes(run(true, null, "fixture", PageRequest.of(0, 50)))).isEmpty();
        // '%' and '_' must not behave as LIKE wildcards
        assertThat(run(true, null, "%", PageRequest.of(0, 50)).getContent()).isEmpty();
        assertThat(run(true, null, "_-OK", PageRequest.of(0, 50)).getContent()).isEmpty();
    }

    @Test
    void sortingIsDoneInSqlForWhitelistedProperties() {
        Page<StockRiskAlertResponse> byCodeDesc = run(true, null, null,
            PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "materialCode")));
        assertThat(codes(byCodeDesc)).containsExactly("G-IGN", "F-OLD", "E-OK", "D-LOW", "C-MED", "B-HIGH", "A-CRIT");

        Page<StockRiskAlertResponse> byDays = run(true, null, null,
            PageRequest.of(0, 50, Sort.by(Sort.Direction.ASC, "daysUntilMinStock")));
        assertThat(codes(byDays).subList(0, 2)).containsExactly("B-HIGH", "C-MED");

        Page<StockRiskAlertResponse> byAvailable = run(true, null, null,
            PageRequest.of(0, 50, Sort.by(Sort.Direction.ASC, "availableQuantity")));
        assertThat(codes(byAvailable).subList(0, 4)).containsExactly("A-CRIT", "B-HIGH", "D-LOW", "C-MED");
    }

    @Test
    void unknownSortIsRejectedAndUnknownSeverityMatchesNoRows() {
        assertThatThrownBy(() -> run(true, null, null, PageRequest.of(0, 10, Sort.by("passwordHash"))))
            .isInstanceOf(BadRequestException.class);
        assertThat(run(true, List.of("BOGUS", "high", " "), null, PageRequest.of(0, 10)).getContent()).isEmpty();
    }

    @Test
    void directMaterialLookupIncludesInactiveAndRejectsMissingIds() {
        SecurityContextHolder.clearContext(); // no warehouse role: company-wide lookup
        Material inactive = material("INACTIVE-MAT", 0, 0, 100);
        inactive.setStatus("INACTIVE");
        materials.saveAndFlush(inactive);
        var page = service.search(90, true, inactive.getId(), null, null, null, null, PageRequest.of(0, 20));
        assertThat(codes(page)).containsExactly("INACTIVE-MAT");
        assertThatThrownBy(() -> service.search(90, true, UUID.randomUUID(), null, null, null, null, PageRequest.of(0, 20)))
                .isInstanceOf(com.enterprise.mm.error.NotFoundException.class);
        assertThat(service.search(90, true, null, null, null, null, null, PageRequest.of(0, 50)).getContent())
                .noneMatch(row -> row.materialId().equals(inactive.getId()));
    }

    @Test
    void unitLabelUsesOriginalSanitizationAndLanguageFallback() {
        unit.setMeasurementNameShort("kg [1C-import]");
        unit.setCode("1C-UOM-123");
        units.saveAndFlush(unit);
        assertThat(run(true, null, null, PageRequest.of(0, 20)).getContent())
                .allSatisfy(row -> assertThat(row.unitOfMeasureShortName()).isEqualTo("kg"));
    }

    @Test
    void searchRetainsLeadingSpacesAsInOriginal() {
        assertThat(run(true, null, "  C-MED", PageRequest.of(0, 20)).getContent()).isEmpty();
        assertThat(codes(run(true, null, "C-MED", PageRequest.of(0, 20)))).containsExactly("C-MED");
    }

    @Test
    void companyListExcludesUnusedCatalogButDirectLookupStillWorks() {
        SecurityContextHolder.clearContext();
        Material unused = catalogOnly("UNUSED", 0);
        var page = run(true, null, null, PageRequest.of(0, 50));
        assertThat(codes(page)).doesNotContain("UNUSED");
        assertThat(page.getTotalElements()).isEqualTo(7);
        var direct = service.search(90, true, unused.getId(), null, null, null, null, PageRequest.of(0, 20));
        assertThat(codes(direct)).containsExactly("UNUSED");
    }

    @Test
    void configuredShortageAndDepletedStockRemainCritical() {
        SecurityContextHolder.clearContext();
        catalogOnly("CONFIGURED", 10);
        material("DEPLETED", 0, 0, 0);
        Material historical = catalogOnly("HISTORICAL", 0);
        movement(historical, MovementType.ISSUE, "POSTED", 10, LocalDateTime.now().minusDays(120));
        var page = run(false, null, null, PageRequest.of(0, 50));
        for (String code : List.of("CONFIGURED", "DEPLETED", "HISTORICAL")) {
            assertThat(byCode(page, code).severity()).isEqualTo(StockRiskSeverity.CRITICAL);
        }
    }

    private Material catalogOnly(String code, int min) {
        Material material = base(new Material(), "Material " + code);
        material.setCode(code);
        material.setUnitOfMeasureId(unit.getId());
        material.setMinStock(BigDecimal.valueOf(min));
        return materials.saveAndFlush(material);
    }

    private Page<StockRiskAlertResponse> run(
        boolean includeHealthy, List<String> status, String search, Pageable pageable
    ) {
        return service.search(90, includeHealthy, null, null, null, search, status, pageable);
    }

    private List<String> codes(Page<StockRiskAlertResponse> page) {
        return page.getContent().stream().map(StockRiskAlertResponse::materialCode).toList();
    }

    private StockRiskAlertResponse byCode(Page<StockRiskAlertResponse> page, String code) {
        return page.getContent().stream()
            .filter(row -> code.equals(row.materialCode()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing row " + code));
    }

    private Material material(String code, int minStock, int reorderPoint, int available) {
        Material material = base(new Material(), "Material " + code);
        material.setCode(code);
        material.setUnitOfMeasureId(unit.getId());
        material.setMinStock(BigDecimal.valueOf(minStock));
        material.setReorderPoint(BigDecimal.valueOf(reorderPoint));
        material = materials.saveAndFlush(material);

        StockBalance balance = base(new StockBalance(), "Balance " + code);
        balance.setWarehouse(own);
        balance.setMaterial(material);
        balance.setQuantity(BigDecimal.valueOf(available));
        balance.setAvailableQuantity(BigDecimal.valueOf(available));
        stockBalances.saveAndFlush(balance);
        return material;
    }

    private void movement(Material material, MovementType type, String status, int quantity, LocalDateTime postedAt) {
        StockMovement movement = base(new StockMovement(), "Movement " + material.getCode());
        movement.setMaterialId(material.getId());
        movement.setWarehouseId(own.getId());
        movement.setMovementType(type);
        movement.setQuantity(BigDecimal.valueOf(quantity));
        movement.setStatus(status);
        movement.setPostedAt(postedAt);
        movement.setRequestDate(postedAt);
        movement.setMovementDate(postedAt.toLocalDate());
        stockMovements.saveAndFlush(movement);
    }

    private <T extends BusinessEntity> T base(T entity, String name) {
        entity.setName(name);
        entity.setCode(UUID.randomUUID().toString());
        entity.setStatus("ACTIVE");
        return entity;
    }
}
