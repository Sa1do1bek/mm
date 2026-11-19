package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.IlliquidStockPolicyCreateRequest;
import com.enterprise.mm.payload.IlliquidStockPolicyResolvePreviewRequest;
import com.enterprise.mm.payload.IlliquidStockPolicyUpdateRequest;
import com.enterprise.mm.service.IlliquidStockPolicyService;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@WithMockUser(username = "policy-tester", roles = "ADMIN")
class IlliquidStockPolicyServiceTest {
    @Autowired
    private IlliquidStockPolicyService service;

    @Test
    void resolvePreviewReturnsMostSpecificActivePolicy() {
        withTenant("tenant-illiquid-preview", () -> {
            UUID warehouseId = UUID.randomUUID();

            service.activate(service.create(policy("ILLQ-GENERAL", null, null, "CHEMICAL", null, 180)).getId());
            service.activate(service.create(policy("ILLQ-SPECIFIC", "LEGAL-A", warehouseId, "CHEMICAL", "RAW", 120)).getId());

            IlliquidStockPolicyResolvePreviewRequest request = new IlliquidStockPolicyResolvePreviewRequest();
            request.setAsOfDate(LocalDate.now());
            request.setLegalEntityCode("LEGAL-A");
            request.setWarehouseId(warehouseId);
            request.setMaterialCategory("CHEMICAL");
            request.setMaterialType("RAW");

            var resolved = service.resolvePreview(request);

            assertThat(resolved.isMatched()).isTrue();
            assertThat(resolved.getPolicy()).isNotNull();
            assertThat(resolved.getPolicy().getCode()).isEqualTo("ILLQ-SPECIFIC");
            assertThat(resolved.getPolicy().getAgingDays()).isEqualTo(120);
        });
    }

    @Test
    void activatingOverlappingPolicyIsBlocked() {
        withTenant("tenant-illiquid-overlap", () -> {
            service.activate(service.create(policy("ILLQ-BASE", "LEGAL-A", null, "CHEMICAL", null, 180)).getId());
            var overlapping = service.create(policy("ILLQ-OVERLAP", null, null, "CHEMICAL", null, 150));

            assertThatThrownBy(() -> service.activate(overlapping.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("overlaps");
        });
    }

    @Test
    void activePolicyMustBeDeactivatedBeforeUpdate() {
        withTenant("tenant-illiquid-update-lock", () -> {
            var created = service.create(policy("ILLQ-LOCKED", null, null, "SPARE", null, 90));
            service.activate(created.getId());

            var update = updatePolicy("ILLQ-LOCKED", null, null, "SPARE", "CRITICAL", 120);

            assertThatThrownBy(() -> service.update(created.getId(), update))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cannot be modified");
        });
    }

    private IlliquidStockPolicyCreateRequest policy(
        String code,
        String legalEntityCode,
        UUID warehouseId,
        String materialCategory,
        String materialType,
        int agingDays
    ) {
        IlliquidStockPolicyCreateRequest request = new IlliquidStockPolicyCreateRequest();
        request.setCode(code);
        request.setName(code);
        request.setLegalEntityCode(legalEntityCode);
        request.setWarehouseId(warehouseId);
        request.setMaterialCategory(materialCategory);
        request.setMaterialType(materialType);
        request.setAgingDays(agingDays);
        request.setExcessDays(30);
        request.setEffectiveFrom(LocalDate.now().minusDays(1));
        request.setEffectiveTo(LocalDate.now().plusDays(30));
        return request;
    }

    private IlliquidStockPolicyUpdateRequest updatePolicy(
        String code,
        String legalEntityCode,
        UUID warehouseId,
        String materialCategory,
        String materialType,
        int agingDays
    ) {
        IlliquidStockPolicyUpdateRequest request = new IlliquidStockPolicyUpdateRequest();
        request.setCode(code);
        request.setName(code);
        request.setLegalEntityCode(legalEntityCode);
        request.setWarehouseId(warehouseId);
        request.setMaterialCategory(materialCategory);
        request.setMaterialType(materialType);
        request.setAgingDays(agingDays);
        request.setExcessDays(30);
        request.setEffectiveFrom(LocalDate.now().minusDays(1));
        request.setEffectiveTo(LocalDate.now().plusDays(30));
        return request;
    }

    private void withTenant(String tenantId, Runnable action) {
        String previousTenant = TenantContext.currentTenant();
        try {
            TenantContext.setTenant(tenantId);
            action.run();
        } finally {
            TenantContext.setTenant(previousTenant);
        }
    }
}
