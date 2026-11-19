package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class NonMovingStockWorkspaceApiContractTest {
    @Test
    void exposesCanonicalWorkspaceAndLifecycleCommandsWithVersionedRequests() throws Exception {
        String controller = Files.readString(Path.of(
            "src/main/java/com/enterprise/mm/controller/NonMovingStockCaseController.java"
        ));
        String commands = Files.readString(Path.of(
            "src/main/java/com/enterprise/mm/payload/NonMovingStockCaseCommandDtos.java"
        ));
        String decision = Files.readString(Path.of(
            "src/main/java/com/enterprise/mm/payload/NonMovingStockDecisionCreateRequest.java"
        ));

        assertThat(controller).contains(
            "@GetMapping(\"/workspace/summary\")",
            "@GetMapping(\"/workspace/positions\")",
            "@GetMapping(\"/workspace/positions/{id}\")",
            "@GetMapping(\"/workspace/cases\")",
            "@GetMapping(\"/workspace/saved-views\")",
            "@PostMapping(\"/workspace/saved-views\")",
            "@DeleteMapping(\"/workspace/saved-views/{viewId}\")",
            "@GetMapping(\"/workspace/selectors/users\")",
            "@GetMapping(\"/workspace/selectors/commissions\")",
            "@GetMapping(\"/{id}/workspace\")",
            "@PostMapping(\"/{id}/assign\")",
            "@PostMapping(\"/{id}/start-review\")",
            "@PostMapping(\"/{id}/send-to-commission\")",
            "@PostMapping(\"/{id}/defer-review\")",
            "@PostMapping(\"/{id}/close\")",
            "@PostMapping(\"/{id}/cancel\")",
            "@PostMapping(\"/{id}/reopen\")",
            "@PreAuthorize"
        );
        assertThat(commands).contains("long expectedVersion", "record ExecutionRequest");
        assertThat(decision).contains("private Long expectedVersion");
        assertThat(commands).doesNotContain("responsibleUsername");
    }





    @Test
    void directDetectionExecutionIsRemovedFromDetectionController() throws Exception {
        String controller = Files.readString(Path.of(
            "src/main/java/com/enterprise/mm/controller/NonMovingStockController.java"
        ));
        String security = Files.readString(Path.of(
            "src/main/java/com/enterprise/mm/security/SecurityConfig.java"
        ));

        assertThat(controller).doesNotContain("@Deprecated", "X-Emergency-Override", "overrideReason");
        assertThat(security).contains("/api/non-moving-stock-cases/*/reopen");
        assertThat(security).doesNotContain("/api/non-moving-stock-cases/*/approve-decision", "/api/non-moving-stock-cases/*/execute-decision");
    }
}
