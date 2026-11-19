-- Preserve the existing 15 integer digits while allowing exact seven-place stock quantities.
-- Monetary amounts, prices, exchange rates and budget deltas keep their existing scales.
-- PostgreSQL requires dependent views to be removed while changing a referenced column type.
-- Retain the installed view definition and grants, then recreate it in this same transaction.
CREATE TEMP TABLE mm_quantity_precision_view_backup ON COMMIT DROP AS
SELECT pg_get_viewdef(c.oid, true) AS definition, pg_get_userbyid(c.relowner) AS owner_name, c.relacl AS grants
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public' AND c.relname = 'warehouse_inventory_reconciliation_preflight' AND c.relkind = 'v';
DROP VIEW public.warehouse_inventory_reconciliation_preflight;

ALTER TABLE unit_of_measures ADD COLUMN stock_quantity_scale integer;
ALTER TABLE unit_of_measures ADD CONSTRAINT ck_uom_stock_quantity_scale
    CHECK (stock_quantity_scale IS NULL OR stock_quantity_scale BETWEEN 0 AND 7);

ALTER TABLE advance_shipping_notice_batches
    ALTER COLUMN quantity TYPE numeric(22,7);

ALTER TABLE advance_shipping_notice_items
    ALTER COLUMN quantity TYPE numeric(22,7),
    ALTER COLUMN received_quantity TYPE numeric(22,7),
    ALTER COLUMN cancelled_quantity TYPE numeric(22,7);

ALTER TABLE advance_shipping_notices
    ALTER COLUMN total_quantity TYPE numeric(22,7);

ALTER TABLE asset_acceptance_lines
    ALTER COLUMN delivered_quantity TYPE numeric(22,7),
    ALTER COLUMN accepted_quantity TYPE numeric(22,7),
    ALTER COLUMN rejected_quantity TYPE numeric(22,7),
    ALTER COLUMN pending_quantity TYPE numeric(22,7),
    ALTER COLUMN hold_quantity TYPE numeric(22,7),
    ALTER COLUMN po_received_quantity_recorded TYPE numeric(22,7),
    ALTER COLUMN po_accepted_quantity_recorded TYPE numeric(22,7),
    ALTER COLUMN po_rejected_quantity_recorded TYPE numeric(22,7);

ALTER TABLE consignment_stock_transactions
    ALTER COLUMN quantity TYPE numeric(22,7);

ALTER TABLE consolidated_demands
    ALTER COLUMN total_requested_quantity TYPE numeric(22,7),
    ALTER COLUMN total_approved_quantity TYPE numeric(22,7);

ALTER TABLE contract_specifications
    ALTER COLUMN quantity_limit TYPE numeric(22,7);

ALTER TABLE cross_docking_tasks
    ALTER COLUMN quantity TYPE numeric(22,7);

ALTER TABLE data_migration_rows
    ALTER COLUMN quantity TYPE numeric(22,7);

ALTER TABLE delivery_schedules
    ALTER COLUMN planned_quantity TYPE numeric(22,7),
    ALTER COLUMN received_quantity TYPE numeric(22,7);

ALTER TABLE demand_forecasts
    ALTER COLUMN forecast_quantity TYPE numeric(22,7),
    ALTER COLUMN annualized_forecast_quantity TYPE numeric(22,7);

ALTER TABLE demand_plan_item_months
    ALTER COLUMN qty TYPE numeric(22,7),
    ALTER COLUMN approved_qty TYPE numeric(22,7),
    ALTER COLUMN reserved_qty TYPE numeric(22,7);

ALTER TABLE demand_plan_item_pr_allocations
    ALTER COLUMN allocated_quantity TYPE numeric(22,7);

ALTER TABLE demand_plan_items
    ALTER COLUMN requested_quantity TYPE numeric(22,7),
    ALTER COLUMN approved_quantity TYPE numeric(22,7),
    ALTER COLUMN stock_fulfillment_quantity TYPE numeric(22,7),
    ALTER COLUMN reserved_quantity TYPE numeric(22,7);

ALTER TABLE demand_planning_cycles
    ALTER COLUMN total_reserved_quantity TYPE numeric(22,7);

ALTER TABLE demand_plans
    ALTER COLUMN total_reserved_quantity TYPE numeric(22,7);

