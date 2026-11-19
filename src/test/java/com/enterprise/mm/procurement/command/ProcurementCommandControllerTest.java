package com.enterprise.mm.procurement.command;

import com.enterprise.mm.procurement.command.controller.ProcurementCommandController;
import com.enterprise.mm.procurement.command.enums.ProcurementAllocationMode;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.AvailableActions;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.CaseHeader;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.CommandAction;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.ProcurementCommandCaseDetail;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.QuotationComparisonRow;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.SourcingCoverageSnapshot;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandSearchRequest;
import com.enterprise.mm.procurement.command.service.ProcurementCommandDetailService;
import com.enterprise.mm.procurement.command.service.ProcurementCommandQueryService;
import com.enterprise.mm.procurement.command.service.ProcurementQueueService;
import com.enterprise.mm.procurement.command.service.ProcurementTimelineService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProcurementCommandControllerTest {

    @Test
    void casesEndpointAppliesAllowlistedSort() throws Exception {
        ProcurementCommandQueryService queryService = mock(ProcurementCommandQueryService.class);
        ProcurementCommandDetailService detailService = mock(ProcurementCommandDetailService.class);
        ProcurementQueueService queueService = mock(ProcurementQueueService.class);
        ProcurementTimelineService timelineService = mock(ProcurementTimelineService.class);
        when(queryService.search(any(), any())).thenReturn(new PageImpl<>(List.of()));
        ProcurementCommandController controller = new ProcurementCommandController(
            queryService, detailService, queueService, timelineService);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(get("/api/procurement-command/cases")
                .param("sortBy", "requiredDate")
                .param("sortDir", "asc"))
            .andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(queryService).search(any(), pageable.capture());
        assertThat(pageable.getValue().getSort().getOrderFor("requiredDate").getDirection())
            .isEqualTo(Sort.Direction.ASC);
    }

    @Test
    void casesEndpointUsesRequiredDateThenCreatedAtByDefault() throws Exception {
        ProcurementCommandQueryService queryService = mock(ProcurementCommandQueryService.class);
        ProcurementCommandDetailService detailService = mock(ProcurementCommandDetailService.class);
        ProcurementQueueService queueService = mock(ProcurementQueueService.class);
        ProcurementTimelineService timelineService = mock(ProcurementTimelineService.class);
        when(queryService.search(any(), any())).thenReturn(new PageImpl<>(List.of()));
        ProcurementCommandController controller = new ProcurementCommandController(
            queryService, detailService, queueService, timelineService);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(get("/api/procurement-command/cases"))
            .andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(queryService).search(any(), pageable.capture());
        assertThat(pageable.getValue().getSort().getOrderFor("requiredDate").getDirection())
            .isEqualTo(Sort.Direction.ASC);
        assertThat(pageable.getValue().getSort().getOrderFor("createdAt").getDirection())
            .isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void casesEndpointBindsPurchaseOrderContextFilter() throws Exception {
        UUID purchaseOrderId = UUID.randomUUID();
        ProcurementCommandQueryService queryService = mock(ProcurementCommandQueryService.class);
        ProcurementCommandDetailService detailService = mock(ProcurementCommandDetailService.class);
        ProcurementQueueService queueService = mock(ProcurementQueueService.class);
        ProcurementTimelineService timelineService = mock(ProcurementTimelineService.class);
        when(queryService.search(any(), any())).thenReturn(new PageImpl<>(List.of()));
        ProcurementCommandController controller = new ProcurementCommandController(
            queryService, detailService, queueService, timelineService);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(get("/api/procurement-command/cases")
                .param("purchaseOrderId", purchaseOrderId.toString()))
            .andExpect(status().isOk());

        ArgumentCaptor<ProcurementCommandSearchRequest> request = ArgumentCaptor.forClass(ProcurementCommandSearchRequest.class);
        verify(queryService).search(request.capture(), any());
        assertThat(request.getValue().purchaseOrderId()).isEqualTo(purchaseOrderId);
    }

    @Test
    void caseEndpointReturnsHonestCoverageAndPrimaryAction() throws Exception {
        UUID prId = UUID.randomUUID();
        ProcurementCommandQueryService queryService = mock(ProcurementCommandQueryService.class);
        ProcurementCommandDetailService detailService = mock(ProcurementCommandDetailService.class);
        ProcurementQueueService queueService = mock(ProcurementQueueService.class);
        ProcurementTimelineService timelineService = mock(ProcurementTimelineService.class);
        AvailableActions actions = new AvailableActions(
            new CommandAction("START_SOURCING", true, null, Map.of(), false, "NAVIGATE", null, null),
            List.of());
        ProcurementCommandCaseDetail detail = new ProcurementCommandCaseDetail(
            new CaseHeader(prId, "PR-100", "Pump purchase", "APPROVED", null, null, null, null, null),
            new SourcingCoverageSnapshot(ProcurementAllocationMode.AUTHORITATIVE, List.of(), LocalDateTime.now()),
            List.of(), actions, List.of(), List.of(), List.of(), LocalDateTime.now());
        when(detailService.get(prId)).thenReturn(detail);
        ProcurementCommandController controller = new ProcurementCommandController(
            queryService, detailService, queueService, timelineService);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(get("/api/procurement-command/cases/{id}", prId)
                .accept(MediaType.APPLICATION_JSON)
                .header("X-Tenant-Id", "default")
                .header("X-Company-Id", UUID.randomUUID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.header.purchaseRequestId").value(prId.toString()))
            .andExpect(jsonPath("$.data.coverage.allocationMode").value("AUTHORITATIVE"))
            .andExpect(jsonPath("$.data.blockers").isArray())
            .andExpect(jsonPath("$.data.availableActions.primary.key").value("START_SOURCING"))
            .andExpect(jsonPath("$.data.generatedAt").exists());
    }

    @Test
    void comparisonEndpointReturnsSupplierNameForDisplay() throws Exception {
        UUID prId = UUID.randomUUID();
        UUID quotationId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        ProcurementCommandQueryService queryService = mock(ProcurementCommandQueryService.class);
        ProcurementCommandDetailService detailService = mock(ProcurementCommandDetailService.class);
        ProcurementQueueService queueService = mock(ProcurementQueueService.class);
        ProcurementTimelineService timelineService = mock(ProcurementTimelineService.class);
        when(detailService.comparison(eq(prId), any())).thenReturn(new PageImpl<>(List.of(
            new QuotationComparisonRow(quotationId, "SQ-100", supplierId, "Acme Supplies", null, "UZS", null, null, null, null, false)
        ), PageRequest.of(0, 20), 1));
        ProcurementCommandController controller = new ProcurementCommandController(
            queryService, detailService, queueService, timelineService);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(get("/api/procurement-command/cases/{id}/quotation-comparison", prId)
                .accept(MediaType.APPLICATION_JSON)
                .header("X-Tenant-Id", "default")
                .header("X-Company-Id", UUID.randomUUID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[0].supplierName").value("Acme Supplies"));
    }
}
