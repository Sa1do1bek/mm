package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SupplierPortalCommandApiContractTest {

    @Test
    void accountRecoveryAndLifecycleCommandsAreAuthorizedVersionedAndIdempotent() throws Exception {
        String controller = source("controller/SupplierPortalAccountController.java");
        String commandService = source("service/SupplierPortalAccountCommandService.java");

        assertThat(controller).doesNotContain("@PostMapping(\"/{id}/activate\")");
        assertThat(controller).doesNotContain("@PostMapping(\"/{id}/accept-terms\")");
        assertThat(controller).contains("SupplierPortalAccountCommandService");
        assertThat(controller).contains("@PostMapping(\"/{id}/reinvite\")");
        assertThat(controller).contains("@RequestHeader(\"Idempotency-Key\")");
        assertThat(controller).contains("SupplierPortalLifecycleCommand");
        assertThat(controller).contains("hasAnyRole('ADMIN','SUPPLIER_MANAGER')");
        assertThat(commandService).contains("idempotencyService.execute(");
        assertThat(commandService).doesNotContain("executeRedacted(");
        assertThat(commandService).contains("expectedVersion");
    }

    @Test
    void submissionDecisionsAreAuthorizedVersionedIdempotentAndRequireComments() throws Exception {
        String controller = source("controller/SupplierPortalSubmissionController.java");
        String commandService = source("service/SupplierPortalSubmissionCommandService.java");

        assertThat(controller).contains("SupplierPortalSubmissionCommandService");
        assertThat(controller).contains("@RequestHeader(\"Idempotency-Key\")");
        assertThat(controller).contains("SupplierPortalReviewCommand");
        assertThat(controller).contains("hasAnyRole('ADMIN','SUPPLIER_MANAGER')");
        assertThat(commandService).contains("expectedVersion");
        assertThat(commandService).contains("requireMeaningful");
        assertThat(commandService).contains("idempotencyService.execute(");
    }

    private String source(String relativePath) throws Exception {
        return Files.readString(Path.of("src/main/java/com/enterprise/mm").resolve(relativePath));
    }
}
