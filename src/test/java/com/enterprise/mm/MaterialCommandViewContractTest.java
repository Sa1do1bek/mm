package com.enterprise.mm;

import static com.enterprise.mm.payload.MaterialCommandViewDtos.DecimalMetric;
import static com.enterprise.mm.payload.MaterialCommandViewDtos.LinkTarget;
import static com.enterprise.mm.payload.MaterialCommandViewDtos.SectionKey;
import static com.enterprise.mm.payload.MaterialCommandViewDtos.TabKey;
import static com.enterprise.mm.payload.MaterialCommandViewDtos.Visibility;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.MXIK;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.service.MaterialCommandViewAccessPolicy;
import com.enterprise.mm.service.MaterialCommandViewScopeGuard;
import com.enterprise.mm.service.RoleDataScopeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class MaterialCommandViewContractTest {
    private static final String TENANT_ID = "tenant-a";
    private static final UUID COMPANY_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MaterialRepository materialRepository;
    private RoleDataScopeService roleDataScopeService;
    private MaterialCommandViewScopeGuard scopeGuard;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(TENANT_ID);
        CompanyContext.setCompany(COMPANY_ID);
        materialRepository = mock(MaterialRepository.class);
        roleDataScopeService = mock(RoleDataScopeService.class);
        scopeGuard = new MaterialCommandViewScopeGuard(materialRepository, roleDataScopeService);
        when(roleDataScopeService.currentScope()).thenReturn(RoleDataScopeService.DataScope.unrestricted());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void decimalsSerializeAsStringsAndNoDataDoesNotBecomeZero() throws Exception {
        assertThat(objectMapper.writeValueAsString(DecimalMetric.available(new BigDecimal("12.3400"))))
            .isEqualTo("{\"value\":\"12.3400\",\"status\":\"AVAILABLE\"}");
        assertThat(objectMapper.writeValueAsString(DecimalMetric.noData()))
            .isEqualTo("{\"value\":null,\"status\":\"NO_DATA\"}");
    }

    @Test
    void arbitraryModuleKeysAreRejected() {
        assertThatThrownBy(() -> LinkTarget.of("https://bad.example", UUID.randomUUID(), Map.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void warehouseOperatorCanReadInventoryButNotSupplierEvidence() {
        authenticate("WAREHOUSE_OPERATOR");
        MaterialCommandViewAccessPolicy policy = new MaterialCommandViewAccessPolicy();

        assertThat(policy.visibility(TabKey.AVAILABILITY)).isEqualTo(Visibility.READ);
        assertThat(policy.sectionVisibility(SectionKey.INVENTORY)).isEqualTo(Visibility.READ);
        assertThat(policy.visibility(TabKey.SUPPLIERS)).isEqualTo(Visibility.DENIED);
        assertThat(policy.sectionVisibility(SectionKey.SUPPLIERS)).isEqualTo(Visibility.DENIED);
    }

    @Test
    void budgetControllerCanReadQualityEvidence() {
        authenticate("BUDGET_CONTROLLER");
        MaterialCommandViewAccessPolicy policy = new MaterialCommandViewAccessPolicy();

        assertThat(policy.visibility(TabKey.QUALITY_COMPATIBILITY)).isEqualTo(Visibility.READ);
        assertThat(policy.sectionVisibility(SectionKey.QUALITY)).isEqualTo(Visibility.READ);
    }

    @Test
    void nullMxikIsGoodsButServiceMxikIsRejected() {
        Material legacyGood = material(COMPANY_ID, null);
        when(materialRepository.findMaterial360ByTenantAndId(TENANT_ID, legacyGood.getId()))
            .thenReturn(Optional.of(legacyGood));

        assertThat(scopeGuard.requireGoods(legacyGood.getId()).materialId()).isEqualTo(legacyGood.getId());

        MXIK serviceMxik = new MXIK();
        serviceMxik.setType("SERVICE");
        Material service = material(COMPANY_ID, serviceMxik);
        when(materialRepository.findMaterial360ByTenantAndId(TENANT_ID, service.getId()))
            .thenReturn(Optional.of(service));

        assertThatThrownBy(() -> scopeGuard.requireGoods(service.getId()))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getMessageKey())
            .isEqualTo("material360.goods_only");
    }

    @Test
    void otherCompanyMaterialIsNotFound() {
        Material hidden = material(UUID.randomUUID(), null);
        when(materialRepository.findMaterial360ByTenantAndId(TENANT_ID, hidden.getId()))
            .thenReturn(Optional.of(hidden));

        assertThatThrownBy(() -> scopeGuard.requireGoods(hidden.getId()))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void warehouseScopeIsInjectedAndCannotBeOverridden() {
        UUID assignedWarehouse = UUID.randomUUID();
        when(roleDataScopeService.currentScope()).thenReturn(new RoleDataScopeService.DataScope(
            false, false, true, java.util.Set.of(), java.util.Set.of(), java.util.Set.of(), null, assignedWarehouse));

        assertThat(scopeGuard.resolveWarehouse(null)).isEqualTo(assignedWarehouse);
        assertThat(scopeGuard.resolveWarehouse(assignedWarehouse)).isEqualTo(assignedWarehouse);
        assertThatThrownBy(() -> scopeGuard.resolveWarehouse(UUID.randomUUID()))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void warehouseScopeWithoutAssignmentIsDenied() {
        when(roleDataScopeService.currentScope()).thenReturn(RoleDataScopeService.DataScope.restricted(false, true));

        assertThatThrownBy(() -> scopeGuard.resolveWarehouse(null))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void warehouseScopeDenialHappensBeforeMaterialLookup() {
        when(roleDataScopeService.currentScope()).thenReturn(RoleDataScopeService.DataScope.restricted(false, true));

        assertThatThrownBy(() -> scopeGuard.requireGoods(UUID.randomUUID(), null))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessage("material360.domain.denied");
        verifyNoInteractions(materialRepository);
    }

    @Test
    void commandViewClockUsesConfiguredBusinessTimeZone() {
        Clock clock = new com.enterprise.mm.config.MaterialCommandViewConfig()
            .materialCommandViewClock();

        assertThat(clock.getZone()).isEqualTo(ZoneId.of("Asia/Tashkent"));
    }

    private Material material(UUID companyId, MXIK mxik) {
        Material material = new Material();
        material.setId(UUID.randomUUID());
        material.setTenantId(TENANT_ID);
        material.setCompanyId(companyId);
        material.setMxik(mxik);
        return material;
    }

    private void authenticate(String... roles) {
        List<SimpleGrantedAuthority> authorities = java.util.Arrays.stream(roles)
            .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
            .toList();
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("user", "n/a", authorities));
    }
}
