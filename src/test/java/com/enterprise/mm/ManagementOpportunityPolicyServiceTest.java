package com.enterprise.mm;

import static com.enterprise.mm.enums.ManagementOpportunityType.EXCESS_CARRYING_COST;
import static com.enterprise.mm.enums.ManagementOpportunityType.PROCUREMENT_PRICE_VARIANCE;
import static com.enterprise.mm.enums.ManagementOpportunityType.SUPPLIER_DELAY_EXPOSURE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.MaterialGroup;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.enums.ManagementOpportunityType;
import com.enterprise.mm.payload.ManagementOpportunityPolicyCreateRequest;
import com.enterprise.mm.payload.ManagementOpportunityPolicyUpdateRequest;
import com.enterprise.mm.repository.MaterialGroupRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.service.ManagementOpportunityPolicyService;
import com.enterprise.mm.service.ManagementOpportunityScope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@WithMockUser(username = "policy-tester", roles = "ADMIN")
class ManagementOpportunityPolicyServiceTest {
    @Autowired
    private ManagementOpportunityPolicyService service;
    @Autowired
    private WarehouseRepository warehouseRepository;
    @Autowired
    private MaterialGroupRepository materialGroupRepository;
    @Autowired
    private SupplierRepository supplierRepository;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void resolveSelectsMostSpecificActivePolicy() {
        withTenant("tenant-mo-policy-specific", () -> {
            UUID warehouseId = warehouse("MO-WH-SPECIFIC").getId();
            UUID materialGroupId = materialGroup("MO-MG-SPECIFIC").getId();

            var general = service.create(policy("MO-GENERAL", PROCUREMENT_PRICE_VARIANCE, null, null, null));
            service.activate(general.getId());

            var specific = service.create(policy("MO-SPECIFIC", PROCUREMENT_PRICE_VARIANCE, warehouseId, materialGroupId, null));
            service.activate(specific.getId());

            var resolved = service.resolveActivePolicy(
                PROCUREMENT_PRICE_VARIANCE,
                new ManagementOpportunityScope(warehouseId, materialGroupId, null),
                LocalDate.now()
            );

            assertThat(resolved.getId()).isEqualTo(specific.getId());
        });
    }

    @Test
    void inactivePolicyIsNotResolved() {
        withTenant("tenant-mo-policy-inactive", () -> {
            service.create(policy("MO-INACTIVE", PROCUREMENT_PRICE_VARIANCE, null, null, null));

            assertThatThrownBy(() -> service.resolveActivePolicy(
                PROCUREMENT_PRICE_VARIANCE,
                new ManagementOpportunityScope(null, null, null),
                LocalDate.now()
            ))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("No active management opportunity policy");
        });
    }

    @Test
    void searchSupportsNullAndCaseInsensitiveTermFilter() {
        withTenant("tenant-mo-policy-search", () -> {
            service.create(policy("MO-ALPHA", PROCUREMENT_PRICE_VARIANCE, null, null, null));
            service.create(policy("MO-BETA", PROCUREMENT_PRICE_VARIANCE, null, null, null));

            var unfiltered = service.search(null, null, null, null, false, PageRequest.of(0, 10));
            var filtered = service.search("alpha", null, null, null, false, PageRequest.of(0, 10));

            assertThat(unfiltered.getContent()).hasSize(2);
            assertThat(filtered.getContent())
                .extracting("code")
                .containsExactly("MO-ALPHA");
        });
    }

    @Test
    void activePolicyCannotBeUpdated() {
        withTenant("tenant-mo-policy-locked", () -> {
            var created = service.create(policy("MO-LOCKED", PROCUREMENT_PRICE_VARIANCE, null, null, null));
            service.activate(created.getId());

            ManagementOpportunityPolicyUpdateRequest update = update("MO-LOCKED-UPDATED", PROCUREMENT_PRICE_VARIANCE);

            assertThatThrownBy(() -> service.update(created.getId(), update))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cannot be modified");
        });
    }

