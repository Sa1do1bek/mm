package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SupplierReadinessCommandApiContractTest {
    private static final Path ROOT = Path.of("src/main/java/com/enterprise/mm");

    @Test
    void activationRequiresVersionCommentRoleAndIdempotencyKey() throws Exception {
        String controller = Files.readString(ROOT.resolve("controller/SupplierController.java"));
        String command = Files.readString(ROOT.resolve("payload/SupplierActivationCommand.java"));
        String service = Files.readString(
            ROOT.resolve("supplier/governance/readiness/SupplierActivationService.java"));

        assertThat(controller).contains(
            "@PostMapping(\"/{id}/activate\")",
            "@RequestHeader(\"Idempotency-Key\") String idempotencyKey",
            "activationService.activate(id, command, idempotencyKey)");
        assertThat(command).contains(
            "@NotNull Long expectedVersion",
            "@NotBlank @Size(min = 3, max = 2000) String comment");
        assertThat(service).contains(
            "\"SUPPLIER_ACTIVATE\"",
            "\"SUPPLIER_ACTIVATED\"",
            "readinessService.get(supplierId)",
            "logInCurrentTransaction");
    }
}
