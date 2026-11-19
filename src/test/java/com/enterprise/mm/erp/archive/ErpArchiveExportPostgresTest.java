package com.enterprise.mm.erp.archive;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named="MM_ARCHIVE_TEST_PG_URL", matches=".+")
class ErpArchiveExportPostgresTest {
    @Test
    @EnabledIfEnvironmentVariable(named="MM_ARCHIVE_SCHEMA_PG_URL", matches=".+")
    void exportsMigratedSchemaForErpEndToEnd() throws Exception {
        String url = System.getenv("MM_ARCHIVE_SCHEMA_PG_URL");
        if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/mm_archive_schema_check"))
            throw new IllegalArgumentException("Expected isolated mm_archive_schema_check database");
        var ds = new DriverManagerDataSource(url, System.getenv().getOrDefault("MM_ARCHIVE_TEST_PG_USER", "tenzor"), "");
        var service = new ErpArchiveExportService(new JdbcTemplate(ds), new ObjectMapper());
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        tx.setReadOnly(true); tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        var file = java.nio.file.Path.of("/tmp/mm-archive-expanded-fixture.ndjson");
        try (var out = java.nio.file.Files.newOutputStream(file)) {
            tx.executeWithoutResult(status -> { try { service.write(out, "test-tenant", UUID.fromString("00000000-0000-0000-0000-000000000002")); } catch (Exception e) { throw new RuntimeException(e); } });
        }
        try (var lines = java.nio.file.Files.lines(file)) {
            assertThat(lines.filter(line -> line.contains("\"type\":\"row\"")).count()).isGreaterThanOrEqualTo(25005);
        }
    }
    @Test void oneSnapshotExcludesConcurrentUpdatesAndStreamsLargeData() {
        String baseUrl = System.getenv("MM_ARCHIVE_TEST_PG_URL");
        String user = System.getenv().getOrDefault("MM_ARCHIVE_TEST_PG_USER", "tenzor");
        String schema = "mm_archive_test_" + UUID.randomUUID().toString().replace("-", "");
        var admin = new JdbcTemplate(new DriverManagerDataSource(baseUrl, user, ""));
        admin.execute("create schema " + schema);
        try {
        String scopedUrl = baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        var ds = new DriverManagerDataSource(scopedUrl, user, "");
        var jdbc = new JdbcTemplate(ds); UUID company = UUID.randomUUID(), plan = UUID.randomUUID();
        var concurrentJdbc = new JdbcTemplate(new DriverManagerDataSource(scopedUrl, user, ""));
        String[] tables = {"purchase_orders", "purchase_order_items", "stock_movements", "warehouse_imported_movements", "stock_cost_movements", "stock_balances", "procurement_plans", "purchase_requests", "purchase_request_items", "purchase_order_source_allocations", "contracts", "rfqs", "procurement_campaigns", "procurement_procedures", "goods_receipts", "goods_receipt_lines", "stock_movement_allocations", "stock_movement_allocation_cost_layers", "inventory_cost_layers", "warehouse_history_runs", "warehouse_history_records", "purchase_order_revisions", "rfq_items", "rfq_published_versions", "rfq_purchase_request_sources", "supplier_quotation_threads", "supplier_quotations", "supplier_quotation_items", "procurement_award_outcome_lines", "procurement_award_outcomes", "procurement_direct_awards", "procurement_line_allocations", "procurement_method_decisions", "procurement_closeout_audit_events", "procurement_closeout_blockers", "procurement_closeout_readiness_snapshots", "procurement_closeout_reopen_history", "procurement_closeouts", "procurement_framework_ledger", "procurement_frameworks", "procurement_work_assignments", "contract_addendums", "contract_invoice_evidence_snapshots", "contract_invoices", "contract_payments", "contract_reconciliation_acts", "contract_specifications", "contract_material_links", "goods_receipt_batches", "goods_receipt_evidence", "goods_receipt_units", "stock_batches", "stock_movement_documents", "stock_valuation_documents", "stock_valuation_history", "stock_valuations", "stock_valuation_runs", "stock_valuation_cost_jobs", "inventory_documents", "inventory_document_materials", "inventory_document_material_groups", "inventory_items", "inventory_line_history", "inventory_period_closes", "inventory_fx_differences", "inventory_accounting_reconciliations", "warehouse_account_imports", "warehouse_account_entries", "warehouse_posting_batches", "warehouse_activation_journals", "warehouse_transfer_groups", "warehouse_transfer_group_items", "warehouse_holding_handovers", "warehouse_holding_transfers", "warehouse_custody_ownership_transfers", "warehouse_custody_activations", "warehouse_production_receipts", "warehouse_production_corrections", "warehouse_transformation_orders", "warehouse_accounting_positions", "warehouse_activation_plans", "warehouse_production_operations", "stock_issue_account_reviews", "one_c_warehouse_documents", "one_c_warehouse_revisions", "one_c_warehouse_history_runs", "tender_bid_opening_lines", "tender_bid_opening_protocols", "tender_commission_members", "tender_commissions", "tender_conflict_checks", "tender_evaluation_protocols", "tender_price_calculation_quotations", "tender_price_calculations", "service_acceptance_acts", "consignment_stock_transactions", "contract_powers_of_attorney", "damaged_stock_cases", "faktura_receipt_evidence", "inventory_orders", "issue_request_lines", "issue_requests", "manual_receipt_account_drafts", "material_quality_issues", "mobile_warehouse_operations", "non_moving_stock_case_lines", "non_moving_stock_cases", "non_moving_stock_decision_lines", "non_moving_stock_decisions", "non_moving_stock_execution_outputs", "non_moving_stock_executions", "non_moving_stocks", "procurement_category_strategies", "procurement_method_capabilities", "rfq_delivery_attempts", "rfq_invitation_deliveries", "rfq_supplier_invitations", "transfer_allocation_clearances", "transfer_document_lines", "transfer_documents", "transfer_exceptions", "transfer_shipment_allocations", "transfer_shipments", "transfer_transit_entries", "vmi_stock_agreements", "warehouse_custody_agreements", "warehouse_holdings", "warehouse_installation_capitalizations", "warehouse_installation_positions", "warehouse_posting_rules", "warehouse_report_approvals", "delivery_schedules", "supplier_po_responses", "supplier_po_response_lines", "supplier_po_commitments", "supplier_po_dispatches", "return_documents", "return_document_lines", "service_orders", "service_requests", "service_claims", "work_completion_acts", "finished_goods_opening_positions", "exact_opening_imports", "materials", "suppliers", "warehouses"};
        for (String table : tables) {
            boolean textCompany = java.util.Set.of("warehouse_production_receipts", "warehouse_production_corrections", "warehouse_transformation_orders", "warehouse_installation_capitalizations", "warehouse_installation_positions").contains(table);
            jdbc.execute("create table " + table + " (id uuid primary key, tenant_id text not null, company_id " + (textCompany ? "text" : "uuid") + " not null, value text)");
        }
        jdbc.execute("create table one_c_warehouse_current_rows (document_head_id uuid, row_key text, value text)");
        jdbc.execute("create table one_c_warehouse_history_pages (run_id uuid, page_id text, value text)");
        jdbc.execute("create table one_c_warehouse_outbound (operation_id uuid, tenant_id text, company_id uuid, value text)");
        jdbc.execute("create table one_c_warehouse_outbound_versions (operation_id uuid, revision bigint, value text)");
        jdbc.execute("create table warehouse_history_chunks (import_id uuid, first_seq bigint, value text)");
        jdbc.execute("create table warehouse_posting_effects (batch_id uuid, effect_sequence int, value text)");
        jdbc.execute("create table warehouse_transformation_lines (id uuid primary key, order_id uuid, value text)");
        jdbc.execute("create table warehouse_production_operation_lines (id uuid primary key, operation_id uuid, value text)");
        jdbc.execute("create table warehouse_account_chunks (import_id uuid, first_seq bigint, value text)");
        jdbc.execute("create table inventory_document_departments (inventory_document_id uuid, department_id uuid, value text)");
        jdbc.execute("create table inventory_document_selected_warehouses (inventory_document_id uuid, warehouse_id uuid, value text)");
        jdbc.execute("create table inventory_document_resolved_warehouses (inventory_document_id uuid, warehouse_id uuid, value text)");
        jdbc.execute("create table inventory_order_allocations (id uuid primary key, order_id uuid, value text)");
        jdbc.execute("create table stock_valuation_cost_job_results (id uuid primary key, job_id uuid, value text)");
        jdbc.execute("create table stock_valuation_cost_job_warehouses (job_id uuid, warehouse_id uuid, value text)");
        jdbc.execute("create table stock_valuation_run_items (id uuid primary key, valuation_run_id uuid, value text)");
        jdbc.execute("create table stock_valuation_run_warehouses (valuation_run_id uuid, warehouse_id uuid, value text)");
        jdbc.execute("create table kimyosanoat_issue_links (source_transfer_id bigint, tenant_id text, company_id uuid, value text)");
        jdbc.execute("create table warehouse_issue_source_links (source_document_id uuid, tenant_id text, company_id uuid)");
        jdbc.execute("create table warehouse_source_master_links (tenant_id text, company_id uuid, reference_kind text, reference_type text, source_id text)");
        jdbc.execute("create table warehouse_history_projection_state (tenant_id text, company_id uuid, stage text)");
        jdbc.execute("create table exact_opening_rows (id uuid primary key, import_id uuid, value text)");
        UUID document = UUID.randomUUID(), otherDocument = UUID.randomUUID();
        jdbc.update("insert into one_c_warehouse_documents values (?,?,?,?)", document, "tenant", company, "document");
        jdbc.update("insert into one_c_warehouse_documents values (?,?,?,?)", otherDocument, "tenant", UUID.randomUUID(), "other-document");
        jdbc.update("insert into one_c_warehouse_current_rows values (?,?,?)", document, "row/one", "scoped-row");
        jdbc.update("insert into one_c_warehouse_current_rows values (?,?,?)", otherDocument, "row/two", "other-scoped-row");
        UUID historyRun = UUID.randomUUID(), nativeRun = UUID.randomUUID(), batch = UUID.randomUUID(), operation = UUID.randomUUID();
        jdbc.update("insert into warehouse_history_runs values (?,?,?,?)", historyRun, "tenant", company, "history-run");
        jdbc.update("insert into one_c_warehouse_history_runs values (?,?,?,?)", nativeRun, "tenant", company, "native-run");
        jdbc.update("insert into warehouse_posting_batches values (?,?,?,?)", batch, "tenant", company, "batch");
        jdbc.update("insert into one_c_warehouse_outbound values (?,?,?,?)", operation, "tenant", company, "outbound");
        jdbc.update("insert into one_c_warehouse_history_pages values (?,?,?)", nativeRun, "page/1", "page-data");
        jdbc.update("insert into warehouse_history_chunks values (?,?,?)", historyRun, 3L, "chunk-data");
        jdbc.update("insert into warehouse_posting_effects values (?,?,?)", batch, 2, "posting-data");
        jdbc.update("insert into one_c_warehouse_outbound_versions values (?,?,?)", operation, 7L, "version-data");
        jdbc.update("insert into stock_balances values (?,?,?,?)", UUID.randomUUID(),"tenant",company,"balance");
        jdbc.update("insert into procurement_plans values (?,?,?,?)", plan,"tenant",company,"before");
        jdbc.update("insert into procurement_plans values (?,?,?,?)", UUID.randomUUID(),"tenant",UUID.randomUUID(),"other-company");
        jdbc.update("insert into stock_movements select md5(i::text)::uuid, 'tenant', ?, 'row' from generate_series(1,25000) i", company);
        var service = new ErpArchiveExportService(jdbc, new ObjectMapper());
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds)); tx.setReadOnly(true); tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        var result = new ByteArrayOutputStream();
        OutputStream concurrent = new OutputStream() {
            boolean changed;
            @Override public void write(int b) { result.write(b); }
            @Override public void write(byte[] b, int off, int len) {
                result.write(b,off,len);
                String line = new String(b,off,len,StandardCharsets.UTF_8);
                if (!changed && line.contains("\"type\":\"row\"")) {
                    changed = true; concurrentJdbc.update("update procurement_plans set value='after' where id=?", plan);
                }
            }
        };
        tx.executeWithoutResult(status -> { try { service.write(concurrent,"tenant",company); } catch (Exception e) { throw new RuntimeException(e); } });
        String output = result.toString(StandardCharsets.UTF_8);
        assertThat(output).contains("\"value\":\"before\"").doesNotContain("\"value\":\"after\"", "other-company", "other-scoped-row");
        assertThat(output).contains("\"movements\":25000", "\"one-c-current-rows\":1", "scoped-row", "row/one", "\"type\":\"footer\"");
        assertThat(output).contains("\"one-c-history-pages\":1", "\"one-c-outbound-versions\":1", "\"warehouse-history-chunks\":1", "\"warehouse-posting-effects\":1", "page-data", "chunk-data", "posting-data", "version-data");
        assertThat(jdbc.queryForObject("select value from procurement_plans where id=?",String.class,plan)).isEqualTo("after");
        } finally { admin.execute("drop schema " + schema + " cascade"); }
    }
}