    @Test
    void duplicateActiveScopeAndDateRangeIsRejected() {
        withTenant("tenant-mo-policy-overlap", () -> {
            UUID supplierId = supplier("MO-SUP-OVERLAP").getId();
            service.activate(service.create(policy("MO-FIRST", PROCUREMENT_PRICE_VARIANCE, null, null, supplierId)).getId());
            var duplicate = service.create(policy("MO-DUPLICATE", PROCUREMENT_PRICE_VARIANCE, null, null, supplierId));

            assertThatThrownBy(() -> service.activate(duplicate.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("overlaps");
        });
    }

    @Test
    void invalidDateRangeAndRatioAreRejected() {
        withTenant("tenant-mo-policy-validation", () -> {
            ManagementOpportunityPolicyCreateRequest inverted = policy("MO-BAD-DATE", PROCUREMENT_PRICE_VARIANCE, null, null, null);
            inverted.setEffectiveFrom(LocalDate.now());
            inverted.setEffectiveTo(LocalDate.now().minusDays(1));

            assertThatThrownBy(() -> service.create(inverted))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Effective to date");

            ManagementOpportunityPolicyCreateRequest badRatio = policy("MO-BAD-RATE", EXCESS_CARRYING_COST, null, null, null);
            badRatio.setAnnualCarryingCostRate(BigDecimal.valueOf(2));

            assertThatThrownBy(() -> service.create(badRatio))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Annual carrying cost rate");
        });
    }

    @Test
    void typeSpecificFieldsAreRequired() {
        withTenant("tenant-mo-policy-type-validation", () -> {
            ManagementOpportunityPolicyCreateRequest carrying = policy("MO-CARRYING-MISSING", EXCESS_CARRYING_COST, null, null, null);
            carrying.setAnnualCarryingCostRate(null);

            assertThatThrownBy(() -> service.create(carrying))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Annual carrying cost rate is required");

            ManagementOpportunityPolicyCreateRequest delay = policy("MO-DELAY-MISSING", SUPPLIER_DELAY_EXPOSURE, null, null, null);
            delay.setDailyImpactRate(null);

            assertThatThrownBy(() -> service.create(delay))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Daily impact rate is required");
        });
    }

    @Test
    void crossTenantLookupReturnsNotFound() {
        withTenant("tenant-mo-policy-owner", () -> {
            var created = service.create(policy("MO-TENANT-OWNER", PROCUREMENT_PRICE_VARIANCE, null, null, null));

            TenantContext.setTenant("tenant-mo-policy-other");

            assertThatThrownBy(() -> service.get(created.getId()))
                .isInstanceOf(NotFoundException.class);
        });
    }

    private ManagementOpportunityPolicyCreateRequest policy(
        String code,
        ManagementOpportunityType type,
        UUID warehouseId,
        UUID materialGroupId,
        UUID supplierId
    ) {
        ManagementOpportunityPolicyCreateRequest request = new ManagementOpportunityPolicyCreateRequest();
        request.setCode(code);
        request.setName(code);
        request.setOpportunityType(type);
        request.setWarehouseId(warehouseId);
        request.setMaterialGroupId(materialGroupId);
        request.setSupplierId(supplierId);
        request.setAnnualCarryingCostRate(new BigDecimal("0.120000"));
        request.setDailyImpactRate(new BigDecimal("100.0000"));
        request.setAttributableShare(new BigDecimal("0.500000"));
        request.setCurrencyCode("UZS");
        request.setEffectiveFrom(LocalDate.now().minusDays(1));
        request.setEffectiveTo(LocalDate.now().plusDays(30));
        return request;
    }

    private ManagementOpportunityPolicyUpdateRequest update(String code, ManagementOpportunityType type) {
        ManagementOpportunityPolicyUpdateRequest request = new ManagementOpportunityPolicyUpdateRequest();
        request.setCode(code);
        request.setName(code);
        request.setOpportunityType(type);
        request.setEffectiveFrom(LocalDate.now().minusDays(1));
        request.setEffectiveTo(LocalDate.now().plusDays(30));
        return request;
    }

    private Warehouse warehouse(String code) {
        Warehouse warehouse = new Warehouse();
        warehouse.setCode(code);
        warehouse.setName(code);
        warehouse.setStatus("ACTIVE");
        return warehouseRepository.saveAndFlush(warehouse);
    }

    private MaterialGroup materialGroup(String code) {
        MaterialGroup materialGroup = new MaterialGroup();
        materialGroup.setCode(code);
        materialGroup.setName(code);
        materialGroup.setStatus("ACTIVE");
        return materialGroupRepository.saveAndFlush(materialGroup);
    }

    private Supplier supplier(String code) {
        Supplier supplier = new Supplier();
        supplier.setCode(code);
        supplier.setName(code);
        supplier.setStatus("ACTIVE");
        return supplierRepository.saveAndFlush(supplier);
    }

    private void withTenant(String tenantId, Runnable action) {
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        action.run();
    }
}
