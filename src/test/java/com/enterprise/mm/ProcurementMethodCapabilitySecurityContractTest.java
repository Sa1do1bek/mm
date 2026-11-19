package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ProcurementMethodCapabilitySecurityContractTest {
    @Test
    void controllerAndSecurityConfigDeclareReadAndManageRoles() throws Exception {
        String controller = Files.readString(Path.of("src/main/java/com/enterprise/mm/controller/ProcurementMethodCapabilityController.java"));
        String security = Files.readString(Path.of("src/main/java/com/enterprise/mm/security/SecurityConfig.java"));
        assertThat(controller).contains("@PreAuthorize(\"hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST','FINANCE_CONTROLLER','BUDGET_CONTROLLER','AUDITOR','VIEWER','DEPARTMENT_HEAD','DEPARTMENT_WORKER')\")")
            .contains("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER')")
            .contains("/available", "/availability", "@RequestParam(required = false) BigDecimal amount",
                "@RequestParam(required = false) String currency", "@RequestParam(required = false) String requestType",
                "@RequestParam(required = false) String categoryId");
        assertThat(security).contains("/api/procurement/method-capabilities/**")
            .contains(".requestMatchers(HttpMethod.GET, PROCUREMENT).hasAnyRole")
            .contains(".requestMatchers(PROCUREMENT).hasAnyRole");
    }
}