ALTER TABLE goods_receipt_batches
    ALTER COLUMN accepted_quantity TYPE numeric(22,7),
    ALTER COLUMN rejected_quantity TYPE numeric(22,7),
    ALTER COLUMN damaged_quantity TYPE numeric(22,7);

ALTER TABLE goods_receipt_lines
    ALTER COLUMN open_quantity_before TYPE numeric(22,7),
    ALTER COLUMN physical_received_quantity TYPE numeric(22,7),
    ALTER COLUMN accepted_quantity TYPE numeric(22,7),
    ALTER COLUMN rejected_quantity TYPE numeric(22,7),
    ALTER COLUMN damaged_quantity TYPE numeric(22,7),
    ALTER COLUMN excess_quantity TYPE numeric(22,7),
    ALTER COLUMN shortage_quantity TYPE numeric(22,7);

ALTER TABLE goods_receipts
    ALTER COLUMN accepted_quantity TYPE numeric(22,7),
    ALTER COLUMN rejected_quantity TYPE numeric(22,7),
    ALTER COLUMN damaged_quantity TYPE numeric(22,7),
    ALTER COLUMN excess_quantity TYPE numeric(22,7),
    ALTER COLUMN shortage_quantity TYPE numeric(22,7);

ALTER TABLE inventory_cost_layers
    ALTER COLUMN received_quantity TYPE numeric(22,7),
    ALTER COLUMN remaining_quantity TYPE numeric(22,7);

ALTER TABLE inventory_items
    ALTER COLUMN system_quantity TYPE numeric(22,7),
    ALTER COLUMN counted_quantity TYPE numeric(22,7),
    ALTER COLUMN difference_quantity TYPE numeric(22,7);

ALTER TABLE inventory_line_history
    ALTER COLUMN old_counted_quantity TYPE numeric(22,7),
    ALTER COLUMN new_counted_quantity TYPE numeric(22,7),
    ALTER COLUMN old_difference_quantity TYPE numeric(22,7),
    ALTER COLUMN new_difference_quantity TYPE numeric(22,7);

ALTER TABLE inventory_optimization_recommendations
    ALTER COLUMN eoq_quantity TYPE numeric(22,7);

ALTER TABLE issue_request_lines
    ALTER COLUMN requested_quantity TYPE numeric(22,7),
    ALTER COLUMN approved_quantity TYPE numeric(22,7),
    ALTER COLUMN issued_quantity TYPE numeric(22,7);

ALTER TABLE landed_cost_allocation_lines
    ALTER COLUMN quantity TYPE numeric(22,7);

ALTER TABLE management_opportunities
    ALTER COLUMN quantity TYPE numeric(22,7);

ALTER TABLE material_consumption_norms
    ALTER COLUMN base_quantity TYPE numeric(22,7),
    ALTER COLUMN output_quantity TYPE numeric(22,7);

ALTER TABLE material_cost_observations
    ALTER COLUMN quantity TYPE numeric(22,7);

ALTER TABLE material_reservation_events
    ALTER COLUMN quantity TYPE numeric(22,7),
    ALTER COLUMN on_hand_delta TYPE numeric(22,7),
    ALTER COLUMN available_delta TYPE numeric(22,7),
    ALTER COLUMN reserved_delta TYPE numeric(22,7);

ALTER TABLE material_reservations
    ALTER COLUMN quantity TYPE numeric(22,7),
    ALTER COLUMN reserved_quantity TYPE numeric(22,7),
    ALTER COLUMN released_quantity TYPE numeric(22,7),
    ALTER COLUMN consumed_quantity TYPE numeric(22,7),
    ALTER COLUMN cancelled_quantity TYPE numeric(22,7),
    ALTER COLUMN expired_quantity TYPE numeric(22,7);

ALTER TABLE mobile_warehouse_operations
    ALTER COLUMN quantity TYPE numeric(22,7);

ALTER TABLE non_moving_stock_case_lines
    ALTER COLUMN quantity TYPE numeric(22,7),
    ALTER COLUMN available_quantity TYPE numeric(22,7),
    ALTER COLUMN reserved_quantity TYPE numeric(22,7),
    ALTER COLUMN blocked_quantity TYPE numeric(22,7);

ALTER TABLE non_moving_stock_cases
    ALTER COLUMN total_quantity TYPE numeric(22,7);

ALTER TABLE non_moving_stock_decision_lines
    ALTER COLUMN approved_quantity TYPE numeric(22,7);

