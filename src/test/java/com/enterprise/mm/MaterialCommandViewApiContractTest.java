package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.mm.error.BusinessException;
import org.springframework.security.access.AccessDeniedException;
import com.enterprise.mm.payload.MaterialCommandViewDtos.CommitmentMetrics;
import com.enterprise.mm.payload.MaterialCommandViewDtos.DecimalMetric;
import com.enterprise.mm.payload.MaterialCommandViewDtos.InventoryMetrics;
import com.enterprise.mm.payload.MaterialAttributeResponse;
import com.enterprise.mm.payload.MaterialCommandViewDtos.MaterialCommandViewResponse;
import com.enterprise.mm.payload.MaterialCommandViewDtos.MaterialHeader;
import com.enterprise.mm.payload.MaterialCommandViewDtos.MoneyMetric;
import com.enterprise.mm.payload.MaterialCommandViewDtos.OverviewPreviews;
import com.enterprise.mm.payload.MaterialCommandViewDtos.ScopeInfo;
import com.enterprise.mm.service.MaterialCommandViewService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MaterialCommandViewApiContractTest {
    private static final UUID MATERIAL_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID COMPANY_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");

    @Autowired MockMvc mockMvc;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;
    @MockBean MaterialCommandViewService commandViewService;

    @BeforeEach
    void stubPages() {
        when(commandViewService.availability(eq(MATERIAL_ID), any(), any())).thenReturn(Page.empty());
        when(commandViewService.demandSupply(eq(MATERIAL_ID), any(), any())).thenReturn(Page.empty());
        when(commandViewService.movements(eq(MATERIAL_ID), any(), any())).thenReturn(Page.empty());
        when(commandViewService.suppliers(eq(MATERIAL_ID), any(), any())).thenReturn(Page.empty());
        when(commandViewService.qualityCompatibility(eq(MATERIAL_ID), any(), any())).thenReturn(Page.empty());
        when(commandViewService.relationships(eq(MATERIAL_ID), any(), any())).thenReturn(Page.empty());
    }

    @Test
    void summaryReturnsExistingEnvelopeAndStringDecimals() throws Exception {
        when(commandViewService.summary(eq(MATERIAL_ID), any(Locale.class))).thenReturn(summary());

        mockMvc.perform(get("/api/materials/{id}/command-view", MATERIAL_ID)
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default")
                .header("X-Company-Id", COMPANY_ID)
                .header("X-Request-Id", "mat360-test-1")
                .header(HttpHeaders.ACCEPT_LANGUAGE, "uz"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Request-Id", "mat360-test-1"))
            .andExpect(jsonPath("$.data.material.id").value(MATERIAL_ID.toString()))
            .andExpect(jsonPath("$.data.scope.companyId").value(COMPANY_ID.toString()))
            .andExpect(jsonPath("$.data.material.unitOfMeasureShortName").value("pcs"))
            .andExpect(jsonPath("$.data.material.unitOfMeasureName").value("Piece"))
            .andExpect(jsonPath("$.data.material.specifications[0].key").value("Diameter"))
            .andExpect(jsonPath("$.data.material.specifications[0].value").value("12 mm"))
            .andExpect(jsonPath("$.data.inventory.onHand.value").value("12.3400"))
            .andExpect(jsonPath("$.data.inventory.onHand.value").isString());
    }

    @Test
    void invalidRequestIdIsReplacedWithSafeGeneratedValue() throws Exception {
        when(commandViewService.summary(eq(MATERIAL_ID), any(Locale.class))).thenReturn(summary());

        mockMvc.perform(get("/api/materials/{id}/command-view", MATERIAL_ID)
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default")
                .header("X-Request-Id", "invalid request id"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Request-Id", org.hamcrest.Matchers.matchesPattern(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")));
    }

    @ParameterizedTest
    @MethodSource("tabPaths")
    void everyTabEndpointIsReachableToAdmin(String path) throws Exception {
        mockMvc.perform(get(path, MATERIAL_ID)
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default"))
            .andExpect(status().isOk());
    }

    @Test
    void movementsRejectSizeAboveOneHundred() throws Exception {
        mockMvc.perform(get("/api/materials/{id}/command-view/movements", MATERIAL_ID)
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default")
                .param("size", "101"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void supplierEndpointIsForbiddenToWarehouseOperator() throws Exception {
        mockMvc.perform(get("/api/materials/{id}/command-view/suppliers", MATERIAL_ID)
                .with(user("warehouse").roles("WAREHOUSE_OPERATOR"))
                .header("X-Tenant-Id", "default"))
            .andExpect(status().isForbidden());
    }

    @Test
    void qualityEndpointIsReadableToBudgetController() throws Exception {
        mockMvc.perform(get("/api/materials/{id}/command-view/quality-compatibility", MATERIAL_ID)
                .with(user("budget").roles("BUDGET_CONTROLLER"))
                .header("X-Tenant-Id", "default"))
            .andExpect(status().isOk());
    }

    @Test
    void serviceDomainDenialUsesLocalizedMaterial360Code() throws Exception {
        when(commandViewService.qualityCompatibility(eq(MATERIAL_ID), any(), any()))
            .thenThrow(new AccessDeniedException("material360.domain.denied"));

        mockMvc.perform(get("/api/materials/{id}/command-view/quality-compatibility", MATERIAL_ID)
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default")
                .header(HttpHeaders.ACCEPT_LANGUAGE, "ru"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("material360.domain.denied"))
            .andExpect(jsonPath("$.message").value(
                "У вас нет прав для просмотра этого раздела Material 360"));
    }

    @Test
    void controllerPassesLockedSortMetadata() throws Exception {
        mockMvc.perform(get("/api/materials/{id}/command-view/availability", MATERIAL_ID)
                .with(user("admin").roles("ADMIN")).header("X-Tenant-Id", "default"))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/materials/{id}/command-view/demand-supply", MATERIAL_ID)
                .with(user("admin").roles("ADMIN")).header("X-Tenant-Id", "default"))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/materials/{id}/command-view/suppliers", MATERIAL_ID)
                .with(user("admin").roles("ADMIN")).header("X-Tenant-Id", "default"))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/materials/{id}/command-view/quality-compatibility", MATERIAL_ID)
                .with(user("admin").roles("ADMIN")).header("X-Tenant-Id", "default"))
            .andExpect(status().isOk());

        var availability = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        var demandSupply = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        var suppliers = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        var quality = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(commandViewService).availability(eq(MATERIAL_ID), any(), availability.capture());
        verify(commandViewService).demandSupply(eq(MATERIAL_ID), any(), demandSupply.capture());
        verify(commandViewService).suppliers(eq(MATERIAL_ID), any(), suppliers.capture());
        verify(commandViewService).qualityCompatibility(eq(MATERIAL_ID), any(), quality.capture());

        assertSort(availability.getValue(), "balanceAt", "id");
        assertSort(demandSupply.getValue(), "relevantDate", "id");
        assertSort(suppliers.getValue(), "evidenceDate", "id");
        assertSort(quality.getValue(), "effectiveFrom", "id");
    }

    @Test
    void serviceMaterialReturnsLocalizedGoodsOnlyError() throws Exception {
        when(commandViewService.summary(eq(MATERIAL_ID), any(Locale.class))).thenThrow(new BusinessException(
            "Material 360 is available only for goods materials", "material360.goods_only"));

        mockMvc.perform(get("/api/materials/{id}/command-view", MATERIAL_ID)
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default")
                .header(HttpHeaders.ACCEPT_LANGUAGE, "ru"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errorCode").value("material360.goods_only"))
            .andExpect(jsonPath("$.message").value("Material 360 доступен только для товарных материалов"));
    }

    @Test
    void everyCommandViewHandlerIsGetOnly() {
        var mappings = handlerMapping.getHandlerMethods().entrySet().stream()
            .filter(entry -> entry.getKey().getPatternValues().stream()
                .anyMatch(path -> path.contains("/command-view")))
            .toList();

        assertThat(mappings).hasSize(17);
        assertThat(mappings).allSatisfy(entry ->
            assertThat(entry.getKey().getMethodsCondition().getMethods()).containsOnly(RequestMethod.GET));
    }

    private static Stream<String> tabPaths() {
        return Stream.of(
            "/api/materials/{id}/command-view/availability",
            "/api/materials/{id}/command-view/demand-supply",
            "/api/materials/{id}/command-view/movements",
            "/api/materials/{id}/command-view/valuation",
            "/api/materials/{id}/command-view/suppliers",
            "/api/materials/{id}/command-view/quality-compatibility",
            "/api/materials/{id}/command-view/stock-health",
            "/api/materials/{id}/command-view/relationships");
    }

    private void assertSort(Pageable pageable, String primary, String tieBreaker) {
        assertThat(pageable.getSort().toList())
            .extracting(Sort.Order::getProperty, Sort.Order::getDirection)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple(primary, Sort.Direction.DESC),
                org.assertj.core.groups.Tuple.tuple(tieBreaker, Sort.Direction.DESC));
    }

    private MaterialCommandViewResponse summary() {
        MaterialHeader material = new MaterialHeader(MATERIAL_ID, "MAT-1", "Material 1", "ACTIVE",
            null, null, null, "ta", "pcs", "Piece", null, null, com.enterprise.mm.enums.ValuationMethod.MOVING_AVERAGE, null, null, null, null,
            List.of(new MaterialAttributeResponse(UUID.randomUUID(), "Diameter", "12 mm", 1)));
        InventoryMetrics inventory = new InventoryMetrics(
            DecimalMetric.available(new BigDecimal("12.3400")), DecimalMetric.available(BigDecimal.TEN),
            DecimalMetric.available(BigDecimal.ZERO), DecimalMetric.available(BigDecimal.ZERO),
            MoneyMetric.noData(), 1);
        return new MaterialCommandViewResponse(material, new ScopeInfo("default", COMPANY_ID, null),
            Instant.parse("2026-07-13T00:00:00Z"), Map.of(), Map.of(), inventory,
            new CommitmentMetrics(DecimalMetric.noData(), DecimalMetric.noData(), DecimalMetric.noData()),
            List.of(), Map.of(), new OverviewPreviews(List.of(), List.of(), List.of(), List.of(), List.of(), List.of()),
            List.of());
    }
}
