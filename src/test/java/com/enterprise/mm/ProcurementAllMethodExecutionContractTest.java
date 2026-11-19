package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ProcurementAllMethodExecutionContractTest {
    @Test
    void exposesDirectAwardExecutionEndpoints() throws Exception {
        String controller = Files.readString(Path.of("src/main/java/com/enterprise/mm/controller/ProcurementProcedureController.java"));

        assertThat(controller)
            .contains("@PostMapping(\"/{id}/direct-award\")")
            .contains("@PutMapping(\"/direct-awards/{id}\")")
            .contains("@PostMapping(\"/direct-awards/{id}/submit\")")
            .contains("@PostMapping(\"/direct-awards/{id}/approve\")")
            .contains("@PostMapping(\"/direct-awards/{id}/create-po\")")
            .contains("@PostMapping(\"/{id}/auction/publish\")")
            .contains("@PostMapping(\"/{id}/auction/award\")")
            .contains("@PostMapping(\"/{id}/framework/award\")")
            .contains("@PostMapping(\"/{id}/framework/submit\")")
            .contains("@PostMapping(\"/{id}/framework/activate\")")
            .contains("@PostMapping(\"/{id}/framework/create-po\")")
            .contains("PROCUREMENT_MANAGER");
    }



    @Test
    void securesProcurementMethodDecisionMutations() throws Exception {
        String controller = Files.readString(Path.of("src/main/java/com/enterprise/mm/controller/ProcurementMethodDecisionController.java"));
        String security = Files.readString(Path.of("src/main/java/com/enterprise/mm/security/SecurityConfig.java"));

        assertThat(controller)
            .contains("@PreAuthorize(\"hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER')\")")
            .contains("@PreAuthorize(\"hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST')\")");
        assertThat(security)
            .contains("/api/procurement/method-decisions/**")
            .contains("/api/procurement/procedures/**");
    }








}
