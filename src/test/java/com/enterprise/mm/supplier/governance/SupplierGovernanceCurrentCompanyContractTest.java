package com.enterprise.mm.supplier.governance;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SupplierGovernanceCurrentCompanyContractTest {

    @Test
    void workspaceEntryPointsForceCurrentCompanyForGlobalAdministrators() throws Exception {
        Path root = Path.of("src/main/java/com/enterprise/mm/supplier/governance");
        String controller = Files.readString(
            root.resolve("controller/SupplierGovernanceWorkspaceController.java"));
        String service = Files.readString(
            root.resolve("service/SupplierGovernanceWorkspaceService.java"));

        assertThat(controller).contains(
            "service.overviewForCompany(horizonDays)",
            "service.queuesForCompany(horizonDays, purchaseOrderId)",
            "service.queueForCompany(");
        assertThat(service).contains(
            "queryRepository.searchForCompany(normalized, pageable)",
            "Objects.equals(CompanyContext.currentCompany(), supplier.getCompanyId())",
            "forceCompanyScope ? \"COMPANY\" : dataScope()"
        );
    }
}
