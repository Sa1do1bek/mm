package com.enterprise.mm.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

class SupplierRepositoryRfqCandidateQueryTest {

    @Test
    void typedSearchQueryNeverUsesNullableParameterInsideLower() throws Exception {
        Method typedSearch = SupplierRepository.class.getMethod(
            "findActiveRfqSupplierCandidates", String.class, java.util.UUID.class, String.class);
        String query = typedSearch.getAnnotation(Query.class).value();

        assertThat(query).doesNotContain(":search is null");
        assertThat(query).contains("s.tenantId = :tenantId", "s.companyId = :companyId");
        assertThat(query).contains("lower(s.code)", "lower(s.name)", "lower(s.email)");
    }

    @Test
    void blankSearchUsesAParameterFreeTenantAndCompanyScopedQuery() throws Exception {
        Method blankSearch = SupplierRepository.class.getMethod(
            "findActiveRfqSupplierCandidates", String.class, java.util.UUID.class);
        String query = blankSearch.getAnnotation(Query.class).value();

        assertThat(query).doesNotContain(":search");
        assertThat(query).contains("s.tenantId = :tenantId", "s.companyId = :companyId");
    }
}
