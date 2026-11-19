package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SupplierAccreditationCommandApiContractTest {
    private static final Path ROOT = Path.of("src/main/java/com/enterprise/mm");

    @Test
    void allGovernanceAccreditationCommandsRequireVersionAndIdempotencyHeader() throws Exception {
        Path dtoPath = ROOT.resolve("payload/SupplierAccreditationCommandDtos.java");
        Path commandServicePath = ROOT.resolve("service/SupplierAccreditationCommandService.java");
        assertThat(dtoPath).exists();
        assertThat(commandServicePath).exists();

        String controller = Files.readString(ROOT.resolve("controller/SupplierAccreditationController.java"));
        String commands = Files.readString(dtoPath);
        String commandService = Files.readString(commandServicePath);

        assertThat(commands).contains(
            "record VersionedCommand",
            "record DecisionCommand",
            "@NotNull Long expectedVersion"
        );
        assertThat(commands).doesNotContain(
            "String approvedScope",
            "String conditions",
            "String commissionProtocolNo"
        );
        assertThat(occurrences(controller, "@RequestHeader(\"Idempotency-Key\") String idempotencyKey"))
            .isEqualTo(9);
        assertThat(controller).contains(
            "commandService.submit(id, command, idempotencyKey)",
            "commandService.startReview(id, command, idempotencyKey)",
            "commandService.runComplianceCheck(id, command, idempotencyKey)",
            "commandService.requestClarification(id, command, idempotencyKey)",
            "commandService.submitForApproval(id, command, idempotencyKey)",
            "commandService.approve(id, command, idempotencyKey)",
            "commandService.reject(id, command, idempotencyKey)",
            "commandService.suspend(id, command, idempotencyKey)",
            "commandService.renew(id, command, idempotencyKey)"
        );
        assertThat(commandService).contains(
            "SUPPLIER_ACCREDITATION_SUBMIT",
            "SUPPLIER_ACCREDITATION_START_REVIEW",
            "SUPPLIER_ACCREDITATION_RUN_COMPLIANCE_CHECK",
            "SUPPLIER_ACCREDITATION_REQUEST_CLARIFICATION",
            "SUPPLIER_ACCREDITATION_SUBMIT_FOR_APPROVAL",
            "SUPPLIER_ACCREDITATION_APPROVE",
            "SUPPLIER_ACCREDITATION_REJECT",
            "SUPPLIER_ACCREDITATION_SUSPEND",
            "SUPPLIER_ACCREDITATION_RENEW",
            "idempotencyService.execute(",
            "requireVersion("
        );
    }

    private int occurrences(String value, String token) {
        return value.split(java.util.regex.Pattern.quote(token), -1).length - 1;
    }
}
