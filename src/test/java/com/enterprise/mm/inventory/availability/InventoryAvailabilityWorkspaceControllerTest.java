package com.enterprise.mm.inventory.availability;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.mm.inventory.availability.controller.InventoryAvailabilityWorkspaceController;
import com.enterprise.mm.inventory.availability.service.InventoryAvailabilityWorkspaceService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class InventoryAvailabilityWorkspaceControllerTest {
    @Test
    void positionsEndpointEchoesTheAppliedQueueContract() throws Exception {
        InventoryAvailabilityWorkspaceService service = mock(InventoryAvailabilityWorkspaceService.class);
        when(service.positions(any(), any()))
            .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 25), 0));
        MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new InventoryAvailabilityWorkspaceController(service))
            .setMessageConverters(new MappingJackson2HttpMessageConverter())
            .build();

        mvc.perform(get("/api/workspaces/inventory-availability/positions")
                .param("queue", "BELOW_SAFETY")
                .param("search", "bearing"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content").isArray());
    }

    @Test
    void positionsEndpointBindsAllNewFilterParameters() throws Exception {
        InventoryAvailabilityWorkspaceService service = mock(InventoryAvailabilityWorkspaceService.class);
        when(service.positions(any(), any()))
            .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 25), 0));
        MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new InventoryAvailabilityWorkspaceController(service))
            .setMessageConverters(new MappingJackson2HttpMessageConverter())
            .build();

        java.util.UUID warehouseId1 = java.util.UUID.randomUUID();
        java.util.UUID warehouseId2 = java.util.UUID.randomUUID();
        java.util.UUID materialId = java.util.UUID.randomUUID();

        mvc.perform(get("/api/workspaces/inventory-availability/positions")
                .param("materialCode", "MAT-100")
                .param("materialName", "Ball Bearing")
                .param("materialId", materialId.toString())
                .param("warehouseIds", warehouseId1.toString(), warehouseId2.toString())
                .param("asOfDate", "2026-10-20")
                .param("dateFrom", "2026-10-01")
                .param("dateTo", "2026-10-31"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content").isArray());

        org.mockito.ArgumentCaptor<com.enterprise.mm.inventory.availability.payload.InventoryAvailabilityDtos.InventoryAvailabilityFilter> captor =
            org.mockito.ArgumentCaptor.forClass(com.enterprise.mm.inventory.availability.payload.InventoryAvailabilityDtos.InventoryAvailabilityFilter.class);
        org.mockito.Mockito.verify(service).positions(captor.capture(), any());

        var filter = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(filter.materialCode()).isEqualTo("MAT-100");
        org.assertj.core.api.Assertions.assertThat(filter.materialName()).isEqualTo("Ball Bearing");
        org.assertj.core.api.Assertions.assertThat(filter.materialId()).isEqualTo(materialId);
        org.assertj.core.api.Assertions.assertThat(filter.warehouseIds()).containsExactly(warehouseId1, warehouseId2);
        org.assertj.core.api.Assertions.assertThat(filter.asOfDate()).isEqualTo(java.time.LocalDate.of(2026, 10, 20));
        org.assertj.core.api.Assertions.assertThat(filter.dateFrom()).isEqualTo(java.time.LocalDate.of(2026, 10, 1));
        org.assertj.core.api.Assertions.assertThat(filter.dateTo()).isEqualTo(java.time.LocalDate.of(2026, 10, 31));
    }
}