ALTER TABLE non_moving_stock_decisions
    ALTER COLUMN approved_quantity TYPE numeric(22,7);

ALTER TABLE non_moving_stock_execution_outputs
    ALTER COLUMN quantity TYPE numeric(22,7);

ALTER TABLE non_moving_stocks
    ALTER COLUMN quantity TYPE numeric(22,7);

ALTER TABLE procurement_award_outcome_lines
    ALTER COLUMN awarded_quantity TYPE numeric(22,7);

ALTER TABLE procurement_line_allocations
    ALTER COLUMN allocated_quantity TYPE numeric(22,7),
    ALTER COLUMN cancelled_quantity TYPE numeric(22,7),
    ALTER COLUMN reversed_quantity TYPE numeric(22,7);

ALTER TABLE purchase_order_items
    ALTER COLUMN quantity TYPE numeric(22,7),
    ALTER COLUMN received_quantity TYPE numeric(22,7),
    ALTER COLUMN accepted_quantity TYPE numeric(22,7),
    ALTER COLUMN rejected_quantity TYPE numeric(22,7),
    ALTER COLUMN damaged_quantity TYPE numeric(22,7);

ALTER TABLE purchase_order_source_allocations
    ALTER COLUMN ordered_quantity TYPE numeric(22,7);

ALTER TABLE purchase_request_items
    ALTER COLUMN quantity TYPE numeric(22,7);

ALTER TABLE putaway_rules
    ALTER COLUMN max_quantity TYPE numeric(22,7);

ALTER TABLE quality_inspections
    ALTER COLUMN received_quantity TYPE numeric(22,7),
    ALTER COLUMN sample_quantity TYPE numeric(22,7),
    ALTER COLUMN accepted_quantity TYPE numeric(22,7),
    ALTER COLUMN rejected_quantity TYPE numeric(22,7),
    ALTER COLUMN quarantined_quantity TYPE numeric(22,7);

ALTER TABLE quality_nonconformances
    ALTER COLUMN detected_quantity TYPE numeric(22,7);

ALTER TABLE quality_quarantine_lots
    ALTER COLUMN quarantined_quantity TYPE numeric(22,7),
    ALTER COLUMN released_quantity TYPE numeric(22,7),
    ALTER COLUMN rejected_quantity TYPE numeric(22,7),
    ALTER COLUMN remaining_quantity TYPE numeric(22,7);

ALTER TABLE return_document_lines
    ALTER COLUMN issued_quantity TYPE numeric(22,7),
    ALTER COLUMN previously_returned_quantity TYPE numeric(22,7),
    ALTER COLUMN return_quantity TYPE numeric(22,7),
    ALTER COLUMN accepted_quantity TYPE numeric(22,7),
    ALTER COLUMN rejected_quantity TYPE numeric(22,7);

ALTER TABLE rfq_items
    ALTER COLUMN quantity TYPE numeric(22,7);

ALTER TABLE rfq_purchase_request_sources
    ALTER COLUMN allocated_quantity TYPE numeric(22,7);

ALTER TABLE service_request_milestones
    ALTER COLUMN ordered_quantity TYPE numeric(22,7),
    ALTER COLUMN accepted_quantity TYPE numeric(22,7),
    ALTER COLUMN rejected_quantity TYPE numeric(22,7);

ALTER TABLE service_requests
    ALTER COLUMN ordered_quantity TYPE numeric(22,7),
    ALTER COLUMN accepted_quantity TYPE numeric(22,7),
    ALTER COLUMN rejected_quantity TYPE numeric(22,7);

ALTER TABLE stock_balances
    ALTER COLUMN quantity TYPE numeric(22,7),
    ALTER COLUMN available_quantity TYPE numeric(22,7),
    ALTER COLUMN reserved_quantity TYPE numeric(22,7),
    ALTER COLUMN quarantine_quantity TYPE numeric(22,7);

ALTER TABLE stock_batches
    ALTER COLUMN quantity TYPE numeric(22,7);

ALTER TABLE stock_cost_movements
    ALTER COLUMN quantity TYPE numeric(22,7);

ALTER TABLE stock_movement_allocation_cost_layers
    ALTER COLUMN quantity_delta TYPE numeric(22,7);

