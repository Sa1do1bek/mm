package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class DisplayNameSourceContractTest {
    private static final Pattern CODE_DERIVED_NAME = Pattern.compile(
        "\\.setName\\([^;]*\\.getCode\\(\\)[^;]*\\);"
    );

    @Test
    void productionServicesNeverCopyInternalCodesIntoNames() throws IOException {
        Path services = Path.of("src/main/java/com/enterprise/mm/service");
        List<String> violations = new ArrayList<>();
        try (var files = Files.walk(services)) {
            files.filter(path -> path.toString().endsWith(".java"))
                .forEach(path -> violations.addAll(forbiddenMatches(path)));
        }
        assertThat(violations).isEmpty();
    }

    private List<String> forbiddenMatches(Path path) {
        try {
            String source = Files.readString(path);
            var matcher = CODE_DERIVED_NAME.matcher(source);
            List<String> violations = new ArrayList<>();
            while (matcher.find()) {
                String assignment = matcher.group();
                if (!assignment.contains("BusinessDisplayNamePolicy.requireHumanName")
                    && !assignment.contains("BusinessDisplayNamePolicy.requireWorkflowDocumentName")) {
                    violations.add(path + " -> " + assignment.replaceAll("\\s+", " "));
                }
            }
            return violations;
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
