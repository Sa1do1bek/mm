package com.enterprise.mm.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.mm.enums.MaterialUnitOperationType;
import com.enterprise.mm.enums.MaterialUnitStatus;
import com.enterprise.mm.payload.MaterialUnitResponse;
import com.enterprise.mm.service.MaterialUnitQueryService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MaterialUnitControllerTest {
    @Test
    void searchesCompanyScopedMaterialUnitsForExactUnitSelection() throws Exception {
        MaterialUnitQueryService service = org.mockito.Mockito.mock(MaterialUnitQueryService.class);
        UUID id = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        UUID referenceId = UUID.randomUUID();
        when(service.search(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(new PageImpl<>(List.of(new MaterialUnitResponse(
                id,
                materialId,
                "SN-001",
                "UNIT-001",
                MaterialUnitStatus.IN_STOCK,
                warehouseId,
                "Main warehouse",
                null,
                "A1",
                null,
                null,
                batchId,
                "B001",
                java.time.LocalDateTime.parse("2026-09-21T10:15:30")
            )), PageRequest.of(0, 20), 1));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new MaterialUnitController(service))
            .setMessageConverters(new MappingJackson2HttpMessageConverter())
            .build();

        mockMvc.perform(get("/api/material-units")
                .param("materialId", materialId.toString())
                .param("warehouseId", warehouseId.toString())
                .param("status", MaterialUnitStatus.IN_STOCK.name())
                .param("search", "SN-001")
                .param("sourceReferenceType", "IssueRequest")
                .param("sourceReferenceId", referenceId.toString())
                .param("sourceOperationType", MaterialUnitOperationType.ISSUE.name())
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[0].id").value(id.toString()))
            .andExpect(jsonPath("$.data.content[0].manufacturerSerialNumber").value("SN-001"))
            .andExpect(jsonPath("$.data.content[0].internalUnitNumber").value("UNIT-001"))
            .andExpect(jsonPath("$.data.content[0].unitStatus").value("IN_STOCK"))
            .andExpect(jsonPath("$.data.content[0].stockBatchId").value(batchId.toString()))
            .andExpect(jsonPath("$.data.content[0].batchNumber").value("B001"));

        verify(service).search(org.mockito.ArgumentMatchers.argThat(request ->
            materialId.equals(request.materialId())
                && warehouseId.equals(request.warehouseId())
                && request.status().contains(MaterialUnitStatus.IN_STOCK)
                && "SN-001".equals(request.search())
                && "IssueRequest".equals(request.sourceReferenceType())
                && referenceId.equals(request.sourceReferenceId())
                && request.sourceOperationType() == MaterialUnitOperationType.ISSUE
        ), org.mockito.ArgumentMatchers.any());
    }
}
