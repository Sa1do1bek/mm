package com.enterprise.mm.erp.archive;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** One PostgreSQL MVCC snapshot is held until the final manifest is written. */
@Service
public class ErpArchiveExportService {
    private static final Map<String, String> TABLES = Map.ofEntries(
        Map.entry("orders", "purchase_orders"), Map.entry("order-items", "purchase_order_items"),
        Map.entry("requests", "purchase_requests"), Map.entry("request-items", "purchase_request_items"),
        Map.entry("order-allocations", "purchase_order_source_allocations"), Map.entry("contracts", "contracts"),
        Map.entry("rfqs", "rfqs"), Map.entry("campaigns", "procurement_campaigns"),
        Map.entry("procedures", "procurement_procedures"), Map.entry("plans", "procurement_plans"),
        Map.entry("receipts", "goods_receipts"), Map.entry("receipt-lines", "goods_receipt_lines"),
        Map.entry("movements", "stock_movements"), Map.entry("movement-allocations", "stock_movement_allocations"),
        Map.entry("movement-cost-allocations", "stock_movement_allocation_cost_layers"),
        Map.entry("cost-movements", "stock_cost_movements"), Map.entry("cost-layers", "inventory_cost_layers"),
        Map.entry("balances", "stock_balances"), Map.entry("imported-movements", "warehouse_imported_movements"),
        Map.entry("history-runs", "warehouse_history_runs"), Map.entry("history-records", "warehouse_history_records"),
        Map.entry("purchase-order-revisions", "purchase_order_revisions"),
        Map.entry("rfq-items", "rfq_items"),
        Map.entry("rfq-published-versions", "rfq_published_versions"),
        Map.entry("rfq-purchase-request-sources", "rfq_purchase_request_sources"),
        Map.entry("supplier-quotation-threads", "supplier_quotation_threads"),
        Map.entry("supplier-quotations", "supplier_quotations"),
        Map.entry("supplier-quotation-items", "supplier_quotation_items"),
        Map.entry("procurement-award-outcome-lines", "procurement_award_outcome_lines"),
        Map.entry("procurement-award-outcomes", "procurement_award_outcomes"),
        Map.entry("procurement-direct-awards", "procurement_direct_awards"),
        Map.entry("procurement-line-allocations", "procurement_line_allocations"),
        Map.entry("procurement-method-decisions", "procurement_method_decisions"),
        Map.entry("procurement-closeout-audit-events", "procurement_closeout_audit_events"),
        Map.entry("procurement-closeout-blockers", "procurement_closeout_blockers"),
        Map.entry("procurement-closeout-readiness-snapshots", "procurement_closeout_readiness_snapshots"),
        Map.entry("procurement-closeout-reopen-history", "procurement_closeout_reopen_history"),
        Map.entry("procurement-closeouts", "procurement_closeouts"),
        Map.entry("procurement-framework-ledger", "procurement_framework_ledger"),
        Map.entry("procurement-frameworks", "procurement_frameworks"),
        Map.entry("procurement-work-assignments", "procurement_work_assignments"),
        Map.entry("contract-addendums", "contract_addendums"),
        Map.entry("contract-invoice-evidence-snapshots", "contract_invoice_evidence_snapshots"),
        Map.entry("contract-invoices", "contract_invoices"),
        Map.entry("contract-payments", "contract_payments"),
        Map.entry("contract-reconciliation-acts", "contract_reconciliation_acts"),
        Map.entry("contract-specifications", "contract_specifications"),
        Map.entry("contract-material-links", "contract_material_links"),
        Map.entry("goods-receipt-batches", "goods_receipt_batches"),
        Map.entry("goods-receipt-evidence", "goods_receipt_evidence"),
        Map.entry("goods-receipt-units", "goods_receipt_units"),
        Map.entry("stock-batches", "stock_batches"),
        Map.entry("stock-movement-documents", "stock_movement_documents"),
        Map.entry("stock-valuation-documents", "stock_valuation_documents"),
        Map.entry("stock-valuation-history", "stock_valuation_history"),
        Map.entry("stock-valuations", "stock_valuations"),
        Map.entry("stock-valuation-runs", "stock_valuation_runs"),
        Map.entry("stock-valuation-cost-jobs", "stock_valuation_cost_jobs"),
        Map.entry("inventory-documents", "inventory_documents"),
        Map.entry("inventory-document-materials", "inventory_document_materials"),
        Map.entry("inventory-document-material-groups", "inventory_document_material_groups"),
        Map.entry("inventory-items", "inventory_items"),
        Map.entry("inventory-line-history", "inventory_line_history"),
        Map.entry("inventory-period-closes", "inventory_period_closes"),
        Map.entry("inventory-fx-differences", "inventory_fx_differences"),
        Map.entry("inventory-accounting-reconciliations", "inventory_accounting_reconciliations"),
        Map.entry("warehouse-account-imports", "warehouse_account_imports"),
        Map.entry("warehouse-account-entries", "warehouse_account_entries"),
        Map.entry("warehouse-posting-batches", "warehouse_posting_batches"),
        Map.entry("warehouse-activation-journals", "warehouse_activation_journals"),
        Map.entry("warehouse-transfer-groups", "warehouse_transfer_groups"),
        Map.entry("warehouse-transfer-group-items", "warehouse_transfer_group_items"),
        Map.entry("warehouse-holding-handovers", "warehouse_holding_handovers"),
        Map.entry("warehouse-holding-transfers", "warehouse_holding_transfers"),
        Map.entry("warehouse-custody-ownership-transfers", "warehouse_custody_ownership_transfers"),
        Map.entry("warehouse-custody-activations", "warehouse_custody_activations"),
        Map.entry("warehouse-production-receipts", "warehouse_production_receipts"),
        Map.entry("warehouse-production-corrections", "warehouse_production_corrections"),
        Map.entry("warehouse-transformation-orders", "warehouse_transformation_orders"),
        Map.entry("warehouse-accounting-positions", "warehouse_accounting_positions"),
        Map.entry("warehouse-activation-plans", "warehouse_activation_plans"),
        Map.entry("warehouse-production-operations", "warehouse_production_operations"),
        Map.entry("stock-issue-account-reviews", "stock_issue_account_reviews"),
        Map.entry("one-c-warehouse-documents", "one_c_warehouse_documents"),
        Map.entry("one-c-warehouse-revisions", "one_c_warehouse_revisions"),
        Map.entry("one-c-warehouse-history-runs", "one_c_warehouse_history_runs"),
        Map.entry("tender-bid-opening-lines", "tender_bid_opening_lines"),
        Map.entry("tender-bid-opening-protocols", "tender_bid_opening_protocols"),
        Map.entry("tender-commission-members", "tender_commission_members"),
        Map.entry("tender-commissions", "tender_commissions"),
        Map.entry("tender-conflict-checks", "tender_conflict_checks"),
        Map.entry("tender-evaluation-protocols", "tender_evaluation_protocols"),
        Map.entry("tender-price-calculation-quotations", "tender_price_calculation_quotations"),
        Map.entry("tender-price-calculations", "tender_price_calculations"),
        Map.entry("service-acceptance-acts", "service_acceptance_acts"),
        Map.entry("consignment-stock-transactions", "consignment_stock_transactions"),
        Map.entry("contract-powers-of-attorney", "contract_powers_of_attorney"),
        Map.entry("damaged-stock-cases", "damaged_stock_cases"),
        Map.entry("faktura-receipt-evidence", "faktura_receipt_evidence"),
        Map.entry("inventory-orders", "inventory_orders"),
        Map.entry("issue-request-lines", "issue_request_lines"),
        Map.entry("issue-requests", "issue_requests"),
        Map.entry("manual-receipt-account-drafts", "manual_receipt_account_drafts"),
        Map.entry("material-quality-issues", "material_quality_issues"),
        Map.entry("mobile-warehouse-operations", "mobile_warehouse_operations"),
        Map.entry("non-moving-stock-case-lines", "non_moving_stock_case_lines"),
        Map.entry("non-moving-stock-cases", "non_moving_stock_cases"),
        Map.entry("non-moving-stock-decision-lines", "non_moving_stock_decision_lines"),
        Map.entry("non-moving-stock-decisions", "non_moving_stock_decisions"),
        Map.entry("non-moving-stock-execution-outputs", "non_moving_stock_execution_outputs"),
        Map.entry("non-moving-stock-executions", "non_moving_stock_executions"),
        Map.entry("non-moving-stocks", "non_moving_stocks"),
        Map.entry("procurement-category-strategies", "procurement_category_strategies"),
        Map.entry("procurement-method-capabilities", "procurement_method_capabilities"),
        Map.entry("rfq-delivery-attempts", "rfq_delivery_attempts"),
        Map.entry("rfq-invitation-deliveries", "rfq_invitation_deliveries"),
        Map.entry("rfq-supplier-invitations", "rfq_supplier_invitations"),
        Map.entry("transfer-allocation-clearances", "transfer_allocation_clearances"),
        Map.entry("transfer-document-lines", "transfer_document_lines"),
        Map.entry("transfer-documents", "transfer_documents"),
        Map.entry("transfer-exceptions", "transfer_exceptions"),
        Map.entry("transfer-shipment-allocations", "transfer_shipment_allocations"),
        Map.entry("transfer-shipments", "transfer_shipments"),
        Map.entry("transfer-transit-entries", "transfer_transit_entries"),
        Map.entry("vmi-stock-agreements", "vmi_stock_agreements"),
        Map.entry("warehouse-custody-agreements", "warehouse_custody_agreements"),
        Map.entry("warehouse-holdings", "warehouse_holdings"),
        Map.entry("warehouse-installation-capitalizations", "warehouse_installation_capitalizations"),
        Map.entry("warehouse-installation-positions", "warehouse_installation_positions"),
        Map.entry("warehouse-posting-rules", "warehouse_posting_rules"),
        Map.entry("warehouse-report-approvals", "warehouse_report_approvals"),
        Map.entry("delivery-schedules", "delivery_schedules"),
        Map.entry("supplier-po-responses", "supplier_po_responses"),
        Map.entry("supplier-po-response-lines", "supplier_po_response_lines"),
        Map.entry("supplier-po-commitments", "supplier_po_commitments"),
        Map.entry("supplier-po-dispatches", "supplier_po_dispatches"),
        Map.entry("return-documents", "return_documents"),
        Map.entry("return-document-lines", "return_document_lines"),
        Map.entry("service-orders", "service_orders"),
        Map.entry("service-requests", "service_requests"),
        Map.entry("service-claims", "service_claims"),
        Map.entry("work-completion-acts", "work_completion_acts"),
        Map.entry("finished-goods-opening-positions", "finished_goods_opening_positions"),
        Map.entry("exact-opening-imports", "exact_opening_imports"),
        Map.entry("materials", "materials"), Map.entry("suppliers", "suppliers"), Map.entry("warehouses", "warehouses"));
    private static final Map<String, String> JOINED = Map.ofEntries(
        Map.entry("one-c-current-rows", "select encode(sha256(convert_to(json_build_array(t.document_head_id, t.row_key)::text, 'UTF8')), 'hex') as source_id, (to_jsonb(t) || jsonb_build_object('id', encode(sha256(convert_to(json_build_array(t.document_head_id, t.row_key)::text, 'UTF8')), 'hex'), 'tenant_id', p.tenant_id, 'company_id', p.company_id))::text from one_c_warehouse_current_rows t join one_c_warehouse_documents p on p.id = t.document_head_id where p.tenant_id=? and p.company_id=? order by t.document_head_id, t.row_key"),
        Map.entry("one-c-history-pages", "select encode(sha256(convert_to(json_build_array(t.run_id, t.page_id)::text, 'UTF8')), 'hex') as source_id, (to_jsonb(t) || jsonb_build_object('id', encode(sha256(convert_to(json_build_array(t.run_id, t.page_id)::text, 'UTF8')), 'hex'), 'tenant_id', p.tenant_id, 'company_id', p.company_id))::text from one_c_warehouse_history_pages t join one_c_warehouse_history_runs p on p.id = t.run_id where p.tenant_id=? and p.company_id=? order by t.run_id, t.page_id"),
        Map.entry("one-c-outbound-versions", "select encode(sha256(convert_to(json_build_array(t.operation_id, t.revision)::text, 'UTF8')), 'hex') as source_id, (to_jsonb(t) || jsonb_build_object('id', encode(sha256(convert_to(json_build_array(t.operation_id, t.revision)::text, 'UTF8')), 'hex'), 'tenant_id', p.tenant_id, 'company_id', p.company_id))::text from one_c_warehouse_outbound_versions t join one_c_warehouse_outbound p on p.operation_id = t.operation_id where p.tenant_id=? and p.company_id=? order by t.operation_id, t.revision"),
        Map.entry("warehouse-history-chunks", "select encode(sha256(convert_to(json_build_array(t.import_id, t.first_seq)::text, 'UTF8')), 'hex') as source_id, (to_jsonb(t) || jsonb_build_object('id', encode(sha256(convert_to(json_build_array(t.import_id, t.first_seq)::text, 'UTF8')), 'hex'), 'tenant_id', p.tenant_id, 'company_id', p.company_id))::text from warehouse_history_chunks t join warehouse_history_runs p on p.id = t.import_id where p.tenant_id=? and p.company_id=? order by t.import_id, t.first_seq"),
        Map.entry("warehouse-posting-effects", "select encode(sha256(convert_to(json_build_array(t.batch_id, t.effect_sequence)::text, 'UTF8')), 'hex') as source_id, (to_jsonb(t) || jsonb_build_object('id', encode(sha256(convert_to(json_build_array(t.batch_id, t.effect_sequence)::text, 'UTF8')), 'hex'), 'tenant_id', p.tenant_id, 'company_id', p.company_id))::text from warehouse_posting_effects t join warehouse_posting_batches p on p.id = t.batch_id where p.tenant_id=? and p.company_id=? order by t.batch_id, t.effect_sequence"),
        Map.entry("warehouse-transformation-lines", "select t.id, (to_jsonb(t) || jsonb_build_object('tenant_id', p.tenant_id, 'company_id', p.company_id))::text from warehouse_transformation_lines t join warehouse_transformation_orders p on p.id=t.order_id where p.tenant_id=? and p.company_id=?::text order by t.id"),
        Map.entry("warehouse-production-operation-lines", "select t.id, (to_jsonb(t) || jsonb_build_object('tenant_id', p.tenant_id, 'company_id', p.company_id))::text from warehouse_production_operation_lines t join warehouse_production_operations p on p.id=t.operation_id where p.tenant_id=? and p.company_id=? order by t.id"),
        Map.entry("warehouse-account-chunks", "select encode(sha256(convert_to(json_build_array(t.import_id, t.first_seq)::text, 'UTF8')), 'hex'), (to_jsonb(t) || jsonb_build_object('id', encode(sha256(convert_to(json_build_array(t.import_id, t.first_seq)::text, 'UTF8')), 'hex'), 'tenant_id', p.tenant_id, 'company_id', p.company_id))::text from warehouse_account_chunks t join warehouse_account_imports p on p.id=t.import_id where p.tenant_id=? and p.company_id=? order by t.import_id, t.first_seq"),
        Map.entry("inventory-document-departments", "select encode(sha256(convert_to(json_build_array(t.inventory_document_id, t.department_id)::text, 'UTF8')), 'hex'), (to_jsonb(t) || jsonb_build_object('id', encode(sha256(convert_to(json_build_array(t.inventory_document_id, t.department_id)::text, 'UTF8')), 'hex'), 'tenant_id', p.tenant_id, 'company_id', p.company_id))::text from inventory_document_departments t join inventory_documents p on p.id=t.inventory_document_id where p.tenant_id=? and p.company_id=? order by t.inventory_document_id, t.department_id"),
        Map.entry("inventory-document-selected-warehouses", "select encode(sha256(convert_to(json_build_array(t.inventory_document_id, t.warehouse_id)::text, 'UTF8')), 'hex'), (to_jsonb(t) || jsonb_build_object('id', encode(sha256(convert_to(json_build_array(t.inventory_document_id, t.warehouse_id)::text, 'UTF8')), 'hex'), 'tenant_id', p.tenant_id, 'company_id', p.company_id))::text from inventory_document_selected_warehouses t join inventory_documents p on p.id=t.inventory_document_id where p.tenant_id=? and p.company_id=? order by t.inventory_document_id, t.warehouse_id"),
        Map.entry("inventory-document-resolved-warehouses", "select encode(sha256(convert_to(json_build_array(t.inventory_document_id, t.warehouse_id)::text, 'UTF8')), 'hex'), (to_jsonb(t) || jsonb_build_object('id', encode(sha256(convert_to(json_build_array(t.inventory_document_id, t.warehouse_id)::text, 'UTF8')), 'hex'), 'tenant_id', p.tenant_id, 'company_id', p.company_id))::text from inventory_document_resolved_warehouses t join inventory_documents p on p.id=t.inventory_document_id where p.tenant_id=? and p.company_id=? order by t.inventory_document_id, t.warehouse_id"),
        Map.entry("inventory-order-allocations", "select t.id, (to_jsonb(t) || jsonb_build_object('tenant_id', p.tenant_id, 'company_id', p.company_id))::text from inventory_order_allocations t join inventory_orders p on p.id=t.order_id where p.tenant_id=? and p.company_id=? order by 1"),
        Map.entry("stock-valuation-cost-job-results", "select t.id, (to_jsonb(t) || jsonb_build_object('tenant_id', p.tenant_id, 'company_id', p.company_id))::text from stock_valuation_cost_job_results t join stock_valuation_cost_jobs p on p.id=t.job_id where p.tenant_id=? and p.company_id=? order by 1"),
        Map.entry("stock-valuation-cost-job-warehouses", "select encode(sha256(convert_to(json_build_array(t.job_id, t.warehouse_id)::text, 'UTF8')), 'hex'), (to_jsonb(t) || jsonb_build_object('id', encode(sha256(convert_to(json_build_array(t.job_id, t.warehouse_id)::text, 'UTF8')), 'hex'), 'tenant_id', p.tenant_id, 'company_id', p.company_id))::text from stock_valuation_cost_job_warehouses t join stock_valuation_cost_jobs p on p.id=t.job_id where p.tenant_id=? and p.company_id=? order by t.job_id, t.warehouse_id"),
        Map.entry("stock-valuation-run-items", "select t.id, (to_jsonb(t) || jsonb_build_object('tenant_id', p.tenant_id, 'company_id', p.company_id))::text from stock_valuation_run_items t join stock_valuation_runs p on p.id=t.valuation_run_id where p.tenant_id=? and p.company_id=? order by 1"),
        Map.entry("stock-valuation-run-warehouses", "select encode(sha256(convert_to(json_build_array(t.valuation_run_id, t.warehouse_id)::text, 'UTF8')), 'hex'), (to_jsonb(t) || jsonb_build_object('id', encode(sha256(convert_to(json_build_array(t.valuation_run_id, t.warehouse_id)::text, 'UTF8')), 'hex'), 'tenant_id', p.tenant_id, 'company_id', p.company_id))::text from stock_valuation_run_warehouses t join stock_valuation_runs p on p.id=t.valuation_run_id where p.tenant_id=? and p.company_id=? order by t.valuation_run_id, t.warehouse_id"),
        Map.entry("kimyosanoat-issue-links", "select t.source_transfer_id::text, (to_jsonb(t) || jsonb_build_object('id', t.source_transfer_id::text))::text from kimyosanoat_issue_links t where t.tenant_id=? and t.company_id=? order by t.source_transfer_id"),
        Map.entry("warehouse-issue-source-links", "select t.source_document_id::text, (to_jsonb(t) || jsonb_build_object('id', t.source_document_id::text))::text from warehouse_issue_source_links t where t.tenant_id=? and t.company_id=? order by t.source_document_id"),
        Map.entry("warehouse-source-master-links", "select encode(sha256(convert_to(json_build_array(t.tenant_id,t.company_id,t.reference_kind,t.reference_type,t.source_id)::text, 'UTF8')), 'hex'), (to_jsonb(t) || jsonb_build_object('id', encode(sha256(convert_to(json_build_array(t.tenant_id,t.company_id,t.reference_kind,t.reference_type,t.source_id)::text, 'UTF8')), 'hex')))::text from warehouse_source_master_links t where t.tenant_id=? and t.company_id=? order by t.tenant_id, t.company_id, t.reference_kind, t.reference_type, t.source_id"),
        Map.entry("warehouse-history-projection-state", "select encode(sha256(convert_to(json_build_array(t.tenant_id,t.company_id,t.stage)::text, 'UTF8')), 'hex'), (to_jsonb(t) || jsonb_build_object('id', encode(sha256(convert_to(json_build_array(t.tenant_id,t.company_id,t.stage)::text, 'UTF8')), 'hex')))::text from warehouse_history_projection_state t where t.tenant_id=? and t.company_id=? order by t.tenant_id, t.company_id, t.stage"),
        Map.entry("exact-opening-rows", "select t.id, (to_jsonb(t) || jsonb_build_object('tenant_id', p.tenant_id, 'company_id', p.company_id))::text from exact_opening_rows t join exact_opening_imports p on p.id=t.import_id where p.tenant_id=? and p.company_id=? order by t.id"),
        Map.entry("one-c-outbound", "select t.operation_id::text, (to_jsonb(t) || jsonb_build_object('id', t.operation_id))::text from one_c_warehouse_outbound t where t.tenant_id=? and t.company_id=? order by t.operation_id"));
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public ErpArchiveExportService(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public void write(OutputStream output, String tenant, UUID company) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
        var resources = new java.util.TreeSet<String>(TABLES.keySet()); resources.addAll(JOINED.keySet());
        String header = mapper.writeValueAsString(Map.of("type", "header", "version", 1, "tenantId", tenant,
                "companyId", company.toString(), "snapshotId", UUID.randomUUID().toString(), "resources", resources));
        output.write((header + "\n").getBytes(StandardCharsets.UTF_8));
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String resource : resources) {
            boolean textCompany = java.util.Set.of("warehouse-production-receipts", "warehouse-production-corrections", "warehouse-transformation-orders", "warehouse-installation-capitalizations", "warehouse-installation-positions").contains(resource);
            String sql = JOINED.containsKey(resource) ? JOINED.get(resource) : "select id, row_to_json(t)::text from " + TABLES.get(resource) + " t where tenant_id=? and company_id=" + (textCompany ? "?::text" : "?") + " order by id";
            long[] count = {0};
            try {
                jdbc.query(connection -> {
                    var ps = connection.prepareStatement(sql, java.sql.ResultSet.TYPE_FORWARD_ONLY, java.sql.ResultSet.CONCUR_READ_ONLY);
                    ps.setFetchSize(100); ps.setString(1, tenant); ps.setObject(2, company); return ps;
                }, rs -> {
                    String id = rs.getString(1), payload = rs.getString(2);
                    try {
                        String line = "{\"type\":\"row\",\"resource\":" + mapper.writeValueAsString(resource)
                            + ",\"sourceId\":" + mapper.writeValueAsString(id) + ",\"payload\":" + payload + "}\n";
                        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
                        output.write(bytes); digest.update(bytes); count[0]++;
                    } catch (IOException ex) { throw new StreamFailure(ex); }
                });
            } catch (StreamFailure ex) { throw (IOException) ex.getCause(); }
            counts.put(resource, count[0]);
        }
        String footer = mapper.writeValueAsString(Map.of("type", "footer", "counts", counts, "sha256", HexFormat.of().formatHex(digest.digest())));
        output.write((footer + "\n").getBytes(StandardCharsets.UTF_8)); output.flush();
    }

    private static final class StreamFailure extends RuntimeException { StreamFailure(IOException cause) { super(cause); } }
}
