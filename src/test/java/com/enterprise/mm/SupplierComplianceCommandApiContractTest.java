package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SupplierComplianceCommandApiContractTest {
    private static final Path ROOT = Path.of("src/main/java/com/enterprise/mm");

    @Test
    void everyKycAndCapaLifecycleEndpointRequiresVersionBodyAndIdempotencyHeader() throws Exception {
        String kycController = Files.readString(ROOT.resolve("controller/SupplierKycCheckController.java"));
        String capaController = Files.readString(ROOT.resolve("controller/SupplierCorrectiveActionController.java"));
        String commands = Files.readString(ROOT.resolve("payload/SupplierComplianceDtos.java"));

        assertThat(commands).contains(
            "record VersionedSupplierComplianceCommand",
            "record SupplierKycDecisionCommand",
            "record SupplierCapaActionCommand",
            "@NotNull Long expectedVersion"
        );
        assertThat(occurrences(kycController, "@RequestHeader(\"Idempotency-Key\") String idempotencyKey"))
            .isEqualTo(5);
        assertThat(occurrences(capaController, "@RequestHeader(\"Idempotency-Key\") String idempotencyKey"))
            .isEqualTo(6);
        assertThat(kycController).contains(
            "commandService.request(id, command, idempotencyKey)",
            "commandService.pass(id, command, idempotencyKey)",
            "commandService.fail(id, command, idempotencyKey)",
            "commandService.waive(id, command, idempotencyKey)",
            "commandService.expire(id, command, idempotencyKey)"
        );
        assertThat(capaController).contains(
            "commandService.open(id, command, idempotencyKey)",
            "commandService.start(id, command, idempotencyKey)",
            "commandService.submit(id, command, idempotencyKey)",
            "commandService.verify(id, command, idempotencyKey)",
            "commandService.close(id, command, idempotencyKey)",
            "commandService.cancel(id, command, idempotencyKey)"
        );
    }

    private int occurrences(String value, String token) {
        return value.split(java.util.regex.Pattern.quote(token), -1).length - 1;
    }
}
