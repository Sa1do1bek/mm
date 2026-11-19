package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class SearchRequestArchitectureTest {
    private static final Path SOURCE_ROOT = Path.of("src/main/java");
    private static final List<String> FORBIDDEN_PUBLIC_SEARCH_DTOS = List.of(
        "com.enterprise.mm.payload.SearchRequest",
        "com.enterprise.mm.payload.MasterDataSearchRequest"
    );

    @Test
    void controllersAndServicesDoNotExposeGenericSearchDtos() throws IOException {
        try (Stream<Path> files = Files.walk(SOURCE_ROOT.resolve("com/enterprise/mm"))) {
            List<Path> offenders = files
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith("Controller.java") || path.toString().endsWith("Service.java"))
                .filter(this::importsForbiddenSearchDto)
                .toList();

            assertThat(offenders)
                .as("Controllers and services must use module-specific search request DTOs")
                .isEmpty();
        }
    }

    @Test
    void genericSearchDtosAreNotPublishedAsPayloadContracts() {
        assertThat(SOURCE_ROOT.resolve("com/enterprise/mm/payload/SearchRequest.java"))
            .as("SearchRequest must not remain a public payload contract")
            .doesNotExist();
        assertThat(SOURCE_ROOT.resolve("com/enterprise/mm/payload/MasterDataSearchRequest.java"))
            .as("MasterDataSearchRequest must not remain a public payload contract")
            .doesNotExist();
    }

    @Test
    void dataPageBackedCustomSearchControllersDeclareSharedDateRangeParams() throws IOException {
        Map<String, Path> controllers = Map.of(
            "/api/management-opportunities", SOURCE_ROOT.resolve("com/enterprise/mm/controller/ManagementOpportunityController.java"),
            "/api/approval-flows", SOURCE_ROOT.resolve("com/enterprise/mm/controller/ApprovalFlowController.java"),
            "/api/service-requests", SOURCE_ROOT.resolve("com/enterprise/mm/controller/ServiceRequestController.java"),
            "/api/approval-tasks/my", SOURCE_ROOT.resolve("com/enterprise/mm/controller/ApprovalTaskController.java"),
            "/api/exchange-rates", SOURCE_ROOT.resolve("com/enterprise/mm/controller/masterdata/ExchangeRateController.java")
        );

        List<String> missingDateRange = controllers.entrySet().stream()
            .filter(entry -> !declaresSharedDateRange(entry.getValue()))
            .map(Map.Entry::getKey)
            .sorted()
            .toList();

        assertThat(missingDateRange)
            .as("DataPage sends dateFrom/dateTo to these list endpoints; backend must declare the same period contract")
            .isEmpty();
    }

    private boolean importsForbiddenSearchDto(Path path) {
        try {
            String source = Files.readString(path);
            return FORBIDDEN_PUBLIC_SEARCH_DTOS.stream().anyMatch(source::contains);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read " + path, exception);
        }
    }

    private boolean declaresSharedDateRange(Path path) {
        try {
            String source = Files.readString(path);
            return source.matches("(?s).*\\bdateFrom\\b.*") && source.matches("(?s).*\\bdateTo\\b.*");
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read " + path, exception);
        }
    }

}
