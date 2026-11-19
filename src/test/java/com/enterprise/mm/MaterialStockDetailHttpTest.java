package com.enterprise.mm;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.enterprise.mm.enums.ValuationMethod;
import com.enterprise.mm.payload.MaterialCommandViewDtos.DecimalMetric;
import com.enterprise.mm.payload.MaterialCommandViewDtos.MoneyMetric;
import com.enterprise.mm.payload.MaterialStockDetailDtos.*;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.service.MaterialStockDetailService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MaterialStockDetailHttpTest {
    @Autowired MockMvc mvc;
    @MockBean MaterialStockDetailService service;
    final UUID material = UUID.randomUUID(), warehouse = UUID.randomUUID();

    @Test void accountantCanReadDetailWithStringDecimalsAndNoStore() throws Exception {
        when(service.detail(eq(material), any(), eq(0), eq(25))).thenReturn(detail());
        mvc.perform(get(path("stock-detail")).with(user("accountant").roles("ACCOUNTANT"))
                .param("warehouseId", warehouse.toString()))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.data.material.id").value(material.toString()))
            .andExpect(jsonPath("$.data.summary.onHand.value").value("20.0000001"))
            .andExpect(jsonPath("$.data.summary.totalBookValue.currencyCode").value("UZS"))
            .andExpect(jsonPath("$.data.quantityBasis").value("COST_LAYER_ALLOCATION"));
        verify(service).detail(material, new Filter(warehouse, null, null, true), 0, 25);
    }

    @Test void originsAliasPassesDateBatchAndPaginationWithoutLosingScope() throws Exception {
        UUID batch = UUID.randomUUID();
        mvc.perform(get(path("receipt-origins")).with(user("keeper").roles("WAREHOUSE_KEEPER"))
                .param("warehouseId", warehouse.toString()).param("stockBatchId", batch.toString())
                .param("asOf", "2026-10-12").param("positiveOnly", "false").param("page", "2").param("size", "10"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        verify(service).detail(material, new Filter(warehouse, batch, LocalDate.parse("2026-10-12"), false), 2, 10);
    }

    @Test void inventoryEndpointDeniesProcurementOnlyRoleAtHttpBoundary() throws Exception {
        mvc.perform(get(path("stock-detail")).with(user("procurement").roles("PROCUREMENT_SPECIALIST")))
            .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test void movementRoutePassesMaterialMovementAndWarehouse() throws Exception {
        UUID movement = UUID.randomUUID();
        mvc.perform(get(path("movements/" + movement + "/allocations"))
                .with(user("keeper").roles("WAREHOUSE_KEEPER")).param("warehouseId", warehouse.toString()))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        verify(service).movement(material, movement, warehouse, 0, 25);
    }

    @Test void malformedDateIsBadRequest() throws Exception {
        mvc.perform(get(path("stock-detail")).with(user("admin").roles("ADMIN")).param("asOf", "invalid"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    private String path(String suffix) { return "/api/materials/" + material + "/command-view/" + suffix; }

    private Detail detail() {
        var quantity = DecimalMetric.available(new BigDecimal("20.0000001"));
        var zero = DecimalMetric.available(BigDecimal.ZERO);
        var value = MoneyMetric.available(new BigDecimal("2200.0000"), "UZS");
        var summary = new Summary(quantity, quantity, zero, zero, value, quantity, zero, zero, value,
            MoneyMetric.available(BigDecimal.ZERO, "UZS"));
        return new Detail(new MaterialInfo(material, "M1", "Material", null), warehouse, null,
            LocalDate.parse("2026-10-26"), Instant.parse("2026-10-26T07:00:00Z"), false, DataStatus.AVAILABLE,
            new Permissions(true, true, true), ValuationMethod.FIFO, QuantityBasis.COST_LAYER_ALLOCATION,
            summary, PageResponse.from(List.of()), List.of());
    }
}