ALTER TABLE stock_movement_allocations
    ALTER COLUMN on_hand_delta TYPE numeric(22,7),
    ALTER COLUMN available_delta TYPE numeric(22,7),
    ALTER COLUMN reserved_delta TYPE numeric(22,7),
    ALTER COLUMN quarantine_delta TYPE numeric(22,7),
    ALTER COLUMN batch_quantity_delta TYPE numeric(22,7);

ALTER TABLE stock_movements
    ALTER COLUMN quantity TYPE numeric(22,7);

ALTER TABLE stock_valuation_cost_job_results
    ALTER COLUMN quantity TYPE numeric(22,7);

ALTER TABLE stock_valuation_documents
    ALTER COLUMN quantity TYPE numeric(22,7);

ALTER TABLE stock_valuation_history
    ALTER COLUMN quantity TYPE numeric(22,7);

ALTER TABLE stock_valuation_run_items
    ALTER COLUMN quantity TYPE numeric(22,7);

ALTER TABLE stock_valuations
    ALTER COLUMN quantity TYPE numeric(22,7);

ALTER TABLE storage_bins
    ALTER COLUMN capacity_quantity TYPE numeric(22,7);

ALTER TABLE storage_locations
    ALTER COLUMN capacity_quantity TYPE numeric(22,7);

ALTER TABLE supplier_evaluations
    ALTER COLUMN ordered_quantity TYPE numeric(22,7),
    ALTER COLUMN received_quantity TYPE numeric(22,7),
    ALTER COLUMN accepted_quantity TYPE numeric(22,7),
    ALTER COLUMN rejected_quantity TYPE numeric(22,7);

ALTER TABLE supplier_po_commitments
    ALTER COLUMN proposed_quantity TYPE numeric(22,7),
    ALTER COLUMN confirmed_quantity TYPE numeric(22,7);

ALTER TABLE supplier_po_response_lines
    ALTER COLUMN ordered_quantity TYPE numeric(22,7),
    ALTER COLUMN open_quantity_at_dispatch TYPE numeric(22,7),
    ALTER COLUMN accepted_quantity TYPE numeric(22,7),
    ALTER COLUMN unconfirmed_quantity TYPE numeric(22,7);

ALTER TABLE supplier_quotation_items
    ALTER COLUMN quantity TYPE numeric(22,7);

ALTER TABLE transfer_allocation_clearances
    ALTER COLUMN quantity TYPE numeric(22,7);

ALTER TABLE transfer_document_lines
    ALTER COLUMN cancelled_quantity TYPE numeric(22,7),
    ALTER COLUMN planned_quantity TYPE numeric(22,7),
    ALTER COLUMN dispatched_quantity TYPE numeric(22,7),
    ALTER COLUMN in_transit_quantity TYPE numeric(22,7),
    ALTER COLUMN received_quantity TYPE numeric(22,7),
    ALTER COLUMN damaged_quantity TYPE numeric(22,7),
    ALTER COLUMN missing_quantity TYPE numeric(22,7);

ALTER TABLE transfer_exceptions
    ALTER COLUMN quantity TYPE numeric(22,7);

ALTER TABLE transfer_shipment_allocations
    ALTER COLUMN dispatched_quantity TYPE numeric(22,7);

ALTER TABLE transfer_transit_entries
    ALTER COLUMN quantity_delta TYPE numeric(22,7);

ALTER TABLE work_completion_acts
    ALTER COLUMN completed_quantity TYPE numeric(22,7);

DO $restore_stock_view$
DECLARE
    saved record;
    permission record;
    grantee_name text;
BEGIN
    SELECT * INTO STRICT saved FROM mm_quantity_precision_view_backup;
    EXECUTE 'CREATE VIEW public.warehouse_inventory_reconciliation_preflight AS ' || saved.definition;
    FOR permission IN SELECT * FROM aclexplode(saved.grants) LOOP
        grantee_name := CASE WHEN permission.grantee = 0 THEN 'PUBLIC'
            ELSE quote_ident(pg_get_userbyid(permission.grantee)) END;
        EXECUTE 'GRANT ' || permission.privilege_type || ' ON public.warehouse_inventory_reconciliation_preflight TO '
            || grantee_name || CASE WHEN permission.is_grantable THEN ' WITH GRANT OPTION' ELSE '' END;
    END LOOP;
    EXECUTE format('ALTER VIEW public.warehouse_inventory_reconciliation_preflight OWNER TO %I', saved.owner_name);
END
$restore_stock_view$;
