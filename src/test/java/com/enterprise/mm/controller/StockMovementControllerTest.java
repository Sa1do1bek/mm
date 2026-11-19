package com.enterprise.mm.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.mockito.Mockito.*;
import org.springframework.data.domain.Sort;

import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.payload.StockMovementResponse;
import com.enterprise.mm.payload.StockMovementSearchRequest;
import com.enterprise.mm.service.StockMovementService;
import com.enterprise.mm.warehouse.history.ImportedStockMovementQuery;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class StockMovementControllerTest {

    private StockMovementService service;
    private ImportedStockMovementQuery imported;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(StockMovementService.class);
        imported = mock(ImportedStockMovementQuery.class);
        StockMovementController controller = new StockMovementController(service, imported);
        ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc = MockMvcBuilders
            .standaloneSetup(controller)
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
            .build();
    }

    @Test
    void search_withAllExtendedFilters_bindsParametersCorrectly() throws Exception {
        when(imported.available()).thenReturn(false);
        when(service.search(any(), any(), any()))
            .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        UUID warehouseId = UUID.randomUUID();
        UUID wh1 = UUID.randomUUID();
        UUID wh2 = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        UUID mat1 = UUID.randomUUID();
        UUID mat2 = UUID.randomUUID();

        mvc.perform(get("/api/stock/movements")
                .param("search", "bearing")
                .param("warehouseId", warehouseId.toString())
                .param("warehouseIds", wh1.toString(), wh2.toString())
                .param("materialId", materialId.toString())
                .param("materialIds", mat1.toString(), mat2.toString())
                .param("materialCode", "MAT-100")
                .param("materialName", "Ball Bearing")
                .param("movementDate", "2026-10-20")
                .param("dateFrom", "2026-10-01")
                .param("dateTo", "2026-10-31")
                .param("movementType", "RECEIPT")
                .param("movementTypes", "RECEIPT", "ISSUE")
                .param("page", "0")
                .param("size", "20"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content").isArray());

        ArgumentCaptor<StockMovementSearchRequest> captor = ArgumentCaptor.forClass(StockMovementSearchRequest.class);
        verify(service).search(captor.capture(), any(), any());

        StockMovementSearchRequest captured = captor.getValue();
        assertThat(captured.search()).isEqualTo("bearing");
        assertThat(captured.warehouseId()).isEqualTo(warehouseId);
        assertThat(captured.warehouseIds()).containsExactly(wh1, wh2);
        assertThat(captured.materialId()).isEqualTo(materialId);
        assertThat(captured.materialIds()).containsExactly(mat1, mat2);
        assertThat(captured.materialCode()).isEqualTo("MAT-100");
        assertThat(captured.materialName()).isEqualTo("Ball Bearing");
        assertThat(captured.movementDate()).isEqualTo(LocalDate.of(2026, 10, 20));
        assertThat(captured.dateFrom()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(captured.dateTo()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(captured.movementType()).isEqualTo(MovementType.RECEIPT);
        assertThat(captured.movementTypes()).containsExactly(MovementType.RECEIPT, MovementType.ISSUE);
    }

    @Test
    void search_withOperationTypeAlias_bindsToEffectiveMovementType() throws Exception {
        when(imported.available()).thenReturn(false);
        when(service.search(any(), any(), any()))
            .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mvc.perform(get("/api/stock/movements")
                .param("operationType", "ISSUE"))
            .andExpect(status().isOk());

        ArgumentCaptor<StockMovementSearchRequest> captor = ArgumentCaptor.forClass(StockMovementSearchRequest.class);
        verify(service).search(captor.capture(), any(), any());

        StockMovementSearchRequest captured = captor.getValue();
        assertThat(captured.movementType()).isEqualTo(MovementType.ISSUE);
    }

    @Test
    void search_returnsEnrichedFieldsInResponseJson() throws Exception {
        when(imported.available()).thenReturn(false);

        StockMovementResponse item = new StockMovementResponse();
        item.setId(UUID.randomUUID());
        item.setCode("SMV-2026-001");
        item.setMovementType(MovementType.RECEIPT);
        item.setMovementDate(LocalDate.of(2026, 10, 20));
        item.setSourceWarehouseName("Central Supplier Hub");
        item.setSourceLocation("Navoiy Kimyo Zavod");
        item.setSourceType("SUPPLIER");
        item.setDestinationWarehouseName("Warehouse A");
        item.setDestinationLocation("Warehouse A");
        item.setDestinationType("WAREHOUSE");
        item.setCounterparty("Navoiy Kimyo Zavod");

        when(service.search(any(), any(), any()))
            .thenReturn(new PageImpl<>(List.of(item), PageRequest.of(0, 20), 1));

        mvc.perform(get("/api/stock/movements")
                .param("page", "0")
                .param("size", "20"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[0].code").value("SMV-2026-001"))
            .andExpect(jsonPath("$.data.content[0].movementDate").value("2026-10-20"))
            .andExpect(jsonPath("$.data.content[0].sourceWarehouseName").value("Central Supplier Hub"))
            .andExpect(jsonPath("$.data.content[0].sourceLocation").value("Navoiy Kimyo Zavod"))
            .andExpect(jsonPath("$.data.content[0].sourceType").value("SUPPLIER"))
            .andExpect(jsonPath("$.data.content[0].destinationWarehouseName").value("Warehouse A"))
            .andExpect(jsonPath("$.data.content[0].destinationLocation").value("Warehouse A"))
            .andExpect(jsonPath("$.data.content[0].destinationType").value("WAREHOUSE"))
            .andExpect(jsonPath("$.data.content[0].counterparty").value("Navoiy Kimyo Zavod"));
    }
    @Test void exportEndpointBindsEveryFilterAndRepeatedSortAndReturnsWorkbook() throws Exception {
        StockMovementService mockExporter = mock(StockMovementService.class);
        when(mockExporter.exportMovements(any(),any(),anyString())).thenReturn(new byte[]{1,2});
        var mvc = MockMvcBuilders.standaloneSetup(new StockMovementController(mockExporter, mock(ImportedStockMovementQuery.class))).build();
        UUID warehouse = UUID.randomUUID(), material = UUID.randomUUID(), reference = UUID.randomUUID();
        mvc.perform(get("/api/stock/movements/export")
            .param("search","copper").param("status","POSTED","DRAFT")
            .param("warehouseId",warehouse.toString()).param("warehouseIds",warehouse.toString())
            .param("materialId",material.toString()).param("materialIds",material.toString())
            .param("materialCode","CU").param("materialName","Mis")
            .param("dateFrom","2026-10-01").param("dateTo","2026-10-22").param("movementDate","2026-10-10")
            .param("inventoryId",reference.toString()).param("referenceId",reference.toString()).param("referenceType","ONE_C_HISTORY")
            .param("includeDeleted","true").param("operationType","RECEIPT").param("movementTypes","RECEIPT","ISSUE")
            .param("sort","quantity,desc","code,asc").param("language","uz").param("page","3").param("size","10"))
            .andExpect(status().isOk()).andExpect(header().string("Content-Language","uz"))
            .andExpect(header().string("Cache-Control","no-store"))
            .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        var filters = ArgumentCaptor.forClass(StockMovementSearchRequest.class);
        var sorts = ArgumentCaptor.forClass(Sort.class);
        verify(mockExporter).exportMovements(filters.capture(),sorts.capture(),eq("uz"));
        var f = filters.getValue();
        assertThat(f.search()).isEqualTo("copper"); assertThat(f.status()).containsExactly("POSTED","DRAFT");
        assertThat(f.warehouseId()).isEqualTo(warehouse); assertThat(f.warehouseIds()).containsExactly(warehouse);
        assertThat(f.materialId()).isEqualTo(material); assertThat(f.materialIds()).containsExactly(material);
        assertThat(f.materialCode()).isEqualTo("CU"); assertThat(f.materialName()).isEqualTo("Mis");
        assertThat(f.dateFrom()).isEqualTo(LocalDate.of(2026,10,1)); assertThat(f.dateTo()).isEqualTo(LocalDate.of(2026,10,22));
        assertThat(f.movementDate()).isEqualTo(LocalDate.of(2026,10,10));
        assertThat(f.inventoryId()).isEqualTo(reference); assertThat(f.referenceId()).isEqualTo(reference);
        assertThat(f.referenceType()).isEqualTo("ONE_C_HISTORY"); assertThat(f.includeDeleted()).isTrue();
        assertThat(f.movementType()).isEqualTo(MovementType.RECEIPT);
        assertThat(f.movementTypes()).containsExactly(MovementType.RECEIPT,MovementType.ISSUE);
        assertThat(sorts.getValue().toString()).isEqualTo("quantity: DESC,code: ASC,id: ASC");
    }

    @Test void headerLocaleUsedWhenLanguageNotSpecified() throws Exception {
        var mockExporter = mock(StockMovementService.class);
        when(mockExporter.exportMovements(any(),any(),anyString())).thenReturn(new byte[0]);
        var mvc = MockMvcBuilders.standaloneSetup(new StockMovementController(mockExporter, mock(ImportedStockMovementQuery.class)))
            .setLocaleResolver(new com.enterprise.mm.config.LocaleConfig().localeResolver()).build();
        mvc.perform(get("/api/stock/movements/export").header("Accept-Language","ru-RU,ru;q=0.9"))
            .andExpect(status().isOk()).andExpect(header().string("Content-Language","ru"));
        mvc.perform(get("/api/stock/movements/export"))
            .andExpect(status().isOk()).andExpect(header().string("Content-Language","uz"));
    }

    @Test void invalidHttpParametersReturn400BeforeGeneratingAFile() throws Exception {
        var mockExporter=mock(StockMovementService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new StockMovementController(mockExporter, mock(ImportedStockMovementQuery.class)))
            .setControllerAdvice(new com.enterprise.mm.error.GlobalExceptionHandler()).build();
        mvc.perform(get("/api/stock/movements/export").param("sort","unknown,asc")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/stock/movements/export").param("language","de")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/stock/movements/export").param("dateFrom","not-a-date")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/stock/movements/export").param("warehouseId","not-a-uuid")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/stock/movements/export").param("movementType","INVALID")).andExpect(status().isBadRequest());
        verifyNoInteractions(mockExporter);
    }
}
