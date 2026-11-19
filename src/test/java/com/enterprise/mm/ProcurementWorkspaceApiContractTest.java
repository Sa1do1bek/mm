package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ProcurementWorkspaceApiContractTest {
    private static final Path ROOT = Path.of("src/main/java/com/enterprise/mm");

    @Test
    void purchaseRequestWorkspaceExposesWorklistAndDetailEndpoints() throws Exception {
        String controller = Files.readString(ROOT.resolve("controller/PurchaseRequestWorkspaceController.java"));

        assertThat(controller).contains("@RequestMapping(\"/api/purchase-requests\")");
        assertThat(controller).contains("@GetMapping(\"/workspace\")");
        assertThat(controller).contains("@GetMapping(\"/workspace/summary\")");
        assertThat(controller).contains("@GetMapping(\"/{id}/workspace\")");
        assertThat(controller).contains("PurchaseRequestWorkspaceView view");
        assertThat(controller).contains("PurchaseRequestProcessStage processStage");
    }

    @Test
    void purchaseOrderWorkspaceExposesWorklistAndDetailEndpoints() throws Exception {
        String controller = Files.readString(ROOT.resolve("controller/PurchaseOrderWorkspaceController.java"));

        assertThat(controller).contains("@RequestMapping(\"/api/purchase-orders\")");
        assertThat(controller).contains("@GetMapping(\"/workspace\")");
        assertThat(controller).contains("@GetMapping(\"/workspace/summary\")");
        assertThat(controller).contains("@GetMapping(\"/{id}/workspace\")");
        assertThat(controller).contains("PurchaseOrderWorkspaceView view");
        assertThat(controller).contains("PurchaseOrderProcessStage processStage");
    }

    @Test
    void workspaceDetailModelsCarryLineageAndCoverageFields() throws Exception {
        String purchaseRequestDtos = Files.readString(ROOT.resolve("payload/PurchaseRequestWorkspaceDtos.java"));
        String purchaseOrderDtos = Files.readString(ROOT.resolve("payload/PurchaseOrderWorkspaceDtos.java"));

        assertThat(purchaseRequestDtos).contains("UUID demandPlanId");
        assertThat(purchaseRequestDtos).contains("UUID sourceDemandPlanItemId");
        assertThat(purchaseRequestDtos).contains("List<ProcessStage> stages");
        assertThat(purchaseRequestDtos).contains("AvailableActions availableActions");
        assertThat(purchaseRequestDtos).contains("PurchaseRequestWorkspaceSummary");
        assertThat(purchaseRequestDtos).contains("long requestCount");

        assertThat(purchaseOrderDtos).contains("UUID demandPlanId");
        assertThat(purchaseOrderDtos).contains("boolean sourceAllocationAvailable");
        assertThat(purchaseOrderDtos).contains("List<SourceAllocationLineage> sourceAllocations");
        assertThat(purchaseOrderDtos).contains("List<SourceWarning> sourceWarnings");
        assertThat(purchaseOrderDtos).contains("PurchaseOrderWorkspaceSummary");
        assertThat(purchaseOrderDtos).contains("long orderCount");
    }

    @Test
    void lineageEnricherIsWiredIntoReadResponses() throws Exception {
        String purchaseRequestService = Files.readString(ROOT.resolve("service/PurchaseRequestService.java"));
        String purchaseOrderService = Files.readString(ROOT.resolve("service/PurchaseOrderService.java"));

        assertThat(purchaseRequestService).contains("ProcurementLineageEnricher");
        assertThat(purchaseOrderService).contains("ProcurementLineageEnricher");
    }
}
