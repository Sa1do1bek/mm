package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ProcurementMethodEnumCatalogTest {
    @Test
    void procurementCatalogsUseSingleMethodEnum() throws Exception {
        String source = Files.readString(Path.of(
            "src/main/java/com/enterprise/mm/service/EnumCatalogService.java"
        ));

        assertThat(source)
            .contains("Map.entry(\"methods\", \"ProcurementMethodType\")")
            .contains("Map.entry(\"sourcing-approaches\", \"ProcurementMethodType\")")
            .doesNotContain("ProcurementSourcingApproach");
        assertThat(Path.of(
            "src/main/java/com/enterprise/mm/enums/ProcurementSourcingApproach.java"
        )).doesNotExist();
    }
}
