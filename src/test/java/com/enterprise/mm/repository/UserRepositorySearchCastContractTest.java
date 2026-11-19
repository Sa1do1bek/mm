package com.enterprise.mm.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Guards the PostgreSQL-safe search casts on UserRepository.
 * Without CAST(:search AS string), Hibernate can bind LIKE parameters as bytea and
 * PostgreSQL rejects lower(bytea) on /api/workspaces/supply/selectors/owners.
 */
class UserRepositorySearchCastContractTest {

    @Test
    void activeCompanyUserSearchCastsSearchAsStringForPostgreSQL() throws Exception {
        String source = Files.readString(Path.of(
            "src/main/java/com/enterprise/mm/repository/UserRepository.java"));

        assertThat(source).contains("searchActiveCompanyUsers");
        assertThat(source).contains("CAST(:search AS string) IS NULL");
        assertThat(source).contains("LOWER(CAST(:search AS string))");
        assertThat(source).doesNotContain(
            "LOWER(COALESCE(e.username, '')) LIKE LOWER(CONCAT('%', :search, '%'))");
    }
}
