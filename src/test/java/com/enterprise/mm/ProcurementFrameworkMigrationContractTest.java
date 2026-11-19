package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ProcurementFrameworkMigrationContractTest {




    @Test
    void exposesFrameworkLifecycleEndpoints() throws Exception {
        String procedureController = Files.readString(Path.of(
            "src/main/java/com/enterprise/mm/controller/ProcurementProcedureController.java"));
        String frameworkController = Files.readString(Path.of(
            "src/main/java/com/enterprise/mm/controller/ProcurementFrameworkController.java"));

        assertThat(procedureController)
            .contains("@PostMapping(\"/{id}/framework/award\")")
            .contains("@PostMapping(\"/{id}/framework/submit\")")
            .contains("@PostMapping(\"/{id}/framework/activate\")")
            .contains("@PostMapping(\"/{id}/framework/create-po\")");
        assertThat(frameworkController)
            .contains("@GetMapping(\"/by-procedure/{procedureId}\")")
            .contains("@PostMapping(\"/{id}/submit\")")
            .contains("@PostMapping(\"/{id}/activate\")")
            .contains("@PostMapping(\"/{id}/call-offs\")");
    }
}
