package com.enterprise.mm.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.mapper.StockValuationMapper;
import com.enterprise.mm.payload.StockValuationCostJobResponse;
import com.enterprise.mm.payload.StockValuationSelectedStocksResponse;
import com.enterprise.mm.service.InventoryValuationPolicyService;
import com.enterprise.mm.service.StockValuationCostSelectionService;
import com.enterprise.mm.service.StockValuationCostJobService;
import com.enterprise.mm.service.StockValuationHistoryService;
import com.enterprise.mm.service.StockValuationRecordService;
import com.enterprise.mm.service.StockValuationRunService;
import com.enterprise.mm.service.StockValuationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;

class StockValuationControllerTest {
    @Test
    void stockCostsDelegatesAndKeepsSelectedStocksResponseContract() {
        StockValuationRecordService recordService = mock(StockValuationRecordService.class);
        StockValuationService valuationService = mock(StockValuationService.class);
        StockValuationMapper mapper = mock(StockValuationMapper.class);
        InventoryValuationPolicyService policyService = mock(InventoryValuationPolicyService.class);
        StockValuationCostSelectionService selectionService = mock(StockValuationCostSelectionService.class);
        StockValuationCostJobService jobService = mock(StockValuationCostJobService.class);
        StockValuationHistoryService historyService = mock(StockValuationHistoryService.class);
        StockValuationRunService runService = mock(StockValuationRunService.class);
        StockValuationController controller = new StockValuationController(
            recordService, valuationService, mapper, policyService, selectionService, jobService, historyService, runService);
        UUID warehouseId = UUID.randomUUID();
        StockValuationSelectedStocksResponse.StockCost stock = new StockValuationSelectedStocksResponse.StockCost(UUID.randomUUID(), "Steel", warehouseId, "Main", null, com.enterprise.mm.enums.ValuationMethod.FIFO, null, false, "unavailable");
        when(selectionService.calculateStockCosts(warehouseId, (UUID) null)).thenReturn(List.of(stock));

        var response = controller.stockCosts(warehouseId, null);

        assertThat(response.data()).isInstanceOf(StockValuationSelectedStocksResponse.class);
        assertThat(response.data().stocks()).containsExactly(stock);
        verify(selectionService).calculateStockCosts(warehouseId, (UUID) null);
    }

    /**
     * TODO item 4 (action-prefill / "Оценка"): the drawer's valuation action must be
     * able to scope the cost/stocks endpoint down to the single material the operator
     * picked, instead of always returning every stock in the warehouse (or tenant).
     */
    @Test
    void stockCostsPassesMaterialIdThroughToTheSelectionService() {
        StockValuationRecordService recordService = mock(StockValuationRecordService.class);
        StockValuationService valuationService = mock(StockValuationService.class);
        StockValuationMapper mapper = mock(StockValuationMapper.class);
        InventoryValuationPolicyService policyService = mock(InventoryValuationPolicyService.class);
        StockValuationCostSelectionService selectionService = mock(StockValuationCostSelectionService.class);
        StockValuationCostJobService jobService = mock(StockValuationCostJobService.class);
        StockValuationHistoryService historyService = mock(StockValuationHistoryService.class);
        StockValuationRunService runService = mock(StockValuationRunService.class);
        StockValuationController controller = new StockValuationController(
                recordService, valuationService, mapper, policyService, selectionService, jobService, historyService, runService);
        UUID warehouseId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        StockValuationSelectedStocksResponse.StockCost stock = new StockValuationSelectedStocksResponse.StockCost(
                materialId, "Steel", warehouseId, "Main", java.math.BigDecimal.TEN, com.enterprise.mm.enums.ValuationMethod.FIFO,
                java.math.BigDecimal.TEN, true, null);
        when(selectionService.calculateStockCosts(warehouseId, materialId)).thenReturn(List.of(stock));

        var response = controller.stockCosts(warehouseId, materialId);

        assertThat(response.data().stocks()).containsExactly(stock);
        verify(selectionService).calculateStockCosts(warehouseId, materialId);
    }

