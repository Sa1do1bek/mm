package com.enterprise.mm.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Guards the department selector used by service acceptance intake options.
 */
class DepartmentRepositorySearchContractTest {

    @Test
    void departmentSearchCastsSearchAsStringForPostgreSQL() throws Exception {
        String source = Files.readString(Path.of(
            "src/main/java/com/enterprise/mm/repository/DepartmentRepository.java"));

        assertThat(source).contains("Page<Department> search");
        assertThat(source).contains("CAST(:search AS string) IS NULL");
        assertThat(source).contains("LOWER(CAST(:search AS string))");
        assertThat(source).doesNotContain(
            "LOWER(COALESCE(e.code, '')) LIKE LOWER(CONCAT('%', :search, '%'))");
    }


}
