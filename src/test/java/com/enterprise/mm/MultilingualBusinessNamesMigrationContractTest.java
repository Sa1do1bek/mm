package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MultilingualBusinessNamesMigrationContractTest {



    @Test
    void mxikSearchQueriesDoNotReferenceDroppedLanguageColumns() throws Exception {
        String repository = Files.readString(Path.of(
            "src/main/java/com/enterprise/mm/repository/MXIKRepository.java"
        ));

        assertThat(repository)
            .doesNotContain("name_uz_latn")
            .doesNotContain("name_ru");
    }
}
