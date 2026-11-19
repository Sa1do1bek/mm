package com.enterprise.mm.inventory.availability;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.inventory.availability.payload.InventoryAvailabilityDtos.InventoryAvailabilityFilter;
import com.enterprise.mm.inventory.availability.repository.InventoryAvailabilityQueryRepository;
import com.enterprise.mm.security.AccessScope;
import com.enterprise.mm.security.CompanyScope;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "app.seed.enabled=false")
@ActiveProfiles("test")
@Transactional
class InventoryAvailabilityCurrentCompanyScopeIntegrationTest {
    private static final InventoryAvailabilityFilter ALL =
        new InventoryAvailabilityFilter(null, "scope-material", null, null,
                null, null, null, null, null);

    @Autowired InventoryAvailabilityQueryRepository repository;
    @Autowired EntityManager entityManager;

    @AfterEach
    void tearDown() {
        AccessScope.clearRequestedCompanyScope();
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void globalAdminIsTenantWideUntilCurrentCompanyIsExplicitlyRequested() {
        UUID currentCompany = UUID.randomUUID();
        UUID otherCompany = UUID.randomUUID();
        TenantContext.setTenant("tenant-inventory-admin-scope");
        CompanyContext.setCompany(currentCompany);
        inventory("SCOPE-MATERIAL-CURRENT", currentCompany, "10");
        inventory("SCOPE-MATERIAL-OTHER", otherCompany, "20");
        entityManager.flush();
        authenticate("ADMIN", true);

        assertAvailability("SCOPE-MATERIAL-CURRENT", "SCOPE-MATERIAL-OTHER");

        AccessScope.setRequestedCompanyScope(CompanyScope.CURRENT_COMPANY);

        assertAvailability("SCOPE-MATERIAL-CURRENT");
    }

    @Test
    void nonGlobalUserIsAlwaysLimitedToCurrentCompany() {
        UUID currentCompany = UUID.randomUUID();
        UUID otherCompany = UUID.randomUUID();
        TenantContext.setTenant("tenant-inventory-user-scope");
        CompanyContext.setCompany(currentCompany);
        inventory("SCOPE-MATERIAL-CURRENT", currentCompany, "10");
        inventory("SCOPE-MATERIAL-OTHER", otherCompany, "20");
        entityManager.flush();
        authenticate("MM_MANAGER", false);

        assertAvailability("SCOPE-MATERIAL-CURRENT");
    }

    private void assertAvailability(String... expectedCodes) {
        var page = repository.search(ALL, PageRequest.of(0, 20));

        assertThat(page.getContent())
            .extracting(row -> row.materialCode())
            .containsExactlyInAnyOrder(expectedCodes);
        assertThat(repository.count(ALL)).isEqualTo(expectedCodes.length);
        assertThat(repository.overview(ALL).materialCount()).isEqualTo(expectedCodes.length);
    }

    private void inventory(String code, UUID companyId, String quantity) {
        Warehouse warehouse = base(new Warehouse(), "WH-" + code, companyId);
        entityManager.persist(warehouse);
        Material material = base(new Material(), code, companyId);
        material.setSafetyStock(BigDecimal.ZERO);
        entityManager.persist(material);
        StockBalance balance = base(new StockBalance(), "BAL-" + code, companyId);
        balance.setMaterialId(material.getId());
        balance.setWarehouseId(warehouse.getId());
        balance.setQuantity(new BigDecimal(quantity));
        balance.setAvailableQuantity(new BigDecimal(quantity));
        balance.setReservedQuantity(BigDecimal.ZERO);
        balance.setQuarantineQuantity(BigDecimal.ZERO);
        balance.setTotalValue(new BigDecimal(quantity).multiply(BigDecimal.TEN));
        balance.setBalanceAt(LocalDateTime.now());
        entityManager.persist(balance);
    }

    private <T extends BusinessEntity> T base(T entity, String code, UUID companyId) {
        entity.setTenantId(TenantContext.currentTenant());
        entity.setCompanyId(companyId);
        entity.setCode(code);
        entity.setName(code);
        entity.setStatus("ACTIVE");
        return entity;
    }

    private void authenticate(String role, boolean globalAdmin) {
        var authentication = new UsernamePasswordAuthenticationToken(
            role.toLowerCase(java.util.Locale.ROOT),
            null,
            List.of(new SimpleGrantedAuthority("ROLE_" + role))
        );
        if (globalAdmin) {
            authentication.setDetails(new AccessScope.RequestScope(true));
        }
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