    @Test
    void stockCostsExposesBothLegacyRouteAliases() throws NoSuchMethodException {
        GetMapping mapping = StockValuationController.class
                .getDeclaredMethod("stockCosts", UUID.class, UUID.class).getAnnotation(GetMapping.class);

        assertThat(mapping.value()).containsExactly("/cost/stocks", "/costs/stocks");
    }

    @Test
    void startingJobAcceptsAnOmittedBodyAndReturnsAcceptedStatus() throws Exception {
        StockValuationRecordService recordService = mock(StockValuationRecordService.class);
        StockValuationService valuationService = mock(StockValuationService.class);
        StockValuationMapper mapper = mock(StockValuationMapper.class);
        InventoryValuationPolicyService policyService = mock(InventoryValuationPolicyService.class);
        StockValuationCostSelectionService selectionService = mock(StockValuationCostSelectionService.class);
        StockValuationCostJobService jobService = mock(StockValuationCostJobService.class);
        StockValuationHistoryService historyService = mock(StockValuationHistoryService.class);
        StockValuationRunService runService = mock(StockValuationRunService.class);
        StockValuationController controller = new StockValuationController(
            recordService, valuationService, mapper, policyService, selectionService, jobService, historyService, runService);
        StockValuationCostJobResponse response = new StockValuationCostJobResponse(
            UUID.randomUUID(), null, null, "alice", "QUEUED",
            "WAITING", null, 0, 0, 0, 0, null, null, null, null, null);
        when(jobService.startJob(null, null)).thenReturn(response);

        assertThat(controller.startStockCostJob(null).data()).isEqualTo(response);
        verify(jobService).startJob(null, null);

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper().findAndRegisterModules()))
            .build();
        mockMvc.perform(post("/api/valuation/costs/stocks/jobs"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.data.status").value("QUEUED"))
            .andExpect(jsonPath("$.data.phase").value("WAITING"));

        PostMapping mapping = StockValuationController.class
            .getDeclaredMethod("startStockCostJob", com.enterprise.mm.payload.StockValuationCostJobStartRequest.class)
            .getAnnotation(PostMapping.class);
        ResponseStatus status = StockValuationController.class
            .getDeclaredMethod("startStockCostJob", com.enterprise.mm.payload.StockValuationCostJobStartRequest.class)
            .getAnnotation(ResponseStatus.class);
        assertThat(mapping.value()).containsExactly("/costs/stocks/jobs");
        assertThat(status.value()).isEqualTo(HttpStatus.ACCEPTED);
    }
    @Test
    void getStatisticsReturnsOkAndDelegatesToRunService() throws Exception {
        StockValuationRecordService recordService = mock(StockValuationRecordService.class);
        StockValuationService valuationService = mock(StockValuationService.class);
        StockValuationMapper mapper = mock(StockValuationMapper.class);
        InventoryValuationPolicyService policyService = mock(InventoryValuationPolicyService.class);
        StockValuationCostSelectionService selectionService = mock(StockValuationCostSelectionService.class);
        StockValuationCostJobService jobService = mock(StockValuationCostJobService.class);
        StockValuationHistoryService historyService = mock(StockValuationHistoryService.class);
        StockValuationRunService runService = mock(StockValuationRunService.class);
        StockValuationController controller = new StockValuationController(
            recordService, valuationService, mapper, policyService, selectionService, jobService, historyService, runService);

        com.enterprise.mm.payload.StockValuationStatisticsResponse mockResponse = 
            com.enterprise.mm.payload.StockValuationStatisticsResponse.builder()
                .totalRuns(10L)
                .completedRuns(8L)
                .failedRuns(2L)
                .currentGlobalStockValue(java.math.BigDecimal.valueOf(1000000))
                .valuatedMaterialsCount(500L)
                .build();
                
        when(runService.getStatistics()).thenReturn(mockResponse);

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper().findAndRegisterModules()))
            .build();
            
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/valuation/runs/statistics"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalRuns").value(10))
            .andExpect(jsonPath("$.data.completedRuns").value(8))
            .andExpect(jsonPath("$.data.failedRuns").value(2))
            .andExpect(jsonPath("$.data.currentGlobalStockValue").value(1000000))
            .andExpect(jsonPath("$.data.valuatedMaterialsCount").value(500));
            
        verify(runService).getStatistics();
    }
}