package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ProcurementPriceDiscoveryContractTest {
    @Test
    void exposesPriceDiscoveryWorkflowEndpoints() throws Exception {
        String campaignController = Files.readString(Path.of("src/main/java/com/enterprise/mm/controller/ProcurementCampaignController.java"));
        String rfqController = Files.readString(Path.of("src/main/java/com/enterprise/mm/controller/RFQController.java"));

        assertThat(campaignController)
            .contains("@PostMapping(\"/{id}/create-price-discovery-rfq\")")
            .contains("@PostMapping(\"/{id}/create-tender\")");
        assertThat(rfqController)
            .contains("@GetMapping(\"/{id}/price-discovery-preview\")")
            .contains("@PostMapping(\"/{id}/finalize-price-discovery\")");
    }



    @Test
    void directPurchaseRequestRfqCreationRequiresPmd() throws Exception {
        String purchaseRequestService = Files.readString(Path.of("src/main/java/com/enterprise/mm/service/PurchaseRequestService.java"));
        String rfqAggregationService = Files.readString(Path.of("src/main/java/com/enterprise/mm/service/RfqAggregationService.java"));
        String workspaceActionKeys = Files.readString(Path.of("src/main/java/com/enterprise/mm/enums/PurchaseRequestWorkspaceActionKey.java"));

        assertThat(purchaseRequestService)
            .contains("procurement.pmd.required")
            .contains("Approved purchase requests must create a procurement method decision before sourcing");
        assertThat(rfqAggregationService)
            .contains("rejectDirectPrSourcing()")
            .contains("procurement.pmd.required");
        assertThat(workspaceActionKeys)
            .contains("CREATE_PMD")
            .contains("OPEN_PMD");
    }
}
