package com.enterprise.mm.integration.faktura;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.enterprise.mm.error.BusinessException;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class FakturaBrowseValidationTest {
    @Test
    void documentSortRejectsUnknownSqlBeforeQuerying() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        FakturaDocumentQuery query = new FakturaDocumentQuery(jdbc, null, null, null);
        FakturaDocumentQuery.BrowseFilters filters = new FakturaDocumentQuery.BrowseFilters(
            null, null, null, null, null, null, null, null, null, null, null, null,
            "lastSeenAt; DROP TABLE faktura_source_documents", "desc", null, null);

        assertThatThrownBy(() -> query.browse(filters, 0, 20)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    void failureSortRejectsUnknownSqlBeforeQuerying() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        FakturaImportService service = new FakturaImportService(null, null, jdbc, mock(Executor.class));

        assertThatThrownBy(() -> service.failures(UUID.randomUUID(), null, null, null, null,
            "createdAt; DROP TABLE faktura_import_failures", "asc", 0, 20))
            .isInstanceOf(BusinessException.class);
        verifyNoInteractions(jdbc);
    }
}
