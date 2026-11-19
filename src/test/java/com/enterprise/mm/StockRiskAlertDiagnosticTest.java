package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Company;
import com.enterprise.mm.entity.Employee;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.enums.RoleName;
import com.enterprise.mm.enums.StockMovementStatus;
import com.enterprise.mm.repository.CompanyRepository;
import com.enterprise.mm.repository.EmployeeRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.StockRiskAlertQueryRepository;
import com.enterprise.mm.repository.UnitOfMeasureRepository;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.service.StockRiskAlertService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Regression for bound UUID/tenant parameters being lost in nested CTEs by H2 2.2.224.
 * Exercises both repository and warehouse-scoped service against persisted fixtures.
 */
@SpringBootTest(properties = "app.seed.enabled=false")
@ActiveProfiles("test")
@Transactional
class StockRiskAlertDiagnosticTest {
    @Autowired CompanyRepository companies;
    @Autowired WarehouseRepository warehouses;
    @Autowired EmployeeRepository employees;
    @Autowired UserRepository users;
    @Autowired UnitOfMeasureRepository units;
    @Autowired MaterialRepository materials;
    @Autowired StockBalanceRepository stockBalances;
    @Autowired StockRiskAlertQueryRepository queryRepository;
    @Autowired StockRiskAlertService service;

    private Warehouse own;
    private Material material;

    @BeforeEach
    void fixtures() {
        TenantContext.setTenant("stock-risk-diagnostic");
        Company company = companies.saveAndFlush(base(new Company(), "Company"));
        CompanyContext.setCompany(company.getId());
        own = warehouses.saveAndFlush(base(new Warehouse(), "Assigned warehouse"));
        Employee employee = base(new Employee(), "Head");
        employee.setWarehouse(own);
        employee = employees.saveAndFlush(employee);
        User user = base(new User(), "Diagnostic user");
        user.setUsername("stock-risk-diagnostic-user");
        user.setPasswordHash("unused-test-password-hash");
        user.setEmployee(employee);
        user.setRoles(Set.of(RoleName.WAREHOUSE_MANAGER));
        users.saveAndFlush(user);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                user.getUsername(), "unused", List.of(new SimpleGrantedAuthority("ROLE_WAREHOUSE_MANAGER"))));

        UnitOfMeasure unit = units.saveAndFlush(base(new UnitOfMeasure(), "Unit"));
        material = base(new Material(), "Diagnostic material");
        material.setUnitOfMeasureId(unit.getId());
        material = materials.saveAndFlush(material);
        StockBalance balance = base(new StockBalance(), "Balance");
        balance.setWarehouse(own);
        balance.setMaterial(material);
        balance.setQuantity(BigDecimal.TEN);
        balance.setAvailableQuantity(BigDecimal.TEN);
        stockBalances.saveAndFlush(balance);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void boundCteParametersPreserveTheMaterialAndWarehouseScope() {
        var criteria = new StockRiskAlertQueryRepository.Criteria(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), own.getId(),
                null, null, null, true, 90, LocalDateTime.now().minusDays(90),
                StockMovementStatus.effectiveStatusNames(), List.of("ISSUE"), 7, 30);
        var result = queryRepository.findPage(criteria, PageRequest.of(0, 50));
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.materialId()).isEqualTo(material.getId());
            assertThat(row.available()).isEqualByComparingTo(BigDecimal.TEN);
        });

        var page = service.search(90, true, null, null, null, null, null, PageRequest.of(0, 50));
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).singleElement().satisfies(row -> {
            assertThat(row.materialId()).isEqualTo(material.getId());
            assertThat(row.companyId()).isEqualTo(CompanyContext.currentCompany());
            assertThat(row.warehouseId()).isEqualTo(own.getId());
            assertThat(row.availableQuantity()).isEqualByComparingTo(BigDecimal.TEN);
        });
    }

    private <T extends BusinessEntity> T base(T entity, String name) {
        entity.setName(name);
        entity.setCode(UUID.randomUUID().toString());
        entity.setStatus("ACTIVE");
        return entity;
    }
}