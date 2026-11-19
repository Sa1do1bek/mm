package com.enterprise.mm.integration.faktura;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

class FakturaMigrationTest {
    @Test
    void sourceLinesRunsAndReceiptEvidenceMigrateTogether() throws Exception {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").load().migrate();
            try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 var statement = connection.createStatement()) {
                try (var rows = statement.executeQuery("""
                    SELECT table_name FROM information_schema.tables
                    WHERE table_schema='public' AND table_name IN
                      ('faktura_source_documents','faktura_source_lines','faktura_import_runs',
                       'faktura_import_failures','faktura_receipt_evidence')
                    """)) {
                    int count = 0;
                    while (rows.next()) count++;
                    assertThat(count).isEqualTo(5);
                }
                UUID company = UUID.fromString("11111111-1111-1111-1111-111111111111");
                UUID otherCompany = UUID.fromString("22222222-2222-2222-2222-222222222222");
                statement.executeUpdate("INSERT INTO public.companies(id,tenant_id,company_id,created_at,name) VALUES "
                    + "('" + company + "','tenant-a','" + company + "',now(),'Company A'),"
                    + "('" + otherCompany + "','tenant-a','" + otherCompany + "',now(),'Company B')");
                statement.executeUpdate("""
                    INSERT INTO public.contracts(id,tenant_id,company_id,created_at,name,supplier_id)
                    VALUES
                      ('11111111-aaaa-aaaa-aaaa-aaaaaaaaaaaa','tenant-a','11111111-1111-1111-1111-111111111111',now(),'Contract A','aaaaaaaa-0000-0000-0000-000000000001'),
                      ('22222222-aaaa-aaaa-aaaa-aaaaaaaaaaaa','tenant-a','11111111-1111-1111-1111-111111111111',now(),'Contract B','aaaaaaaa-0000-0000-0000-000000000002')
                    """);
                statement.executeUpdate("""
                    INSERT INTO public.faktura_source_documents
                      (id,tenant_id,company_id,source_unique_id,document_type,document_number,
                       document_date,owner_name,contractor_name,contract_number,contract_id,payload_sha256,source_payload)
                    VALUES
                      ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa','tenant-a','11111111-1111-1111-1111-111111111111',
                       'doc-a',28,'A-1','2026-09-01','Supplier A','Company A','C-1','11111111-aaaa-aaaa-aaaa-aaaaaaaaaaaa','hash-a','{}'),
                      ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb','tenant-a','11111111-1111-1111-1111-111111111111',
                       'doc-b',20,'B-1','2026-10-01','Company A','Supplier B','C-2','22222222-aaaa-aaaa-aaaa-aaaaaaaaaaaa','hash-b','{}'),
                      ('cccccccc-cccc-cccc-cccc-cccccccccccc','tenant-a','22222222-2222-2222-2222-222222222222',
                       'doc-c',28,'C-1','2026-08-01','Supplier C','Company B','C-3',NULL,'hash-c','{}')
                    """);
                statement.executeUpdate("""
                    INSERT INTO public.faktura_import_runs
                      (id,tenant_id,company_id,requested_from,requested_to,status)
                    VALUES ('dddddddd-dddd-dddd-dddd-dddddddddddd','tenant-a',
                      '11111111-1111-1111-1111-111111111111','2026-09-01','2026-09-30','PARTIAL')
                    """);
                statement.executeUpdate("""
                    INSERT INTO public.faktura_import_failures
                      (id,run_id,tenant_id,company_id,document_type,source_unique_id,
                       document_number,stage,reason_code)
                    VALUES
                      ('eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee','dddddddd-dddd-dddd-dddd-dddddddddddd',
                       'tenant-a','11111111-1111-1111-1111-111111111111',28,'doc-a','A-1','CONTENT','CONTENT_MISSING'),
                      ('ffffffff-ffff-ffff-ffff-ffffffffffff','dddddddd-dddd-dddd-dddd-dddddddddddd',
                       'tenant-a','11111111-1111-1111-1111-111111111111',20,'doc-b','B-1','SAVE','SAVE_FAILED')
                    """);
                var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                var jdbc = new JdbcTemplate(dataSource);
                var query = new FakturaDocumentQuery(jdbc, null, null, null);
                TenantContext.setTenant("tenant-a");
                CompanyContext.setCompany(company);
                try {
                    var filters = new FakturaDocumentQuery.BrowseFilters(null, null, null,
                        null, null, null, null, null, null, null, null, null, "date", "asc", null, null);
                    var firstPage = query.browse(filters, 0, 1);
                    assertThat(firstPage.totalElements()).isEqualTo(2);
                    assertThat(firstPage.content()).extracting(FakturaDocumentQuery.DocumentSummary::number)
                        .containsExactly("A-1");
                    var filtered = query.browse(new FakturaDocumentQuery.BrowseFilters(null, null, null,
                        "B-1", null, null, null, null, null, null, null, null, "number", "desc", null, null), 0, 20);
                    assertThat(filtered.totalElements()).isEqualTo(1);
                    assertThat(filtered.content()).extracting(FakturaDocumentQuery.DocumentSummary::number)
                        .containsExactly("B-1");
                    var linkedInvoices = query.browse(new FakturaDocumentQuery.BrowseFilters(null, null, null,
                        null, null, null, null, null, null, null, null, null, "date", "desc",
                        UUID.fromString("11111111-aaaa-aaaa-aaaa-aaaaaaaaaaaa"), "INVOICES"), 0, 20);
                    assertThat(linkedInvoices.totalElements()).isEqualTo(1);
                    assertThat(linkedInvoices.content()).extracting(FakturaDocumentQuery.DocumentSummary::number)
                        .containsExactly("A-1");
                    var linkedPoa = query.browse(new FakturaDocumentQuery.BrowseFilters(null, 20, null,
                        null, null, null, null, null, null, null, null, null, "date", "desc",
                        UUID.fromString("22222222-aaaa-aaaa-aaaa-aaaaaaaaaaaa"), null), 0, 20);
                    assertThat(linkedPoa.content()).extracting(FakturaDocumentQuery.DocumentSummary::number)
                        .containsExactly("B-1");
                    var failures = new FakturaImportService(null, null, jdbc, null).failures(
                        UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd"), null, "A-1", "CONTENT", null,
                        "documentNumber", "desc", 0, 20);
                    assertThat(failures.totalElements()).isEqualTo(1);
                    assertThat(failures.content()).extracting(FakturaImportService.ImportFailure::documentNumber)
                        .containsExactly("A-1");
                } finally {
                    CompanyContext.clear();
                    TenantContext.clear();
                }
            }
        }
    }
}
