package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.InventoryOrder;
import com.enterprise.mm.repository.AiAnalysisJobRepository;
import com.enterprise.mm.repository.CommissionRepository;
import com.enterprise.mm.repository.InventoryOrderAllocationRepository;
import com.enterprise.mm.repository.InventoryOrderRepository;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.service.inventory.InventoryAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

class InventoryOrderPagingTest {
    private final UUID company = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final InventoryOrderRepository orders = mock(InventoryOrderRepository.class);
    private InventoryOrder row;
    private final InventoryOrderAllocationRepository allocations = mock(InventoryOrderAllocationRepository.class);
    private final InventoryAccessService access = mock(InventoryAccessService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final InventoryOrderService service = new InventoryOrderService(orders,
        allocations, mock(AiAnalysisJobRepository.class),
        mock(InventoryAiDraftService.class), mock(InventoryDocumentService.class), access, mapper,
        mock(JdbcTemplate.class), mock(InventoryOrderCommissionMatchService.class),
        mock(InventoryOrderObjectMatchService.class), mock(AttachmentService.class),
        mock(UserRepository.class), mock(CommissionRepository.class));

    @BeforeEach
    void setup() {
        TenantContext.setTenant("tenant-a");
        CompanyContext.setCompany(company);
        when(access.username()).thenReturn("owner");
        when(allocations.findByOrderIdOrderByCreatedAtAsc(orderId)).thenReturn(List.of());
        ObjectNode analysis = mapper.createObjectNode();
        analysis.put("status", "NEEDS_REVIEW");
        analysis.putObject("scope").put("mode", "ALL");
        ObjectNode orderAnalysis = analysis.putObject("orderAnalysis");
        orderAnalysis.put("schemaVersion", 2);
        orderAnalysis.putObject("header").putObject("orderNumber").put("value", "1749");
        var assignments = orderAnalysis.putArray("chairAssignments");
        for (int i = 0; i < 45; i++) assignments.addObject().put("sourceKey", "chair-" + i);
        orderAnalysis.putArray("workItems");
        orderAnalysis.putArray("commissionRosters");
        row = new InventoryOrder();
        row.setId(orderId);
        row.setOwnerUsername("owner");
        row.setAnalysisJobId(UUID.randomUUID());
        row.setAnalysisPayload(analysis.toString());
        when(orders.findByIdAndTenantIdAndCompanyId(orderId, "tenant-a", company)).thenReturn(Optional.of(row));
    }

    @AfterEach
    void cleanup() { TenantContext.clear(); CompanyContext.clear(); }

    @Test
    void pagesCoverEveryChairExactlyOnceAndSummaryOmitsLargeArray() {
        List<String> keys = new ArrayList<>();
        for (int offset : List.of(0, 20, 40)) {
            var page = service.page(orderId, "chair-assignments", offset, 20, null);
            assertThat(page.total()).isEqualTo(45);
            page.items().forEach(item -> keys.add(item.path("sourceKey").asText()));
        }
        assertThat(keys).hasSize(45);
        assertThat(new HashSet<>(keys)).hasSize(45);
        assertThat(service.get(orderId).analysis().path("orderAnalysis").path("chairAssignments")).isEmpty();
        assertThat(service.get(orderId).analysis().path("orderAnalysis").path("chairAssignmentsCount").asInt()).isEqualTo(45);
    }

    @Test
    void historyShowsAnalysisCountsAndKeepsDepartmentHeadScope() {
        when(access.hasRole("DEPARTMENT_HEAD")).thenReturn(true);
        when(orders.history("tenant-a", company, true, "owner", true, "1749", "%1749%", PageRequest.of(0, 20)))
            .thenReturn(new PageImpl<>(List.of(row)));

        var history = service.history(0, 20, " 1749 ");

        assertThat(history.getTotalElements()).isEqualTo(1);
        assertThat(history.getContent().get(0).orderNumber()).isEqualTo("1749");
        assertThat(history.getContent().get(0).analysisStatus()).isEqualTo("NEEDS_REVIEW");
        assertThat(history.getContent().get(0).analysisVersion()).isEqualTo(2);
        assertThat(history.getContent().get(0).chairAssignmentsCount()).isEqualTo(45);
    }
}
