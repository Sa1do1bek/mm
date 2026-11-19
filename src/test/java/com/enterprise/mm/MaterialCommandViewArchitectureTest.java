package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mm.payload.MaterialCommandViewDtos.TabKey;
import com.enterprise.mm.service.MaterialCommandViewObservation;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class MaterialCommandViewArchitectureTest {

    @Test
    void timerUsesOnlyTabAndOutcomeTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MaterialCommandViewObservation observation = new MaterialCommandViewObservation(registry);

        assertThat(observation.observe(TabKey.OVERVIEW, UUID.randomUUID(), () -> "done")).isEqualTo("done");

        var timer = registry.get("material360.command_view.duration")
            .tags("tab", "OVERVIEW", "outcome", "SUCCESS")
            .timer();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.getId().getTags()).extracting(tag -> tag.getKey())
            .containsExactlyInAnyOrder("tab", "outcome");
    }

    @Test
    void failedObservationRecordsErrorWithoutSwallowingFailure() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MaterialCommandViewObservation observation = new MaterialCommandViewObservation(registry);

        assertThatThrownBy(() -> observation.observe(TabKey.MOVEMENTS, UUID.randomUUID(), () -> {
            throw new IllegalStateException("safe test failure");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(registry.get("material360.command_view.duration")
            .tags("tab", "MOVEMENTS", "outcome", "ERROR").timer().count()).isEqualTo(1);
    }

    @Test
    void material360CodeDoesNotReferenceMutationServicesOrRawPayloadFields() throws IOException {
        String source = readAllMaterial360Sources();

        assertThat(source).doesNotContain(
            "StockMovementService", "StockMovementDocumentService", "PurchaseOrderService",
            "MaterialReservationService", "StockValuationService", "IntegrationEventService",
            "getPayload()", "getDetails()", "getLastError()", "attachmentBytes");
    }

    @Test
    void repositoryNativeQueriesDoNotUseEntityGraphs() throws IOException {
        Pattern invalidCombination = Pattern.compile(
            "@EntityGraph[^;]*?@Query\\([^;]*?nativeQuery\\s*=\\s*true");
        Path root = Path.of("src/main/java/com/enterprise/mm/repository");

        try (var files = Files.walk(root)) {
            assertThat(files.filter(path -> path.toString().endsWith(".java"))
                .filter(path -> invalidCombination.matcher(read(path)).find())
                .map(Path::toString)
                .toList()).isEmpty();
        }
    }

    private String readAllMaterial360Sources() throws IOException {
        Path root = Path.of("src/main/java/com/enterprise/mm");
        try (var files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java"))
                .map(this::read)
                .filter(source -> source.contains("MaterialCommandView") || source.contains("material360"))
                .sorted(Comparator.naturalOrder())
                .reduce("", (left, right) -> left + System.lineSeparator() + right);
        }
    }

    private String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read source: " + path, ex);
        }
    }
}
