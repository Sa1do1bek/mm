--
-- PostgreSQL database dump
--


-- Dumped from database version 16.15
-- Dumped by pg_dump version 16.15

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: pg_trgm; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;


--
-- Name: multilingual_name_value(text, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.multilingual_name_value(stored_name text, requested_language text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE
    AS $$
    select coalesce(
        nullif(btrim(case lower(requested_language)
            when 'en' then split_part(split_part(stored_name, E'\nen:', 2), E'\nuz:', 1)
            when 'uz' then split_part(stored_name, E'\nuz:', 2)
            else split_part(split_part(stored_name, 'ru:', 2), E'\nen:', 1)
        end), ''),
        nullif(btrim(split_part(split_part(stored_name, 'ru:', 2), E'\nen:', 1)), ''),
        stored_name
    )
$$;


--
-- Name: normalize_multilingual_name(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.normalize_multilingual_name() RETURNS trigger
    LANGUAGE plpgsql
    AS $_$
begin
    if new.name is null or btrim(new.name) = '' then
        new.name := 'ru: Деловой документ' || E'\nen: Business document' || E'\nuz: Biznes hujjati';
    elsif new.name !~* '(^|\n)[[:space:]]*(ru|en|uz)[[:space:]]*:'
    then
        if new.name ~* '[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}'
           or new.name ~* '^[A-Z][A-Z0-9_ -]{1,30}-[0-9A-F]{4,}$'
        then
            new.name := 'ru: Деловой документ' || E'\nen: Business document' || E'\nuz: Biznes hujjati';
        else
            new.name :=
                'ru: ' || btrim(new.name) ||
                E'\nen: ' || btrim(new.name) ||
                E'\nuz: ' || btrim(new.name);
        end if;
    end if;
    return new;
end
$_$;


--
-- Name: prevent_budget_transaction_mutation(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.prevent_budget_transaction_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
begin
    raise exception 'budget_transactions are append-only; post a compensating transaction instead';
end;
$$;


--
-- Name: prevent_stock_cost_movements_mutation(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.prevent_stock_cost_movements_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
begin
    raise exception 'stock_cost_movements is append-only; create reversal rows instead';
end;
$$;


--
-- Name: reject_contract_invoice_evidence_snapshot_mutation(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.reject_contract_invoice_evidence_snapshot_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'contract invoice evidence snapshots are append-only';
END;
$$;


--
-- Name: reject_goods_receipt_evidence_mutation(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.reject_goods_receipt_evidence_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
begin
    if tg_op = 'UPDATE' and tg_table_name = 'goods_receipt_lines' then
        if new.id is not distinct from old.id
            and new.tenant_id is not distinct from old.tenant_id
            and new.created_at is not distinct from old.created_at
            and new.created_by is not distinct from old.created_by
            and new.is_delete is not distinct from old.is_delete
            and new.company_id is not distinct from old.company_id
            and new.code is not distinct from old.code
            and new.name is not distinct from old.name
            and new.status is not distinct from old.status
            and new.description is not distinct from old.description
            and new.goods_receipt_id is not distinct from old.goods_receipt_id
            and new.purchase_order_id is not distinct from old.purchase_order_id
            and new.purchase_order_item_id is not distinct from old.purchase_order_item_id
            and new.supplier_id is not distinct from old.supplier_id
            and new.material_id is not distinct from old.material_id
            and new.material_group_id is not distinct from old.material_group_id
            and new.uom_code is not distinct from old.uom_code
            and new.warehouse_id is not distinct from old.warehouse_id
            and new.storage_location_id is not distinct from old.storage_location_id
            and new.storage_bin_id is not distinct from old.storage_bin_id
            and new.batch_number is not distinct from old.batch_number
            and new.open_quantity_before is not distinct from old.open_quantity_before
            and new.physical_received_quantity is not distinct from old.physical_received_quantity
            and new.accepted_quantity is not distinct from old.accepted_quantity
            and new.rejected_quantity is not distinct from old.rejected_quantity
            and new.damaged_quantity is not distinct from old.damaged_quantity
            and new.excess_quantity is not distinct from old.excess_quantity
            and new.shortage_quantity is not distinct from old.shortage_quantity
            and new.shortage_reason is not distinct from old.shortage_reason
            and new.stock_movement_id is not distinct from old.stock_movement_id
            and new.accreditation_status is not distinct from old.accreditation_status
            and new.accreditation_message is not distinct from old.accreditation_message
            and new.accreditation_exception_id is not distinct from old.accreditation_exception_id then
            return new;
        end if;
    end if;

    raise exception 'goods receipt posting evidence is append-only';
end;
$$;


--
-- Name: reject_material_reservation_event_mutation(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.reject_material_reservation_event_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
begin
    raise exception 'material_reservation_events is append-only';
end;
$$;


--
-- Name: reject_mixed_purchase_order_material_types(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.reject_mixed_purchase_order_material_types() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
begin
    if new.material_type_snapshot not in ('TMB', 'FIXED_ASSET') or coalesce(new.is_delete, false) then
        return new;
    end if;

    if exists (
        select 1
        from purchase_order_items item
        where item.tenant_id = new.tenant_id
          and item.company_id = new.company_id
          and item.purchase_order_id = new.purchase_order_id
          and (new.id is null or item.id <> new.id)
          and item.is_delete = false
          and item.material_type_snapshot in ('TMB', 'FIXED_ASSET')
          and item.material_type_snapshot <> new.material_type_snapshot
    ) then
        raise exception 'PROCUREMENT_MATERIAL_TYPE_MIXED: purchaseOrder cannot mix TMB and FIXED_ASSET lines';
    end if;
    return new;
end;
$$;


--
-- Name: reject_mixed_purchase_request_material_types(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.reject_mixed_purchase_request_material_types() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
begin
    if new.material_type_snapshot not in ('TMB', 'FIXED_ASSET') or coalesce(new.is_delete, false) then
        return new;
    end if;

    if exists (
        select 1
        from purchase_request_items item
        where item.tenant_id = new.tenant_id
          and item.company_id = new.company_id
          and item.purchase_request_id = new.purchase_request_id
          and (new.id is null or item.id <> new.id)
          and item.is_delete = false
          and item.material_type_snapshot in ('TMB', 'FIXED_ASSET')
          and item.material_type_snapshot <> new.material_type_snapshot
    ) then
        raise exception 'PROCUREMENT_MATERIAL_TYPE_MIXED: purchaseRequest cannot mix TMB and FIXED_ASSET lines';
    end if;
    return new;
end;
$$;


--
-- Name: reject_stock_movement_allocation_evidence_mutation(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.reject_stock_movement_allocation_evidence_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
begin
    raise exception 'stock movement allocation evidence is append-only';
end;
$$;


--
-- Name: reject_transfer_fact_mutation(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.reject_transfer_fact_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
begin
    raise exception 'Transfer facts are append-only; use an authorized compensating operation';
end;
$$;


--
-- Name: validate_budget_transaction_ownership(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.validate_budget_transaction_ownership() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
begin
    if NEW.source_type <> 'LEGACY' and NEW.source_id is null then
        raise exception 'source_id is required for non-legacy budget transactions';
    end if;
    if not exists (
        select 1 from budget_items bi
        where bi.id = NEW.budget_item_id
          and bi.company_id = NEW.company_id
    ) then
        raise exception 'budget transaction company does not own the budget item';
    end if;
    return NEW;
end;
$$;


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: advance_shipping_notice_batches; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.advance_shipping_notice_batches (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    asn_item_id uuid NOT NULL,
    batch_number character varying(160),
    quantity numeric(19,4) DEFAULT 0 NOT NULL,
    expiration_date date
);


--
-- Name: advance_shipping_notice_items; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.advance_shipping_notice_items (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    asn_id uuid NOT NULL,
    purchase_order_item_id uuid,
    material_id uuid NOT NULL,
    quantity numeric(19,4) DEFAULT 0,
    package_count numeric(19,4) DEFAULT 0,
    package_type character varying(80),
    gross_weight numeric(19,4) DEFAULT 0,
    net_weight numeric(19,4) DEFAULT 0,
    batch_number character varying(120),
    serial_numbers text,
    expiration_date date,
    supplier_po_commitment_id uuid,
    received_quantity numeric(19,4) DEFAULT 0 NOT NULL,
    cancelled_quantity numeric(19,4) DEFAULT 0 NOT NULL
);


--
-- Name: advance_shipping_notice_serials; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.advance_shipping_notice_serials (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    asn_item_id uuid NOT NULL,
    asn_batch_id uuid,
    batch_number character varying(160),
    manufacturer_serial_number character varying(160),
    normalized_serial_number character varying(160)
);


--
-- Name: advance_shipping_notices; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.advance_shipping_notices (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    asn_number character varying(120),
    supplier_id uuid NOT NULL,
    purchase_order_id uuid,
    contract_id uuid,
    incoterm_id uuid,
    notice_date date,
    expected_ship_date date,
    expected_arrival_date date,
    transport_mode character varying(40),
    carrier_name character varying(240),
    vehicle_number character varying(120),
    container_number character varying(120),
    tracking_number character varying(160),
    origin_country character varying(3),
    destination_country character varying(3),
    loading_point character varying(240),
    unloading_point character varying(240),
    total_quantity numeric(19,4) DEFAULT 0,
    total_amount numeric(19,4) DEFAULT 0,
    currency_code character varying(3),
    sent_at timestamp without time zone,
    confirmed_at timestamp without time zone,
    received_at timestamp without time zone,
    source character varying(30) DEFAULT 'INTERNAL'::character varying NOT NULL,
    portal_account_id uuid
);


--
-- Name: ai_analysis_jobs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ai_analysis_jobs (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    department_id uuid,
    warehouse_id uuid,
    analysis_type character varying(60) NOT NULL,
    entity_type character varying(80) NOT NULL,
    entity_id uuid,
    status character varying(40) NOT NULL,
    requested_by character varying(120) NOT NULL,
    requested_language character varying(12) NOT NULL,
    request_hash character varying(80) NOT NULL,
    request_payload text NOT NULL,
    result_payload text,
    error_code character varying(80),
    error_message text,
    provider character varying(80),
    model character varying(120),
    model_version character varying(120),
    requested_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    started_at timestamp without time zone,
    completed_at timestamp without time zone,
    updated_at timestamp without time zone,
    version bigint DEFAULT 0 NOT NULL
);


--
-- Name: approval_flow_parallel_assignees; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.approval_flow_parallel_assignees (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    company_id uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    approval_flow_id uuid NOT NULL,
    assignee_user_id uuid,
    assignee_username character varying(120) NOT NULL
);


--
-- Name: approval_flow_steps; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.approval_flow_steps (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    company_id uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    approval_flow_id uuid NOT NULL,
    step_order integer NOT NULL,
    assignee_type character varying(20) NOT NULL,
    assignee_role character varying(80),
    assignee_user_id uuid,
    assignee_username character varying(120),
    can_return boolean DEFAULT false NOT NULL,
    comment_required boolean DEFAULT true NOT NULL,
    department_id uuid,
    step_type character varying(40) DEFAULT 'STANDARD'::character varying NOT NULL,
    warehouse_id uuid,
    is_approver boolean DEFAULT false NOT NULL,
    CONSTRAINT chk_approval_flow_step_assignee CHECK (((((assignee_type)::text = 'ROLE'::text) AND (assignee_role IS NOT NULL)) OR (((assignee_type)::text = 'USER'::text) AND ((assignee_user_id IS NOT NULL) OR (assignee_username IS NOT NULL)))))
);


--
-- Name: approval_flows; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.approval_flows (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    company_id uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    module_key character varying(80) NOT NULL,
    active boolean DEFAULT true NOT NULL,
    department_id uuid,
    request_type character varying(20),
    flow_type character varying(40) DEFAULT 'SEQUENTIAL'::character varying NOT NULL,
    CONSTRAINT chk_approval_flows_request_type CHECK (((request_type IS NULL) OR ((request_type)::text = ANY ((ARRAY['GOODS'::character varying, 'SERVICE'::character varying, 'BOTH'::character varying])::text[]))))
);


--
-- Name: approval_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.approval_history (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    company_id uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    approval_instance_id uuid NOT NULL,
    action character varying(40) NOT NULL,
    actor character varying(120),
    from_step_order integer,
    to_step_order integer,
    comment_text text,
    action_at timestamp without time zone NOT NULL
);


--
-- Name: approval_instances; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.approval_instances (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    company_id uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    approval_flow_id uuid NOT NULL,
    module_key character varying(80) NOT NULL,
    entity_id uuid NOT NULL,
    approval_status character varying(40) NOT NULL,
    current_step_order integer,
    started_at timestamp without time zone,
    completed_at timestamp without time zone
);


--
-- Name: approval_steps; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.approval_steps (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    approval_workflow_id uuid NOT NULL,
    step_number integer,
    approver_role character varying(80),
    approved_by character varying(120),
    approved_at timestamp without time zone,
    decision character varying(40),
    comment_text text,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL
);


--
-- Name: approval_tasks; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.approval_tasks (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    company_id uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    approval_instance_id uuid NOT NULL,
    approval_flow_step_id uuid,
    step_order integer NOT NULL,
    assignee_type character varying(20) NOT NULL,
    assignee_role character varying(80),
    assignee_user_id uuid,
    assignee_username character varying(120),
    task_status character varying(40) NOT NULL,
    acted_by character varying(120),
    acted_at timestamp without time zone,
    comment_text text
);


--
-- Name: approval_workflows; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.approval_workflows (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    entity_type character varying(80),
    entity_id uuid,
    current_step integer,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    module_key character varying(80),
    request_type character varying(20),
    CONSTRAINT chk_approval_workflows_request_type CHECK (((request_type IS NULL) OR (upper((request_type)::text) = ANY (ARRAY['GOODS'::text, 'SERVICE'::text]))))
);


--
-- Name: asset_acceptance_lines; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.asset_acceptance_lines (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    asset_acceptance_id uuid NOT NULL,
    purchase_order_item_id uuid NOT NULL,
    material_id uuid NOT NULL,
    material_type_snapshot character varying(30) NOT NULL,
    unit character varying(80),
    delivered_quantity numeric(19,4) NOT NULL,
    accepted_quantity numeric(19,4) DEFAULT 0 NOT NULL,
    rejected_quantity numeric(19,4) DEFAULT 0 NOT NULL,
    pending_quantity numeric(19,4) DEFAULT 0 NOT NULL,
    hold_quantity numeric(19,4) DEFAULT 0 NOT NULL,
    quality_decision character varying(30) DEFAULT 'PENDING'::character varying NOT NULL,
    accepted_value numeric(19,4),
    accepted_currency_code character varying(3),
    po_received_quantity_recorded numeric(19,4) DEFAULT 0 NOT NULL,
    po_accepted_quantity_recorded numeric(19,4) DEFAULT 0 NOT NULL,
    po_rejected_quantity_recorded numeric(19,4) DEFAULT 0 NOT NULL,
    po_fulfillment_recorded_at timestamp without time zone,
    CONSTRAINT ck_asset_acceptance_lines_material_type CHECK (((material_type_snapshot)::text = 'FIXED_ASSET'::text)),
    CONSTRAINT ck_asset_acceptance_lines_quality_decision CHECK (((quality_decision)::text = ANY ((ARRAY['PENDING'::character varying, 'ACCEPTED'::character varying, 'REJECTED'::character varying, 'HOLD'::character varying])::text[]))),
    CONSTRAINT ck_asset_acceptance_lines_quantities_non_negative CHECK (((delivered_quantity >= (0)::numeric) AND (accepted_quantity >= (0)::numeric) AND (rejected_quantity >= (0)::numeric) AND (pending_quantity >= (0)::numeric) AND (hold_quantity >= (0)::numeric)))
);


--
-- Name: asset_acceptance_receiving_unit_number_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.asset_acceptance_receiving_unit_number_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: asset_acceptance_units; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.asset_acceptance_units (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    asset_acceptance_line_id uuid NOT NULL,
    purchase_order_item_id uuid NOT NULL,
    material_id uuid NOT NULL,
    material_type_snapshot character varying(30) NOT NULL,
    line_sequence integer,
    serial_number character varying(120),
    temporary_tag character varying(120),
    condition character varying(80),
    quality_decision character varying(30) DEFAULT 'PENDING'::character varying NOT NULL,
    quality_decision_at timestamp without time zone,
    quality_decision_by character varying(120),
    posted_fixed_asset_id uuid,
    receiving_unit_number character varying(120) NOT NULL,
    manufacturer_serial_number character varying(120),
    CONSTRAINT ck_asset_acceptance_units_material_type CHECK (((material_type_snapshot)::text = 'FIXED_ASSET'::text)),
    CONSTRAINT ck_asset_acceptance_units_quality_decision CHECK (((quality_decision)::text = ANY ((ARRAY['PENDING'::character varying, 'ACCEPTED'::character varying, 'REJECTED'::character varying, 'HOLD'::character varying])::text[])))
);


--
-- Name: asset_acceptances; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.asset_acceptances (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    purchase_order_id uuid NOT NULL,
    supplier_id uuid,
    asn_id uuid,
    delivery_reference character varying(120),
    warehouse_id uuid NOT NULL,
    storage_location_id uuid,
    received_by_employee_id uuid,
    receipt_date date NOT NULL,
    received_at timestamp without time zone NOT NULL,
    currency_code character varying(3),
    exchange_rate numeric(19,6),
    exchange_rate_source character varying(120),
    source_evidence character varying(240),
    approval_status character varying(30) DEFAULT 'DRAFT'::character varying NOT NULL,
    posting_status character varying(30) DEFAULT 'NOT_POSTED'::character varying NOT NULL,
    approval_instance_id uuid,
    posted_at timestamp without time zone,
    posted_by character varying(120),
    CONSTRAINT ck_asset_acceptances_approval_status CHECK (((approval_status)::text = ANY ((ARRAY['DRAFT'::character varying, 'SUBMITTED'::character varying, 'APPROVED'::character varying, 'REJECTED'::character varying])::text[]))),
    CONSTRAINT ck_asset_acceptances_posting_status CHECK (((posting_status)::text = ANY ((ARRAY['NOT_POSTED'::character varying, 'POSTED'::character varying, 'CANCELLED'::character varying])::text[])))
);


--
-- Name: asset_allocations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.asset_allocations (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(30) NOT NULL,
    description text,
    fixed_asset_id uuid NOT NULL,
    asset_handover_id uuid NOT NULL,
    allocated_at timestamp without time zone NOT NULL,
    released_at timestamp without time zone,
    release_reason character varying(80),
    CONSTRAINT ck_asset_allocations_status CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'RELEASED'::character varying, 'CANCELLED'::character varying])::text[])))
);


--
-- Name: asset_depreciation_entries; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.asset_depreciation_entries (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    fixed_asset_id uuid NOT NULL,
    period_year integer NOT NULL,
    period_month integer NOT NULL,
    period_date date NOT NULL,
    opening_book_value numeric(19,4) NOT NULL,
    depreciation_amount numeric(19,4) NOT NULL,
    closing_book_value numeric(19,4) NOT NULL,
    accumulated_depreciation numeric(19,4) NOT NULL,
    calculation_method character varying(40),
    is_posted boolean DEFAULT false NOT NULL,
    posted_at timestamp without time zone,
    posted_by character varying(120),
    run_reference character varying(120)
);


--
-- Name: asset_events; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.asset_events (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    fixed_asset_id uuid NOT NULL,
    event_type character varying(60) NOT NULL,
    command_id character varying(160),
    idempotency_key character varying(160),
    before_lifecycle_status character varying(30),
    after_lifecycle_status character varying(30),
    before_custody_kind character varying(30),
    after_custody_kind character varying(30),
    before_custody_json jsonb,
    after_custody_json jsonb,
    effective_date date NOT NULL,
    recorded_at timestamp without time zone NOT NULL,
    actor_username character varying(120),
    actor_employee_id uuid,
    reason text,
    approval_instance_id uuid,
    source_document_type character varying(60),
    source_document_id uuid,
    aggregate_version bigint
);


--
-- Name: asset_handover_lines; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.asset_handover_lines (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    asset_handover_id uuid NOT NULL,
    fixed_asset_id uuid NOT NULL,
    asset_allocation_id uuid,
    line_status character varying(30) NOT NULL,
    CONSTRAINT ck_asset_handover_lines_status CHECK (((line_status)::text = ANY ((ARRAY['PENDING'::character varying, 'DISPATCHED'::character varying, 'ACKNOWLEDGED'::character varying, 'EXCEPTION_REPORTED'::character varying, 'CLOSED'::character varying, 'CANCELLED'::character varying])::text[])))
);


--
-- Name: asset_handovers; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.asset_handovers (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(30) NOT NULL,
    description text,
    handover_type character varying(30) NOT NULL,
    from_custody_type character varying(30) NOT NULL,
    from_employee_id uuid,
    from_department_id uuid,
    from_warehouse_id uuid,
    to_custody_type character varying(30) NOT NULL,
    to_employee_id uuid,
    to_department_id uuid,
    to_warehouse_id uuid,
    approval_instance_id uuid,
    approved_at timestamp without time zone,
    dispatched_at timestamp without time zone,
    acknowledged_at timestamp without time zone,
    exception_status character varying(30),
    closed_at timestamp without time zone,
    CONSTRAINT ck_asset_handovers_custody CHECK ((((from_custody_type)::text = ANY ((ARRAY['WAREHOUSE'::character varying, 'DEPARTMENT'::character varying, 'IN_TRANSIT'::character varying, 'EXTERNAL'::character varying, 'NONE'::character varying])::text[])) AND ((to_custody_type)::text = ANY ((ARRAY['WAREHOUSE'::character varying, 'DEPARTMENT'::character varying, 'IN_TRANSIT'::character varying, 'EXTERNAL'::character varying, 'NONE'::character varying])::text[])))),
    CONSTRAINT ck_asset_handovers_status CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'SUBMITTED'::character varying, 'APPROVED'::character varying, 'REJECTED'::character varying, 'DISPATCHED'::character varying, 'ACKNOWLEDGED'::character varying, 'RETURN_RESOLUTION'::character varying, 'EXCEPTION_REPORTED'::character varying, 'CLOSED'::character varying, 'CANCELLED'::character varying])::text[])))
);


--
-- Name: asset_management_settings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.asset_management_settings (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    central_warehouse_id uuid NOT NULL,
    central_storage_location_id uuid,
    inventory_number_prefix character varying(30) DEFAULT 'FA'::character varying NOT NULL,
    inventory_number_next_value bigint DEFAULT 1 NOT NULL,
    inventory_number_padding integer DEFAULT 6 NOT NULL,
    auto_number_at_receipt boolean DEFAULT false NOT NULL,
    require_serial_number boolean DEFAULT false NOT NULL,
    CONSTRAINT ck_asset_management_settings_number_next_positive CHECK ((inventory_number_next_value > 0)),
    CONSTRAINT ck_asset_management_settings_padding_positive CHECK ((inventory_number_padding > 0))
);


--
-- Name: asset_number_registry; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.asset_number_registry (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    inventory_number character varying(80) NOT NULL,
    normalized_number character varying(80) NOT NULL,
    fixed_asset_id uuid,
    source character varying(30) NOT NULL,
    reserved_at timestamp without time zone NOT NULL,
    assigned_at timestamp without time zone,
    corrected_at timestamp without time zone,
    retired_at timestamp without time zone,
    CONSTRAINT ck_asset_number_registry_source CHECK (((source)::text = ANY ((ARRAY['GENERATED'::character varying, 'MANUAL'::character varying, 'OPENING_IMPORT'::character varying, 'CORRECTION'::character varying])::text[]))),
    CONSTRAINT ck_asset_number_registry_status CHECK (((status)::text = ANY ((ARRAY['RESERVED'::character varying, 'ASSIGNED'::character varying, 'CORRECTED'::character varying, 'RETIRED'::character varying])::text[])))
);


--
-- Name: asset_opening_import_lines; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.asset_opening_import_lines (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    asset_opening_import_id uuid NOT NULL,
    material_id uuid NOT NULL,
    equipment_id uuid,
    inventory_number character varying(80),
    serial_number character varying(120),
    lifecycle_status character varying(30) NOT NULL,
    custody_kind character varying(30) NOT NULL,
    warehouse_id uuid,
    department_id uuid,
    acquisition_value numeric(19,4),
    validation_status character varying(30),
    validation_message text,
    CONSTRAINT ck_asset_opening_import_lines_custody CHECK (((custody_kind)::text = ANY ((ARRAY['WAREHOUSE'::character varying, 'DEPARTMENT'::character varying, 'IN_TRANSIT'::character varying, 'EXTERNAL'::character varying, 'NONE'::character varying])::text[]))),
    CONSTRAINT ck_asset_opening_import_lines_lifecycle CHECK (((lifecycle_status)::text = ANY ((ARRAY['IN_WAREHOUSE'::character varying, 'ISSUED_PENDING_COMMISSIONING'::character varying, 'IN_USE'::character varying, 'IDLE'::character varying, 'UNDER_REPAIR'::character varying, 'RETURNED_TO_SUPPLIER'::character varying, 'DISPOSED'::character varying, 'CANCELLED'::character varying])::text[])))
);


--
-- Name: asset_opening_imports; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.asset_opening_imports (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(30) NOT NULL,
    description text,
    source_file_id uuid,
    validated_at timestamp without time zone,
    posted_at timestamp without time zone,
    posted_by character varying(120),
    CONSTRAINT ck_asset_opening_imports_status CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'VALIDATED'::character varying, 'SUBMITTED'::character varying, 'APPROVED'::character varying, 'POSTED'::character varying, 'REJECTED'::character varying, 'CANCELLED'::character varying])::text[])))
);


--
-- Name: asset_verification_lines; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.asset_verification_lines (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    asset_verification_id uuid NOT NULL,
    fixed_asset_id uuid NOT NULL,
    expected_custody_type character varying(30) NOT NULL,
    expected_employee_id uuid,
    expected_department_id uuid,
    expected_warehouse_id uuid,
    observed_custody_type character varying(30),
    observed_employee_id uuid,
    observed_department_id uuid,
    observed_warehouse_id uuid,
    observed_at timestamp without time zone,
    discrepancy_type character varying(30),
    resolution_status character varying(30) DEFAULT 'OPEN'::character varying NOT NULL,
    CONSTRAINT ck_asset_verification_lines_discrepancy CHECK (((discrepancy_type IS NULL) OR ((discrepancy_type)::text = ANY ((ARRAY['MISSING_SCAN'::character varying, 'LOCATION_MISMATCH'::character varying, 'CUSTODIAN_MISMATCH'::character varying, 'EXTRA_SCAN'::character varying, 'VERSION_CHANGED'::character varying])::text[])))),
    CONSTRAINT ck_asset_verification_lines_expected_custody CHECK (((expected_custody_type)::text = ANY ((ARRAY['WAREHOUSE'::character varying, 'DEPARTMENT'::character varying, 'IN_TRANSIT'::character varying, 'EXTERNAL'::character varying, 'NONE'::character varying])::text[]))),
    CONSTRAINT ck_asset_verification_lines_observed_custody CHECK (((observed_custody_type IS NULL) OR ((observed_custody_type)::text = ANY ((ARRAY['WAREHOUSE'::character varying, 'DEPARTMENT'::character varying, 'IN_TRANSIT'::character varying, 'EXTERNAL'::character varying, 'NONE'::character varying])::text[])))),
    CONSTRAINT ck_asset_verification_lines_resolution CHECK (((resolution_status)::text = ANY ((ARRAY['OPEN'::character varying, 'UNDER_REVIEW'::character varying, 'RESOLVED'::character varying, 'ACCEPTED'::character varying])::text[])))
);


--
-- Name: asset_verifications; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.asset_verifications (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(30) NOT NULL,
    description text,
    warehouse_id uuid,
    department_id uuid,
    verification_type character varying(30) NOT NULL,
    started_at timestamp without time zone,
    completed_at timestamp without time zone,
    CONSTRAINT ck_asset_verifications_status CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'IN_PROGRESS'::character varying, 'COMPLETED'::character varying, 'CLOSED'::character varying, 'CANCELLED'::character varying])::text[]))),
    CONSTRAINT ck_asset_verifications_type CHECK (((verification_type)::text = ANY ((ARRAY['WAREHOUSE'::character varying, 'DEPARTMENT'::character varying, 'FULL'::character varying, 'OPENING_IMPORT'::character varying])::text[])))
);


--
-- Name: attachments; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.attachments (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    entity_type character varying(80) NOT NULL,
    entity_id uuid NOT NULL,
    attachment_type character varying(80),
    original_file_name character varying(300) NOT NULL,
    content_type character varying(160) NOT NULL,
    size_bytes bigint NOT NULL,
    checksum_sha256 character varying(64) NOT NULL,
    storage_provider character varying(40) NOT NULL,
    storage_key character varying(700) NOT NULL,
    uploaded_at timestamp without time zone NOT NULL,
    downloadable boolean DEFAULT true NOT NULL,
    previewable boolean DEFAULT false NOT NULL
);


--
-- Name: audit_logs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.audit_logs (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    entity_type character varying(120),
    entity_id uuid,
    action character varying(80),
    full_name character varying(120),
    details text,
    occurred_at timestamp without time zone,
    ip_address character varying(80),
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    old_snapshot text,
    new_snapshot text,
    diff text,
    username character varying(120)
);


--
-- Name: banks; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.banks (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    name text NOT NULL,
    mfo character varying(10),
    swift character varying(20),
    bank_type character varying(40),
    city character varying(120),
    sort_order integer DEFAULT 0 NOT NULL,
    company_id uuid NOT NULL
);


--
-- Name: budget_items; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.budget_items (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    annual_limit numeric(19,4),
    used_amount numeric(19,4),
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    reserved_amount numeric(19,4) DEFAULT 0 NOT NULL,
    committed_amount numeric(19,4) DEFAULT 0 NOT NULL,
    currency_code character varying(3) DEFAULT 'UZS'::character varying NOT NULL
);


--
-- Name: budget_transactions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.budget_transactions (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    budget_item_id uuid NOT NULL,
    transaction_type character varying(50) NOT NULL,
    amount numeric(19,4) DEFAULT 0 NOT NULL,
    reserved_delta numeric(19,4) DEFAULT 0 NOT NULL,
    committed_delta numeric(19,4) DEFAULT 0 NOT NULL,
    used_delta numeric(19,4) DEFAULT 0 NOT NULL,
    source_type character varying(80) NOT NULL,
    source_id uuid,
    source_line_id uuid,
    source_key character varying(220) NOT NULL,
    action_key character varying(120) NOT NULL,
    idempotency_key character varying(400) NOT NULL,
    consumed_from_reserved numeric(19,4) DEFAULT 0 NOT NULL,
    consumed_from_committed numeric(19,4) DEFAULT 0 NOT NULL,
    consumed_directly numeric(19,4) DEFAULT 0 NOT NULL,
    currency_code character varying(3) DEFAULT 'UZS'::character varying NOT NULL,
    original_transaction_id uuid,
    reversal_of_transaction_id uuid,
    CONSTRAINT ck_budget_transactions_currency CHECK ((((currency_code)::text = upper((currency_code)::text)) AND (length((currency_code)::text) = 3)))
);


--
-- Name: chart_of_accounts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.chart_of_accounts (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    account_code character varying(40) NOT NULL,
    parent_code character varying(40),
    account_type character varying(40),
    is_group boolean DEFAULT false NOT NULL,
    source_system character varying(40),
    external_id character varying(120),
    last_synced_at timestamp without time zone
);


--
-- Name: classifier_types; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.classifier_types (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    module character varying(120),
    system_type boolean DEFAULT false NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL
);


--
-- Name: classifiers; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.classifiers (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    classifier_type character varying(80),
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL
);


--
-- Name: commission_members; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.commission_members (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    commission_id uuid NOT NULL,
    user_id uuid,
    member_role character varying(60) DEFAULT 'MEMBER'::character varying NOT NULL,
    active_flag boolean DEFAULT true NOT NULL,
    employee_id uuid,
    member_name character varying(240) NOT NULL
);


--
-- Name: commissions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.commissions (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    commission_type character varying(40) DEFAULT 'INVENTORY'::character varying NOT NULL,
    valid_from date,
    valid_to date,
    active_flag boolean DEFAULT true NOT NULL
);


--
-- Name: companies; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.companies (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    legal_name character varying(300),
    short_name character varying(180),
    tax_id character varying(500),
    oked_code character varying(20),
    registration_number character varying(80),
    registration_date date,
    legal_address character varying(500),
    phone character varying(80),
    email character varying(160),
    non_moving_stock_threshold_months integer DEFAULT 12 NOT NULL,
    non_moving_stock_auto_detect_enabled boolean DEFAULT false NOT NULL
);


--
-- Name: consignment_stock_transactions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.consignment_stock_transactions (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    vmi_agreement_id uuid NOT NULL,
    supplier_id uuid NOT NULL,
    material_id uuid NOT NULL,
    warehouse_id uuid NOT NULL,
    storage_location_id uuid,
    stock_movement_id uuid,
    transaction_type character varying(40) NOT NULL,
    quantity numeric(19,4) DEFAULT 0 NOT NULL,
    unit_cost numeric(19,4) DEFAULT 0,
    amount numeric(19,4) DEFAULT 0,
    settlement_status character varying(40),
    settlement_reference character varying(160),
    transaction_at timestamp without time zone
);


--
-- Name: consolidated_demands; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.consolidated_demands (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    demand_plan_id uuid NOT NULL,
    material_id uuid NOT NULL,
    budget_item_id uuid,
    period_start date NOT NULL,
    period_end date NOT NULL,
    highest_priority character varying(30) NOT NULL,
    total_requested_quantity numeric(19,4) DEFAULT 0 NOT NULL,
    total_approved_quantity numeric(19,4) DEFAULT 0 NOT NULL,
    total_estimated_amount numeric(19,4) DEFAULT 0 NOT NULL,
    currency_code character varying(3) DEFAULT 'UZS'::character varying,
    source_item_count integer DEFAULT 0 NOT NULL,
    purchase_request_id uuid,
    consolidated_at timestamp without time zone
);


--
-- Name: contract_addendums; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.contract_addendums (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    code character varying(80) NOT NULL,
    name text NOT NULL,
    description text,
    status character varying(50),
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    version bigint DEFAULT 0 NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    contract_id uuid NOT NULL,
    addendum_number character varying(120),
    addendum_date date,
    addendum_type character varying(40),
    effective_date date,
    new_end_date date,
    limit_delta numeric(19,4) DEFAULT 0 NOT NULL,
    new_limit_amount numeric(19,4),
    reason text,
    posted_at timestamp without time zone
);


--
-- Name: contract_invoice_evidence_snapshots; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.contract_invoice_evidence_snapshots (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    company_id uuid NOT NULL,
    code character varying(80),
    name character varying(240) NOT NULL,
    status character varying(50) NOT NULL,
    description text,
    contract_invoice_id uuid NOT NULL,
    purchase_order_id uuid NOT NULL,
    commercial_instrument_type character varying(40) NOT NULL,
    request_type character varying(30) NOT NULL,
    source_document_type character varying(64),
    source_document_id uuid,
    evidence_amount numeric(19,4) DEFAULT 0 NOT NULL,
    invoiced_amount_before numeric(19,4) DEFAULT 0 NOT NULL,
    invoice_amount numeric(19,4) DEFAULT 0 NOT NULL,
    remaining_invoiceable_amount numeric(19,4) DEFAULT 0 NOT NULL,
    currency_code character varying(12) NOT NULL,
    invoice_status_snapshot character varying(50),
    recorded_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    evidence_currency_code character varying(12),
    evidence_exchange_rate numeric(19,6),
    evidence_exchange_rate_source character varying(120),
    acquisition_evidence_type character varying(64),
    CONSTRAINT chk_contract_invoice_evidence_snapshots_request_type CHECK ((upper((request_type)::text) = ANY (ARRAY['GOODS'::text, 'SERVICE'::text]))),
    CONSTRAINT ck_contract_invoice_evidence_snapshot_amounts CHECK (((evidence_amount >= (0)::numeric) AND (invoiced_amount_before >= (0)::numeric) AND (invoice_amount >= (0)::numeric) AND (remaining_invoiceable_amount >= (0)::numeric)))
);


--
-- Name: contract_invoices; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.contract_invoices (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    contract_id uuid,
    invoice_number character varying(120),
    invoice_date date,
    due_date date,
    amount numeric(19,4),
    currency_code character varying(3),
    supplier_id uuid NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    purchase_order_id uuid,
    paid_amount numeric(19,4) DEFAULT 0 NOT NULL,
    commercial_instrument_type character varying(40) DEFAULT 'CONTRACT_CALLOFF'::character varying NOT NULL,
    CONSTRAINT chk_contract_invoices_commercial_source CHECK (((((commercial_instrument_type)::text = 'STANDALONE_PO'::text) AND (contract_id IS NULL) AND (purchase_order_id IS NOT NULL)) OR (((commercial_instrument_type)::text <> 'STANDALONE_PO'::text) AND (contract_id IS NOT NULL))))
);


--
-- Name: contract_payments; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.contract_payments (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    contract_id uuid,
    payment_number character varying(120),
    payment_date date,
    amount numeric(19,4),
    currency_code character varying(3),
    payment_method character varying(120),
    bank_reference character varying(240),
    supplier_id uuid NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    contract_invoice_id uuid,
    purchase_order_id uuid,
    source_document_type character varying(64),
    source_document_id uuid,
    treasury_reference character varying(240),
    commercial_instrument_type character varying(40) DEFAULT 'CONTRACT_CALLOFF'::character varying NOT NULL,
    CONSTRAINT chk_contract_payments_commercial_source CHECK (((((commercial_instrument_type)::text = 'STANDALONE_PO'::text) AND (contract_id IS NULL) AND (contract_invoice_id IS NOT NULL) AND (purchase_order_id IS NOT NULL)) OR (((commercial_instrument_type)::text <> 'STANDALONE_PO'::text) AND (contract_id IS NOT NULL))))
);


--
-- Name: contract_powers_of_attorney; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.contract_powers_of_attorney (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    contract_id uuid NOT NULL,
    attorney_number character varying(120),
    issued_to character varying(240),
    issue_date date,
    valid_to date,
    amount_limit numeric(19,4),
    supplier_id uuid NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL
);


--
-- Name: contract_reconciliation_acts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.contract_reconciliation_acts (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    contract_id uuid NOT NULL,
    act_number character varying(120),
    period_start date,
    period_end date,
    act_date date,
    opening_balance numeric(19,4),
    debit_amount numeric(19,4),
    credit_amount numeric(19,4),
    closing_balance numeric(19,4),
    supplier_id uuid NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL
);


--
-- Name: contract_specifications; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.contract_specifications (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    contract_id uuid NOT NULL,
    material_id uuid,
    quantity_limit numeric(19,4),
    price numeric(19,4),
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    line_type character varying(20) DEFAULT 'MATERIAL'::character varying NOT NULL,
    service_description text,
    service_category character varying(120),
    unit character varying(80),
    period character varying(120),
    stage character varying(120),
    service_limit numeric(19,4) DEFAULT 0 NOT NULL,
    sla character varying(120),
    pricing_model character varying(40),
    source_rfq_item_id uuid,
    source_quotation_item_id uuid,
    source_purchase_request_item_id uuid,
    material_type_snapshot character varying(30),
    CONSTRAINT ck_contract_specifications_material_snapshot_present CHECK (((material_id IS NULL) OR (material_type_snapshot IS NOT NULL))),
    CONSTRAINT ck_contract_specifications_material_type_snapshot CHECK (((material_type_snapshot IS NULL) OR ((material_type_snapshot)::text = ANY ((ARRAY['SERVICES'::character varying, 'FIXED_ASSET'::character varying, 'TMB'::character varying])::text[]))))
);


--
-- Name: contracts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.contracts (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    supplier_id uuid NOT NULL,
    contract_number character varying(120),
    start_date date,
    end_date date,
    limit_amount numeric(19,4),
    used_amount numeric(19,4),
    currency_code character varying(3),
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    service_used_amount numeric(19,4) DEFAULT 0 NOT NULL,
    source_rfq_id uuid,
    source_quotation_id uuid
);


--
-- Name: country_code; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.country_code (
    id bigint NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone,
    created_by_id bigint,
    code character varying(255),
    short_name character varying(255),
    long_name character varying(255),
    alpha2 character varying(255),
    alpha3 character varying(255),
    deleted boolean DEFAULT false NOT NULL
);


--
-- Name: country_code_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.country_code_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: country_code_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.country_code_id_seq OWNED BY public.country_code.id;


--
-- Name: cross_docking_tasks; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.cross_docking_tasks (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    task_number character varying(120),
    supplier_id uuid,
    purchase_order_id uuid,
    inbound_stock_movement_id uuid,
    outbound_reference_type character varying(80),
    outbound_reference_id uuid,
    material_id uuid NOT NULL,
    stock_batch_id uuid,
    warehouse_id uuid NOT NULL,
    staging_location_id uuid,
    destination_warehouse_id uuid,
    destination_storage_location_id uuid,
    quantity numeric(19,4) DEFAULT 0 NOT NULL,
    unit_cost numeric(19,4) DEFAULT 0,
    outbound_stock_movement_id uuid,
    planned_ship_at timestamp without time zone,
    started_at timestamp without time zone,
    completed_at timestamp without time zone
);


--
-- Name: currencies; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.currencies (
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80) NOT NULL,
    name text NOT NULL,
    status character varying(50),
    description text,
    iso_code character varying(3),
    numeric_code character varying(3),
    symbol character varying(12),
    minor_unit integer,
    base_currency boolean DEFAULT false NOT NULL,
    legacy_uuid uuid,
    country_name character varying(255),
    currency_name character varying(255),
    created_by_id bigint,
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL
);


--
-- Name: currency_rates; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.currency_rates (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    currency_code character varying(3),
    base_currency_code character varying(3),
    rate_date date,
    nominal numeric(19,6),
    rate numeric(19,6),
    source character varying(120),
    official_rate boolean DEFAULT false NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    currency_legacy_uuid uuid,
    currency_id uuid
);


--
-- Name: customs_declarations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.customs_declarations (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    import_delivery_id uuid,
    asn_id uuid,
    purchase_order_id uuid,
    supplier_id uuid NOT NULL,
    declaration_number character varying(120),
    declaration_date date,
    customs_post_code character varying(80),
    customs_regime character varying(80),
    broker_name character varying(240),
    tnved_code_id uuid,
    country_of_origin character varying(3),
    customs_value numeric(19,4) DEFAULT 0,
    duty_amount numeric(19,4) DEFAULT 0,
    vat_amount numeric(19,4) DEFAULT 0,
    excise_amount numeric(19,4) DEFAULT 0,
    fees_amount numeric(19,4) DEFAULT 0,
    total_customs_amount numeric(19,4) DEFAULT 0,
    currency_code character varying(3),
    submitted_at timestamp without time zone,
    released_at timestamp without time zone,
    attachment_id uuid,
    remarks character varying(1000)
);


--
-- Name: data_migration_rows; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.data_migration_rows (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    run_id uuid NOT NULL,
    row_number integer NOT NULL,
    row_status character varying(30) NOT NULL,
    source_key character varying(260),
    company_code character varying(80),
    warehouse_code character varying(80),
    storage_location_code character varying(80),
    material_code character varying(80),
    material_name character varying(240),
    article character varying(120),
    uom_code character varying(80),
    batch_number character varying(120),
    serial_number character varying(120),
    quantity numeric(19,4),
    unit_cost numeric(19,4),
    total_value numeric(19,4),
    currency_code character varying(20),
    account_code character varying(80),
    ikpu_code character varying(40),
    tnved_code character varying(40),
    expiration_date date,
    balance_date date,
    source_document character varying(180),
    error_messages text,
    warning_messages text,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL
);


--
-- Name: data_migration_runs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.data_migration_runs (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    migration_type character varying(80) NOT NULL,
    source_system character varying(80) NOT NULL,
    file_name character varying(260),
    total_rows integer DEFAULT 0 NOT NULL,
    valid_rows integer DEFAULT 0 NOT NULL,
    warning_rows integer DEFAULT 0 NOT NULL,
    error_rows integer DEFAULT 0 NOT NULL,
    committed_rows integer DEFAULT 0 NOT NULL,
    finished_at timestamp without time zone,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL
);


--
-- Name: delivery_schedules; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.delivery_schedules (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    purchase_order_id uuid,
    contract_id uuid,
    schedule_date date,
    planned_quantity numeric(19,4) DEFAULT 0,
    planned_amount numeric(19,4) DEFAULT 0,
    received_quantity numeric(19,4) DEFAULT 0,
    received_amount numeric(19,4) DEFAULT 0,
    overdue_flag boolean DEFAULT false NOT NULL,
    controlled_at timestamp without time zone,
    business_impact_reference character varying(160),
    business_impact_comment text,
    attributable_share numeric(9,6),
    impact_currency_code character varying(3),
    purchase_order_item_id uuid,
    supplier_po_commitment_id uuid,
    source character varying(30) DEFAULT 'MANUAL'::character varying NOT NULL
);


--
-- Name: demand_forecasts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.demand_forecasts (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    material_id uuid NOT NULL,
    warehouse_id uuid,
    forecast_method character varying(40) NOT NULL,
    period_start date NOT NULL,
    period_end date NOT NULL,
    history_months integer NOT NULL,
    forecast_months integer NOT NULL,
    average_monthly_demand numeric(19,4) DEFAULT 0,
    forecast_quantity numeric(19,4) DEFAULT 0,
    demand_std_dev numeric(19,4) DEFAULT 0,
    variation_coefficient numeric(19,4) DEFAULT 0,
    confidence numeric(7,4) DEFAULT 0,
    recommended_safety_stock numeric(19,4) DEFAULT 0,
    recommended_reorder_point numeric(19,4) DEFAULT 0,
    generated_at timestamp without time zone,
    source character varying(80),
    annualized_forecast_quantity numeric(19,4) DEFAULT 0,
    model_quality character varying(30) DEFAULT 'LOW'::character varying,
    trend_slope numeric(19,4) DEFAULT 0,
    seasonality_index numeric(9,4) DEFAULT 1,
    lead_time_days integer,
    service_level numeric(9,4) DEFAULT 0.95,
    lead_time_demand numeric(19,4) DEFAULT 0,
    recommended_min_stock numeric(19,4) DEFAULT 0,
    recommended_max_stock numeric(19,4) DEFAULT 0
);


--
-- Name: demand_plan_item_months; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.demand_plan_item_months (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    company_id uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    demand_plan_item_id uuid NOT NULL,
    demand_plan_id uuid NOT NULL,
    month integer NOT NULL,
    qty numeric(19,4) DEFAULT 0 NOT NULL,
    approved_qty numeric(19,4) DEFAULT 0 NOT NULL,
    reserved_qty numeric(19,4) DEFAULT 0 NOT NULL,
    CONSTRAINT ck_demand_plan_item_months_approved_qty CHECK ((approved_qty >= (0)::numeric)),
    CONSTRAINT ck_demand_plan_item_months_qty CHECK ((qty >= (0)::numeric)),
    CONSTRAINT ck_demand_plan_item_months_reserved_qty CHECK ((reserved_qty >= (0)::numeric)),
    CONSTRAINT demand_plan_item_months_month_check CHECK (((month >= 1) AND (month <= 12)))
);


--
-- Name: demand_plan_item_pr_allocations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.demand_plan_item_pr_allocations (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    company_id uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    demand_plan_item_id uuid NOT NULL,
    purchase_request_item_id uuid NOT NULL,
    allocated_quantity numeric(19,4) NOT NULL,
    allocated_amount numeric(19,4) NOT NULL,
    currency_code character varying(3),
    CONSTRAINT ck_demand_item_pr_allocations_amount CHECK ((allocated_amount >= (0)::numeric)),
    CONSTRAINT ck_demand_item_pr_allocations_quantity CHECK ((allocated_quantity >= (0)::numeric))
);


--
-- Name: demand_plan_items; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.demand_plan_items (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    demand_plan_id uuid NOT NULL,
    department_id uuid,
    material_id uuid NOT NULL,
    budget_item_id uuid,
    required_date date,
    demand_type character varying(30) DEFAULT 'OPERATIONS'::character varying NOT NULL,
    priority character varying(30) DEFAULT 'NORMAL'::character varying NOT NULL,
    project_code character varying(80),
    repair_order_code character varying(80),
    requested_quantity numeric(19,4) DEFAULT 0 NOT NULL,
    approved_quantity numeric(19,4) DEFAULT 0 NOT NULL,
    reserved_quantity numeric(19,4) DEFAULT 0 NOT NULL,
    estimated_unit_price numeric(19,4) DEFAULT 0 NOT NULL,
    estimated_amount numeric(19,4) DEFAULT 0 NOT NULL,
    currency_code character varying(3) DEFAULT 'UZS'::character varying,
    consolidated_demand_id uuid,
    purchase_request_item_id uuid,
    need_by_date date,
    material_type_snapshot character varying(30),
    CONSTRAINT ck_demand_plan_items_material_snapshot_present CHECK (((material_id IS NULL) OR (material_type_snapshot IS NOT NULL))),
    CONSTRAINT ck_demand_plan_items_material_type_snapshot CHECK (((material_type_snapshot IS NULL) OR ((material_type_snapshot)::text = ANY ((ARRAY['SERVICES'::character varying, 'FIXED_ASSET'::character varying, 'TMB'::character varying])::text[]))))
);


--
-- Name: demand_planning_cycles; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.demand_planning_cycles (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    plan_year integer NOT NULL,
    period_type character varying(20) NOT NULL,
    quarter_no integer,
    period_start date NOT NULL,
    period_end date NOT NULL,
    collection_start_date date,
    collection_end_date date,
    budget_review_deadline date,
    consolidation_deadline date,
    activation_date date,
    owner_username character varying(120),
    currency_code character varying(3) DEFAULT 'UZS'::character varying,
    total_plan_count integer DEFAULT 0 NOT NULL,
    total_item_count integer DEFAULT 0 NOT NULL,
    total_planned_amount numeric(19,4) DEFAULT 0 NOT NULL,
    total_approved_amount numeric(19,4) DEFAULT 0 NOT NULL,
    total_reserved_quantity numeric(19,4) DEFAULT 0 NOT NULL,
    generated_at timestamp without time zone,
    submitted_at timestamp without time zone,
    approved_at timestamp without time zone,
    activated_at timestamp without time zone,
    closed_at timestamp without time zone,
    owner_employee_id uuid
);


--
-- Name: demand_plans; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.demand_plans (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    plan_year integer NOT NULL,
    period_type character varying(20) NOT NULL,
    quarter_no integer,
    period_start date NOT NULL,
    period_end date NOT NULL,
    department_id uuid,
    budget_item_id uuid,
    owner_username character varying(120),
    currency_code character varying(3) DEFAULT 'UZS'::character varying,
    total_planned_amount numeric(19,4) DEFAULT 0 NOT NULL,
    total_approved_amount numeric(19,4) DEFAULT 0 NOT NULL,
    total_reserved_quantity numeric(19,4) DEFAULT 0 NOT NULL,
    consolidated_at timestamp without time zone,
    approved_at timestamp without time zone,
    planning_cycle_id uuid,
    request_type character varying(20) DEFAULT 'GOODS'::character varying NOT NULL,
    CONSTRAINT chk_demand_plans_request_type CHECK (((request_type IS NULL) OR (upper((request_type)::text) = ANY (ARRAY['GOODS'::text, 'SERVICE'::text]))))
);


--
-- Name: department_types; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.department_types (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    level_code character varying(120),
    hierarchy_level integer,
    system_type boolean DEFAULT false NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL
);


--
-- Name: departments; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.departments (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    external_id character varying(120),
    parent_department_id uuid,
    department_type character varying(80),
    manager_employee_id uuid,
    cost_center character varying(80),
    hierarchy_path character varying(500),
    sort_order integer,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL
);


--
-- Name: employee_roles; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.employee_roles (
    employee_id uuid NOT NULL,
    role character varying(80) NOT NULL
);


--
-- Name: employees; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.employees (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    external_id character varying(120),
    personnel_number character varying(80),
    department_id uuid,
    position_id uuid,
    manager_employee_id uuid,
    email character varying(240),
    phone character varying(80),
    employment_status character varying(40),
    hire_date date,
    dismissal_date date,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    warehouse_id uuid
);


--
-- Name: enum_translations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.enum_translations (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    enum_name character varying(120) NOT NULL,
    code character varying(120) NOT NULL,
    locale character varying(8) NOT NULL,
    label character varying(240) NOT NULL
);


--
-- Name: equipment; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.equipment (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    external_equipment_id character varying(120),
    model character varying(120),
    manufacturer character varying(240),
    serial_number character varying(120),
    department_id uuid,
    location character varying(240),
    manufacturer_id uuid
);


--
-- Name: exchange_rates; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.exchange_rates (
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    rate_date date NOT NULL,
    rate_to_base numeric(19,6) NOT NULL,
    source character varying(120) NOT NULL,
    legacy_uuid uuid,
    currency_legacy_uuid uuid,
    created_by_id bigint,
    id uuid NOT NULL,
    currency_id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL
);


--
-- Name: external_inbound_records; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.external_inbound_records (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    source_system character varying(80) NOT NULL,
    dataset character varying(120) NOT NULL,
    external_id character varying(180) NOT NULL,
    external_code character varying(180),
    provider_status character varying(80),
    source_path character varying(500),
    payload_hash character varying(64),
    payload text,
    applied_entity_type character varying(120),
    applied_entity_id uuid,
    synced_at timestamp without time zone
);


--
-- Name: fcm_tokens; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.fcm_tokens (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    user_id uuid NOT NULL,
    token text NOT NULL,
    platform character varying(16) NOT NULL,
    device_id character varying(255),
    active boolean DEFAULT true NOT NULL,
    last_seen_at timestamp with time zone DEFAULT now() NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    language character varying(5) DEFAULT 'en'::character varying NOT NULL,
    CONSTRAINT fcm_tokens_platform_check CHECK (((platform)::text = ANY ((ARRAY['WEB'::character varying, 'ANDROID'::character varying, 'IOS'::character varying])::text[])))
);


--
-- Name: fixed_assets; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.fixed_assets (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    material_id uuid NOT NULL,
    equipment_id uuid,
    source_acceptance_unit_id uuid,
    opening_source_type character varying(40),
    opening_source_id uuid,
    asset_code character varying(80),
    inventory_number character varying(80),
    serial_number character varying(120),
    lifecycle_status character varying(30) NOT NULL,
    custody_kind character varying(30) NOT NULL,
    custodian_employee_id uuid,
    department_id uuid,
    warehouse_id uuid,
    storage_location_id uuid,
    operating_location character varying(240),
    active_transfer_id uuid,
    acquisition_value numeric(19,4),
    acquisition_currency_code character varying(3),
    acquisition_exchange_rate numeric(19,6),
    acquisition_evidence_type character varying(40),
    acquisition_evidence_id uuid,
    first_commissioned_on date,
    first_commissioned_at timestamp without time zone,
    first_commissioned_by character varying(120),
    disposed_at timestamp without time zone,
    category character varying(50) DEFAULT 'MACHINERY_EQUIPMENT'::character varying,
    depreciation_method character varying(40) DEFAULT 'STRAIGHT_LINE'::character varying,
    useful_life_months integer DEFAULT 60,
    salvage_value numeric(19,4) DEFAULT 0,
    accumulated_depreciation numeric(19,4) DEFAULT 0,
    current_book_value numeric(19,4),
    depreciation_start_date date,
    last_depreciation_date date,
    depreciation_rate numeric(10,4) DEFAULT 0,
    CONSTRAINT ck_fixed_assets_custody_kind CHECK (((custody_kind)::text = ANY ((ARRAY['WAREHOUSE'::character varying, 'DEPARTMENT'::character varying, 'IN_TRANSIT'::character varying, 'EXTERNAL'::character varying, 'NONE'::character varying])::text[]))),
    CONSTRAINT ck_fixed_assets_lifecycle_status CHECK (((lifecycle_status)::text = ANY ((ARRAY['IN_WAREHOUSE'::character varying, 'ISSUED_PENDING_COMMISSIONING'::character varying, 'IN_USE'::character varying, 'IDLE'::character varying, 'UNDER_REPAIR'::character varying, 'RETURNED_TO_SUPPLIER'::character varying, 'DISPOSED'::character varying, 'CANCELLED'::character varying])::text[])))
);


--
-- Name: goods_receipt_batches; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.goods_receipt_batches (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    goods_receipt_line_id uuid NOT NULL,
    stock_batch_id uuid,
    manufacturer_batch_number character varying(160),
    manufactured_on date,
    expires_on date,
    accepted_quantity numeric(19,4) DEFAULT 0 NOT NULL,
    rejected_quantity numeric(19,4) DEFAULT 0 NOT NULL,
    damaged_quantity numeric(19,4) DEFAULT 0 NOT NULL
);


--
-- Name: goods_receipt_evidence; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.goods_receipt_evidence (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    company_id uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50) NOT NULL,
    description text,
    goods_receipt_id uuid NOT NULL,
    goods_receipt_line_id uuid NOT NULL,
    attachment_id uuid NOT NULL,
    purchase_order_id uuid NOT NULL,
    purchase_order_item_id uuid NOT NULL,
    supplier_id uuid NOT NULL,
    material_id uuid NOT NULL,
    material_group_id uuid,
    stock_movement_id uuid
);


--
-- Name: goods_receipt_lines; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.goods_receipt_lines (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    company_id uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50) NOT NULL,
    description text,
    goods_receipt_id uuid NOT NULL,
    purchase_order_id uuid NOT NULL,
    purchase_order_item_id uuid NOT NULL,
    supplier_id uuid NOT NULL,
    material_id uuid NOT NULL,
    material_group_id uuid,
    uom_code character varying(40),
    warehouse_id uuid NOT NULL,
    storage_location_id uuid,
    storage_bin_id uuid,
    batch_number character varying(120),
    open_quantity_before numeric(19,4) NOT NULL,
    accepted_quantity numeric(19,4) NOT NULL,
    rejected_quantity numeric(19,4) NOT NULL,
    damaged_quantity numeric(19,4) NOT NULL,
    excess_quantity numeric(19,4) NOT NULL,
    shortage_quantity numeric(19,4) NOT NULL,
    shortage_reason text,
    quality_status character varying(50),
    stock_movement_id uuid,
    accreditation_status character varying(50) NOT NULL,
    accreditation_message text,
    accreditation_exception_id uuid,
    physical_received_quantity numeric(19,4) DEFAULT 0 NOT NULL,
    CONSTRAINT ck_goods_receipt_line_quantities_non_negative CHECK (((open_quantity_before >= (0)::numeric) AND (accepted_quantity >= (0)::numeric) AND (rejected_quantity >= (0)::numeric) AND (damaged_quantity >= (0)::numeric) AND (excess_quantity >= (0)::numeric) AND (shortage_quantity >= (0)::numeric)))
);


--
-- Name: goods_receipt_units; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.goods_receipt_units (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    goods_receipt_line_id uuid NOT NULL,
    manufacturer_serial_number character varying(160),
    normalized_serial_number character varying(160),
    condition character varying(80),
    quality_decision character varying(40) NOT NULL,
    notes text,
    material_unit_id uuid,
    goods_receipt_batch_id uuid
);


--
-- Name: goods_receipts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.goods_receipts (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    company_id uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50) NOT NULL,
    description text,
    purchase_order_id uuid NOT NULL,
    supplier_id uuid NOT NULL,
    asn_id uuid,
    warehouse_id uuid NOT NULL,
    idempotency_key character varying(160),
    currency_code character varying(12) NOT NULL,
    exchange_rate numeric(19,8) NOT NULL,
    base_currency_code character varying(12) DEFAULT 'UZS'::character varying NOT NULL,
    accepted_quantity numeric(19,4) NOT NULL,
    rejected_quantity numeric(19,4) NOT NULL,
    damaged_quantity numeric(19,4) NOT NULL,
    excess_quantity numeric(19,4) NOT NULL,
    shortage_quantity numeric(19,4) NOT NULL,
    accepted_amount numeric(19,4) NOT NULL,
    base_accepted_amount numeric(19,4) NOT NULL,
    posted_at timestamp without time zone NOT NULL,
    posted_by character varying(120) NOT NULL,
    CONSTRAINT ck_goods_receipt_quantities_non_negative CHECK (((accepted_quantity >= (0)::numeric) AND (rejected_quantity >= (0)::numeric) AND (damaged_quantity >= (0)::numeric) AND (excess_quantity >= (0)::numeric) AND (shortage_quantity >= (0)::numeric) AND (accepted_amount >= (0)::numeric) AND (base_accepted_amount >= (0)::numeric) AND (exchange_rate > (0)::numeric)))
);


--
-- Name: idempotency_records; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.idempotency_records (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    company_id uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    operation character varying(100) NOT NULL,
    resource_id uuid NOT NULL,
    idempotency_key character varying(160) NOT NULL,
    request_hash character varying(64) NOT NULL,
    response_json text NOT NULL
);


--
-- Name: ikpu_codes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ikpu_codes (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    ikpu_code character varying(40) NOT NULL,
    package_code character varying(40),
    unit_code character varying(40),
    group_code character varying(80),
    vat_rate numeric(7,2),
    is_service boolean DEFAULT false NOT NULL,
    source_system character varying(40),
    external_id character varying(120),
    last_synced_at timestamp without time zone
);


--
-- Name: illiquid_stock_policies; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.illiquid_stock_policies (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    legal_entity_code character varying(80),
    warehouse_id uuid,
    material_category character varying(120),
    material_type character varying(120),
    aging_days integer NOT NULL,
    excess_days integer,
    effective_from date NOT NULL,
    effective_to date,
    active boolean DEFAULT false NOT NULL,
    activated_at timestamp without time zone,
    activated_by character varying(120)
);


--
-- Name: import_deliveries; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.import_deliveries (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    shipment_number character varying(120),
    supplier_id uuid NOT NULL,
    purchase_order_id uuid,
    asn_id uuid,
    incoterm_id uuid,
    import_flag boolean DEFAULT true NOT NULL,
    transport_mode character varying(40),
    carrier_name character varying(240),
    forwarder_name character varying(240),
    broker_name character varying(240),
    origin_country character varying(3),
    origin_port character varying(240),
    border_post character varying(240),
    destination_country character varying(3),
    destination_place character varying(240),
    destination_warehouse_id uuid,
    etd date,
    eta date,
    actual_arrival_date date,
    customs_declaration_id uuid,
    base_goods_amount numeric(19,4) DEFAULT 0,
    logistics_cost_amount numeric(19,4) DEFAULT 0,
    customs_cost_amount numeric(19,4) DEFAULT 0,
    landed_cost_amount numeric(19,4) DEFAULT 0,
    currency_code character varying(3),
    current_milestone character varying(80),
    last_event_at timestamp without time zone,
    risk_flag boolean DEFAULT false NOT NULL,
    risk_reason character varying(1000)
);


--
-- Name: import_freight_bookings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.import_freight_bookings (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    booking_number character varying(120),
    import_delivery_id uuid NOT NULL,
    carrier_supplier_id uuid,
    forwarder_supplier_id uuid,
    transport_mode character varying(40),
    carrier_name character varying(240),
    forwarder_name character varying(240),
    origin_country character varying(3),
    origin_port character varying(240),
    destination_country character varying(3),
    destination_place character varying(240),
    container_number character varying(120),
    bill_of_lading_number character varying(120),
    waybill_number character varying(120),
    planned_pickup_date date,
    planned_delivery_date date,
    actual_pickup_date date,
    actual_delivery_date date,
    freight_amount numeric(19,4) DEFAULT 0 NOT NULL,
    fuel_surcharge_amount numeric(19,4) DEFAULT 0 NOT NULL,
    other_charges_amount numeric(19,4) DEFAULT 0 NOT NULL,
    total_freight_amount numeric(19,4) DEFAULT 0 NOT NULL,
    currency_code character varying(3) DEFAULT 'UZS'::character varying NOT NULL,
    allocation_method character varying(40) DEFAULT 'VALUE'::character varying NOT NULL,
    included_in_inventory_cost boolean DEFAULT true NOT NULL,
    invoice_number character varying(120),
    invoice_date date,
    landed_cost_component_id uuid,
    posted_at timestamp without time zone
);


--
-- Name: import_insurance_policies; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.import_insurance_policies (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    policy_number character varying(120),
    import_delivery_id uuid NOT NULL,
    insurer_supplier_id uuid,
    insurer_name character varying(240),
    coverage_type character varying(120),
    insured_amount numeric(19,4) DEFAULT 0 NOT NULL,
    premium_amount numeric(19,4) DEFAULT 0 NOT NULL,
    deductible_amount numeric(19,4) DEFAULT 0 NOT NULL,
    currency_code character varying(3) DEFAULT 'UZS'::character varying NOT NULL,
    allocation_method character varying(40) DEFAULT 'VALUE'::character varying NOT NULL,
    included_in_inventory_cost boolean DEFAULT true NOT NULL,
    valid_from date,
    valid_to date,
    policy_attachment_id uuid,
    landed_cost_component_id uuid,
    activated_at timestamp without time zone,
    claimed_at timestamp without time zone,
    closed_at timestamp without time zone
);


--
-- Name: incoterms; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.incoterms (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    version_year integer DEFAULT 2020,
    mode_scope character varying(80) DEFAULT 'ANY'::character varying,
    risk_transfer_point character varying(500),
    seller_cost_responsibility character varying(1000),
    buyer_cost_responsibility character varying(1000),
    named_place_required boolean DEFAULT true NOT NULL
);


--
-- Name: integration_events; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.integration_events (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    event_type character varying(120),
    aggregate_type character varying(120),
    aggregate_id uuid,
    payload text,
    retry_count integer,
    next_retry_at timestamp without time zone,
    idempotency_key character varying(120),
    last_error text,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    external_id character varying(200),
    last_attempt_at timestamp without time zone,
    replay_of_event_id uuid,
    manual_reason text,
    direction character varying(20) DEFAULT 'OUTBOUND'::character varying NOT NULL
);


--
-- Name: integration_settings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.integration_settings (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    system_code character varying(60) NOT NULL,
    provider_name character varying(120),
    enabled boolean DEFAULT true NOT NULL,
    mock_mode boolean DEFAULT true NOT NULL,
    base_url character varying(500),
    auth_type character varying(40),
    username character varying(180),
    password_encrypted text,
    bearer_token_encrypted text,
    api_key_header character varying(120),
    api_key_encrypted text,
    client_id character varying(180),
    client_secret_encrypted text,
    sender_tin character varying(40),
    sender_branch_code character varying(80),
    callback_url character varying(500),
    auth_path character varying(240),
    health_path character varying(240),
    events_path character varying(240),
    invoice_path character varying(240),
    document_path character varying(240),
    supplier_status_path character varying(240),
    provider_version character varying(80),
    organization_id character varying(180),
    project_id character varying(180),
    ai_model character varying(120),
    ai_request_path character varying(320),
    ai_system_prompt text,
    ai_temperature numeric(5,2),
    ai_max_tokens integer,
    ai_timeout_seconds integer
);


--
-- Name: inventory_accounting_reconciliations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.inventory_accounting_reconciliations (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    period_start date NOT NULL,
    period_end date NOT NULL,
    warehouse_id uuid,
    accounting_system character varying(80) DEFAULT 'FI'::character varying NOT NULL,
    external_reference character varying(160),
    inventory_value numeric(19,4) DEFAULT 0 NOT NULL,
    accounting_value numeric(19,4) DEFAULT 0 NOT NULL,
    difference_amount numeric(19,4) DEFAULT 0 NOT NULL,
    tolerance_amount numeric(19,4) DEFAULT 0 NOT NULL,
    difference_status character varying(30) DEFAULT 'NOT_CHECKED'::character varying NOT NULL,
    reconciled_at timestamp without time zone,
    posted_at timestamp without time zone,
    transit_stock_value numeric(19,4) DEFAULT 0 NOT NULL
);


--
-- Name: inventory_accounting_settings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.inventory_accounting_settings (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    default_valuation_method character varying(40) DEFAULT 'ACTUAL_COST'::character varying NOT NULL,
    allow_warehouse_valuation_override boolean DEFAULT false NOT NULL,
    allow_negative_stock boolean DEFAULT false NOT NULL,
    period_close_mode character varying(60) DEFAULT 'CURRENT_BALANCE'::character varying NOT NULL,
    base_currency_code character varying(3) DEFAULT 'UZS'::character varying NOT NULL,
    rounding_scale integer DEFAULT 4 NOT NULL,
    rounding_mode character varying(40) DEFAULT 'HALF_UP'::character varying NOT NULL,
    revaluation_posting_mode character varying(60) DEFAULT 'VALUATION_RECORD_ONLY'::character varying NOT NULL,
    impairment_posting_mode character varying(60) DEFAULT 'VALUATION_RECORD_ONLY'::character varying NOT NULL,
    allow_zero_cost_receipt boolean DEFAULT false NOT NULL
);


--
-- Name: inventory_commission_member_settings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.inventory_commission_member_settings (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    commission_id uuid NOT NULL,
    commission_member_id uuid NOT NULL,
    show_system_quantity_to_counter boolean DEFAULT true NOT NULL
);


--
-- Name: inventory_cost_layers; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.inventory_cost_layers (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    material_id uuid NOT NULL,
    warehouse_id uuid NOT NULL,
    stock_batch_id uuid,
    source_movement_id uuid NOT NULL,
    source_document_type character varying(50),
    source_document_id uuid,
    parent_cost_layer_id uuid,
    layer_type character varying(30) NOT NULL,
    received_quantity numeric(19,4) DEFAULT 0 NOT NULL,
    remaining_quantity numeric(19,4) DEFAULT 0 NOT NULL,
    unit_cost numeric(19,6) DEFAULT 0 NOT NULL,
    total_cost numeric(19,4) DEFAULT 0 NOT NULL,
    currency_code character varying(10) DEFAULT 'UZS'::character varying NOT NULL,
    exchange_rate numeric(19,6) DEFAULT 1 NOT NULL,
    received_at timestamp without time zone NOT NULL,
    transfer_transaction_id uuid,
    source_warehouse_id uuid,
    target_warehouse_id uuid,
    source_stock_movement_id uuid,
    target_stock_movement_id uuid
);


--
-- Name: inventory_document_material_groups; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.inventory_document_material_groups (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    inventory_document_id uuid NOT NULL,
    material_group_id uuid NOT NULL
);


--
-- Name: inventory_document_materials; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.inventory_document_materials (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    inventory_document_id uuid NOT NULL,
    material_id uuid NOT NULL
);


--
-- Name: inventory_documents; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.inventory_documents (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    warehouse_id uuid,
    inventory_date date,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    inventory_type character varying(40) DEFAULT 'PLANNED'::character varying,
    commission_id uuid,
    planned_start_date date,
    planned_end_date date,
    actual_start_date date,
    actual_end_date date,
    responsible_user_id uuid,
    global_warehouse boolean DEFAULT false NOT NULL,
    freeze_operations boolean DEFAULT true NOT NULL,
    snapshot_at timestamp without time zone,
    total_items integer DEFAULT 0 NOT NULL,
    counted_items integer DEFAULT 0 NOT NULL,
    discrepancy_items integer DEFAULT 0 NOT NULL,
    count_revision bigint DEFAULT 0 NOT NULL,
    submitted_count_revision bigint,
    approved_count_revision bigint,
    posting_date date,
    storage_location_id uuid,
    storage_bin_id uuid,
    approval_instance_id uuid,
    commission_approved_revision bigint,
    commission_approved_by character varying(120),
    commission_approved_at timestamp without time zone,
    finance_approved_revision bigint,
    finance_approved_by character varying(120),
    finance_approved_at timestamp without time zone,
    correction_reason character varying(1000),
    unidentified_stock jsonb DEFAULT '[]'::jsonb NOT NULL
);


--
-- Name: inventory_fx_differences; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.inventory_fx_differences (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    period_start date NOT NULL,
    period_end date NOT NULL,
    document_date date NOT NULL,
    reference_type character varying(80),
    reference_id uuid,
    material_id uuid,
    warehouse_id uuid,
    supplier_id uuid,
    document_currency_code character varying(3) DEFAULT 'USD'::character varying NOT NULL,
    base_currency_code character varying(3) DEFAULT 'UZS'::character varying NOT NULL,
    foreign_amount numeric(19,4) DEFAULT 0 NOT NULL,
    document_rate numeric(19,6) DEFAULT 1 NOT NULL,
    closing_rate numeric(19,6) DEFAULT 1 NOT NULL,
    base_amount numeric(19,4) DEFAULT 0 NOT NULL,
    revalued_base_amount numeric(19,4) DEFAULT 0 NOT NULL,
    difference_amount numeric(19,4) DEFAULT 0 NOT NULL,
    difference_type character varying(20) DEFAULT 'NONE'::character varying NOT NULL,
    posted_at timestamp without time zone
);


--
-- Name: inventory_items; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.inventory_items (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    inventory_document_id uuid NOT NULL,
    material_id uuid NOT NULL,
    system_quantity numeric(19,4),
    counted_quantity numeric(19,4),
    difference_quantity numeric(19,4),
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    warehouse_id uuid,
    storage_location_id uuid,
    storage_bin_id uuid,
    stock_batch_id uuid,
    unit_cost numeric(19,4),
    difference_amount numeric(19,4),
    line_status character varying(40),
    discrepancy_type character varying(60),
    discrepancy_reason character varying(500),
    decision_type character varying(80),
    responsible_user_id uuid,
    linked_stock_movement_id uuid,
    linked_purchase_order_id uuid,
    linked_supplier_id uuid,
    linked_contract_id uuid,
    compensation_amount numeric(19,4),
    decision_comment character varying(1000),
    decided_by character varying(120),
    decided_at timestamp without time zone,
    comment_text character varying(1000),
    counted_by character varying(120),
    counted_at timestamp without time zone,
    explanation_status character varying(40),
    explanation_requested_from character varying(120),
    explanation_requested_at timestamp without time zone,
    explanation_text character varying(1000),
    explanation_provided_at timestamp without time zone,
    explanation_review_comment character varying(500),
    recount_requested_at timestamp without time zone,
    recount_reason character varying(500),
    count_revision bigint DEFAULT 0 NOT NULL,
    decision_count_revision bigint,
    reconciled_source_movement_id uuid,
    count_comment character varying(1000),
    found_stock boolean DEFAULT false NOT NULL,
    found_reason character varying(1000),
    found_evidence_ids jsonb DEFAULT '[]'::jsonb NOT NULL,
    decision_evidence_ids jsonb DEFAULT '[]'::jsonb NOT NULL,
    observed_batch_number character varying(120),
    observed_serial_number character varying(120),
    observed_expiry_date date,
    valuation_basis character varying(1000),
    valuation_evidence_ids jsonb DEFAULT '[]'::jsonb NOT NULL,
    valuation_count_revision bigint,
    valued_by character varying(120),
    valued_at timestamp without time zone,
    review_blocker character varying(100),
    source_correction_movement_id uuid
);


--
-- Name: inventory_line_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.inventory_line_history (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name character varying(240) NOT NULL,
    status character varying(50),
    description text,
    company_id uuid NOT NULL,
    inventory_item_id uuid NOT NULL,
    inventory_document_id uuid NOT NULL,
    action character varying(80) NOT NULL,
    old_counted_quantity numeric(19,4),
    new_counted_quantity numeric(19,4),
    old_status character varying(40),
    new_status character varying(40),
    old_difference_quantity numeric(19,4),
    new_difference_quantity numeric(19,4),
    reason character varying(1000),
    changed_by character varying(120) NOT NULL,
    changed_at timestamp without time zone NOT NULL,
    old_count_revision bigint,
    new_count_revision bigint,
    review_evidence jsonb DEFAULT '{}'::jsonb NOT NULL
);


--
-- Name: inventory_optimization_recommendations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.inventory_optimization_recommendations (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    material_id uuid NOT NULL,
    warehouse_id uuid,
    demand_forecast_id uuid,
    annual_demand numeric(19,4) DEFAULT 0 NOT NULL,
    average_monthly_demand numeric(19,4) DEFAULT 0,
    order_cost numeric(19,4) DEFAULT 0 NOT NULL,
    holding_cost_per_unit numeric(19,4) DEFAULT 0 NOT NULL,
    unit_cost numeric(19,4) DEFAULT 0,
    holding_rate numeric(9,4) DEFAULT 0,
    eoq_quantity numeric(19,4) DEFAULT 0,
    lead_time_days integer,
    service_level numeric(9,4) DEFAULT 0,
    recommended_safety_stock numeric(19,4) DEFAULT 0,
    recommended_reorder_point numeric(19,4) DEFAULT 0,
    recommended_min_stock numeric(19,4) DEFAULT 0,
    recommended_max_stock numeric(19,4) DEFAULT 0,
    generated_at timestamp without time zone,
    applied_at timestamp without time zone,
    order_frequency_days numeric(19,4) DEFAULT 0,
    annual_ordering_cost numeric(19,4) DEFAULT 0,
    annual_holding_cost numeric(19,4) DEFAULT 0,
    annual_total_cost numeric(19,4) DEFAULT 0
);


--
-- Name: inventory_period_closes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.inventory_period_closes (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    period_start date NOT NULL,
    period_end date NOT NULL,
    warehouse_id uuid,
    total_stock_value numeric(19,4) DEFAULT 0 NOT NULL,
    landed_cost_amount numeric(19,4) DEFAULT 0 NOT NULL,
    fx_difference_amount numeric(19,4) DEFAULT 0 NOT NULL,
    movement_count bigint DEFAULT 0 NOT NULL,
    valuation_count bigint DEFAULT 0 NOT NULL,
    accounting_reconciliation_id uuid,
    closed_at timestamp without time zone,
    open_movement_count bigint DEFAULT 0 NOT NULL,
    accounting_reconciliation_status character varying(30),
    accounting_difference_amount numeric(19,4) DEFAULT 0 NOT NULL,
    transit_stock_value numeric(19,4) DEFAULT 0 NOT NULL
);


--
-- Name: issue_request_lines; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.issue_request_lines (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    company_id uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    issue_request_id uuid NOT NULL,
    material_id uuid NOT NULL,
    requested_quantity numeric(19,4) NOT NULL,
    approved_quantity numeric(19,4) DEFAULT 0 NOT NULL,
    issued_quantity numeric(19,4) DEFAULT 0 NOT NULL,
    unit_cost numeric(19,4),
    line_comment text,
    reservation_id uuid,
    storage_location_id uuid,
    stock_batch_id uuid,
    stock_movement_id uuid,
    source_demand_plan_item_id uuid
);


--
-- Name: issue_requests; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.issue_requests (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    company_id uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    warehouse_id uuid NOT NULL,
    requester_user_id uuid NOT NULL,
    receiver_user_id uuid NOT NULL,
    cost_object_type character varying(30) NOT NULL,
    cost_object_id uuid NOT NULL,
    required_date date,
    issued_date date,
    purpose text,
    approval_comment text,
    stock_movement_document_id uuid
);


--
-- Name: laboratory_tests; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.laboratory_tests (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    quality_inspection_id uuid NOT NULL,
    material_id uuid NOT NULL,
    sample_number character varying(120),
    test_type character varying(120) NOT NULL,
    test_method character varying(240),
    specification character varying(1000),
    measured_value character varying(240),
    result text,
    passed boolean DEFAULT false NOT NULL,
    requested_at timestamp without time zone,
    started_at timestamp without time zone,
    completed_at timestamp without time zone,
    lab_technician_employee_id uuid,
    protocol_attachment_id uuid,
    lims_sample_id character varying(160),
    lims_status character varying(80),
    lims_requested_at timestamp without time zone,
    lims_result_received_at timestamp without time zone
);


--
-- Name: landed_cost_allocation_lines; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.landed_cost_allocation_lines (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    allocation_document_id uuid NOT NULL,
    material_id uuid NOT NULL,
    warehouse_id uuid NOT NULL,
    storage_location_id uuid,
    stock_batch_id uuid,
    allocation_base numeric(19,4) DEFAULT 0 NOT NULL,
    quantity numeric(19,4) DEFAULT 0 NOT NULL,
    allocated_amount numeric(19,4) DEFAULT 0 NOT NULL,
    allocated_base_amount numeric(19,4) DEFAULT 0 NOT NULL,
    unit_cost_increase numeric(19,6) DEFAULT 0 NOT NULL
);


--
-- Name: landed_cost_allocations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.landed_cost_allocations (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    import_delivery_id uuid NOT NULL,
    allocation_method character varying(40) DEFAULT 'VALUE'::character varying NOT NULL,
    document_date date NOT NULL,
    currency_code character varying(3) DEFAULT 'UZS'::character varying NOT NULL,
    base_currency_code character varying(3) DEFAULT 'UZS'::character varying NOT NULL,
    exchange_rate numeric(19,6) DEFAULT 1 NOT NULL,
    total_amount numeric(19,4) DEFAULT 0 NOT NULL,
    base_total_amount numeric(19,4) DEFAULT 0 NOT NULL,
    posted_at timestamp without time zone
);


--
-- Name: landed_cost_components; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.landed_cost_components (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    import_delivery_id uuid NOT NULL,
    component_type character varying(80) NOT NULL,
    amount numeric(19,4) DEFAULT 0,
    currency_code character varying(3),
    allocation_method character varying(40),
    supplier_id uuid,
    invoice_number character varying(120),
    invoice_date date,
    included_in_inventory_cost boolean DEFAULT true NOT NULL,
    posted_at timestamp without time zone
);


--
-- Name: landed_logistics_events; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.landed_logistics_events (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    import_delivery_id uuid NOT NULL,
    event_type character varying(80) NOT NULL,
    event_date timestamp without time zone,
    location character varying(240),
    carrier_status character varying(240),
    external_system character varying(80),
    external_event_id character varying(160),
    exception_flag boolean DEFAULT false NOT NULL,
    resolved_at timestamp without time zone
);


--
-- Name: management_opportunities; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.management_opportunities (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    opportunity_type character varying(60) NOT NULL,
    calculation_run_id uuid NOT NULL,
    policy_id uuid,
    source_aggregate_type character varying(80) NOT NULL,
    source_aggregate_id uuid NOT NULL,
    source_line_id uuid,
    material_id uuid,
    supplier_id uuid,
    warehouse_id uuid,
    as_of_date date NOT NULL,
    source_currency_code character varying(3) NOT NULL,
    normalized_currency_code character varying(3),
    source_amount numeric(19,4) DEFAULT 0 NOT NULL,
    normalized_amount numeric(19,4),
    baseline_amount numeric(19,4),
    actual_amount numeric(19,4),
    quantity numeric(19,4),
    exchange_rate numeric(19,8),
    exchange_rate_id uuid,
    exchange_rate_date date,
    exchange_rate_source character varying(120),
    normalization_quality character varying(50),
    formula_code character varying(120) NOT NULL,
    formula_snapshot text NOT NULL,
    confidence character varying(30) NOT NULL,
    data_completeness character varying(30) NOT NULL,
    assigned_role character varying(80),
    submitted_at timestamp without time zone,
    submitted_by character varying(120),
    reviewed_at timestamp without time zone,
    reviewed_by character varying(120),
    review_comment text,
    approved_at timestamp without time zone,
    approved_by character varying(120),
    rejection_reason text,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120)
);


--
-- Name: management_opportunity_evidence; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.management_opportunity_evidence (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    opportunity_id uuid NOT NULL,
    evidence_type character varying(80) NOT NULL,
    source_entity_type character varying(80) NOT NULL,
    source_entity_id uuid,
    captured_snapshot text NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120)
);


--
-- Name: management_opportunity_policies; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.management_opportunity_policies (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    opportunity_type character varying(60) NOT NULL,
    active boolean DEFAULT false NOT NULL,
    effective_from date NOT NULL,
    effective_to date,
    warehouse_id uuid,
    material_group_id uuid,
    supplier_id uuid,
    annual_carrying_cost_rate numeric(9,6),
    daily_impact_rate numeric(19,4),
    attributable_share numeric(9,6),
    currency_code character varying(3),
    activated_at timestamp without time zone,
    activated_by character varying(120),
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120)
);


--
-- Name: management_opportunity_runs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.management_opportunity_runs (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    as_of_date date NOT NULL,
    target_currency_code character varying(3) NOT NULL,
    requested_types text NOT NULL,
    candidate_count integer DEFAULT 0 NOT NULL,
    skipped_count integer DEFAULT 0 NOT NULL,
    skip_summary text,
    started_at timestamp without time zone NOT NULL,
    completed_at timestamp without time zone,
    failure_summary text,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120)
);


--
-- Name: manufacturers; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.manufacturers (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    country character varying(120),
    website character varying(240),
    email character varying(160),
    phone character varying(80),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    country_code character varying(3)
);


--
-- Name: material_analogs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.material_analogs (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    material_id uuid NOT NULL,
    analog_material_id uuid NOT NULL,
    analog_type character varying(40) NOT NULL,
    interchangeability character varying(40),
    conversion_factor numeric(19,6) DEFAULT 1 NOT NULL,
    priority integer DEFAULT 100 NOT NULL,
    approved_by character varying(120),
    valid_from date,
    valid_to date
);


--
-- Name: material_attribute_templates; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.material_attribute_templates (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    material_group_id uuid NOT NULL,
    attribute_name character varying(160) NOT NULL,
    required_flag boolean DEFAULT false NOT NULL,
    data_type character varying(40) NOT NULL,
    allowed_values text
);


--
-- Name: material_attributes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.material_attributes (
    id uuid NOT NULL,
    material_id uuid NOT NULL,
    attribute_key character varying(255) NOT NULL,
    attribute_value character varying(4000) NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone,
    is_deleted boolean DEFAULT false NOT NULL
);


--
-- Name: material_consumption_norms; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.material_consumption_norms (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    material_id uuid NOT NULL,
    equipment_id uuid,
    department_id uuid,
    product_code character varying(120),
    operation_code character varying(120),
    norm_type character varying(40) NOT NULL,
    base_quantity numeric(19,4) DEFAULT 0 NOT NULL,
    output_quantity numeric(19,4) DEFAULT 1 NOT NULL,
    unit_of_measure_id uuid,
    loss_percent numeric(8,4) DEFAULT 0 NOT NULL,
    effective_from date,
    effective_to date
);


--
-- Name: material_cost_observations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.material_cost_observations (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    material_id uuid,
    mxik_code character varying(40) NOT NULL,
    unit_cost numeric(19,4) NOT NULL,
    currency_code character varying(3) DEFAULT 'UZS'::character varying NOT NULL,
    quantity numeric(19,4),
    source_type character varying(40) NOT NULL,
    source_entity_id uuid,
    source_line_id uuid NOT NULL,
    document_code character varying(120),
    supplier_id uuid,
    observed_at timestamp without time zone NOT NULL
);


--
-- Name: material_duplicate_candidates; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.material_duplicate_candidates (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    material_id uuid NOT NULL,
    candidate_material_id uuid NOT NULL,
    similarity_score numeric(8,4),
    reason character varying(240),
    decision_comment text,
    decided_at timestamp without time zone,
    merged_at timestamp without time zone,
    ai_source_job_id uuid,
    ai_evidence_json text
);


--
-- Name: material_equipment_compatibilities; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.material_equipment_compatibilities (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    material_id uuid NOT NULL,
    equipment_id uuid NOT NULL,
    compatibility_type character varying(40) NOT NULL,
    manufacturer_part_no character varying(120),
    required_flag boolean DEFAULT false NOT NULL,
    valid_from date,
    valid_to date
);


--
-- Name: material_groups; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.material_groups (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL
);


--
-- Name: material_market_prices; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.material_market_prices (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    material_id uuid NOT NULL,
    warehouse_id uuid,
    market_unit_price numeric(19,4) DEFAULT 0 NOT NULL,
    net_realizable_unit_price numeric(19,4) DEFAULT 0 NOT NULL,
    currency_code character varying(3) DEFAULT 'UZS'::character varying NOT NULL,
    effective_date date NOT NULL,
    source_type character varying(80),
    source_reference character varying(160),
    approved_by character varying(120),
    approved_at timestamp without time zone
);


--
-- Name: material_master_requests; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.material_master_requests (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    request_type character varying(40) NOT NULL,
    material_id uuid,
    proposed_name character varying(240),
    material_group_id uuid,
    uom_id uuid,
    mxik_code character varying(80),
    tnved_code character varying(80),
    requested_by character varying(120),
    last_action_comment text,
    target_material_id uuid,
    submitted_at timestamp without time zone,
    reviewed_at timestamp without time zone,
    decided_at timestamp without time zone,
    applied_at timestamp without time zone
);


--
-- Name: material_quality_issues; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.material_quality_issues (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    material_id uuid NOT NULL,
    issue_type character varying(80) NOT NULL,
    severity character varying(40) NOT NULL,
    field_name character varying(120),
    assigned_to character varying(120),
    resolution_comment text,
    evidence_reference character varying(500),
    assigned_at timestamp without time zone,
    started_at timestamp without time zone,
    resolved_at timestamp without time zone
);


--
-- Name: material_reservation_events; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.material_reservation_events (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by character varying(120),
    reservation_id uuid NOT NULL,
    sequence_no bigint NOT NULL,
    event_type character varying(40) NOT NULL,
    idempotency_key character varying(160) NOT NULL,
    expected_version bigint,
    reservation_version bigint NOT NULL,
    stock_balance_id uuid NOT NULL,
    material_id uuid NOT NULL,
    warehouse_id uuid NOT NULL,
    storage_location_id uuid,
    storage_bin_id uuid,
    stock_batch_id uuid,
    disposition_snapshot character varying(40) NOT NULL,
    uom_id_snapshot uuid NOT NULL,
    uom_code_snapshot character varying(80) NOT NULL,
    quantity numeric(19,4) NOT NULL,
    on_hand_delta numeric(19,4) NOT NULL,
    available_delta numeric(19,4) NOT NULL,
    reserved_delta numeric(19,4) NOT NULL,
    total_value_delta numeric(19,4) NOT NULL,
    status_from character varying(50),
    status_to character varying(50) NOT NULL,
    reason character varying(500),
    evidence_reference character varying(500),
    CONSTRAINT ck_material_reservation_event_quantity CHECK ((quantity > (0)::numeric))
);


--
-- Name: material_reservations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.material_reservations (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    material_id uuid NOT NULL,
    warehouse_id uuid NOT NULL,
    storage_location_id uuid,
    stock_batch_id uuid,
    demand_plan_id uuid,
    demand_plan_item_id uuid,
    project_code character varying(80),
    repair_order_code character varying(80),
    reservation_type character varying(30) DEFAULT 'MANUAL'::character varying NOT NULL,
    priority character varying(30) DEFAULT 'NORMAL'::character varying NOT NULL,
    quantity numeric(19,4) DEFAULT 0 NOT NULL,
    reserved_quantity numeric(19,4) DEFAULT 0 NOT NULL,
    released_quantity numeric(19,4) DEFAULT 0 NOT NULL,
    required_date date,
    expires_at timestamp without time zone,
    reference_type character varying(80),
    reference_id uuid,
    reserved_by character varying(120),
    storage_bin_id uuid,
    disposition_snapshot character varying(40),
    uom_id_snapshot uuid,
    uom_code_snapshot character varying(80),
    consumed_quantity numeric(19,4),
    cancelled_quantity numeric(19,4),
    expired_quantity numeric(19,4),
    released_at timestamp without time zone,
    released_by character varying(120),
    consumed_at timestamp without time zone,
    consumed_by character varying(120),
    cancelled_at timestamp without time zone,
    cancelled_by character varying(120),
    cancellation_reason character varying(500),
    expired_at timestamp without time zone,
    expired_by character varying(120),
    expiration_reason character varying(500),
    terminal_at timestamp without time zone,
    terminal_by character varying(120),
    terminal_reason character varying(500),
    terminal_evidence_reference character varying(500),
    terminal_idempotency_key character varying(160)
);


--
-- Name: material_unit_events; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.material_unit_events (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    material_unit_id uuid NOT NULL,
    event_type character varying(40) NOT NULL,
    source_type character varying(80) NOT NULL,
    source_id uuid NOT NULL,
    warehouse_id uuid NOT NULL,
    storage_location_id uuid,
    storage_bin_id uuid,
    stock_batch_id uuid,
    performed_by character varying(120),
    occurred_at timestamp without time zone NOT NULL,
    CONSTRAINT chk_material_unit_events_event_type CHECK (((event_type)::text = ANY ((ARRAY['RECEIVED'::character varying, 'RESERVED'::character varying, 'RESERVATION_RELEASED'::character varying, 'ISSUED'::character varying, 'TRANSFER_DISPATCHED'::character varying, 'TRANSFER_RECEIVED'::character varying, 'RETURNED'::character varying, 'ADJUSTED_IN'::character varying, 'ADJUSTED_OUT'::character varying])::text[])))
);


--
-- Name: material_unit_operations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.material_unit_operations (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    material_unit_id uuid NOT NULL,
    operation_type character varying(40) NOT NULL,
    reference_type character varying(80) NOT NULL,
    reference_id uuid NOT NULL,
    reference_line_id uuid,
    from_status character varying(40),
    to_status character varying(40) NOT NULL,
    from_warehouse_id uuid,
    to_warehouse_id uuid,
    from_storage_location_id uuid,
    to_storage_location_id uuid,
    from_storage_bin_id uuid,
    to_storage_bin_id uuid,
    stock_batch_id uuid,
    operation_key character varying(160),
    occurred_at timestamp without time zone NOT NULL,
    CONSTRAINT chk_material_unit_operations_to_status CHECK (((to_status)::text = ANY ((ARRAY['IN_STOCK'::character varying, 'RESERVED'::character varying, 'IN_TRANSIT'::character varying, 'ISSUED'::character varying, 'QUARANTINE'::character varying, 'RETURNED'::character varying, 'DISPOSED'::character varying])::text[]))),
    CONSTRAINT chk_material_unit_operations_type CHECK (((operation_type)::text = ANY ((ARRAY['RESERVATION'::character varying, 'ISSUE'::character varying, 'TRANSFER'::character varying, 'RETURN'::character varying, 'INVENTORY_COUNT'::character varying, 'ADJUSTMENT'::character varying])::text[])))
);


--
-- Name: material_units; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.material_units (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    material_id uuid NOT NULL,
    manufacturer_serial_number character varying(160) NOT NULL,
    normalized_serial_number character varying(160) NOT NULL,
    internal_unit_number character varying(120),
    unit_status character varying(40) NOT NULL,
    warehouse_id uuid NOT NULL,
    storage_location_id uuid,
    storage_bin_id uuid,
    stock_batch_id uuid,
    goods_receipt_line_id uuid NOT NULL,
    goods_receipt_unit_id uuid NOT NULL,
    received_at timestamp without time zone NOT NULL,
    CONSTRAINT chk_material_units_unit_status CHECK (((unit_status)::text = ANY ((ARRAY['IN_STOCK'::character varying, 'RESERVED'::character varying, 'IN_TRANSIT'::character varying, 'ISSUED'::character varying, 'QUARANTINE'::character varying, 'RETURNED'::character varying, 'DISPOSED'::character varying])::text[])))
);


--
-- Name: material_valuation_method_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.material_valuation_method_history (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    material_id uuid NOT NULL,
    old_valuation_method character varying(40),
    new_valuation_method character varying(40) NOT NULL,
    effective_at timestamp without time zone NOT NULL,
    change_reason text
);


--
-- Name: materials; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.materials (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    material_group_id uuid,
    unit_of_measure_id uuid,
    valuation_method character varying(40) DEFAULT 'ACTUAL_COST'::character varying,
    min_stock numeric(19,4),
    safety_stock numeric(19,4),
    reorder_point numeric(19,4),
    barcode character varying(120),
    article character varying(120),
    manufacturer character varying(240),
    country_of_origin character varying(120),
    shelf_life_days integer,
    hazard_class character varying(40),
    max_stock numeric(19,4),
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    qr_code character varying(160),
    rfid_tag character varying(160),
    ikpu_code_id uuid,
    ikpu_code character varying(40),
    tnved_code_id uuid,
    tnved_code character varying(40),
    replacement_material_id uuid,
    lifecycle_reason text,
    lifecycle_changed_at timestamp without time zone,
    manufacturer_id uuid,
    replenishment_lead_time_days integer,
    average_cost numeric(19,4),
    average_cost_currency_code character varying(3),
    average_cost_observation_id uuid,
    average_cost_source_type character varying(40),
    average_cost_source_entity_id uuid,
    average_cost_source_line_id uuid,
    average_cost_document_code character varying(120),
    average_cost_observed_at timestamp without time zone,
    average_cost_selected_at timestamp without time zone,
    material_type character varying(30) DEFAULT 'TMB'::character varying NOT NULL,
    tracking_mode character varying(20) NOT NULL,
    batch_tracking_policy character varying(20) NOT NULL,
    serial_tracking_policy character varying(20) NOT NULL,
    CONSTRAINT chk_materials_batch_tracking_policy CHECK (((batch_tracking_policy)::text = ANY ((ARRAY['NONE'::character varying, 'OPTIONAL'::character varying, 'REQUIRED'::character varying])::text[]))),
    CONSTRAINT chk_materials_serial_tracking_policy CHECK (((serial_tracking_policy)::text = ANY ((ARRAY['NONE'::character varying, 'REQUIRED'::character varying])::text[]))),
    CONSTRAINT chk_materials_tracking_mode CHECK (((tracking_mode)::text = ANY ((ARRAY['QUANTITY'::character varying, 'LOT'::character varying, 'SERIAL'::character varying])::text[]))),
    CONSTRAINT ck_materials_material_type CHECK (((material_type)::text = ANY ((ARRAY['SERVICES'::character varying, 'FIXED_ASSET'::character varying, 'TMB'::character varying])::text[])))
);


--
-- Name: mobile_warehouse_operations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.mobile_warehouse_operations (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    operation_type character varying(40) NOT NULL,
    material_id uuid,
    warehouse_id uuid,
    target_warehouse_id uuid,
    storage_location_id uuid,
    target_storage_location_id uuid,
    quantity numeric(19,4) DEFAULT 0 NOT NULL,
    unit_cost numeric(19,4) DEFAULT 0 NOT NULL,
    stock_movement_id uuid,
    barcode_or_rfid character varying(160),
    signed boolean DEFAULT false NOT NULL,
    signature_payload text,
    signed_at timestamp without time zone
);


--
-- Name: mxik; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.mxik (
    id bigint NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by_id bigint,
    name character varying(255) NOT NULL,
    kod character varying(20) NOT NULL,
    type character varying(255) NOT NULL,
    group_name character varying(255),
    group_name_ru character varying(255),
    group_name_cyril character varying(255),
    class_name character varying(255),
    class_name_ru character varying(255),
    class_name_cyril character varying(255),
    position_name character varying(255),
    position_name_ru character varying(255),
    position_name_cyril character varying(255),
    sub_position_name character varying(255),
    sub_position_name_ru character varying(255),
    sub_position_name_cyril character varying(255),
    brand_name character varying(255),
    brand_name_ru character varying(255),
    brand_name_cyril character varying(255),
    attribute_name character varying(255),
    attribute_name_ru character varying(255),
    attribute_name_cyril character varying(255),
    barcode character varying(255),
    CONSTRAINT chk_mxik_type_no_works CHECK (((type IS NULL) OR (upper((type)::text) <> ALL (ARRAY['WORKS'::text, 'WORK'::text]))))
);


--
-- Name: mxik_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.mxik ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.mxik_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: non_moving_stock_case_lines; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.non_moving_stock_case_lines (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    case_id uuid NOT NULL,
    non_moving_stock_id uuid NOT NULL,
    material_id uuid NOT NULL,
    warehouse_id uuid NOT NULL,
    storage_location_id uuid,
    batch_id uuid,
    quantity numeric(19,4) DEFAULT 0,
    book_value numeric(19,4) DEFAULT 0,
    months_without_movement integer,
    aging_bucket_months integer,
    last_movement_at timestamp without time zone,
    replacement_material_id uuid,
    material_status character varying(50),
    available_quantity numeric(19,4) DEFAULT 0 NOT NULL,
    reserved_quantity numeric(19,4) DEFAULT 0 NOT NULL,
    blocked_quantity numeric(19,4) DEFAULT 0 NOT NULL,
    unit_cost numeric(19,4) DEFAULT 0 NOT NULL,
    currency_code character varying(3),
    valuation_date date,
    analysis_run_id uuid,
    detection_rule character varying(120),
    detection_rule_version character varying(40),
    detection_threshold integer,
    analysis_as_of_date date,
    exclusion_result character varying(80),
    active_membership boolean DEFAULT true NOT NULL,
    valuation_health character varying(20) DEFAULT 'PARTIAL'::character varying NOT NULL,
    valuation_health_message text DEFAULT 'Valuation provenance was not explicitly captured; review the persisted accounting currency before use.'::text
);


--
-- Name: non_moving_stock_cases; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.non_moving_stock_cases (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    case_no character varying(80) NOT NULL,
    detected_at timestamp without time zone,
    review_due_date date,
    total_quantity numeric(19,4) DEFAULT 0,
    total_book_value numeric(19,4) DEFAULT 0,
    responsible_user character varying(120),
    commission_protocol_no character varying(120),
    commission_id uuid,
    responsible_user_id uuid,
    responsible_username character varying(120),
    accounting_currency_code character varying(3),
    blocker_type character varying(80),
    blocker_details text,
    closed_at timestamp without time zone,
    closed_by character varying(120),
    closure_reason text,
    cancelled_at timestamp without time zone,
    cancelled_by character varying(120),
    cancellation_reason text,
    reopened_at timestamp without time zone,
    reopened_by character varying(120),
    reopen_reason text,
    valuation_health character varying(20) DEFAULT 'PARTIAL'::character varying NOT NULL,
    valuation_health_message text DEFAULT 'Valuation provenance was not explicitly captured; review the persisted accounting currency before use.'::text
);


--
-- Name: non_moving_stock_decision_lines; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.non_moving_stock_decision_lines (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    decision_id uuid NOT NULL,
    case_line_id uuid NOT NULL,
    decision_type character varying(50) NOT NULL,
    approved_quantity numeric(19,4) NOT NULL,
    target_warehouse_id uuid,
    expected_recovery_amount numeric(19,4),
    financial_amount numeric(19,4),
    currency_code character varying(3),
    execution_due_date date,
    controlled_reason text,
    comment text,
    line_order integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_nms_decision_line_quantity CHECK ((approved_quantity > (0)::numeric))
);


--
-- Name: non_moving_stock_decisions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.non_moving_stock_decisions (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    case_id uuid NOT NULL,
    decision_type character varying(50) NOT NULL,
    approved_quantity numeric(19,4) DEFAULT 0,
    target_warehouse_id uuid,
    sale_price numeric(19,4),
    write_off_reason text,
    comment text,
    next_review_date date,
    impairment_reserve_amount numeric(19,4) DEFAULT 0 NOT NULL,
    expected_recovery_amount numeric(19,4),
    expected_recovery_currency_code character varying(3),
    recovery_basis_reference character varying(160),
    recovery_allocation_basis character varying(40),
    decision_version integer DEFAULT 1 NOT NULL,
    immutable boolean DEFAULT false NOT NULL,
    submitted_at timestamp without time zone,
    submitted_by character varying(120),
    supersedes_decision_id uuid
);


--
-- Name: non_moving_stock_execution_outputs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.non_moving_stock_execution_outputs (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    execution_id uuid NOT NULL,
    decision_line_id uuid,
    case_line_id uuid,
    output_type character varying(50) NOT NULL,
    document_type character varying(80),
    document_id uuid,
    document_number character varying(120),
    output_status character varying(50) NOT NULL,
    quantity numeric(19,4),
    amount numeric(19,4),
    currency_code character varying(3),
    error_code character varying(80),
    error_message text,
    completed_at timestamp without time zone
);


--
-- Name: non_moving_stock_executions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.non_moving_stock_executions (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    case_id uuid NOT NULL,
    decision_id uuid NOT NULL,
    execution_status character varying(50) NOT NULL,
    stock_movement_id uuid,
    valuation_document_id uuid,
    integration_event_id uuid,
    idempotency_key character varying(120),
    attempt_no integer DEFAULT 1 NOT NULL,
    reconciliation_status character varying(50),
    failure_summary text,
    started_at timestamp without time zone,
    completed_at timestamp without time zone
);


--
-- Name: non_moving_stock_saved_views; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.non_moving_stock_saved_views (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    owner_username character varying(120) NOT NULL,
    workspace_mode character varying(20) NOT NULL,
    search_text character varying(240),
    case_statuses character varying(800),
    warehouse_id uuid,
    responsible_user_id uuid,
    decision_types character varying(800),
    overdue_only boolean DEFAULT false NOT NULL,
    default_view boolean DEFAULT false NOT NULL,
    detection_date_from date,
    detection_date_to date,
    material_group_id uuid,
    min_book_value numeric(19,4),
    max_book_value numeric(19,4),
    execution_statuses character varying(800),
    blocker_types character varying(800),
    integration_statuses character varying(800),
    exclusion_results character varying(800),
    active_case_only boolean DEFAULT false NOT NULL
);


--
-- Name: non_moving_stocks; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.non_moving_stocks (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    material_id uuid NOT NULL,
    warehouse_id uuid NOT NULL,
    months_without_movement integer,
    detected_at timestamp without time zone,
    planned_action character varying(80),
    action_comment text,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    last_movement_at timestamp without time zone,
    quantity numeric(19,4),
    estimated_book_value numeric(19,4),
    responsible_user character varying(120),
    review_due_at timestamp without time zone,
    closed_at timestamp without time zone,
    resolution_code character varying(120),
    resolution_note text
);


--
-- Name: notifications; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.notifications (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    recipient_username character varying(120),
    title character varying(300),
    message text,
    read_flag boolean DEFAULT false NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    title_key character varying(200),
    message_key character varying(200),
    template_args jsonb,
    rendered_language character varying(5),
    entity_type character varying(80),
    entity_id uuid
);


--
-- Name: positions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.positions (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    external_id character varying(120),
    department_id uuid,
    position_category character varying(80),
    grade character varying(40),
    headcount numeric(19,4),
    occupied_headcount numeric(19,4),
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL
);


--
-- Name: procurement_award_outcome_lines; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.procurement_award_outcome_lines (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    company_id uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    procurement_award_outcome_id uuid NOT NULL,
    procurement_procedure_id uuid NOT NULL,
    source_type character varying(40) NOT NULL,
    purchase_request_id uuid NOT NULL,
    purchase_request_item_id uuid NOT NULL,
    rfq_item_id uuid,
    supplier_quotation_item_id uuid,
    rfq_purchase_request_source_id uuid,
    purchase_order_id uuid,
    purchase_order_item_id uuid,
    contract_id uuid,
    contract_specification_id uuid,
    awarded_quantity numeric(19,4) DEFAULT 0 NOT NULL,
    unit_price numeric(19,4) DEFAULT 0,
    awarded_amount numeric(19,4) DEFAULT 0 NOT NULL,
    currency_code character varying(3) DEFAULT 'UZS'::character varying NOT NULL,
    idempotency_key character varying(220) NOT NULL,
    CONSTRAINT ck_procurement_award_outcome_line_amounts CHECK (((awarded_quantity >= (0)::numeric) AND (awarded_amount >= (0)::numeric)))
);


--
-- Name: procurement_award_outcomes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.procurement_award_outcomes (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    company_id uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    procurement_procedure_id uuid NOT NULL,
    source_type character varying(40) NOT NULL,
    source_id uuid,
    awarded_supplier_id uuid,
    purchase_order_id uuid,
    contract_id uuid,
    award_amount numeric(19,4),
    currency_code character varying(3),
    award_reference character varying(160),
    idempotency_key character varying(180) NOT NULL,
    finalized_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: procurement_campaigns; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.procurement_campaigns (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    campaign_number character varying(120),
    procurement_plan_id uuid,
    category_strategy_id uuid,
    material_group_id uuid,
    rfq_id uuid,
    purchase_request_id uuid,
    campaign_type character varying(60) DEFAULT 'ANNUAL_TENDER'::character varying NOT NULL,
    procedure_type character varying(60) DEFAULT 'REQUEST_FOR_QUOTATION'::character varying NOT NULL,
    target_supplier_count integer DEFAULT 3 NOT NULL,
    planned_spend_amount numeric(19,4) DEFAULT 0 NOT NULL,
    approved_spend_amount numeric(19,4) DEFAULT 0 NOT NULL,
    savings_target_amount numeric(19,4) DEFAULT 0 NOT NULL,
    currency_code character varying(3) DEFAULT 'UZS'::character varying NOT NULL,
    planned_start_date date,
    planned_end_date date,
    launched_at timestamp without time zone,
    completed_at timestamp without time zone,
    method_decision_id uuid,
    procurement_procedure_id uuid,
    price_discovery_rfq_id uuid,
    price_basis_calculation_id uuid
);


--
-- Name: procurement_category_strategies; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.procurement_category_strategies (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    material_group_id uuid NOT NULL,
    strategy_year integer NOT NULL,
    owner_employee_id uuid,
    budget_item_id uuid,
    strategy_type character varying(60) DEFAULT 'SUPPLY_SECURITY'::character varying NOT NULL,
    sourcing_approach character varying(60) DEFAULT 'COMPETITIVE_TENDER'::character varying NOT NULL,
    supplier_market text,
    risk_level character varying(40) DEFAULT 'MEDIUM'::character varying NOT NULL,
    target_savings_percent numeric(9,4) DEFAULT 0 NOT NULL,
    planned_spend_amount numeric(19,4) DEFAULT 0 NOT NULL,
    approved_spend_limit numeric(19,4) DEFAULT 0 NOT NULL,
    currency_code character varying(3) DEFAULT 'UZS'::character varying NOT NULL,
    valid_from date,
    valid_to date,
    approved_at timestamp without time zone,
    activated_at timestamp without time zone,
    closed_at timestamp without time zone
);


--
-- Name: procurement_closeout_audit_events; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.procurement_closeout_audit_events (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    closeout_id uuid NOT NULL,
    action_code character varying(80) NOT NULL,
    actor character varying(120),
    event_at timestamp without time zone DEFAULT now() NOT NULL,
    idempotency_key character varying(220) NOT NULL,
    snapshot_json text NOT NULL
);


--
-- Name: procurement_closeout_blockers; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.procurement_closeout_blockers (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid NOT NULL,
    closeout_id uuid,
    readiness_dimension character varying(80) NOT NULL,
    blocker_code character varying(120) NOT NULL,
    severity character varying(30) DEFAULT 'BLOCKER'::character varying NOT NULL,
    entity_type character varying(120),
    entity_id uuid,
    entity_line_id uuid,
    quantity numeric(19,4),
    amount numeric(19,4),
    currency character varying(3),
    message text NOT NULL,
    recommended_action text,
    resolved boolean DEFAULT false NOT NULL,
    resolved_at timestamp without time zone,
    snapshot_timestamp timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_procurement_closeout_blockers_nonnegative CHECK ((((quantity IS NULL) OR (quantity >= (0)::numeric)) AND ((amount IS NULL) OR (amount >= (0)::numeric))))
);


--
-- Name: procurement_closeout_readiness_snapshots; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.procurement_closeout_readiness_snapshots (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid NOT NULL,
    closeout_id uuid NOT NULL,
    readiness_status character varying(40) NOT NULL,
    snapshot_json text NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: procurement_closeout_reopen_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.procurement_closeout_reopen_history (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid NOT NULL,
    closeout_id uuid NOT NULL,
    requested_dimensions text NOT NULL,
    requested_by character varying(120) NOT NULL,
    reason text NOT NULL,
    approval_instance_id uuid,
    approved_by character varying(120),
    applied_by character varying(120),
    previous_closeout_state character varying(40) NOT NULL,
    resulting_state character varying(40),
    idempotency_key character varying(180) NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    approved_at timestamp without time zone,
    applied_at timestamp without time zone,
    version bigint DEFAULT 0 NOT NULL
);


--
-- Name: procurement_closeouts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.procurement_closeouts (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    procurement_procedure_id uuid NOT NULL,
    purchase_order_id uuid,
    purchase_request_id uuid,
    closeout_status character varying(40) DEFAULT 'DRAFT'::character varying NOT NULL,
    closeout_type character varying(40) DEFAULT 'NORMAL'::character varying NOT NULL,
    financial_close_policy character varying(60) DEFAULT 'PAYMENT_REQUIRED'::character varying NOT NULL,
    readiness_status character varying(40) DEFAULT 'BLOCKED'::character varying NOT NULL,
    readiness_checked_at timestamp without time zone,
    readiness_checked_by character varying(120),
    blocking_reason_count integer DEFAULT 0 NOT NULL,
    submitted_at timestamp without time zone,
    submitted_by character varying(120),
    approval_instance_id uuid,
    approved_at timestamp without time zone,
    approved_by character varying(120),
    closed_at timestamp without time zone,
    closed_by character varying(120),
    closeout_note text,
    close_reason text,
    early_close_reason text,
    cancellation_reason text,
    reopen_reason text,
    reopened_at timestamp without time zone,
    reopened_by character varying(120),
    liability_disposition character varying(60) DEFAULT 'NONE'::character varying NOT NULL,
    expected_liability_amount numeric(19,4) DEFAULT 0 NOT NULL,
    unresolved_liability_amount numeric(19,4) DEFAULT 0 NOT NULL,
    readiness_snapshot text,
    CONSTRAINT ck_procurement_closeouts_blocking_count CHECK ((blocking_reason_count >= 0)),
    CONSTRAINT ck_procurement_closeouts_closed_timestamp CHECK ((((closeout_status)::text <> 'CLOSED'::text) OR (closed_at IS NOT NULL))),
    CONSTRAINT ck_procurement_closeouts_early_reason CHECK ((((closeout_type)::text <> 'EARLY_CLOSE'::text) OR (NULLIF(TRIM(BOTH FROM early_close_reason), ''::text) IS NOT NULL))),
    CONSTRAINT ck_procurement_closeouts_financial_policy CHECK (((financial_close_policy)::text = ANY ((ARRAY['PAYMENT_REQUIRED'::character varying, 'INVOICE_ISSUED_AND_TRANSFERRED_TO_AP'::character varying, 'LIABILITY_RECORDED'::character varying, 'NO_INVOICE_EXPECTED'::character varying])::text[]))),
    CONSTRAINT ck_procurement_closeouts_liability_disposition CHECK (((liability_disposition)::text = ANY ((ARRAY['NONE'::character varying, 'FULLY_PAID'::character varying, 'TRANSFERRED_TO_ACCOUNTS_PAYABLE'::character varying, 'DISPUTED'::character varying, 'WRITTEN_OFF'::character varying, 'LEGALLY_RESERVED'::character varying])::text[]))),
    CONSTRAINT ck_procurement_closeouts_nonnegative_liability CHECK (((expected_liability_amount >= (0)::numeric) AND (unresolved_liability_amount >= (0)::numeric))),
    CONSTRAINT ck_procurement_closeouts_readiness CHECK (((readiness_status)::text = ANY ((ARRAY['COMPLETE'::character varying, 'READY'::character varying, 'BLOCKED'::character varying, 'WARNING'::character varying, 'NOT_APPLICABLE'::character varying])::text[]))),
    CONSTRAINT ck_procurement_closeouts_reopen_reason CHECK ((((closeout_status)::text <> ALL ((ARRAY['REOPEN_REQUESTED'::character varying, 'REOPEN_APPROVAL_PENDING'::character varying, 'REOPENED'::character varying])::text[])) OR (NULLIF(TRIM(BOTH FROM reopen_reason), ''::text) IS NOT NULL))),
    CONSTRAINT ck_procurement_closeouts_status CHECK (((closeout_status)::text = ANY ((ARRAY['DRAFT'::character varying, 'BLOCKED'::character varying, 'READY'::character varying, 'APPROVAL_PENDING'::character varying, 'APPROVED'::character varying, 'RETURNED'::character varying, 'REJECTED'::character varying, 'CLOSED'::character varying, 'REOPEN_REQUESTED'::character varying, 'REOPEN_APPROVAL_PENDING'::character varying, 'REOPENED'::character varying, 'CANCELLED'::character varying])::text[]))),
    CONSTRAINT ck_procurement_closeouts_type CHECK (((closeout_type)::text = ANY ((ARRAY['NORMAL'::character varying, 'EARLY_CLOSE'::character varying, 'CANCELLED_PROCUREMENT'::character varying, 'FAILED_PROCUREMENT'::character varying, 'NO_AWARD'::character varying, 'PARTIAL_COMPLETION'::character varying, 'ADMINISTRATIVE'::character varying])::text[])))
);


--
-- Name: procurement_direct_awards; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.procurement_direct_awards (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    procurement_procedure_id uuid NOT NULL,
    method_decision_id uuid NOT NULL,
    purchase_request_id uuid,
    supplier_id uuid NOT NULL,
    award_amount numeric(19,4) NOT NULL,
    currency_code character varying(3) NOT NULL,
    justification text NOT NULL,
    award_basis text,
    approved_at timestamp without time zone,
    approved_by character varying(120),
    purchase_order_id uuid,
    award_basis_type character varying(60) NOT NULL,
    method character varying(60) NOT NULL,
    evidence_reference character varying(500),
    market_price_amount numeric(19,4),
    market_price_evidence_reference character varying(500),
    negotiation_summary text,
    conflict_of_interest_declaration text,
    conflict_of_interest_confirmed boolean DEFAULT false NOT NULL,
    approved_supplier_id uuid,
    approved_award_amount numeric(19,4),
    approved_method character varying(60),
    CONSTRAINT chk_procurement_direct_award_amount CHECK ((award_amount > (0)::numeric)),
    CONSTRAINT chk_procurement_direct_award_basis_type CHECK (((award_basis_type)::text = ANY ((ARRAY['MARKET_RESEARCH'::character varying, 'EXCLUSIVITY'::character varying, 'URGENCY'::character varying, 'APPROVED_EXCEPTION'::character varying])::text[]))),
    CONSTRAINT chk_procurement_direct_award_market_price CHECK (((market_price_amount IS NULL) OR (market_price_amount > (0)::numeric))),
    CONSTRAINT chk_procurement_direct_award_method CHECK (((method)::text = ANY ((ARRAY['DIRECT_PURCHASE'::character varying, 'SINGLE_SOURCE'::character varying])::text[]))),
    CONSTRAINT chk_procurement_direct_award_status CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'APPROVAL_PENDING'::character varying, 'RETURNED'::character varying, 'REJECTED'::character varying, 'APPROVED'::character varying, 'CANCELLED'::character varying])::text[])))
);


--
-- Name: procurement_framework_ledger; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.procurement_framework_ledger (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    framework_id uuid NOT NULL,
    transaction_type character varying(30) NOT NULL,
    call_off_reference character varying(160),
    purchase_order_id uuid,
    source_purchase_request_id uuid,
    source_procedure_id uuid,
    amount numeric(19,4) NOT NULL,
    currency_code character varying(3) NOT NULL,
    idempotency_key character varying(180) NOT NULL,
    reversal_reference uuid,
    occurred_at timestamp without time zone NOT NULL,
    CONSTRAINT chk_proc_framework_ledger_amount CHECK ((amount > (0)::numeric)),
    CONSTRAINT chk_proc_framework_ledger_currency CHECK (((currency_code)::text ~ '^[A-Z]{3}$'::text)),
    CONSTRAINT chk_proc_framework_ledger_type CHECK (((transaction_type)::text = ANY ((ARRAY['COMMIT'::character varying, 'COMMIT_REVERSAL'::character varying, 'CONSUME'::character varying, 'CONSUME_REVERSAL'::character varying])::text[])))
);


--
-- Name: procurement_frameworks; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.procurement_frameworks (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    procedure_id uuid,
    method_decision_id uuid,
    purchase_request_id uuid,
    contract_id uuid,
    award_reference character varying(160),
    supplier_id uuid NOT NULL,
    supplier_ids text,
    supplier_model character varying(30) DEFAULT 'SINGLE_SUPPLIER'::character varying NOT NULL,
    allocation_basis character varying(40),
    valid_from date NOT NULL,
    valid_to date,
    currency_code character varying(3) NOT NULL,
    ceiling_amount numeric(19,4) NOT NULL,
    committed_amount numeric(19,4) DEFAULT 0 NOT NULL,
    consumed_amount numeric(19,4) DEFAULT 0 NOT NULL,
    call_off_policy text,
    approval_reference character varying(160),
    approved_at timestamp without time zone,
    approved_by character varying(120),
    CONSTRAINT chk_proc_framework_amounts CHECK (((ceiling_amount > (0)::numeric) AND (committed_amount >= (0)::numeric) AND (consumed_amount >= (0)::numeric))),
    CONSTRAINT chk_proc_framework_currency CHECK (((currency_code)::text ~ '^[A-Z]{3}$'::text)),
    CONSTRAINT chk_proc_framework_dates CHECK (((valid_to IS NULL) OR (valid_from <= valid_to))),
    CONSTRAINT chk_proc_framework_ranked_multi_supplier CHECK ((((supplier_model)::text = 'SINGLE_SUPPLIER'::text) OR ((allocation_basis)::text = 'RANKED_SUPPLIERS'::text))),
    CONSTRAINT chk_proc_framework_status CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'APPROVAL_PENDING'::character varying, 'APPROVED'::character varying, 'ACTIVE'::character varying, 'SUSPENDED'::character varying, 'EXPIRED'::character varying, 'CLOSED'::character varying, 'CANCELLED'::character varying])::text[]))),
    CONSTRAINT chk_proc_framework_supplier_model CHECK (((supplier_model)::text = ANY ((ARRAY['SINGLE_SUPPLIER'::character varying, 'MULTI_SUPPLIER'::character varying])::text[])))
);


--
-- Name: procurement_line_allocations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.procurement_line_allocations (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    company_id uuid NOT NULL,
    code character varying(80),
    name character varying(240) NOT NULL,
    status character varying(50) DEFAULT 'ACTIVE'::character varying NOT NULL,
    description text,
    source_document_type character varying(40) NOT NULL,
    source_document_id uuid NOT NULL,
    source_line_id uuid NOT NULL,
    target_document_type character varying(40) NOT NULL,
    target_document_id uuid NOT NULL,
    target_line_id uuid NOT NULL,
    allocated_quantity numeric(19,4) NOT NULL,
    allocated_amount numeric(19,4) DEFAULT 0 NOT NULL,
    currency_code character varying(3) NOT NULL,
    supplier_id uuid,
    cancelled_quantity numeric(19,4) DEFAULT 0 NOT NULL,
    cancelled_amount numeric(19,4) DEFAULT 0 NOT NULL,
    reversed_quantity numeric(19,4) DEFAULT 0 NOT NULL,
    reversed_amount numeric(19,4) DEFAULT 0 NOT NULL,
    idempotency_key character varying(220) NOT NULL,
    CONSTRAINT ck_procurement_line_allocations_non_negative CHECK (((allocated_quantity > (0)::numeric) AND (allocated_amount >= (0)::numeric) AND (cancelled_quantity >= (0)::numeric) AND (cancelled_amount >= (0)::numeric) AND (reversed_quantity >= (0)::numeric) AND (reversed_amount >= (0)::numeric) AND ((cancelled_quantity + reversed_quantity) <= allocated_quantity) AND ((cancelled_amount + reversed_amount) <= allocated_amount)))
);


--
-- Name: procurement_method_capabilities; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.procurement_method_capabilities (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name character varying(255) NOT NULL,
    status character varying(50),
    description text,
    method_code character varying(60) NOT NULL,
    capability_state character varying(20) DEFAULT 'DISABLED'::character varying NOT NULL,
    effective_from date,
    effective_to date,
    min_amount numeric(19,4),
    max_amount numeric(19,4),
    currency_code character varying(3),
    allowed_request_types jsonb DEFAULT '[]'::jsonb NOT NULL,
    allowed_category_ids jsonb DEFAULT '[]'::jsonb NOT NULL,
    justification_required boolean DEFAULT false NOT NULL,
    supplier_required boolean DEFAULT false NOT NULL,
    approval_required boolean DEFAULT true NOT NULL,
    configuration_version integer DEFAULT 1 NOT NULL,
    change_reason text,
    configured_at timestamp without time zone,
    configured_by character varying(120),
    CONSTRAINT ck_proc_method_capability_amount_range CHECK (((min_amount IS NULL) OR (max_amount IS NULL) OR (min_amount <= max_amount))),
    CONSTRAINT ck_proc_method_capability_currency CHECK (((currency_code IS NULL) OR ((currency_code)::text ~ '^[A-Za-z]{3}$'::text))),
    CONSTRAINT ck_proc_method_capability_dates CHECK (((effective_from IS NULL) OR (effective_to IS NULL) OR (effective_from <= effective_to))),
    CONSTRAINT ck_proc_method_capability_max_amount CHECK (((max_amount IS NULL) OR (max_amount >= (0)::numeric))),
    CONSTRAINT ck_proc_method_capability_min_amount CHECK (((min_amount IS NULL) OR (min_amount >= (0)::numeric))),
    CONSTRAINT ck_proc_method_capability_state CHECK (((capability_state)::text = ANY ((ARRAY['ENABLED'::character varying, 'PILOT'::character varying, 'DISABLED'::character varying, 'LEGACY'::character varying, 'DEPRECATED'::character varying])::text[]))),
    CONSTRAINT ck_proc_method_capability_version CHECK ((configuration_version > 0))
);


--
-- Name: procurement_method_decisions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.procurement_method_decisions (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50) DEFAULT 'DRAFT'::character varying NOT NULL,
    description text,
    decision_number character varying(120) NOT NULL,
    purchase_request_id uuid,
    selected_method character varying(60) NOT NULL,
    justification text,
    estimated_amount numeric(19,4) DEFAULT 0 NOT NULL,
    currency_code character varying(3) DEFAULT 'UZS'::character varying NOT NULL,
    potential_supplier_count integer DEFAULT 0 NOT NULL,
    market_research_attachment_id uuid,
    technical_specification_attachment_id uuid,
    exception_reason text,
    submitted_at timestamp without time zone,
    approved_at timestamp without time zone,
    approved_by character varying(120),
    rejected_at timestamp without time zone,
    rejected_by character varying(120),
    rejection_reason text,
    commission_id uuid,
    capability_state_snapshot character varying(20),
    capability_version_snapshot integer,
    CONSTRAINT chk_procurement_method_decision_amount CHECK ((estimated_amount >= (0)::numeric)),
    CONSTRAINT chk_procurement_method_decision_status CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'UNDER_REVIEW'::character varying, 'APPROVED'::character varying, 'REJECTED'::character varying])::text[]))),
    CONSTRAINT chk_procurement_method_supplier_count CHECK ((potential_supplier_count >= 0)),
    CONSTRAINT ck_pmd_capability_snapshot_state CHECK (((capability_state_snapshot IS NULL) OR ((capability_state_snapshot)::text = ANY ((ARRAY['ENABLED'::character varying, 'PILOT'::character varying, 'DISABLED'::character varying, 'LEGACY'::character varying, 'DEPRECATED'::character varying])::text[])))),
    CONSTRAINT ck_pmd_capability_snapshot_version CHECK (((capability_version_snapshot IS NULL) OR (capability_version_snapshot > 0)))
);


--
-- Name: procurement_procedures; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.procurement_procedures (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50) DEFAULT 'DRAFT'::character varying NOT NULL,
    description text,
    procedure_number character varying(120) NOT NULL,
    method_decision_id uuid NOT NULL,
    procurement_plan_id uuid,
    purchase_request_id uuid,
    method character varying(60) NOT NULL,
    campaign_id uuid,
    rfq_id uuid,
    launched_at timestamp without time zone,
    completed_at timestamp without time zone,
    cancelled_at timestamp without time zone,
    cancellation_reason text,
    price_discovery_rfq_id uuid,
    requires_price_discovery boolean DEFAULT false NOT NULL,
    finalized_amount numeric(19,4),
    finalized_currency_code character varying(3),
    price_basis_calculation_id uuid,
    execution_path character varying(60) NOT NULL,
    direct_award_id uuid,
    awarded_supplier_id uuid,
    purchase_order_id uuid,
    contract_id uuid,
    award_reference character varying(160),
    capability_state_snapshot character varying(20),
    capability_version_snapshot integer,
    CONSTRAINT chk_procurement_procedure_execution_path CHECK (((execution_path)::text = ANY ((ARRAY['RFQ'::character varying, 'TENDER_WITH_PRICE_DISCOVERY'::character varying, 'DIRECT_AWARD'::character varying, 'AUCTION'::character varying, 'FRAMEWORK_AGREEMENT'::character varying])::text[]))),
    CONSTRAINT chk_procurement_procedure_status CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'READY'::character varying, 'PRICE_DISCOVERY'::character varying, 'TENDER_READY'::character varying, 'TENDER_DRAFT'::character varying, 'DIRECT_AWARD_DRAFT'::character varying, 'DIRECT_AWARD_APPROVED'::character varying, 'AUCTION_DRAFT'::character varying, 'AUCTION_PUBLISHED'::character varying, 'AUCTION_RUNNING'::character varying, 'AUCTION_CLOSED'::character varying, 'FRAMEWORK_DRAFT'::character varying, 'FRAMEWORK_AWARDED'::character varying, 'FRAMEWORK_ACTIVE'::character varying, 'LAUNCHED'::character varying, 'COMPLETED'::character varying, 'CANCELLED'::character varying])::text[]))),
    CONSTRAINT ck_proc_capability_snapshot_state CHECK (((capability_state_snapshot IS NULL) OR ((capability_state_snapshot)::text = ANY ((ARRAY['ENABLED'::character varying, 'PILOT'::character varying, 'DISABLED'::character varying, 'LEGACY'::character varying, 'DEPRECATED'::character varying])::text[])))),
    CONSTRAINT ck_proc_capability_snapshot_version CHECK (((capability_version_snapshot IS NULL) OR (capability_version_snapshot > 0)))
);


--
-- Name: procurement_method_stabilization_report; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.procurement_method_stabilization_report AS
 SELECT procedure.tenant_id,
    procedure.company_id,
    'PROCUREMENT_PROCEDURE'::character varying(80) AS record_type,
    procedure.id AS record_id,
    'AUCTION_NON_COMPLETED'::character varying(80) AS issue_code,
    'REQUIRES_MANUAL_REVIEW'::character varying(40) AS classification,
    procedure.method AS method_code,
    procedure.status AS current_status
   FROM public.procurement_procedures procedure
  WHERE ((procedure.is_delete = false) AND ((procedure.method)::text = 'E_AUCTION'::text) AND ((procedure.status)::text <> 'COMPLETED'::text))
UNION ALL
 SELECT procedure.tenant_id,
    procedure.company_id,
    'PROCUREMENT_PROCEDURE'::character varying AS record_type,
    procedure.id AS record_id,
    'AUCTION_COMPLETED'::character varying AS issue_code,
    'GRANDFATHERED'::character varying AS classification,
    procedure.method AS method_code,
    procedure.status AS current_status
   FROM public.procurement_procedures procedure
  WHERE ((procedure.is_delete = false) AND ((procedure.method)::text = 'E_AUCTION'::text) AND ((procedure.status)::text = 'COMPLETED'::text))
UNION ALL
 SELECT decision.tenant_id,
    decision.company_id,
    'PROCUREMENT_METHOD_DECISION'::character varying AS record_type,
    decision.id AS record_id,
    'HISTORICAL_METHOD_ALIAS'::character varying AS issue_code,
    'AUTO_MIGRATABLE'::character varying AS classification,
    decision.selected_method AS method_code,
    decision.status AS current_status
   FROM public.procurement_method_decisions decision
  WHERE ((decision.is_delete = false) AND ((decision.selected_method)::text = ANY ((ARRAY['SINGLE_SUPPLIER'::character varying, 'COMPETITIVE_TENDER'::character varying, 'SPOT_BUY'::character varying])::text[])))
UNION ALL
 SELECT procedure.tenant_id,
    procedure.company_id,
    'PROCUREMENT_PROCEDURE'::character varying AS record_type,
    procedure.id AS record_id,
    'HISTORICAL_METHOD_ALIAS'::character varying AS issue_code,
    'AUTO_MIGRATABLE'::character varying AS classification,
    procedure.method AS method_code,
    procedure.status AS current_status
   FROM public.procurement_procedures procedure
  WHERE ((procedure.is_delete = false) AND ((procedure.method)::text = ANY ((ARRAY['SINGLE_SUPPLIER'::character varying, 'COMPETITIVE_TENDER'::character varying, 'SPOT_BUY'::character varying])::text[])))
UNION ALL
 SELECT procedure.tenant_id,
    procedure.company_id,
    'PROCUREMENT_PROCEDURE'::character varying AS record_type,
    procedure.id AS record_id,
    'MISSING_CAPABILITY_SNAPSHOT'::character varying AS issue_code,
    'REQUIRES_CONFIGURATION'::character varying AS classification,
    procedure.method AS method_code,
    procedure.status AS current_status
   FROM public.procurement_procedures procedure
  WHERE ((procedure.is_delete = false) AND ((procedure.status)::text <> 'COMPLETED'::text) AND ((procedure.capability_state_snapshot IS NULL) OR (procedure.capability_version_snapshot IS NULL)))
UNION ALL
 SELECT outcome.tenant_id,
    outcome.company_id,
    'PROCUREMENT_AWARD_OUTCOME'::character varying AS record_type,
    outcome.id AS record_id,
    'FRAMEWORK_CALLOFF_WITHOUT_LEDGER'::character varying AS issue_code,
    'INVALID'::character varying AS classification,
    'FRAME_AGREEMENT'::character varying AS method_code,
    outcome.source_type AS current_status
   FROM public.procurement_award_outcomes outcome
  WHERE ((outcome.is_delete = false) AND ((outcome.source_type)::text = 'FRAMEWORK_CALLOFF'::text) AND (outcome.purchase_order_id IS NOT NULL) AND (NOT (EXISTS ( SELECT 1
           FROM public.procurement_framework_ledger ledger
          WHERE (((ledger.tenant_id)::text = (outcome.tenant_id)::text) AND (ledger.company_id = outcome.company_id) AND (ledger.purchase_order_id = outcome.purchase_order_id) AND ((ledger.transaction_type)::text = ANY ((ARRAY['COMMIT'::character varying, 'CONSUME'::character varying])::text[])) AND (ledger.is_delete = false))))))
UNION ALL
 SELECT outcome.tenant_id,
    outcome.company_id,
    'PROCUREMENT_AWARD_OUTCOME'::character varying AS record_type,
    outcome.id AS record_id,
    'DEPRECATED_PATH_AWARD'::character varying AS issue_code,
    'REQUIRES_MANUAL_REVIEW'::character varying AS classification,
    'E_AUCTION'::character varying AS method_code,
    outcome.source_type AS current_status
   FROM public.procurement_award_outcomes outcome
  WHERE ((outcome.is_delete = false) AND ((outcome.source_type)::text = 'MANAGED_AUCTION'::text));


--
-- Name: procurement_plans; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.procurement_plans (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    plan_number character varying(120),
    plan_year integer NOT NULL,
    period_type character varying(20) DEFAULT 'YEAR'::character varying NOT NULL,
    period_quarter integer,
    demand_planning_cycle_id uuid,
    category_strategy_id uuid,
    material_group_id uuid,
    budget_item_id uuid,
    owner_employee_id uuid,
    planned_spend_amount numeric(19,4) DEFAULT 0 NOT NULL,
    approved_budget_amount numeric(19,4) DEFAULT 0 NOT NULL,
    reserved_budget_amount numeric(19,4) DEFAULT 0 NOT NULL,
    expected_savings_amount numeric(19,4) DEFAULT 0 NOT NULL,
    currency_code character varying(3) DEFAULT 'UZS'::character varying NOT NULL,
    plan_start_date date,
    plan_end_date date,
    demand_count integer DEFAULT 0 NOT NULL,
    campaign_count integer DEFAULT 0 NOT NULL,
    submitted_at timestamp without time zone,
    approved_at timestamp without time zone,
    activated_at timestamp without time zone,
    closed_at timestamp without time zone
);


--
-- Name: procurement_work_assignments; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.procurement_work_assignments (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    company_id uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    purchase_request_id uuid NOT NULL,
    owner_user_id uuid,
    owner_username character varying(120),
    owner_role character varying(80),
    due_date date,
    severity character varying(30),
    escalated boolean DEFAULT false NOT NULL,
    escalation_level integer DEFAULT 0 NOT NULL,
    reason text
);


--
-- Name: production_evidence; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.production_evidence (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    evidence_type character varying(80) NOT NULL,
    run_id character varying(160),
    environment_name character varying(120),
    base_url character varying(500),
    evidence_source character varying(120),
    overall_status character varying(40),
    production_ready boolean DEFAULT false NOT NULL,
    blocks_production_count integer DEFAULT 0 NOT NULL,
    warnings_count integer DEFAULT 0 NOT NULL,
    evidence_json text,
    preflight_json text,
    checks_json text,
    security_checklist_json text,
    grafana_dashboard_uid character varying(160),
    grafana_dashboard_url character varying(700),
    grafana_dashboard_snapshot_url character varying(700),
    evidence_attachment_id uuid,
    dashboard_screenshot_attachment_id uuid,
    load_report_attachment_id uuid,
    dr_report_attachment_id uuid,
    backup_report_attachment_id uuid,
    security_report_attachment_id uuid,
    incident_drill_at timestamp without time zone,
    incident_drill_summary text,
    backup_retention_days integer,
    backup_retention_verified boolean DEFAULT false NOT NULL,
    latest_backup_checksum character varying(160),
    restore_checksum character varying(160),
    restore_started_at timestamp without time zone,
    restore_finished_at timestamp without time zone,
    actual_rpo_minutes integer,
    actual_rto_minutes integer,
    pentest_status character varying(40),
    dependency_audit_status character varying(40),
    tls_cipher_scan_status character varying(40),
    backup_access_review_status character varying(40),
    security_notes text,
    monitoring_accepted_by character varying(160),
    monitoring_accepted_at timestamp without time zone,
    accepted_by character varying(160),
    accepted_at timestamp without time zone,
    acceptance_notes text,
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120)
);


--
-- Name: purchase_order_items; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.purchase_order_items (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    purchase_order_id uuid NOT NULL,
    material_id uuid,
    quantity numeric(19,4),
    unit_price numeric(19,4),
    received_quantity numeric(19,4),
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    rejected_quantity numeric(19,4),
    damaged_quantity numeric(19,4),
    shortage_reason text,
    receipt_batch_number character varying(120),
    receipt_warehouse_id uuid,
    receipt_storage_location_id uuid,
    quality_status character varying(50),
    accreditation_warning text,
    line_type character varying(20) DEFAULT 'MATERIAL'::character varying NOT NULL,
    service_request_id uuid,
    service_order_id uuid,
    service_act_id uuid,
    service_description text,
    service_category character varying(120),
    unit character varying(80),
    period character varying(120),
    stage character varying(120),
    budget_item_id uuid,
    accepted_quantity numeric(19,4) DEFAULT 0 NOT NULL,
    material_type_snapshot character varying(30),
    tracking_mode_snapshot character varying(20),
    batch_tracking_snapshot character varying(20),
    serial_tracking_snapshot character varying(20),
    CONSTRAINT chk_purchase_order_items_batch_tracking_snapshot CHECK (((batch_tracking_snapshot)::text = ANY ((ARRAY['NONE'::character varying, 'OPTIONAL'::character varying, 'REQUIRED'::character varying])::text[]))),
    CONSTRAINT chk_purchase_order_items_serial_tracking_snapshot CHECK (((serial_tracking_snapshot)::text = ANY ((ARRAY['NONE'::character varying, 'REQUIRED'::character varying])::text[]))),
    CONSTRAINT chk_purchase_order_items_tracking_mode_snapshot CHECK (((tracking_mode_snapshot)::text = ANY ((ARRAY['QUANTITY'::character varying, 'LOT'::character varying, 'SERIAL'::character varying])::text[]))),
    CONSTRAINT ck_purchase_order_items_material_snapshot_present CHECK (((material_id IS NULL) OR (material_type_snapshot IS NOT NULL))),
    CONSTRAINT ck_purchase_order_items_material_type_snapshot CHECK (((material_type_snapshot IS NULL) OR ((material_type_snapshot)::text = ANY ((ARRAY['SERVICES'::character varying, 'FIXED_ASSET'::character varying, 'TMB'::character varying])::text[]))))
);


--
-- Name: purchase_order_revisions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.purchase_order_revisions (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    purchase_order_id uuid NOT NULL,
    revision_number integer,
    reason character varying(500),
    diff_json text,
    snapshot_json text,
    revised_at timestamp without time zone
);


--
-- Name: purchase_order_source_allocations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.purchase_order_source_allocations (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    company_id uuid NOT NULL,
    code character varying(80),
    name character varying(240) NOT NULL,
    status character varying(50),
    description text,
    purchase_order_item_id uuid NOT NULL,
    purchase_request_item_id uuid NOT NULL,
    rfq_purchase_request_source_id uuid,
    ordered_quantity numeric(19,4) NOT NULL,
    CONSTRAINT ck_po_source_allocations_quantity CHECK ((ordered_quantity > (0)::numeric))
);


--
-- Name: purchase_orders; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.purchase_orders (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    supplier_id uuid NOT NULL,
    purchase_request_id uuid,
    contract_id uuid,
    order_date date,
    expected_delivery_date date,
    total_amount numeric(19,4),
    currency_code character varying(3),
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    request_type character varying(20) DEFAULT 'GOODS'::character varying NOT NULL,
    exchange_rate numeric(19,6) DEFAULT 1 NOT NULL,
    CONSTRAINT chk_purchase_orders_request_type CHECK (((request_type IS NULL) OR (upper((request_type)::text) = ANY (ARRAY['GOODS'::text, 'SERVICE'::text]))))
);


--
-- Name: purchase_request_items; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.purchase_request_items (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    purchase_request_id uuid NOT NULL,
    material_id uuid,
    quantity numeric(19,4),
    estimated_price numeric(19,4),
    budget_item_id uuid,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    line_type character varying(20) DEFAULT 'MATERIAL'::character varying NOT NULL,
    service_request_id uuid,
    service_description text,
    service_category character varying(120),
    unit character varying(80),
    period character varying(120),
    stage character varying(120),
    required_date date,
    material_type_snapshot character varying(30),
    CONSTRAINT ck_purchase_request_items_material_snapshot_present CHECK (((material_id IS NULL) OR (material_type_snapshot IS NOT NULL))),
    CONSTRAINT ck_purchase_request_items_material_type_snapshot CHECK (((material_type_snapshot IS NULL) OR ((material_type_snapshot)::text = ANY ((ARRAY['SERVICES'::character varying, 'FIXED_ASSET'::character varying, 'TMB'::character varying])::text[]))))
);


--
-- Name: purchase_requests; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.purchase_requests (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    department_id uuid,
    requested_by character varying(120),
    required_date date,
    total_amount numeric(19,4),
    currency_code character varying(3),
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    demand_plan_id uuid,
    request_type character varying(20) DEFAULT 'GOODS'::character varying NOT NULL,
    warehouse_id uuid,
    CONSTRAINT chk_purchase_requests_request_type CHECK (((request_type IS NULL) OR (upper((request_type)::text) = ANY (ARRAY['GOODS'::text, 'SERVICE'::text]))))
);


--
-- Name: putaway_rules; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.putaway_rules (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    priority integer DEFAULT 100 NOT NULL,
    warehouse_id uuid,
    material_id uuid,
    material_group_id uuid,
    hazard_class character varying(40),
    temperature_zone character varying(80),
    abc_class character varying(10),
    storage_zone_code character varying(80),
    target_storage_location_id uuid,
    max_quantity numeric(19,4),
    min_shelf_life_days integer,
    allow_mixed_materials boolean DEFAULT true NOT NULL,
    allow_cross_dock boolean DEFAULT false NOT NULL
);


--
-- Name: qms_assessment_details; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.qms_assessment_details (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    assessment_id uuid NOT NULL,
    criteria_id uuid NOT NULL,
    given_score numeric(7,2) DEFAULT 0 NOT NULL,
    comment_text text,
    CONSTRAINT chk_qms_assessment_details_given_score_non_negative CHECK ((given_score >= (0)::numeric))
);


--
-- Name: qms_assessments; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.qms_assessments (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50) DEFAULT 'DRAFT'::character varying NOT NULL,
    description text,
    supplier_id uuid NOT NULL,
    assessment_date date NOT NULL,
    total_weighted_score numeric(7,2) DEFAULT 0 NOT NULL,
    assessed_by uuid,
    CONSTRAINT chk_qms_assessments_status CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'APPROVED'::character varying])::text[]))),
    CONSTRAINT chk_qms_assessments_total_score_non_negative CHECK ((total_weighted_score >= (0)::numeric))
);


--
-- Name: qms_criteria; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.qms_criteria (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80) NOT NULL,
    name text NOT NULL,
    status character varying(50),
    description text,
    max_score numeric(7,2) DEFAULT 20 NOT NULL,
    weight numeric(7,3) DEFAULT 1 NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    CONSTRAINT chk_qms_criteria_max_score_min CHECK ((max_score >= (1)::numeric)),
    CONSTRAINT chk_qms_criteria_weight_range CHECK (((weight >= (1)::numeric) AND (weight <= (10)::numeric)))
);


--
-- Name: quality_claims; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.quality_claims (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    claim_number character varying(120),
    supplier_id uuid NOT NULL,
    quality_nonconformance_id uuid,
    quality_inspection_id uuid,
    purchase_order_id uuid,
    material_id uuid,
    claim_date date,
    claim_amount numeric(19,4) DEFAULT 0,
    currency_code character varying(3) DEFAULT 'UZS'::character varying,
    requested_action character varying(1000),
    supplier_response text,
    response_due_date date,
    corrective_action_id uuid,
    sent_at timestamp without time zone,
    acknowledged_at timestamp without time zone,
    closed_at timestamp without time zone
);


--
-- Name: quality_inspections; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.quality_inspections (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    inspection_number character varying(120),
    inspection_type character varying(40) DEFAULT 'INCOMING'::character varying NOT NULL,
    supplier_id uuid,
    purchase_order_id uuid,
    purchase_order_item_id uuid,
    stock_movement_id uuid,
    material_id uuid NOT NULL,
    warehouse_id uuid NOT NULL,
    storage_location_id uuid,
    stock_batch_id uuid,
    received_quantity numeric(19,4) DEFAULT 0 NOT NULL,
    sample_quantity numeric(19,4) DEFAULT 0,
    accepted_quantity numeric(19,4) DEFAULT 0,
    rejected_quantity numeric(19,4) DEFAULT 0,
    quarantined_quantity numeric(19,4) DEFAULT 0,
    inspection_date date,
    due_date date,
    inspector_employee_id uuid,
    quality_passport_attachment_id uuid,
    quarantine_started_at timestamp without time zone,
    quarantine_released_at timestamp without time zone,
    inspection_started_at timestamp without time zone,
    completed_at timestamp without time zone,
    conclusion text
);


--
-- Name: quality_nonconformances; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.quality_nonconformances (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    quality_inspection_id uuid NOT NULL,
    supplier_id uuid,
    purchase_order_id uuid,
    material_id uuid NOT NULL,
    stock_movement_id uuid,
    severity character varying(40) DEFAULT 'MEDIUM'::character varying NOT NULL,
    nonconformance_type character varying(120),
    detected_quantity numeric(19,4) DEFAULT 0,
    defect_description text,
    root_cause text,
    disposition character varying(80) DEFAULT 'QUARANTINE'::character varying,
    capa_required boolean DEFAULT false NOT NULL,
    claim_id uuid,
    corrective_action_id uuid,
    detected_at timestamp without time zone,
    closed_at timestamp without time zone
);


--
-- Name: quality_quarantine_lots; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.quality_quarantine_lots (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    quarantine_number character varying(120),
    quality_inspection_id uuid,
    quality_nonconformance_id uuid,
    supplier_id uuid,
    purchase_order_id uuid,
    purchase_order_item_id uuid,
    stock_movement_id uuid,
    material_id uuid NOT NULL,
    warehouse_id uuid NOT NULL,
    storage_location_id uuid,
    stock_batch_id uuid,
    quarantine_reason text,
    quarantined_quantity numeric(19,4) DEFAULT 0 NOT NULL,
    released_quantity numeric(19,4) DEFAULT 0 NOT NULL,
    rejected_quantity numeric(19,4) DEFAULT 0 NOT NULL,
    remaining_quantity numeric(19,4) DEFAULT 0 NOT NULL,
    disposition character varying(80) DEFAULT 'QUARANTINE'::character varying,
    responsible_employee_id uuid,
    quarantine_started_at timestamp without time zone,
    released_at timestamp without time zone,
    rejected_at timestamp without time zone,
    closed_at timestamp without time zone,
    transfer_allocation_id uuid,
    transfer_clearance_id uuid,
    CONSTRAINT ck_quarantine_source CHECK (((quality_inspection_id IS NOT NULL) OR (transfer_clearance_id IS NOT NULL)))
);


--
-- Name: reference_mappings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.reference_mappings (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    reference_entity_type character varying(80) NOT NULL,
    reference_id uuid,
    reference_code character varying(120),
    reference_name character varying(240),
    external_system character varying(80) NOT NULL,
    external_code character varying(160) NOT NULL,
    external_name character varying(240),
    accounting_area character varying(60) DEFAULT 'MANAGEMENT'::character varying NOT NULL,
    chart_of_accounts_code character varying(80),
    account_code character varying(80),
    sub_account_code character varying(80),
    cost_center character varying(80),
    profit_center character varying(80),
    warehouse_accounting_code character varying(80),
    tax_code character varying(80),
    currency_code character varying(3),
    priority integer DEFAULT 100 NOT NULL,
    default_mapping boolean DEFAULT false NOT NULL,
    valid_from date,
    valid_to date
);


--
-- Name: return_document_lines; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.return_document_lines (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    company_id uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    return_document_id uuid NOT NULL,
    issue_request_line_id uuid NOT NULL,
    material_id uuid NOT NULL,
    original_stock_movement_id uuid,
    stock_batch_id uuid,
    storage_location_id uuid,
    issued_quantity numeric(19,4) DEFAULT 0 NOT NULL,
    previously_returned_quantity numeric(19,4) DEFAULT 0 NOT NULL,
    return_quantity numeric(19,4) DEFAULT 0 NOT NULL,
    accepted_quantity numeric(19,4) DEFAULT 0 NOT NULL,
    rejected_quantity numeric(19,4) DEFAULT 0 NOT NULL,
    material_condition character varying(40) NOT NULL,
    target_stock_status character varying(40),
    original_unit_cost numeric(19,6) DEFAULT 0 NOT NULL,
    line_comment text,
    inspection_note text
);


--
-- Name: return_documents; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.return_documents (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    company_id uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    issue_request_id uuid NOT NULL,
    warehouse_id uuid NOT NULL,
    reason text,
    acceptance_status character varying(50),
    stock_movement_document_id uuid
);


--
-- Name: rfid_tags; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.rfid_tags (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    tag_value character varying(160) NOT NULL,
    entity_type character varying(80) NOT NULL,
    entity_id uuid NOT NULL,
    device_id character varying(120),
    last_seen_at timestamp without time zone
);


--
-- Name: rfq_delivery_attempts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.rfq_delivery_attempts (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50) NOT NULL,
    description text,
    delivery_id uuid NOT NULL,
    attempt_number integer NOT NULL,
    idempotency_key character varying(180) NOT NULL,
    attempted_at timestamp without time zone NOT NULL,
    recipient_email character varying(240) NOT NULL,
    provider_message_id character varying(240),
    error_category character varying(80),
    error_message character varying(500)
);


--
-- Name: rfq_invitation_deliveries; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.rfq_invitation_deliveries (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50) DEFAULT 'PENDING'::character varying NOT NULL,
    description text,
    invitation_id uuid NOT NULL,
    published_version_id uuid NOT NULL,
    supplier_id uuid NOT NULL,
    recipient_email character varying(240) NOT NULL,
    recipient_name character varying(240),
    provider_message_id character varying(240),
    retry_count integer DEFAULT 0 NOT NULL,
    next_retry_at timestamp without time zone,
    first_sent_at timestamp without time zone,
    last_sent_at timestamp without time zone,
    delivered_at timestamp without time zone,
    portal_opened_at timestamp without time zone,
    declined_at timestamp without time zone,
    quotation_received_at timestamp without time zone,
    last_error_category character varying(80),
    delivery_purpose character varying(50) DEFAULT 'FULL_RFQ_PACKAGE'::character varying NOT NULL,
    CONSTRAINT chk_rfq_invitation_delivery_status CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'SENT'::character varying, 'DELIVERED'::character varying, 'FAILED'::character varying])::text[])))
);


--
-- Name: rfq_items; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.rfq_items (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    rfq_id uuid NOT NULL,
    material_id uuid,
    quantity numeric(19,4),
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    line_type character varying(20) DEFAULT 'MATERIAL'::character varying NOT NULL,
    service_request_id uuid,
    service_description text,
    service_category character varying(120),
    unit character varying(80),
    period character varying(120),
    stage character varying(120),
    budget_item_id uuid,
    material_type_snapshot character varying(30),
    CONSTRAINT ck_rfq_items_material_snapshot_present CHECK (((material_id IS NULL) OR (material_type_snapshot IS NOT NULL))),
    CONSTRAINT ck_rfq_items_material_type_snapshot CHECK (((material_type_snapshot IS NULL) OR ((material_type_snapshot)::text = ANY ((ARRAY['SERVICES'::character varying, 'FIXED_ASSET'::character varying, 'TMB'::character varying])::text[]))))
);


--
-- Name: rfq_published_versions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.rfq_published_versions (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50) DEFAULT 'PUBLISHED'::character varying NOT NULL,
    description text,
    rfq_id uuid NOT NULL,
    version_number integer NOT NULL,
    snapshot_json jsonb NOT NULL,
    package_language character varying(5) NOT NULL,
    template_version character varying(40) NOT NULL,
    pdf_attachment_id uuid,
    excel_attachment_id uuid,
    published_at timestamp without time zone NOT NULL,
    published_by character varying(120) NOT NULL,
    amendment_reason text
);


--
-- Name: rfq_purchase_request_sources; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.rfq_purchase_request_sources (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50) NOT NULL,
    description text,
    rfq_id uuid NOT NULL,
    rfq_item_id uuid NOT NULL,
    purchase_request_id uuid NOT NULL,
    purchase_request_item_id uuid NOT NULL,
    allocated_quantity numeric(19,4) NOT NULL,
    source_required_date date,
    source_department_id uuid,
    source_budget_item_id uuid,
    source_currency_code character varying(3),
    source_unit character varying(80),
    allocated_at timestamp without time zone NOT NULL,
    released_at timestamp without time zone,
    CONSTRAINT ck_rfq_pr_source_quantity CHECK ((allocated_quantity > (0)::numeric)),
    CONSTRAINT ck_rfq_pr_source_release_state CHECK (((((status)::text = 'ACTIVE'::text) AND (released_at IS NULL)) OR (((status)::text = 'RELEASED'::text) AND (released_at IS NOT NULL)))),
    CONSTRAINT ck_rfq_pr_source_status CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'RELEASED'::character varying])::text[])))
);


--
-- Name: rfq_supplier_invitations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.rfq_supplier_invitations (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50) DEFAULT 'INVITED'::character varying NOT NULL,
    description text,
    rfq_id uuid NOT NULL,
    supplier_id uuid NOT NULL,
    CONSTRAINT chk_rfq_supplier_invitation_status CHECK (((status)::text = ANY ((ARRAY['INVITED'::character varying, 'WITHDRAWN'::character varying])::text[])))
);


--
-- Name: rfqs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.rfqs (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    purchase_request_id uuid,
    due_date date,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    tender_procedure_type character varying(50) DEFAULT 'REQUEST_FOR_QUOTATION'::character varying,
    selection_rule character varying(50) DEFAULT 'LOWEST_PRICE'::character varying,
    min_quotations_required integer DEFAULT 1,
    aggregated_tender boolean DEFAULT false NOT NULL,
    aggregation_mode character varying(30),
    material_group_id uuid,
    source_purchase_request_count integer DEFAULT 0 NOT NULL,
    source_item_count integer DEFAULT 0 NOT NULL,
    currency_code character varying(3),
    responsible_buyer character varying(120),
    response_instructions text,
    package_language character varying(5) DEFAULT 'ru'::character varying NOT NULL,
    active_published_version_id uuid,
    published_at timestamp without time zone,
    published_by character varying(120),
    closed_at timestamp without time zone,
    pending_amendment_reason text,
    purpose character varying(40) DEFAULT 'PROCUREMENT'::character varying NOT NULL,
    procurement_procedure_id uuid,
    source_rfq_id uuid,
    price_basis_calculation_id uuid
);


--
-- Name: roles; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.roles (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    role_name character varying(80) NOT NULL,
    company_id uuid
);


--
-- Name: segregation_of_duty_rules; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.segregation_of_duty_rules (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    entity_type character varying(80) NOT NULL,
    action_code character varying(80) NOT NULL,
    rule_type character varying(80) DEFAULT 'CREATOR_CANNOT_APPROVE'::character varying NOT NULL,
    active_flag boolean DEFAULT true NOT NULL,
    allow_admin_override boolean DEFAULT true NOT NULL,
    severity character varying(40) DEFAULT 'BLOCK'::character varying NOT NULL,
    message character varying(500),
    subject_field character varying(120),
    source_action_code character varying(80),
    status_scope character varying(500),
    applies_to_roles character varying(500),
    exempt_roles character varying(500)
);


--
-- Name: segregation_of_duty_violations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.segregation_of_duty_violations (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    rule_id uuid,
    entity_type character varying(120) NOT NULL,
    entity_id uuid NOT NULL,
    action_code character varying(80) NOT NULL,
    rule_type character varying(80) NOT NULL,
    username character varying(120) NOT NULL,
    matched_principal character varying(120),
    decision character varying(40) NOT NULL,
    severity character varying(40) NOT NULL,
    occurred_at timestamp without time zone NOT NULL,
    message character varying(500)
);


--
-- Name: service_acceptance_acts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.service_acceptance_acts (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    service_order_id uuid NOT NULL,
    accepted_at timestamp without time zone,
    accepted_by character varying(120),
    amount numeric(19,4),
    commission_notes text,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    finance_reviewed_at timestamp without time zone,
    sent_to_payment_at timestamp without time zone,
    paid_at timestamp without time zone,
    budget_consumed_at timestamp without time zone,
    contract_consumed_at timestamp without time zone,
    payment_reference character varying(120),
    service_request_id uuid,
    vat_amount numeric(19,4) DEFAULT 0,
    vat_rate numeric(7,2) DEFAULT 0,
    period_start date,
    period_end date,
    stage character varying(120),
    document_reviewed_at timestamp without time zone,
    remarks text,
    purchase_order_id uuid,
    contract_id uuid,
    CONSTRAINT chk_service_acceptance_acts_period CHECK (((period_end IS NULL) OR (period_start IS NULL) OR (period_end >= period_start)))
);


--
-- Name: service_claims; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.service_claims (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    service_request_id uuid,
    service_act_id uuid,
    supplier_id uuid NOT NULL,
    claim_type character varying(60) NOT NULL,
    severity character varying(40) DEFAULT 'MEDIUM'::character varying NOT NULL,
    deadline date,
    penalty_amount numeric(19,4) DEFAULT 0,
    currency_code character varying(3) DEFAULT 'UZS'::character varying,
    supplier_response text,
    sent_at timestamp without time zone,
    resolved_at timestamp without time zone,
    closed_at timestamp without time zone,
    assigned_to uuid,
    origin_type character varying(30) NOT NULL,
    service_order_id uuid,
    purchase_order_item_id uuid,
    work_completion_act_id uuid,
    CONSTRAINT chk_service_claims_origin_links CHECK (((((origin_type)::text = 'SERVICE_REQUEST'::text) AND (service_request_id IS NOT NULL) AND (service_order_id IS NULL) AND (purchase_order_item_id IS NULL) AND (work_completion_act_id IS NULL)) OR (((origin_type)::text = 'PO_SERVICE'::text) AND (service_request_id IS NULL) AND (service_order_id IS NOT NULL) AND (purchase_order_item_id IS NULL) AND (work_completion_act_id IS NULL)) OR (((origin_type)::text = 'PO_WORKS'::text) AND (service_request_id IS NULL) AND (service_order_id IS NULL) AND (service_act_id IS NULL) AND (purchase_order_item_id IS NOT NULL))))
);


--
-- Name: service_executions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.service_executions (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    service_request_id uuid NOT NULL,
    service_order_id uuid,
    planned_start_date date,
    planned_end_date date,
    actual_start_date date,
    actual_end_date date,
    progress_percent numeric(7,2) DEFAULT 0,
    responsible_user_id uuid,
    remarks text,
    execution_report text,
    CONSTRAINT chk_service_executions_dates CHECK (((planned_end_date IS NULL) OR (planned_start_date IS NULL) OR (planned_end_date >= planned_start_date)))
);


--
-- Name: service_orders; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.service_orders (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    supplier_id uuid NOT NULL,
    contract_id uuid,
    purchase_order_id uuid,
    service_type character varying(80),
    total_amount numeric(19,4),
    currency_code character varying(3),
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    service_request_id uuid
);


--
-- Name: service_request_milestones; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.service_request_milestones (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    company_id uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50) NOT NULL,
    description text,
    service_request_id uuid NOT NULL,
    milestone_sequence integer NOT NULL,
    due_date date,
    acceptance_mode character varying(20) NOT NULL,
    ordered_quantity numeric(19,4),
    accepted_quantity numeric(19,4),
    rejected_quantity numeric(19,4),
    uom_code character varying(40),
    ordered_amount numeric(19,4),
    accepted_amount numeric(19,4),
    rejected_amount numeric(19,4),
    currency_code character varying(3),
    acceptance_remarks text,
    accepted_at timestamp without time zone,
    CONSTRAINT chk_service_request_milestones_acceptance_mode CHECK (((acceptance_mode)::text = ANY ((ARRAY['AMOUNT'::character varying, 'QUANTITY'::character varying])::text[]))),
    CONSTRAINT chk_service_request_milestones_basis CHECK (((((acceptance_mode)::text = 'AMOUNT'::text) AND (ordered_amount > (0)::numeric) AND (COALESCE(accepted_amount, (0)::numeric) >= (0)::numeric) AND (COALESCE(rejected_amount, (0)::numeric) >= (0)::numeric) AND ((COALESCE(accepted_amount, (0)::numeric) + COALESCE(rejected_amount, (0)::numeric)) <= ordered_amount) AND (currency_code IS NOT NULL) AND (btrim((currency_code)::text) <> ''::text) AND (ordered_quantity IS NULL) AND (accepted_quantity IS NULL) AND (rejected_quantity IS NULL) AND (uom_code IS NULL)) OR (((acceptance_mode)::text = 'QUANTITY'::text) AND (ordered_quantity > (0)::numeric) AND (COALESCE(accepted_quantity, (0)::numeric) >= (0)::numeric) AND (COALESCE(rejected_quantity, (0)::numeric) >= (0)::numeric) AND ((COALESCE(accepted_quantity, (0)::numeric) + COALESCE(rejected_quantity, (0)::numeric)) <= ordered_quantity) AND (uom_code IS NOT NULL) AND (btrim((uom_code)::text) <> ''::text) AND (ordered_amount IS NULL) AND (accepted_amount IS NULL) AND (rejected_amount IS NULL) AND (currency_code IS NULL)))),
    CONSTRAINT chk_service_request_milestones_sequence CHECK ((milestone_sequence > 0)),
    CONSTRAINT chk_service_request_milestones_status CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'PARTIALLY_ACCEPTED'::character varying, 'REWORK_REQUIRED'::character varying, 'ACCEPTED'::character varying])::text[])))
);


--
-- Name: service_request_remarks; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.service_request_remarks (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    company_id uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    service_request_id uuid NOT NULL,
    severity character varying(30) NOT NULL,
    remark_description text NOT NULL,
    due_date date,
    assigned_to uuid
);


--
-- Name: service_requests; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.service_requests (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    request_number character varying(120),
    title character varying(240),
    category character varying(120),
    urgency character varying(40),
    department_id uuid,
    responsible_user_id uuid,
    supplier_id uuid,
    contract_id uuid,
    purchase_order_id uuid,
    budget_item_id uuid,
    cost_center_id uuid,
    estimated_amount numeric(19,4) DEFAULT 0,
    approved_amount numeric(19,4) DEFAULT 0,
    actual_amount numeric(19,4) DEFAULT 0,
    planned_start_date date,
    planned_end_date date,
    budget_reserved_amount numeric(19,4) DEFAULT 0,
    budget_reserved_at timestamp without time zone,
    budget_released_at timestamp without time zone,
    budget_consumed_at timestamp without time zone,
    accepted_volume numeric(19,4) DEFAULT 0 NOT NULL,
    rejected_volume numeric(19,4) DEFAULT 0 NOT NULL,
    acceptance_remarks text,
    acceptance_mode character varying(20) DEFAULT 'AMOUNT'::character varying NOT NULL,
    ordered_amount numeric(19,4),
    accepted_amount numeric(19,4),
    rejected_amount numeric(19,4),
    currency_code character varying(3),
    ordered_quantity numeric(19,4),
    accepted_quantity numeric(19,4),
    rejected_quantity numeric(19,4),
    uom_code character varying(40)
);


--
-- Name: service_review_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.service_review_history (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    service_request_id uuid,
    service_act_id uuid,
    review_type character varying(60) NOT NULL,
    decision character varying(60) NOT NULL,
    actor character varying(120),
    acted_at timestamp without time zone NOT NULL,
    comment_text text,
    service_claim_id uuid,
    claim_origin_type character varying(30),
    claim_origin_id uuid
);


--
-- Name: sms_gateway_settings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sms_gateway_settings (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    enabled boolean DEFAULT false NOT NULL,
    mfa_enabled boolean DEFAULT false NOT NULL,
    url character varying(500),
    http_method character varying(10) DEFAULT 'POST'::character varying NOT NULL,
    auth_header character varying(120),
    auth_token text,
    sender character varying(120),
    template text,
    timeout_seconds integer DEFAULT 8 NOT NULL,
    ttl_seconds integer DEFAULT 300 NOT NULL,
    provider_code character varying(40) DEFAULT 'CUSTOM'::character varying NOT NULL,
    auth_url character varying(500),
    login character varying(180),
    password_secret text,
    account_sid character varying(180),
    service_sid character varying(180),
    from_phone character varying(80),
    last_provider_status character varying(80),
    last_provider_message text,
    last_tested_at timestamp without time zone
);


--
-- Name: sso_settings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sso_settings (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    enabled boolean DEFAULT false NOT NULL,
    provider_type character varying(20) DEFAULT 'OIDC'::character varying NOT NULL,
    login_url character varying(500),
    authorization_url character varying(500),
    token_url character varying(500),
    user_info_url character varying(500),
    client_id character varying(240),
    client_secret text,
    redirect_uri character varying(500),
    scopes character varying(500),
    trusts_idp_mfa boolean DEFAULT true NOT NULL,
    metadata_url character varying(500),
    idp_entity_id character varying(300),
    sp_entity_id character varying(300),
    acs_url character varying(500),
    x509_certificate text,
    saml_private_key text,
    assertion_secret text,
    state_secret text
);


--
-- Name: stock_balances; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.stock_balances (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    material_id uuid NOT NULL,
    warehouse_id uuid NOT NULL,
    storage_location_id uuid,
    stock_batch_id uuid,
    quantity numeric(19,4) NOT NULL,
    available_quantity numeric(19,4) NOT NULL,
    reserved_quantity numeric(19,4) NOT NULL,
    total_value numeric(19,4) NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    quarantine_quantity numeric(19,4) DEFAULT 0 NOT NULL,
    balance_at timestamp without time zone,
    storage_bin_id uuid
);


--
-- Name: stock_batches; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.stock_batches (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    material_id uuid NOT NULL,
    warehouse_id uuid NOT NULL,
    batch_number character varying(120),
    serial_number character varying(120),
    quantity numeric(19,4),
    received_date date,
    expiration_date date,
    unit_cost numeric(19,4),
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    barcode character varying(160),
    qr_code character varying(160),
    rfid_tag character varying(160),
    purchase_unit_cost numeric(19,6) DEFAULT 0 NOT NULL,
    additional_cost_per_unit numeric(19,6) DEFAULT 0 NOT NULL,
    final_unit_cost numeric(19,6) DEFAULT 0 NOT NULL,
    manufacturer_batch_number character varying(160),
    manufactured_on date,
    expires_on date
);


--
-- Name: stock_cost_movements; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.stock_cost_movements (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    material_id uuid NOT NULL,
    warehouse_id uuid NOT NULL,
    stock_movement_id uuid,
    cost_layer_id uuid,
    movement_type character varying(30) NOT NULL,
    quantity numeric(19,4) DEFAULT 0 NOT NULL,
    unit_cost numeric(19,6) DEFAULT 0 NOT NULL,
    total_cost numeric(19,4) DEFAULT 0 NOT NULL,
    cost_center_id uuid,
    source_document_type character varying(50),
    source_document_id uuid,
    is_reversal boolean DEFAULT false NOT NULL,
    reversal_of_id uuid,
    transfer_transaction_id uuid,
    reversal_reason text,
    reversed_at timestamp without time zone,
    reversed_by character varying(120)
);


--
-- Name: stock_movement_allocation_cost_layers; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.stock_movement_allocation_cost_layers (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by character varying(120),
    allocation_id uuid NOT NULL,
    lineage_sequence integer NOT NULL,
    lineage_role character varying(40) NOT NULL,
    cost_layer_id uuid,
    parent_cost_layer_id_snapshot uuid,
    source_cost_movement_id uuid,
    quantity_delta numeric(19,4) NOT NULL,
    value_delta numeric(19,4) NOT NULL,
    unit_cost_snapshot numeric(19,6),
    currency_code_snapshot character varying(10),
    exchange_rate_snapshot numeric(19,6)
);


--
-- Name: stock_movement_allocations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.stock_movement_allocations (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by character varying(120),
    movement_id uuid NOT NULL,
    allocation_sequence integer NOT NULL,
    allocation_role character varying(40) NOT NULL,
    material_id uuid NOT NULL,
    balance_id uuid,
    source_warehouse_id_snapshot uuid,
    source_storage_location_id_snapshot uuid,
    source_storage_bin_id_snapshot uuid,
    source_stock_batch_id_snapshot uuid,
    source_batch_number_snapshot character varying(120),
    source_disposition_snapshot character varying(40),
    target_warehouse_id_snapshot uuid,
    target_storage_location_id_snapshot uuid,
    target_storage_bin_id_snapshot uuid,
    target_stock_batch_id_snapshot uuid,
    target_batch_number_snapshot character varying(120),
    target_disposition_snapshot character varying(40),
    uom_id_snapshot uuid,
    uom_code_snapshot character varying(80),
    on_hand_delta numeric(19,4) NOT NULL,
    available_delta numeric(19,4) NOT NULL,
    reserved_delta numeric(19,4) NOT NULL,
    quarantine_delta numeric(19,4) NOT NULL,
    total_value_delta numeric(19,4) NOT NULL,
    batch_quantity_delta numeric(19,4) NOT NULL,
    unit_cost_snapshot numeric(19,6),
    currency_code_snapshot character varying(10),
    exchange_rate_snapshot numeric(19,6),
    source_inventory_item_id uuid
);


--
-- Name: stock_movement_documents; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.stock_movement_documents (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    warehouse_id uuid NOT NULL,
    target_warehouse_id uuid,
    movement_type character varying(40) NOT NULL,
    movement_date date NOT NULL,
    posted_at timestamp without time zone,
    reference_type character varying(80),
    reference_id uuid,
    reversal_of_id uuid
);


--
-- Name: stock_movements; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.stock_movements (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    material_id uuid NOT NULL,
    warehouse_id uuid NOT NULL,
    target_warehouse_id uuid,
    storage_location_id uuid,
    target_storage_location_id uuid,
    stock_batch_id uuid,
    movement_type character varying(40) NOT NULL,
    quantity numeric(19,4) NOT NULL,
    unit_cost numeric(19,4),
    total_cost numeric(19,4),
    posted_at timestamp without time zone,
    reference_type character varying(80),
    reference_id uuid,
    reversal_of_id uuid,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    movement_date date,
    document_id uuid,
    request_date timestamp without time zone,
    storage_bin_id uuid,
    target_storage_bin_id uuid,
    batch_number character varying(120),
    target_stock_batch_id uuid,
    batch_number_snapshot character varying(120),
    target_batch_number_snapshot character varying(120),
    uom_id_snapshot uuid,
    uom_code_snapshot character varying(80),
    source_disposition_snapshot character varying(40),
    target_disposition_snapshot character varying(40),
    source_inventory_item_id uuid,
    source_currency_code_snapshot character varying(12),
    source_exchange_rate_snapshot numeric(19,8)
);


--
-- Name: stock_valuation_cost_job_results; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.stock_valuation_cost_job_results (
    id uuid NOT NULL,
    job_id uuid NOT NULL,
    material_id uuid NOT NULL,
    material_name character varying(240),
    warehouse_id uuid NOT NULL,
    warehouse_name character varying(240),
    quantity numeric(19,4) NOT NULL,
    valuation_method character varying(50),
    total_cost numeric(19,4),
    available boolean NOT NULL,
    error_message text,
    sequence_no integer NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: stock_valuation_cost_job_warehouses; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.stock_valuation_cost_job_warehouses (
    job_id uuid NOT NULL,
    warehouse_id uuid NOT NULL
);


--
-- Name: stock_valuation_cost_jobs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.stock_valuation_cost_jobs (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    warehouse_id uuid,
    requested_by character varying(120) NOT NULL,
    status character varying(40) NOT NULL,
    phase character varying(40) NOT NULL,
    progress_percent numeric(5,2) DEFAULT 0 NOT NULL,
    total_items integer DEFAULT 0 NOT NULL,
    processed_items integer DEFAULT 0 NOT NULL,
    successful_items integer DEFAULT 0 NOT NULL,
    failed_items integer DEFAULT 0 NOT NULL,
    error_message text,
    started_at timestamp without time zone,
    finished_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone,
    version bigint DEFAULT 0 NOT NULL,
    valuation_method character varying(50),
    CONSTRAINT chk_stock_valuation_cost_job_counters CHECK (((total_items >= 0) AND (processed_items >= 0) AND (successful_items >= 0) AND (failed_items >= 0))),
    CONSTRAINT chk_stock_valuation_cost_job_progress CHECK (((progress_percent >= (0)::numeric) AND (progress_percent <= (100)::numeric)))
);


--
-- Name: stock_valuation_documents; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.stock_valuation_documents (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    code character varying(80) NOT NULL,
    name text NOT NULL,
    description text,
    status character varying(50),
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    version bigint DEFAULT 0 NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    material_id uuid NOT NULL,
    warehouse_id uuid NOT NULL,
    document_type character varying(40) NOT NULL,
    valuation_method character varying(40),
    document_date date,
    quantity numeric(19,4) DEFAULT 0 NOT NULL,
    old_unit_cost numeric(19,4) DEFAULT 0 NOT NULL,
    new_unit_cost numeric(19,4) DEFAULT 0 NOT NULL,
    amount numeric(19,4) DEFAULT 0 NOT NULL,
    reserve_amount numeric(19,4) DEFAULT 0 NOT NULL,
    reason text,
    posted_at timestamp without time zone
);


--
-- Name: stock_valuation_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.stock_valuation_history (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    material_id uuid NOT NULL,
    material_name character varying(240),
    warehouse_id uuid NOT NULL,
    warehouse_name character varying(240),
    quantity numeric(19,4) NOT NULL,
    valuation_method character varying(50),
    unit_cost numeric(19,4),
    total_value numeric(19,4),
    carrying_value numeric(19,4),
    valuation_basis character varying(80),
    source_type character varying(40) NOT NULL,
    source_id uuid,
    calculated_at timestamp without time zone NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: stock_valuation_run_items; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.stock_valuation_run_items (
    id uuid NOT NULL,
    valuation_run_id uuid NOT NULL,
    material_id uuid NOT NULL,
    material_name character varying(240),
    warehouse_id uuid NOT NULL,
    warehouse_name character varying(240),
    quantity numeric(19,4) NOT NULL,
    valuation_method character varying(50),
    unit_cost numeric(19,4),
    total_value numeric(19,4),
    carrying_value numeric(19,4),
    available boolean NOT NULL,
    error_message text,
    sequence_no integer NOT NULL,
    calculated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: stock_valuation_run_warehouses; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.stock_valuation_run_warehouses (
    valuation_run_id uuid NOT NULL,
    warehouse_id uuid NOT NULL
);


--
-- Name: stock_valuation_runs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.stock_valuation_runs (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    is_delete boolean DEFAULT false NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    scope_type character varying(40) NOT NULL,
    warehouse_id uuid,
    description_uz text,
    description_ru text,
    description_en text,
    job_id uuid,
    started_at timestamp without time zone,
    finished_at timestamp without time zone,
    error_message text,
    valuation_method character varying(50),
    CONSTRAINT chk_stock_valuation_run_scope CHECK (((((scope_type)::text = 'GLOBAL'::text) AND (warehouse_id IS NULL)) OR (((scope_type)::text = 'WAREHOUSE'::text) AND (warehouse_id IS NOT NULL)) OR (((scope_type)::text = 'MULTI_WAREHOUSE'::text) AND (warehouse_id IS NULL))))
);


--
-- Name: stock_valuations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.stock_valuations (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    material_id uuid NOT NULL,
    warehouse_id uuid NOT NULL,
    valuation_method character varying(40),
    quantity numeric(19,4),
    unit_cost numeric(19,4),
    total_value numeric(19,4),
    calculated_at timestamp without time zone,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    impairment_reserve numeric(19,4) DEFAULT 0 NOT NULL,
    revaluation_amount numeric(19,4) DEFAULT 0 NOT NULL,
    carrying_value numeric(19,4) DEFAULT 0 NOT NULL,
    valuation_basis character varying(80)
);


--
-- Name: storage_bins; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.storage_bins (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    warehouse_id uuid NOT NULL,
    storage_location_id uuid NOT NULL,
    bin_code character varying(80) NOT NULL,
    barcode character varying(160),
    qr_code character varying(160),
    rfid_tag character varying(160),
    capacity_quantity numeric(19,4),
    max_weight numeric(19,4),
    allow_mixed_materials boolean DEFAULT true NOT NULL
);


--
-- Name: storage_locations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.storage_locations (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    warehouse_id uuid,
    zone_code character varying(80),
    bin_code character varying(80),
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    barcode character varying(160),
    qr_code character varying(160),
    rfid_tag character varying(160),
    storage_zone_type character varying(80),
    temperature_zone character varying(80),
    hazard_class_allowed character varying(40),
    capacity_quantity numeric(19,4),
    max_weight numeric(19,4),
    allow_mixed_materials boolean DEFAULT true NOT NULL
);


--
-- Name: supplier_accreditation_applications; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.supplier_accreditation_applications (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    supplier_id uuid NOT NULL,
    accreditation_type character varying(80) NOT NULL,
    requested_scope character varying(500),
    requested_material_group_ids text,
    submitted_at timestamp without time zone,
    review_started_at timestamp without time zone,
    valid_from date,
    valid_to date,
    risk_score numeric(7,2) DEFAULT 0,
    review_comment character varying(1000),
    compliance_recommendation character varying(50),
    compliance_snapshot_json text,
    parent_application_id uuid
);


--
-- Name: supplier_accreditation_decisions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.supplier_accreditation_decisions (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    application_id uuid NOT NULL,
    decision character varying(80) NOT NULL,
    approved_scope character varying(500),
    allowed_material_group_ids text,
    valid_from date,
    valid_to date,
    conditions character varying(1000),
    commission_protocol_no character varying(120),
    review_comment character varying(1000)
);


--
-- Name: supplier_accreditation_documents; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.supplier_accreditation_documents (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    application_id uuid NOT NULL,
    document_type character varying(80) NOT NULL,
    attachment_id uuid,
    valid_from date,
    valid_to date,
    verified_by character varying(120),
    verified_at timestamp without time zone
);


--
-- Name: supplier_accreditation_evidence_requirements; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.supplier_accreditation_evidence_requirements (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80) NOT NULL,
    name text NOT NULL,
    status character varying(50) NOT NULL,
    description text,
    application_id uuid NOT NULL,
    template_version_id uuid NOT NULL,
    requirement_code character varying(80) NOT NULL,
    document_type character varying(80) NOT NULL,
    mandatory boolean NOT NULL,
    reusable boolean NOT NULL,
    validity_required boolean NOT NULL,
    owner_role character varying(80) NOT NULL,
    due_date date,
    display_order integer NOT NULL,
    linked_document_id uuid
);


--
-- Name: supplier_activation_records; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.supplier_activation_records (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80) NOT NULL,
    name text NOT NULL,
    status character varying(50) NOT NULL,
    description text,
    supplier_id uuid NOT NULL,
    activated_by character varying(120) NOT NULL,
    activated_at timestamp without time zone NOT NULL,
    activation_comment text NOT NULL,
    readiness_snapshot_json text NOT NULL,
    policy_version character varying(40) NOT NULL
);


--
-- Name: supplier_bank_accounts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.supplier_bank_accounts (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    supplier_id uuid NOT NULL,
    bank_name character varying(240),
    bank_mfo character varying(500),
    bank_account character varying(500),
    bank_account_hash character varying(64),
    bank_address character varying(500),
    primary_account boolean DEFAULT false NOT NULL
);


--
-- Name: supplier_certificates; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.supplier_certificates (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    supplier_id uuid NOT NULL,
    certificate_number character varying(120),
    issuer character varying(240),
    valid_from date,
    valid_to date,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL
);


--
-- Name: supplier_corrective_actions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.supplier_corrective_actions (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    supplier_id uuid NOT NULL,
    source_type character varying(80),
    source_id uuid,
    issue_title character varying(240),
    issue_description text,
    root_cause text,
    corrective_action text,
    preventive_action text,
    owner_employee_id uuid,
    supplier_responsible_name character varying(240),
    due_date date,
    submitted_at timestamp without time zone,
    verified_at timestamp without time zone,
    closed_at timestamp without time zone,
    severity character varying(40) DEFAULT 'MEDIUM'::character varying NOT NULL,
    verification_result character varying(1000),
    recurrence_prevented boolean DEFAULT false NOT NULL
);


--
-- Name: supplier_evaluations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.supplier_evaluations (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    supplier_id uuid NOT NULL,
    purchase_order_id uuid,
    delivery_score numeric(7,2),
    quality_score numeric(7,2),
    price_score numeric(7,2),
    claims_score numeric(7,2),
    total_score numeric(7,2),
    evaluated_at timestamp without time zone,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    stock_movement_id uuid,
    material_id uuid,
    purchase_order_item_id uuid,
    ordered_quantity numeric(19,4) DEFAULT 0,
    received_quantity numeric(19,4) DEFAULT 0,
    accepted_quantity numeric(19,4) DEFAULT 0,
    rejected_quantity numeric(19,4) DEFAULT 0,
    late_days integer DEFAULT 0,
    quantity_score numeric(7,2) DEFAULT 0,
    evaluation_source character varying(40),
    calculation_details_json text
);


--
-- Name: supplier_evidence_requirements; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.supplier_evidence_requirements (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80) NOT NULL,
    name text NOT NULL,
    status character varying(50) NOT NULL,
    description text,
    template_version_id uuid NOT NULL,
    requirement_code character varying(80) NOT NULL,
    document_type character varying(80) NOT NULL,
    mandatory boolean NOT NULL,
    reusable boolean NOT NULL,
    validity_required boolean NOT NULL,
    owner_role character varying(80) NOT NULL,
    sla_days integer NOT NULL,
    display_order integer NOT NULL
);


--
-- Name: supplier_evidence_template_versions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.supplier_evidence_template_versions (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80) NOT NULL,
    name text NOT NULL,
    status character varying(50) NOT NULL,
    description text,
    accreditation_type character varying(80) NOT NULL,
    template_version integer NOT NULL,
    effective_from date NOT NULL
);


--
-- Name: supplier_kyc_checks; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.supplier_kyc_checks (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    supplier_id uuid NOT NULL,
    check_type character varying(80) NOT NULL,
    provider character varying(120),
    external_check_id character varying(160),
    risk_level character varying(40) DEFAULT 'LOW'::character varying NOT NULL,
    score numeric(7,2) DEFAULT 0,
    checked_at timestamp without time zone,
    valid_until date,
    next_review_date date,
    evidence_attachment_id uuid,
    findings text,
    remediation_required boolean DEFAULT false NOT NULL
);


--
-- Name: supplier_letters; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.supplier_letters (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    supplier_id uuid NOT NULL,
    letter_number character varying(120),
    letter_date date,
    direction character varying(40),
    subject character varying(500),
    channel character varying(80),
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL
);


--
-- Name: supplier_licenses; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.supplier_licenses (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    supplier_id uuid NOT NULL,
    license_number character varying(120),
    license_type character varying(120),
    issuing_authority character varying(240),
    issue_date date,
    valid_from date,
    valid_to date,
    scope character varying(500),
    territory character varying(240),
    renewal_required boolean DEFAULT false NOT NULL,
    revoked_at date,
    revocation_reason character varying(500)
);


--
-- Name: supplier_magic_link_requests; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.supplier_magic_link_requests (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50) DEFAULT 'ACCEPTED'::character varying NOT NULL,
    description text,
    normalized_email_hash character varying(64) NOT NULL,
    request_ip_hash character varying(64) NOT NULL,
    requested_at timestamp without time zone NOT NULL,
    matched_account boolean DEFAULT false NOT NULL,
    reason_code character varying(80) DEFAULT 'ACCOUNT_NOT_FOUND'::character varying NOT NULL
);


--
-- Name: supplier_magic_links; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.supplier_magic_links (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50) DEFAULT 'ISSUED'::character varying NOT NULL,
    description text,
    portal_account_id uuid NOT NULL,
    supplier_id uuid NOT NULL,
    invitation_id uuid,
    token_hash character varying(64) NOT NULL,
    return_path character varying(500) NOT NULL,
    expires_at timestamp without time zone NOT NULL,
    consumed_at timestamp without time zone,
    revoked_at timestamp without time zone,
    request_ip_hash character varying(64),
    purchase_order_id uuid
);


--
-- Name: supplier_po_commitments; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.supplier_po_commitments (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50) DEFAULT 'PROPOSED'::character varying NOT NULL,
    description text,
    response_line_id uuid NOT NULL,
    purchase_order_id uuid NOT NULL,
    purchase_order_item_id uuid NOT NULL,
    supplier_id uuid NOT NULL,
    proposed_quantity numeric(19,4) DEFAULT 0 NOT NULL,
    proposed_date date,
    confirmed_quantity numeric(19,4),
    confirmed_date date,
    delivery_schedule_id uuid,
    confirmed_by character varying(120),
    confirmed_at timestamp without time zone,
    superseded_commitment_id uuid,
    is_current boolean DEFAULT true NOT NULL,
    due_soon_reminder_sent_at timestamp without time zone,
    overdue_reminder_sent_at timestamp without time zone,
    asn_missing_reminder_sent_at timestamp without time zone
);


--
-- Name: supplier_po_dispatches; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.supplier_po_dispatches (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    purchase_order_id uuid NOT NULL,
    purchase_order_version bigint NOT NULL,
    purchase_order_revision_id uuid,
    supplier_id uuid NOT NULL,
    portal_account_id uuid,
    recipient_email character varying(320),
    dispatch_status character varying(50) DEFAULT 'QUEUED'::character varying NOT NULL,
    response_due_date date,
    queued_at timestamp without time zone,
    sent_at timestamp without time zone,
    delivered_at timestamp without time zone,
    viewed_at timestamp without time zone,
    superseded_at timestamp without time zone,
    cancelled_at timestamp without time zone,
    snapshot_json text NOT NULL,
    snapshot_hash character varying(64) NOT NULL,
    provider_message_id character varying(200),
    delivery_attempts integer DEFAULT 0 NOT NULL,
    last_delivery_error text,
    is_current boolean DEFAULT true NOT NULL,
    due_soon_reminder_sent_at timestamp without time zone,
    overdue_reminder_sent_at timestamp without time zone
);


--
-- Name: supplier_po_reconfirmation_notifications; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.supplier_po_reconfirmation_notifications (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    event_type character varying(120) NOT NULL,
    purchase_order_id uuid NOT NULL,
    purchase_order_item_id uuid NOT NULL,
    commitment_id uuid NOT NULL,
    reconfirmation_response_id uuid NOT NULL,
    supplier_id uuid NOT NULL,
    portal_account_id uuid,
    recipient_email character varying(320) NOT NULL,
    po_code character varying(120),
    line_name text,
    reason text,
    requested_by character varying(120),
    provider_message_id character varying(200),
    attempts integer DEFAULT 0 NOT NULL,
    queued_at timestamp without time zone,
    sent_at timestamp without time zone,
    next_retry_at timestamp without time zone,
    last_error text
);


--
-- Name: supplier_po_response_lines; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.supplier_po_response_lines (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    response_id uuid NOT NULL,
    purchase_order_item_id uuid NOT NULL,
    line_snapshot_json text NOT NULL,
    decision character varying(50) NOT NULL,
    ordered_quantity numeric(19,4) DEFAULT 0 NOT NULL,
    open_quantity_at_dispatch numeric(19,4) DEFAULT 0 NOT NULL,
    accepted_quantity numeric(19,4) DEFAULT 0 NOT NULL,
    unconfirmed_quantity numeric(19,4) DEFAULT 0 NOT NULL,
    reason_code character varying(80),
    supplier_comment text
);


--
-- Name: supplier_po_responses; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.supplier_po_responses (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    dispatch_id uuid NOT NULL,
    purchase_order_id uuid NOT NULL,
    supplier_id uuid NOT NULL,
    portal_account_id uuid,
    response_revision integer DEFAULT 1 NOT NULL,
    response_type character varying(50),
    response_status character varying(50) DEFAULT 'DRAFT'::character varying NOT NULL,
    supplier_comment text,
    rejection_reason text,
    submitted_at timestamp without time zone,
    withdrawn_at timestamp without time zone,
    buyer_decision_by character varying(120),
    buyer_decision_at timestamp without time zone,
    buyer_decision_comment text,
    superseded_response_id uuid,
    is_current boolean DEFAULT true NOT NULL,
    reconfirmation_origin_commitment_id uuid,
    reconfirmation_reason text,
    reconfirmation_requested_by character varying(120),
    reconfirmation_requested_at timestamp without time zone
);


--
-- Name: supplier_portal_accounts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.supplier_portal_accounts (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    supplier_id uuid NOT NULL,
    login_email character varying(240) NOT NULL,
    contact_person character varying(240),
    phone character varying(80),
    invite_token_hash character varying(64),
    invited_at timestamp without time zone,
    activated_at timestamp without time zone,
    last_login_at timestamp without time zone,
    last_submission_at timestamp without time zone,
    allowed_ip_range character varying(240),
    mfa_required boolean DEFAULT true NOT NULL,
    terms_accepted_at timestamp without time zone,
    portal_token_hash character varying(64),
    portal_token_expires_at timestamp without time zone,
    invite_token_expires_at timestamp without time zone,
    portal_role character varying(50) DEFAULT 'SUPPLIER_ADMIN'::character varying NOT NULL
);


--
-- Name: supplier_portal_submissions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.supplier_portal_submissions (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    supplier_id uuid NOT NULL,
    portal_account_id uuid,
    submission_type character varying(80),
    reference_entity_type character varying(80),
    reference_id uuid,
    payload_json text,
    attachment_id uuid,
    submitted_at timestamp without time zone,
    reviewed_at timestamp without time zone,
    reviewed_by character varying(120),
    review_comment character varying(1000)
);


--
-- Name: supplier_quotation_items; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.supplier_quotation_items (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    quotation_id uuid NOT NULL,
    rfq_item_id uuid,
    material_id uuid,
    quantity numeric(19,4),
    unit_price numeric(19,4),
    delivery_days integer,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    line_type character varying(20) DEFAULT 'MATERIAL'::character varying NOT NULL,
    service_request_id uuid,
    service_description text,
    service_category character varying(120),
    unit character varying(80),
    period character varying(120),
    stage character varying(120),
    pricing_model character varying(40),
    budget_item_id uuid,
    no_bid boolean DEFAULT false NOT NULL,
    no_bid_reason text,
    tax_rate numeric(7,4),
    supplier_note text,
    material_type_snapshot character varying(30),
    CONSTRAINT ck_supplier_quotation_items_material_snapshot_present CHECK (((material_id IS NULL) OR (material_type_snapshot IS NOT NULL))),
    CONSTRAINT ck_supplier_quotation_items_material_type_snapshot CHECK (((material_type_snapshot IS NULL) OR ((material_type_snapshot)::text = ANY ((ARRAY['SERVICES'::character varying, 'FIXED_ASSET'::character varying, 'TMB'::character varying])::text[]))))
);


--
-- Name: supplier_quotation_threads; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.supplier_quotation_threads (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50) DEFAULT 'OPEN'::character varying NOT NULL,
    description text,
    rfq_id uuid NOT NULL,
    supplier_id uuid NOT NULL,
    current_quotation_id uuid
);


--
-- Name: supplier_quotations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.supplier_quotations (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    rfq_id uuid NOT NULL,
    supplier_id uuid NOT NULL,
    total_amount numeric(19,4),
    currency_code character varying(3),
    submitted_at timestamp without time zone,
    selected_flag boolean DEFAULT false NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    technical_score numeric(7,2) DEFAULT 0,
    delivery_score numeric(7,2) DEFAULT 0,
    price_score numeric(7,2) DEFAULT 0,
    total_score numeric(7,2) DEFAULT 0,
    selection_reason character varying(500),
    thread_id uuid,
    published_version_id uuid,
    revision_number integer,
    source character varying(30),
    received_at timestamp without time zone,
    recorded_by character varying(120),
    current_revision boolean DEFAULT true NOT NULL,
    supersedes_quotation_id uuid,
    supersession_reason text,
    original_attachment_id uuid,
    payment_terms text,
    delivery_terms text,
    valid_until date,
    tax_included boolean
);


--
-- Name: supplier_related_parties; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.supplier_related_parties (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    supplier_id uuid NOT NULL,
    party_type character varying(80) NOT NULL,
    full_name character varying(240),
    legal_name character varying(300),
    tax_id character varying(500),
    country_code character varying(3),
    ownership_percent numeric(7,2) DEFAULT 0,
    position_title character varying(240),
    phone character varying(80),
    email character varying(160),
    pep_flag boolean DEFAULT false NOT NULL,
    sanctions_match boolean DEFAULT false NOT NULL,
    conflict_of_interest_flag boolean DEFAULT false NOT NULL,
    risk_level character varying(40) DEFAULT 'LOW'::character varying NOT NULL,
    valid_from date,
    valid_to date
);


--
-- Name: supplier_unblock_requests; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.supplier_unblock_requests (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    company_id uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    supplier_id uuid NOT NULL,
    reason text NOT NULL,
    purchase_order_id uuid,
    requested_by uuid NOT NULL,
    reviewed_by uuid,
    reviewed_at timestamp without time zone,
    review_comment text,
    decided_by uuid,
    decided_at timestamp without time zone,
    decision_comment text,
    CONSTRAINT chk_supplier_unblock_status CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'UNDER_REVIEW'::character varying, 'APPROVED'::character varying, 'REJECTED'::character varying])::text[])))
);


--
-- Name: suppliers; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.suppliers (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    legal_name character varying(300),
    short_name character varying(180),
    tax_id character varying(500),
    oked_code character varying(20),
    vat_registration_code character varying(40),
    registration_number character varying(80),
    registration_date date,
    legal_address character varying(500),
    actual_address character varying(500),
    phone character varying(80),
    email character varying(160),
    director_name character varying(240),
    chief_accountant_name character varying(240),
    bank_name character varying(240),
    bank_mfo character varying(500),
    bank_account character varying(500),
    bank_address character varying(500),
    category character varying(5),
    rating numeric(7,2),
    blocked_reason character varying(500),
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    bank_account_hash character varying(64),
    parent_supplier_id uuid,
    base_tax_id character varying(500),
    tax_id_suffix integer,
    supplier_type character varying(40) DEFAULT 'HEAD_OFFICE'::character varying NOT NULL,
    branch_name character varying(240),
    relationship_type character varying(20) DEFAULT 'EXTERNAL'::character varying NOT NULL,
    owner_employee_id uuid,
    primary_contact_name character varying(240),
    country_code character varying(3),
    city character varying(160),
    legal_form character varying(80),
    CONSTRAINT ck_supplier_relationship_type CHECK (((relationship_type)::text = ANY ((ARRAY['INTERNAL'::character varying, 'EXTERNAL'::character varying])::text[])))
);


--
-- Name: supply_work_assignments; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.supply_work_assignments (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    company_id uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    commitment_key character varying(80) NOT NULL,
    commitment_kind character varying(20) NOT NULL,
    owner_user_id uuid,
    owner_username character varying(120),
    owner_role character varying(80),
    due_date date,
    due_date_source character varying(20),
    severity character varying(30),
    escalation_state character varying(30),
    escalation_level integer DEFAULT 0 NOT NULL,
    reason text
);


--
-- Name: tender_bid_opening_lines; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tender_bid_opening_lines (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    protocol_id uuid NOT NULL,
    rfq_id uuid NOT NULL,
    quotation_id uuid NOT NULL,
    supplier_id uuid NOT NULL,
    submitted_at timestamp without time zone,
    total_amount numeric(19,4) DEFAULT 0 NOT NULL,
    currency_code character varying(3) DEFAULT 'UZS'::character varying,
    accepted_for_evaluation boolean DEFAULT false NOT NULL,
    rejection_reason character varying(500)
);


--
-- Name: tender_bid_opening_protocols; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tender_bid_opening_protocols (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    rfq_id uuid NOT NULL,
    protocol_number character varying(80) NOT NULL,
    opened_at timestamp without time zone NOT NULL,
    opened_by character varying(120),
    quorum_required integer DEFAULT 1 NOT NULL,
    quorum_present integer DEFAULT 0 NOT NULL,
    quorum_confirmed boolean DEFAULT false NOT NULL,
    quotation_count integer DEFAULT 0 NOT NULL,
    accepted_quotation_count integer DEFAULT 0 NOT NULL,
    rejected_quotation_count integer DEFAULT 0 NOT NULL,
    minutes text,
    posted_at timestamp without time zone
);


--
-- Name: tender_commission_member_settings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tender_commission_member_settings (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    tender_commission_id uuid NOT NULL,
    commission_member_id uuid NOT NULL,
    voting_weight numeric(7,2) DEFAULT 1 NOT NULL,
    conflict_declaration_signed boolean DEFAULT false NOT NULL,
    declared_at timestamp without time zone,
    active_flag boolean DEFAULT true NOT NULL
);


--
-- Name: tender_commission_members; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tender_commission_members (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    rfq_id uuid NOT NULL,
    employee_id uuid,
    member_name character varying(240) NOT NULL,
    member_role character varying(60) DEFAULT 'MEMBER'::character varying NOT NULL,
    voting_weight numeric(7,2) DEFAULT 1 NOT NULL,
    conflict_declaration_signed boolean DEFAULT false NOT NULL,
    declared_at timestamp without time zone,
    active_flag boolean DEFAULT true NOT NULL
);


--
-- Name: tender_commissions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tender_commissions (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    rfq_id uuid NOT NULL,
    commission_id uuid NOT NULL,
    quorum_required integer,
    active_flag boolean DEFAULT true NOT NULL
);


--
-- Name: tender_conflict_checks; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tender_conflict_checks (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    rfq_id uuid NOT NULL,
    quotation_id uuid,
    supplier_id uuid NOT NULL,
    commission_member_id uuid,
    related_party_id uuid,
    conflict_found boolean DEFAULT false NOT NULL,
    conflict_type character varying(80),
    severity character varying(40) DEFAULT 'LOW'::character varying,
    details text,
    mitigation_action text,
    resolved_flag boolean DEFAULT false NOT NULL,
    checked_at timestamp without time zone NOT NULL
);


--
-- Name: tender_evaluation_protocols; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tender_evaluation_protocols (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    rfq_id uuid NOT NULL,
    protocol_number character varying(80) NOT NULL,
    evaluated_at timestamp without time zone NOT NULL,
    selection_rule character varying(50) NOT NULL,
    recommended_quotation_id uuid NOT NULL,
    recommended_supplier_id uuid NOT NULL,
    recommendation_reason text,
    scoring_summary text,
    conflict_summary text,
    approved_by character varying(120),
    approved_at timestamp without time zone,
    posted_at timestamp without time zone
);


--
-- Name: tender_price_calculation_quotations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tender_price_calculation_quotations (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    calculation_id uuid NOT NULL,
    quotation_id uuid NOT NULL,
    supplier_id uuid NOT NULL,
    total_amount numeric(19,4) NOT NULL,
    currency_code character varying(3) NOT NULL,
    CONSTRAINT chk_tender_price_calc_quote_total CHECK ((total_amount > (0)::numeric))
);


--
-- Name: tender_price_calculations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tender_price_calculations (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    procurement_procedure_id uuid NOT NULL,
    price_discovery_rfq_id uuid NOT NULL,
    amount numeric(19,4) NOT NULL,
    currency_code character varying(3) NOT NULL,
    quotation_count integer NOT NULL,
    calculated_by character varying(120),
    calculated_at timestamp without time zone NOT NULL,
    idempotency_key character varying(200),
    CONSTRAINT chk_tender_price_calculation_amount CHECK ((amount >= (0)::numeric)),
    CONSTRAINT chk_tender_price_calculation_count CHECK ((quotation_count >= 0))
);


--
-- Name: tnved_codes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tnved_codes (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    tnved_code character varying(40) NOT NULL,
    parent_code character varying(40),
    section_code character varying(40),
    unit_code character varying(40),
    duty_rate numeric(7,2),
    vat_rate numeric(7,2),
    source_system character varying(40),
    external_id character varying(120),
    last_synced_at timestamp without time zone
);


--
-- Name: transfer_allocation_clearances; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.transfer_allocation_clearances (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    allocation_id uuid NOT NULL,
    kind character varying(40) NOT NULL,
    quantity numeric(19,4) NOT NULL,
    book_value numeric(19,4) NOT NULL,
    posting_date date NOT NULL,
    operation_key character varying(160) NOT NULL,
    destination_stock_batch_id uuid,
    movement_id uuid,
    exception_id uuid,
    compensates_clearance_id uuid,
    CONSTRAINT ck_transfer_clearance_kind CHECK (((kind)::text = ANY ((ARRAY['GOOD_RECEIPT'::character varying, 'QUARANTINE_RECEIPT'::character varying, 'TRANSIT_RETURN'::character varying, 'TRANSIT_LOSS'::character varying])::text[]))),
    CONSTRAINT ck_transfer_clearance_quantity CHECK (((quantity > (0)::numeric) AND (book_value >= (0)::numeric)))
);


--
-- Name: transfer_document_lines; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.transfer_document_lines (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    company_id uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    transfer_document_id uuid NOT NULL,
    material_id uuid NOT NULL,
    planned_quantity numeric(19,4) DEFAULT 0 NOT NULL,
    dispatched_quantity numeric(19,4) DEFAULT 0 NOT NULL,
    in_transit_quantity numeric(19,4) DEFAULT 0 NOT NULL,
    received_quantity numeric(19,4) DEFAULT 0 NOT NULL,
    damaged_quantity numeric(19,4) DEFAULT 0 NOT NULL,
    missing_quantity numeric(19,4) DEFAULT 0 NOT NULL,
    unit_cost numeric(19,6) DEFAULT 0 NOT NULL,
    source_stock_batch_id uuid,
    destination_stock_batch_id uuid,
    source_storage_location_id uuid,
    destination_storage_location_id uuid,
    shortage_reason text,
    discrepancy_reason text,
    discrepancy_evidence text,
    cancelled_quantity numeric(19,4) DEFAULT 0 NOT NULL,
    CONSTRAINT transfer_document_lines_cancelled_quantity_check CHECK ((cancelled_quantity >= (0)::numeric))
);


--
-- Name: transfer_documents; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.transfer_documents (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    company_id uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    source_warehouse_id uuid NOT NULL,
    source_location_id uuid,
    destination_warehouse_id uuid NOT NULL,
    destination_location_id uuid,
    planned_date date,
    dispatched_date date,
    received_date date,
    dispatch_movement_document_id uuid,
    receive_movement_document_id uuid,
    reconciliation_status character varying(40) DEFAULT 'NOT_REQUIRED'::character varying NOT NULL,
    reconciliation_provenance text,
    return_of_transfer_id uuid
);


--
-- Name: transfer_exceptions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.transfer_exceptions (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    transfer_document_id uuid NOT NULL,
    allocation_id uuid NOT NULL,
    kind character varying(40) NOT NULL,
    quantity numeric(19,4) NOT NULL,
    book_value numeric(19,4) NOT NULL,
    revision bigint DEFAULT 1 NOT NULL,
    approval_revision bigint,
    decision character varying(80),
    reason text NOT NULL,
    reviewed_by character varying(120),
    reviewed_at timestamp without time zone,
    finance_approved_by character varying(120),
    approval_instance_id uuid,
    quarantine_clearance_id uuid,
    quarantine_lot_id uuid,
    reverse_transfer_id uuid,
    attachment_ids jsonb DEFAULT '[]'::jsonb NOT NULL,
    related_document_ids jsonb DEFAULT '[]'::jsonb NOT NULL,
    history jsonb DEFAULT '[]'::jsonb NOT NULL,
    CONSTRAINT ck_exception_kind CHECK (((kind)::text = ANY ((ARRAY['MISSING'::character varying, 'DAMAGED'::character varying])::text[]))),
    CONSTRAINT ck_exception_quantity CHECK (((quantity >= (0)::numeric) AND (book_value >= (0)::numeric)))
);


--
-- Name: transfer_shipment_allocations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.transfer_shipment_allocations (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    shipment_id uuid NOT NULL,
    transfer_document_id uuid NOT NULL,
    transfer_line_id uuid NOT NULL,
    material_id uuid NOT NULL,
    unit_of_measure_id uuid,
    unit_code character varying(80) NOT NULL,
    quantity_scale integer NOT NULL,
    source_warehouse_id uuid NOT NULL,
    source_storage_location_id uuid,
    source_storage_bin_id uuid,
    source_stock_batch_id uuid,
    batch_number character varying(120),
    serial_number character varying(160),
    expiry_date date,
    dispatched_quantity numeric(19,4) NOT NULL,
    original_value numeric(19,4) NOT NULL,
    book_value numeric(19,4) NOT NULL,
    currency_code character varying(10) NOT NULL,
    book_currency_code character varying(10) NOT NULL,
    exchange_rate numeric(19,6) NOT NULL,
    source_movement_allocation_id uuid,
    consumed_cost_layer_ids jsonb DEFAULT '[]'::jsonb NOT NULL,
    allocation_sequence integer DEFAULT 0 NOT NULL,
    source_stock_movement_id uuid,
    CONSTRAINT ck_transfer_allocation_quantity CHECK (((dispatched_quantity > (0)::numeric) AND ((quantity_scale >= 0) AND (quantity_scale <= 4)))),
    CONSTRAINT ck_transfer_allocation_value CHECK (((original_value >= (0)::numeric) AND (book_value >= (0)::numeric) AND (exchange_rate > (0)::numeric)))
);


--
-- Name: transfer_shipments; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.transfer_shipments (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    transfer_document_id uuid NOT NULL,
    dispatched_date date NOT NULL,
    operation_key character varying(160) NOT NULL,
    movement_document_id uuid
);


--
-- Name: transfer_transit_entries; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.transfer_transit_entries (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    allocation_id uuid NOT NULL,
    operation_id uuid NOT NULL,
    kind character varying(40) NOT NULL,
    posting_date date NOT NULL,
    quantity_delta numeric(19,4) NOT NULL,
    value_delta numeric(19,4) NOT NULL,
    CONSTRAINT ck_transfer_transit_direction CHECK (((((kind)::text = 'DISPATCH'::text) AND (quantity_delta > (0)::numeric) AND (value_delta >= (0)::numeric)) OR (((kind)::text = ANY ((ARRAY['GOOD_RECEIPT'::character varying, 'QUARANTINE_RECEIPT'::character varying, 'TRANSIT_RETURN'::character varying, 'TRANSIT_LOSS'::character varying])::text[])) AND (quantity_delta < (0)::numeric) AND (value_delta <= (0)::numeric))))
);


--
-- Name: tsd_offline_operations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tsd_offline_operations (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    device_id character varying(120) NOT NULL,
    client_operation_id character varying(160) NOT NULL,
    operation_type character varying(40) NOT NULL,
    payload_json text NOT NULL,
    server_operation_id uuid,
    stock_movement_id uuid,
    attempt_count integer DEFAULT 0 NOT NULL,
    last_error text,
    created_offline_at timestamp without time zone,
    synced_at timestamp without time zone
);


--
-- Name: unit_of_measures; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.unit_of_measures (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    symbol character varying(20),
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    measurement_name_short character varying(120),
    measurement_name_long character varying(240),
    unit_type character varying(40),
    measurement_name_short_ru character varying(120),
    measurement_name_long_ru character varying(240),
    measurement_name_short_uz character varying(120),
    measurement_name_long_uz character varying(240)
);


--
-- Name: user_feedback; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_feedback (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    comment_text text,
    screenshot_data_url text NOT NULL,
    route character varying(1024),
    client_created_at timestamp with time zone,
    user_agent text,
    viewport_width integer,
    viewport_height integer,
    device_pixel_ratio numeric(8,3)
);


--
-- Name: user_roles; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_roles (
    user_id uuid NOT NULL,
    role character varying(80) NOT NULL
);


--
-- Name: users; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.users (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    username character varying(120) NOT NULL,
    password_hash character varying(500) NOT NULL,
    full_name character varying(240),
    email character varying(240),
    enabled boolean NOT NULL,
    company_id uuid,
    mfa_enabled boolean DEFAULT false NOT NULL,
    mfa_secret character varying(500),
    mfa_method character varying(20) DEFAULT 'TOTP'::character varying,
    mfa_phone_number character varying(80),
    mfa_sms_code_hash character varying(128),
    mfa_sms_expires_at timestamp without time zone,
    department_id uuid,
    employee_id uuid,
    token_version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_users_token_version_nonnegative CHECK ((token_version >= 0))
);


--
-- Name: vmi_stock_agreements; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.vmi_stock_agreements (
    id uuid NOT NULL,
    tenant_id character varying(80) NOT NULL,
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    supplier_id uuid NOT NULL,
    material_id uuid NOT NULL,
    warehouse_id uuid NOT NULL,
    storage_location_id uuid,
    stock_type character varying(40) NOT NULL,
    supplier_sku character varying(120),
    min_stock numeric(19,4) DEFAULT 0,
    reorder_point numeric(19,4) DEFAULT 0,
    target_stock numeric(19,4) DEFAULT 0,
    max_stock numeric(19,4) DEFAULT 0,
    lead_time_days integer,
    review_cycle_days integer,
    ownership_transfer_event character varying(80),
    settlement_cycle character varying(80),
    valid_from date,
    valid_to date,
    last_reviewed_at timestamp without time zone,
    last_replenishment_request_at timestamp without time zone
);


--
-- Name: warehouse_inventory_reconciliation_preflight; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.warehouse_inventory_reconciliation_preflight AS
 WITH duplicate_reversals AS (
         SELECT stock_movements.tenant_id,
            stock_movements.company_id,
            stock_movements.reversal_of_id,
            count(*) AS reversal_count,
            jsonb_agg(stock_movements.id ORDER BY (stock_movements.id)::text) AS reversal_ids
           FROM public.stock_movements
          WHERE ((stock_movements.is_delete = false) AND (stock_movements.reversal_of_id IS NOT NULL))
          GROUP BY stock_movements.tenant_id, stock_movements.company_id, stock_movements.reversal_of_id
         HAVING (count(*) > 1)
        ), direct_adjustment_duplicates AS (
         SELECT stock_movements.tenant_id,
            stock_movements.company_id,
            stock_movements.source_inventory_item_id,
            count(*) AS movement_count,
            jsonb_agg(stock_movements.id ORDER BY (stock_movements.id)::text) AS movement_ids
           FROM public.stock_movements
          WHERE ((stock_movements.is_delete = false) AND (stock_movements.source_inventory_item_id IS NOT NULL))
          GROUP BY stock_movements.tenant_id, stock_movements.company_id, stock_movements.source_inventory_item_id
         HAVING (count(*) > 1)
        ), legacy_adjustment_candidates AS (
         SELECT item.tenant_id,
            item.company_id,
            item.id AS inventory_item_id,
            item.inventory_document_id,
            count(movement.id) AS candidate_count,
            jsonb_agg(movement.id ORDER BY (movement.id)::text) AS movement_ids
           FROM ((public.inventory_items item
             JOIN public.inventory_documents parent_document ON (((parent_document.id = item.inventory_document_id) AND ((parent_document.tenant_id)::text = (item.tenant_id)::text) AND (parent_document.company_id = item.company_id) AND (parent_document.is_delete = false))))
             JOIN public.stock_movements movement ON ((((movement.tenant_id)::text = (item.tenant_id)::text) AND (movement.company_id = item.company_id) AND (movement.reference_id = item.inventory_document_id) AND (regexp_replace(upper((COALESCE(movement.reference_type, ''::character varying))::text), '[^A-Z]'::text, ''::text, 'g'::text) = 'INVENTORYDOCUMENT'::text) AND (upper((COALESCE(movement.movement_type, ''::character varying))::text) = ANY (ARRAY['ADJUSTMENT'::text, 'WRITE_OFF'::text])) AND (upper((COALESCE(movement.status, ''::character varying))::text) = ANY (ARRAY['APPROVED'::text, 'POSTED'::text])) AND (movement.material_id = item.material_id) AND (NOT (movement.warehouse_id IS DISTINCT FROM COALESCE(item.warehouse_id, parent_document.warehouse_id))) AND (NOT (movement.storage_location_id IS DISTINCT FROM item.storage_location_id)) AND (NOT (movement.storage_bin_id IS DISTINCT FROM item.storage_bin_id)) AND (NOT (movement.stock_batch_id IS DISTINCT FROM item.stock_batch_id)) AND (movement.is_delete = false))))
          WHERE (item.is_delete = false)
          GROUP BY item.tenant_id, item.company_id, item.id, item.inventory_document_id
        ), reservation_key_totals AS (
         SELECT material_reservations.tenant_id,
            material_reservations.company_id,
            material_reservations.material_id,
            material_reservations.warehouse_id,
            material_reservations.storage_location_id,
            material_reservations.storage_bin_id,
            material_reservations.stock_batch_id,
            sum(COALESCE(material_reservations.reserved_quantity, (0)::numeric)) AS reserved_quantity,
            array_agg(material_reservations.id ORDER BY (material_reservations.id)::text) AS reservation_ids,
            (array_agg(material_reservations.id ORDER BY (material_reservations.id)::text))[1] AS representative_reservation_id
           FROM public.material_reservations
          WHERE ((material_reservations.is_delete = false) AND (upper((COALESCE(material_reservations.status, ''::character varying))::text) = ANY (ARRAY['ACTIVE'::text, 'PARTIALLY_RELEASED'::text])))
          GROUP BY material_reservations.tenant_id, material_reservations.company_id, material_reservations.material_id, material_reservations.warehouse_id, material_reservations.storage_location_id, material_reservations.storage_bin_id, material_reservations.stock_batch_id
        ), balance_key_totals AS (
         SELECT stock_balances.tenant_id,
            stock_balances.company_id,
            stock_balances.material_id,
            stock_balances.warehouse_id,
            stock_balances.storage_location_id,
            stock_balances.storage_bin_id,
            stock_balances.stock_batch_id,
            sum(COALESCE(stock_balances.reserved_quantity, (0)::numeric)) AS reserved_quantity,
            array_agg(stock_balances.id ORDER BY (stock_balances.id)::text) AS balance_ids,
            (array_agg(stock_balances.id ORDER BY (stock_balances.id)::text))[1] AS representative_balance_id
           FROM public.stock_balances
          WHERE (stock_balances.is_delete = false)
          GROUP BY stock_balances.tenant_id, stock_balances.company_id, stock_balances.material_id, stock_balances.warehouse_id, stock_balances.storage_location_id, stock_balances.storage_bin_id, stock_balances.stock_batch_id
        ), reservation_balance_reconciliation AS (
         SELECT COALESCE(reservation.tenant_id, balance.tenant_id) AS tenant_id,
            COALESCE(reservation.company_id, balance.company_id) AS company_id,
            COALESCE(reservation.material_id, balance.material_id) AS material_id,
            COALESCE(reservation.warehouse_id, balance.warehouse_id) AS warehouse_id,
            COALESCE(reservation.storage_location_id, balance.storage_location_id) AS storage_location_id,
            COALESCE(reservation.storage_bin_id, balance.storage_bin_id) AS storage_bin_id,
            COALESCE(reservation.stock_batch_id, balance.stock_batch_id) AS stock_batch_id,
            COALESCE(reservation.reserved_quantity, (0)::numeric) AS reservation_quantity,
            COALESCE(balance.reserved_quantity, (0)::numeric) AS balance_quantity,
            reservation.reservation_ids,
            balance.balance_ids,
            reservation.representative_reservation_id,
            balance.representative_balance_id
           FROM (reservation_key_totals reservation
             FULL JOIN balance_key_totals balance ON ((((balance.tenant_id)::text = (reservation.tenant_id)::text) AND (balance.company_id = reservation.company_id) AND (balance.material_id = reservation.material_id) AND (balance.warehouse_id = reservation.warehouse_id) AND (COALESCE((balance.storage_location_id)::text, '<NULL>'::text) = COALESCE((reservation.storage_location_id)::text, '<NULL>'::text)) AND (COALESCE((balance.storage_bin_id)::text, '<NULL>'::text) = COALESCE((reservation.storage_bin_id)::text, '<NULL>'::text)) AND (COALESCE((balance.stock_batch_id)::text, '<NULL>'::text) = COALESCE((reservation.stock_batch_id)::text, '<NULL>'::text)))))
          WHERE (COALESCE(reservation.reserved_quantity, (0)::numeric) <> COALESCE(balance.reserved_quantity, (0)::numeric))
        ), bin_resolution_subjects AS (
         SELECT reservation.tenant_id,
            reservation.company_id,
            'MATERIAL_RESERVATION'::text AS entity_type,
            reservation.id AS entity_id,
            reservation.reference_id AS related_entity_id,
            reservation.material_id,
            reservation.warehouse_id,
            reservation.storage_location_id,
            reservation.storage_bin_id AS persisted_storage_bin_id,
            reservation.stock_batch_id
           FROM public.material_reservations reservation
          WHERE ((reservation.is_delete = false) AND ((reservation.storage_bin_id IS NULL) OR (NOT (EXISTS ( SELECT 1
                   FROM public.storage_bins persisted_bin
                  WHERE ((persisted_bin.id = reservation.storage_bin_id) AND ((persisted_bin.tenant_id)::text = (reservation.tenant_id)::text) AND (persisted_bin.company_id = reservation.company_id) AND (persisted_bin.warehouse_id = reservation.warehouse_id) AND (NOT (persisted_bin.storage_location_id IS DISTINCT FROM reservation.storage_location_id)) AND (persisted_bin.is_delete = false)))))))
        UNION ALL
         SELECT balance.tenant_id,
            balance.company_id,
            'STOCK_BALANCE'::text AS text,
            balance.id,
            NULL::uuid AS uuid,
            balance.material_id,
            balance.warehouse_id,
            balance.storage_location_id,
            balance.storage_bin_id,
            balance.stock_batch_id
           FROM public.stock_balances balance
          WHERE ((balance.is_delete = false) AND ((balance.storage_bin_id IS NULL) OR (NOT (EXISTS ( SELECT 1
                   FROM public.storage_bins persisted_bin
                  WHERE ((persisted_bin.id = balance.storage_bin_id) AND ((persisted_bin.tenant_id)::text = (balance.tenant_id)::text) AND (persisted_bin.company_id = balance.company_id) AND (persisted_bin.warehouse_id = balance.warehouse_id) AND (NOT (persisted_bin.storage_location_id IS DISTINCT FROM balance.storage_location_id)) AND (persisted_bin.is_delete = false)))))))
        ), bin_resolution AS (
         SELECT subject.tenant_id,
            subject.company_id,
            subject.entity_type,
            subject.entity_id,
            subject.related_entity_id,
            subject.material_id,
            subject.warehouse_id,
            subject.storage_location_id,
            subject.persisted_storage_bin_id,
            subject.stock_batch_id,
            ( SELECT count(*) AS count
                   FROM public.storage_bins candidate_bin
                  WHERE (((candidate_bin.tenant_id)::text = (subject.tenant_id)::text) AND (candidate_bin.company_id = subject.company_id) AND (candidate_bin.warehouse_id = subject.warehouse_id) AND ((subject.storage_location_id IS NULL) OR (candidate_bin.storage_location_id = subject.storage_location_id)) AND (candidate_bin.is_delete = false))) AS candidate_bin_count,
            ( SELECT jsonb_agg(candidate_bin.id ORDER BY (candidate_bin.id)::text) AS jsonb_agg
                   FROM public.storage_bins candidate_bin
                  WHERE (((candidate_bin.tenant_id)::text = (subject.tenant_id)::text) AND (candidate_bin.company_id = subject.company_id) AND (candidate_bin.warehouse_id = subject.warehouse_id) AND ((subject.storage_location_id IS NULL) OR (candidate_bin.storage_location_id = subject.storage_location_id)) AND (candidate_bin.is_delete = false))) AS candidate_bin_ids,
            ( SELECT count(*) AS count
                   FROM public.stock_balances candidate_balance
                  WHERE (((candidate_balance.tenant_id)::text = (subject.tenant_id)::text) AND (candidate_balance.company_id = subject.company_id) AND (candidate_balance.material_id = subject.material_id) AND (candidate_balance.warehouse_id = subject.warehouse_id) AND ((subject.storage_location_id IS NULL) OR (NOT (candidate_balance.storage_location_id IS DISTINCT FROM subject.storage_location_id))) AND (NOT (candidate_balance.stock_batch_id IS DISTINCT FROM subject.stock_batch_id)) AND (candidate_balance.is_delete = false))) AS candidate_balance_count,
            ( SELECT count(*) AS count
                   FROM public.stock_balances candidate_balance
                  WHERE (((candidate_balance.tenant_id)::text = (subject.tenant_id)::text) AND (candidate_balance.company_id = subject.company_id) AND (candidate_balance.material_id = subject.material_id) AND (candidate_balance.warehouse_id = subject.warehouse_id) AND ((subject.storage_location_id IS NULL) OR (NOT (candidate_balance.storage_location_id IS DISTINCT FROM subject.storage_location_id))) AND (NOT (candidate_balance.stock_batch_id IS DISTINCT FROM subject.stock_batch_id)) AND (candidate_balance.storage_bin_id IS NULL) AND (candidate_balance.is_delete = false))) AS incomplete_balance_key_count
           FROM bin_resolution_subjects subject
        )
 SELECT 'AMBIGUOUS_APPROVED_MOVEMENT'::text AS issue_type,
    movement.tenant_id,
    movement.company_id,
    'STOCK_MOVEMENT'::text AS entity_type,
    movement.id AS entity_id,
    movement.document_id AS related_entity_id,
    jsonb_build_object('status', movement.status, 'postedAt', movement.posted_at, 'reason', 'Legacy approval may already have changed stock; no safe status inference is possible') AS details,
    'Reconcile stock, batch and cost evidence before enabling approval/posting separation'::text AS recommended_action
   FROM public.stock_movements movement
  WHERE ((movement.is_delete = false) AND (upper((COALESCE(movement.status, ''::character varying))::text) = 'APPROVED'::text))
UNION ALL
 SELECT 'DUPLICATE_REVERSAL_LINEAGE'::text AS issue_type,
    duplicate.tenant_id,
    duplicate.company_id,
    'STOCK_MOVEMENT'::text AS entity_type,
    duplicate.reversal_of_id AS entity_id,
    NULL::uuid AS related_entity_id,
    jsonb_build_object('reversalCount', duplicate.reversal_count, 'reversalIds', duplicate.reversal_ids) AS details,
    'Identify the authoritative reversal before adding active-lineage uniqueness'::text AS recommended_action
   FROM duplicate_reversals duplicate
UNION ALL
 SELECT 'AMBIGUOUS_ZERO_OR_NULL_COUNT'::text AS issue_type,
    item.tenant_id,
    item.company_id,
    'INVENTORY_ITEM'::text AS entity_type,
    item.id AS entity_id,
    item.inventory_document_id AS related_entity_id,
    jsonb_build_object('countedQuantity', item.counted_quantity, 'lineStatus', item.line_status, 'countedBy', item.counted_by, 'countedAt', item.counted_at, 'reason',
        CASE
            WHEN (item.counted_quantity IS NULL) THEN 'Null count has no trustworthy classification'::text
            ELSE 'Zero count has neither classification nor actor/time evidence'::text
        END) AS details,
    'Classify as counted zero, not counted, not found or excluded using source evidence'::text AS recommended_action
   FROM public.inventory_items item
  WHERE ((item.is_delete = false) AND (upper((COALESCE(item.line_status, ''::character varying))::text) <> 'NOT_COUNTED'::text) AND ((item.counted_quantity IS NULL) OR ((item.counted_quantity = (0)::numeric) AND (item.counted_at IS NULL) AND (NULLIF(TRIM(BOTH FROM item.counted_by), ''::text) IS NULL) AND (upper((COALESCE(item.line_status, ''::character varying))::text) <> ALL (ARRAY['COUNTED'::text, 'MATCHED'::text])))))
UNION ALL
 SELECT 'DUPLICATE_ADJUSTMENT_LINEAGE'::text AS issue_type,
    duplicate.tenant_id,
    duplicate.company_id,
    'INVENTORY_ITEM'::text AS entity_type,
    duplicate.source_inventory_item_id AS entity_id,
    NULL::uuid AS related_entity_id,
    jsonb_build_object('candidateSource', 'SOURCE_INVENTORY_ITEM_ID', 'movementCount', duplicate.movement_count, 'movementIds', duplicate.movement_ids) AS details,
    'Choose the authoritative adjustment before enforcing one movement per inventory item'::text AS recommended_action
   FROM direct_adjustment_duplicates duplicate
UNION ALL
 SELECT 'DUPLICATE_ADJUSTMENT_LINEAGE'::text AS issue_type,
    candidate.tenant_id,
    candidate.company_id,
    'INVENTORY_ITEM'::text AS entity_type,
    candidate.inventory_item_id AS entity_id,
    candidate.inventory_document_id AS related_entity_id,
    jsonb_build_object('candidateSource', 'LEGACY_DOCUMENT_REFERENCE_EXACT_STOCK_KEY', 'movementCount', candidate.candidate_count, 'movementIds', candidate.movement_ids) AS details,
    'Reconcile duplicate legacy candidates; the migration does not guess the authoritative link'::text AS recommended_action
   FROM legacy_adjustment_candidates candidate
  WHERE (candidate.candidate_count > 1)
UNION ALL
 SELECT 'AMBIGUOUS_ADJUSTMENT_LINEAGE'::text AS issue_type,
    candidate.tenant_id,
    candidate.company_id,
    'INVENTORY_ITEM'::text AS entity_type,
    candidate.inventory_item_id AS entity_id,
    candidate.inventory_document_id AS related_entity_id,
    jsonb_build_object('candidateSource', 'LEGACY_DOCUMENT_REFERENCE_EXACT_STOCK_KEY', 'movementCount', candidate.candidate_count, 'movementIds', candidate.movement_ids) AS details,
    'Verify the single legacy candidate before persisting authoritative lineage'::text AS recommended_action
   FROM legacy_adjustment_candidates candidate
  WHERE (candidate.candidate_count = 1)
UNION ALL
 SELECT 'MISSING_ADJUSTMENT_LINEAGE'::text AS issue_type,
    item.tenant_id,
    item.company_id,
    'INVENTORY_ITEM'::text AS entity_type,
    item.id AS entity_id,
    item.inventory_document_id AS related_entity_id,
    jsonb_build_object('documentStatus', adjustment_document.status, 'differenceQuantity', item.difference_quantity, 'lineStatus', item.line_status, 'legacyStatus', item.status, 'decisionType', item.decision_type) AS details,
    'Reconcile the completed document discrepancy and its missing adjustment movement'::text AS recommended_action
   FROM ((public.inventory_items item
     JOIN public.inventory_documents adjustment_document ON (((adjustment_document.id = item.inventory_document_id) AND ((adjustment_document.tenant_id)::text = (item.tenant_id)::text) AND (adjustment_document.company_id = item.company_id) AND (adjustment_document.is_delete = false))))
     LEFT JOIN legacy_adjustment_candidates candidate ON ((((candidate.tenant_id)::text = (item.tenant_id)::text) AND (candidate.company_id = item.company_id) AND (candidate.inventory_item_id = item.id))))
  WHERE ((item.is_delete = false) AND (upper((COALESCE(adjustment_document.status, ''::character varying))::text) = ANY (ARRAY['ADJUSTMENT_POSTED'::text, 'CLOSED'::text])) AND (COALESCE(item.difference_quantity, (0)::numeric) <> (0)::numeric) AND (item.linked_stock_movement_id IS NULL) AND (candidate.inventory_item_id IS NULL) AND (NOT (EXISTS ( SELECT 1
           FROM public.stock_movements direct_lineage
          WHERE (((direct_lineage.tenant_id)::text = (item.tenant_id)::text) AND (direct_lineage.company_id = item.company_id) AND (direct_lineage.source_inventory_item_id = item.id) AND (direct_lineage.is_delete = false))))))
UNION ALL
 SELECT 'ACTIVE_SOFT_DELETED_RESERVATION'::text AS issue_type,
    reservation.tenant_id,
    reservation.company_id,
    'MATERIAL_RESERVATION'::text AS entity_type,
    reservation.id AS entity_id,
    reservation.reference_id AS related_entity_id,
    jsonb_build_object('status', reservation.status, 'reservedQuantity', reservation.reserved_quantity, 'deleted', reservation.is_delete) AS details,
    'Restore and close through a terminal command, or reconcile the reserved balance manually'::text AS recommended_action
   FROM public.material_reservations reservation
  WHERE ((reservation.is_delete = true) AND (upper((COALESCE(reservation.status, ''::character varying))::text) = ANY (ARRAY['ACTIVE'::text, 'PARTIALLY_RELEASED'::text])) AND (COALESCE(reservation.reserved_quantity, (0)::numeric) <> (0)::numeric))
UNION ALL
 SELECT
        CASE
            WHEN (resolution.persisted_storage_bin_id IS NOT NULL) THEN 'UNRESOLVABLE_BIN_MAPPING'::text
            WHEN (resolution.candidate_bin_count = 0) THEN 'UNRESOLVABLE_BIN_MAPPING'::text
            WHEN (resolution.candidate_bin_count = 1) THEN 'DERIVABLE_BIN_NOT_PERSISTED'::text
            ELSE 'AMBIGUOUS_BIN_MAPPING'::text
        END AS issue_type,
    resolution.tenant_id,
    resolution.company_id,
    resolution.entity_type,
    resolution.entity_id,
    resolution.related_entity_id,
    jsonb_build_object('warehouseId', resolution.warehouse_id, 'storageLocationId', resolution.storage_location_id, 'persistedStorageBinId', resolution.persisted_storage_bin_id, 'candidateBinCount', resolution.candidate_bin_count, 'candidateBinIds', resolution.candidate_bin_ids, 'candidateBalanceCount', resolution.candidate_balance_count, 'incompleteBalanceKeyCount', resolution.incomplete_balance_key_count) AS details,
        CASE
            WHEN (resolution.persisted_storage_bin_id IS NOT NULL) THEN 'Repair the invalid persisted bin reference using verified physical evidence'::text
            WHEN (resolution.candidate_bin_count = 0) THEN 'Create or identify a warehouse-scoped physical bin before cutover'::text
            WHEN (resolution.candidate_bin_count = 1) THEN 'Verify and persist the sole warehouse-scoped bin candidate'::text
            ELSE 'Select the authoritative warehouse-scoped bin from the reported candidates'::text
        END AS recommended_action
   FROM bin_resolution resolution
UNION ALL
 SELECT 'RESERVATION_BALANCE_MISMATCH'::text AS issue_type,
    mismatch.tenant_id,
    mismatch.company_id,
    'RESERVATION_BALANCE_KEY'::text AS entity_type,
    COALESCE(mismatch.representative_reservation_id, mismatch.representative_balance_id) AS entity_id,
        CASE
            WHEN (mismatch.representative_reservation_id IS NOT NULL) THEN mismatch.representative_balance_id
            ELSE NULL::uuid
        END AS related_entity_id,
    jsonb_build_object('materialId', mismatch.material_id, 'warehouseId', mismatch.warehouse_id, 'storageLocationId', mismatch.storage_location_id, 'storageBinId', mismatch.storage_bin_id, 'stockBatchId', mismatch.stock_batch_id, 'activeReservationQuantity', mismatch.reservation_quantity, 'balanceReservedQuantity', mismatch.balance_quantity, 'reservationIds', mismatch.reservation_ids, 'balanceIds', mismatch.balance_ids) AS details,
    'Reconcile both sides of the exact reservation/balance stock key'::text AS recommended_action
   FROM reservation_balance_reconciliation mismatch;


--
-- Name: warehouses; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.warehouses (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint DEFAULT 0 NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    address character varying(500),
    company_id uuid DEFAULT '00000000-0000-0000-0000-000000000001'::uuid NOT NULL
);


--
-- Name: work_completion_acts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.work_completion_acts (
    id uuid NOT NULL,
    tenant_id character varying(80) DEFAULT 'default'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    created_by character varying(120),
    updated_by character varying(120),
    version bigint NOT NULL,
    is_delete boolean DEFAULT false NOT NULL,
    company_id uuid NOT NULL,
    code character varying(80),
    name text NOT NULL,
    status character varying(50),
    description text,
    purchase_order_id uuid NOT NULL,
    purchase_order_item_id uuid NOT NULL,
    contract_id uuid NOT NULL,
    work_location character varying(240),
    milestone character varying(120),
    period_start date,
    period_end date,
    completed_quantity numeric(19,4) NOT NULL,
    amount numeric(19,4) NOT NULL,
    inspection_result character varying(40),
    accepted_at timestamp without time zone,
    accepted_by character varying(120),
    commission_notes text,
    remarks text,
    document_reviewed_at timestamp without time zone,
    finance_reviewed_at timestamp without time zone,
    sent_to_payment_at timestamp without time zone,
    paid_at timestamp without time zone,
    contract_consumed_at timestamp without time zone,
    payment_reference character varying(120)
);


--
-- Name: country_code id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.country_code ALTER COLUMN id SET DEFAULT nextval('public.country_code_id_seq'::regclass);


--
-- Name: advance_shipping_notice_batches advance_shipping_notice_batches_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.advance_shipping_notice_batches
    ADD CONSTRAINT advance_shipping_notice_batches_pkey PRIMARY KEY (id);


--
-- Name: advance_shipping_notice_items advance_shipping_notice_items_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.advance_shipping_notice_items
    ADD CONSTRAINT advance_shipping_notice_items_pkey PRIMARY KEY (id);


--
-- Name: advance_shipping_notice_serials advance_shipping_notice_serials_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.advance_shipping_notice_serials
    ADD CONSTRAINT advance_shipping_notice_serials_pkey PRIMARY KEY (id);


--
-- Name: advance_shipping_notices advance_shipping_notices_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.advance_shipping_notices
    ADD CONSTRAINT advance_shipping_notices_pkey PRIMARY KEY (id);


--
-- Name: ai_analysis_jobs ai_analysis_jobs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ai_analysis_jobs
    ADD CONSTRAINT ai_analysis_jobs_pkey PRIMARY KEY (id);


--
-- Name: approval_flow_parallel_assignees approval_flow_parallel_assignees_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.approval_flow_parallel_assignees
    ADD CONSTRAINT approval_flow_parallel_assignees_pkey PRIMARY KEY (id);


--
-- Name: approval_flow_steps approval_flow_steps_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.approval_flow_steps
    ADD CONSTRAINT approval_flow_steps_pkey PRIMARY KEY (id);


--
-- Name: approval_flows approval_flows_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.approval_flows
    ADD CONSTRAINT approval_flows_pkey PRIMARY KEY (id);


--
-- Name: approval_history approval_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.approval_history
    ADD CONSTRAINT approval_history_pkey PRIMARY KEY (id);


--
-- Name: approval_instances approval_instances_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.approval_instances
    ADD CONSTRAINT approval_instances_pkey PRIMARY KEY (id);


--
-- Name: approval_steps approval_steps_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.approval_steps
    ADD CONSTRAINT approval_steps_pkey PRIMARY KEY (id);


--
-- Name: approval_tasks approval_tasks_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.approval_tasks
    ADD CONSTRAINT approval_tasks_pkey PRIMARY KEY (id);


--
-- Name: approval_workflows approval_workflows_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.approval_workflows
    ADD CONSTRAINT approval_workflows_pkey PRIMARY KEY (id);


--
-- Name: asset_acceptance_lines asset_acceptance_lines_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.asset_acceptance_lines
    ADD CONSTRAINT asset_acceptance_lines_pkey PRIMARY KEY (id);


--
-- Name: asset_acceptance_units asset_acceptance_units_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.asset_acceptance_units
    ADD CONSTRAINT asset_acceptance_units_pkey PRIMARY KEY (id);


--
-- Name: asset_acceptances asset_acceptances_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.asset_acceptances
    ADD CONSTRAINT asset_acceptances_pkey PRIMARY KEY (id);


--
-- Name: asset_allocations asset_allocations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.asset_allocations
    ADD CONSTRAINT asset_allocations_pkey PRIMARY KEY (id);


--
-- Name: asset_depreciation_entries asset_depreciation_entries_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.asset_depreciation_entries
    ADD CONSTRAINT asset_depreciation_entries_pkey PRIMARY KEY (id);


--
-- Name: asset_events asset_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.asset_events
    ADD CONSTRAINT asset_events_pkey PRIMARY KEY (id);


--
-- Name: asset_handover_lines asset_handover_lines_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.asset_handover_lines
    ADD CONSTRAINT asset_handover_lines_pkey PRIMARY KEY (id);


--
-- Name: asset_handovers asset_handovers_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.asset_handovers
    ADD CONSTRAINT asset_handovers_pkey PRIMARY KEY (id);


--
-- Name: asset_management_settings asset_management_settings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.asset_management_settings
    ADD CONSTRAINT asset_management_settings_pkey PRIMARY KEY (id);


--
-- Name: asset_number_registry asset_number_registry_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.asset_number_registry
    ADD CONSTRAINT asset_number_registry_pkey PRIMARY KEY (id);


--
-- Name: asset_opening_import_lines asset_opening_import_lines_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.asset_opening_import_lines
    ADD CONSTRAINT asset_opening_import_lines_pkey PRIMARY KEY (id);


--
-- Name: asset_opening_imports asset_opening_imports_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.asset_opening_imports
    ADD CONSTRAINT asset_opening_imports_pkey PRIMARY KEY (id);


--
-- Name: asset_verification_lines asset_verification_lines_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.asset_verification_lines
    ADD CONSTRAINT asset_verification_lines_pkey PRIMARY KEY (id);


--
-- Name: asset_verifications asset_verifications_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.asset_verifications
    ADD CONSTRAINT asset_verifications_pkey PRIMARY KEY (id);


--
-- Name: attachments attachments_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.attachments
    ADD CONSTRAINT attachments_pkey PRIMARY KEY (id);


--
-- Name: audit_logs audit_logs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_logs
    ADD CONSTRAINT audit_logs_pkey PRIMARY KEY (id);


--
-- Name: banks banks_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.banks
    ADD CONSTRAINT banks_pkey PRIMARY KEY (id);


--
-- Name: budget_items budget_items_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.budget_items
    ADD CONSTRAINT budget_items_pkey PRIMARY KEY (id);


--
-- Name: budget_transactions budget_transactions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.budget_transactions
    ADD CONSTRAINT budget_transactions_pkey PRIMARY KEY (id);


--
-- Name: chart_of_accounts chart_of_accounts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chart_of_accounts
    ADD CONSTRAINT chart_of_accounts_pkey PRIMARY KEY (id);


--
-- Name: service_acceptance_acts chk_service_acceptance_acts_amount_positive; Type: CHECK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE public.service_acceptance_acts
    ADD CONSTRAINT chk_service_acceptance_acts_amount_positive CHECK ((amount > (0)::numeric)) NOT VALID;


--
-- Name: service_acceptance_acts chk_service_acceptance_acts_paid_reference; Type: CHECK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE public.service_acceptance_acts
    ADD CONSTRAINT chk_service_acceptance_acts_paid_reference CHECK ((((status)::text <> 'PAID'::text) OR ((payment_reference IS NOT NULL) AND (btrim((payment_reference)::text) <> ''::text)))) NOT VALID;


--
-- Name: service_acceptance_acts chk_service_acceptance_acts_vat; Type: CHECK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE public.service_acceptance_acts
    ADD CONSTRAINT chk_service_acceptance_acts_vat CHECK (((COALESCE(vat_amount, (0)::numeric) >= (0)::numeric) AND (COALESCE(vat_rate, (0)::numeric) >= (0)::numeric) AND (COALESCE(vat_rate, (0)::numeric) <= (100)::numeric) AND (COALESCE(vat_amount, (0)::numeric) <= amount) AND ((COALESCE(vat_rate, (0)::numeric) > (0)::numeric) OR (COALESCE(vat_amount, (0)::numeric) = (0)::numeric)))) NOT VALID;


--
-- Name: service_claims chk_service_claims_penalty_nonnegative; Type: CHECK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE public.service_claims
    ADD CONSTRAINT chk_service_claims_penalty_nonnegative CHECK ((COALESCE(penalty_amount, (0)::numeric) >= (0)::numeric)) NOT VALID;


--
-- Name: service_requests chk_service_requests_acceptance_mode; Type: CHECK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE public.service_requests
    ADD CONSTRAINT chk_service_requests_acceptance_mode CHECK (((acceptance_mode)::text = ANY ((ARRAY['AMOUNT'::character varying, 'QUANTITY'::character varying])::text[]))) NOT VALID;


--
-- Name: service_requests chk_service_requests_acceptance_values; Type: CHECK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE public.service_requests
    ADD CONSTRAINT chk_service_requests_acceptance_values CHECK (((((acceptance_mode)::text = 'AMOUNT'::text) AND ((ordered_amount IS NULL) OR (ordered_amount > (0)::numeric)) AND (COALESCE(accepted_amount, (0)::numeric) >= (0)::numeric) AND (COALESCE(rejected_amount, (0)::numeric) >= (0)::numeric) AND ((ordered_amount IS NULL) OR ((COALESCE(accepted_amount, (0)::numeric) + COALESCE(rejected_amount, (0)::numeric)) <= ordered_amount)) AND (ordered_quantity IS NULL) AND (accepted_quantity IS NULL) AND (rejected_quantity IS NULL) AND (uom_code IS NULL) AND ((currency_code IS NULL) OR (btrim((currency_code)::text) <> ''::text))) OR (((acceptance_mode)::text = 'QUANTITY'::text) AND (ordered_quantity > (0)::numeric) AND (COALESCE(accepted_quantity, (0)::numeric) >= (0)::numeric) AND (COALESCE(rejected_quantity, (0)::numeric) >= (0)::numeric) AND ((COALESCE(accepted_quantity, (0)::numeric) + COALESCE(rejected_quantity, (0)::numeric)) <= ordered_quantity) AND (ordered_amount IS NULL) AND (accepted_amount IS NULL) AND (rejected_amount IS NULL) AND (currency_code IS NULL) AND (uom_code IS NOT NULL) AND (btrim((uom_code)::text) <> ''::text)))) NOT VALID;


--
-- Name: service_requests chk_service_requests_planned_period; Type: CHECK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE public.service_requests
    ADD CONSTRAINT chk_service_requests_planned_period CHECK (((planned_end_date IS NULL) OR (planned_start_date IS NULL) OR (planned_end_date >= planned_start_date))) NOT VALID;


--
-- Name: supplier_unblock_requests chk_supplier_unblock_maker_checker; Type: CHECK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE public.supplier_unblock_requests
    ADD CONSTRAINT chk_supplier_unblock_maker_checker CHECK (((decided_by IS NULL) OR (decided_by <> requested_by))) NOT VALID;


--
-- Name: work_completion_acts chk_work_completion_acts_paid_reference; Type: CHECK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE public.work_completion_acts
    ADD CONSTRAINT chk_work_completion_acts_paid_reference CHECK ((((status)::text <> 'PAID'::text) OR ((payment_reference IS NOT NULL) AND (btrim((payment_reference)::text) <> ''::text)))) NOT VALID;


--
-- Name: material_duplicate_candidates ck_material_duplicate_candidate_status; Type: CHECK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE public.material_duplicate_candidates
    ADD CONSTRAINT ck_material_duplicate_candidate_status CHECK (((status)::text = ANY ((ARRAY['OPEN'::character varying, 'CONFIRMED'::character varying, 'REJECTED'::character varying, 'MERGED'::character varying])::text[]))) NOT VALID;


--
-- Name: material_master_requests ck_material_master_request_status; Type: CHECK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE public.material_master_requests
    ADD CONSTRAINT ck_material_master_request_status CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'SUBMITTED'::character varying, 'IN_REVIEW'::character varying, 'APPROVED'::character varying, 'REJECTED'::character varying, 'APPLIED'::character varying, 'CANCELLED'::character varying])::text[]))) NOT VALID;


--
-- Name: material_quality_issues ck_material_quality_issue_status; Type: CHECK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE public.material_quality_issues
    ADD CONSTRAINT ck_material_quality_issue_status CHECK (((status)::text = ANY ((ARRAY['OPEN'::character varying, 'ASSIGNED'::character varying, 'IN_PROGRESS'::character varying, 'RESOLVED'::character varying, 'IGNORED'::character varying])::text[]))) NOT VALID;


--
-- Name: material_reservations ck_material_reservation_lifecycle_quantities; Type: CHECK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE public.material_reservations
    ADD CONSTRAINT ck_material_reservation_lifecycle_quantities CHECK (((quantity >= (0)::numeric) AND (reserved_quantity >= (0)::numeric) AND (released_quantity >= (0)::numeric) AND (COALESCE(consumed_quantity, (0)::numeric) >= (0)::numeric) AND (COALESCE(cancelled_quantity, (0)::numeric) >= (0)::numeric) AND (COALESCE(expired_quantity, (0)::numeric) >= (0)::numeric) AND (((((reserved_quantity + released_quantity) + COALESCE(consumed_quantity, (0)::numeric)) + COALESCE(cancelled_quantity, (0)::numeric)) + COALESCE(expired_quantity, (0)::numeric)) = quantity))) NOT VALID;


--
-- Name: material_reservations ck_material_reservation_terminal_evidence; Type: CHECK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE public.material_reservations
    ADD CONSTRAINT ck_material_reservation_terminal_evidence CHECK ((((status)::text <> ALL ((ARRAY['RELEASED'::character varying, 'CONSUMED'::character varying, 'EXPIRED'::character varying, 'CANCELLED'::character varying])::text[])) OR ((reserved_quantity = (0)::numeric) AND (terminal_at IS NOT NULL) AND (terminal_by IS NOT NULL) AND (terminal_idempotency_key IS NOT NULL)))) NOT VALID;


--
-- Name: stock_movement_documents ck_stock_movement_documents_movement_type; Type: CHECK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE public.stock_movement_documents
    ADD CONSTRAINT ck_stock_movement_documents_movement_type CHECK (((movement_type)::text = ANY ((ARRAY['RECEIPT'::character varying, 'ISSUE'::character varying, 'TRANSFER'::character varying, 'RETURN'::character varying, 'WRITE_OFF'::character varying, 'ADJUSTMENT'::character varying, 'REVERSAL'::character varying])::text[]))) NOT VALID;


--
-- Name: classifier_types classifier_types_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.classifier_types
    ADD CONSTRAINT classifier_types_pkey PRIMARY KEY (id);


--
-- Name: classifiers classifiers_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.classifiers
    ADD CONSTRAINT classifiers_pkey PRIMARY KEY (id);


--
-- Name: commission_members commission_members_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.commission_members
    ADD CONSTRAINT commission_members_pkey PRIMARY KEY (id);


--
-- Name: commissions commissions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.commissions
    ADD CONSTRAINT commissions_pkey PRIMARY KEY (id);


--
-- Name: companies companies_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.companies
    ADD CONSTRAINT companies_pkey PRIMARY KEY (id);


--
-- Name: consignment_stock_transactions consignment_stock_transactions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.consignment_stock_transactions
    ADD CONSTRAINT consignment_stock_transactions_pkey PRIMARY KEY (id);


--
-- Name: consolidated_demands consolidated_demands_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.consolidated_demands
    ADD CONSTRAINT consolidated_demands_pkey PRIMARY KEY (id);


--
-- Name: contract_addendums contract_addendums_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.contract_addendums
    ADD CONSTRAINT contract_addendums_pkey PRIMARY KEY (id);


--
-- Name: contract_invoice_evidence_snapshots contract_invoice_evidence_snapshots_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.contract_invoice_evidence_snapshots
    ADD CONSTRAINT contract_invoice_evidence_snapshots_pkey PRIMARY KEY (id);


--
-- Name: contract_invoices contract_invoices_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.contract_invoices
    ADD CONSTRAINT contract_invoices_pkey PRIMARY KEY (id);


--
-- Name: contract_payments contract_payments_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.contract_payments
    ADD CONSTRAINT contract_payments_pkey PRIMARY KEY (id);


--
-- Name: contract_powers_of_attorney contract_powers_of_attorney_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.contract_powers_of_attorney
    ADD CONSTRAINT contract_powers_of_attorney_pkey PRIMARY KEY (id);


--
-- Name: contract_reconciliation_acts contract_reconciliation_acts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.contract_reconciliation_acts
    ADD CONSTRAINT contract_reconciliation_acts_pkey PRIMARY KEY (id);


--
-- Name: contract_specifications contract_specifications_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.contract_specifications
    ADD CONSTRAINT contract_specifications_pkey PRIMARY KEY (id);


--
-- Name: contracts contracts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.contracts
    ADD CONSTRAINT contracts_pkey PRIMARY KEY (id);


--
-- Name: country_code country_code_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.country_code
    ADD CONSTRAINT country_code_pkey PRIMARY KEY (id);


--
-- Name: cross_docking_tasks cross_docking_tasks_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cross_docking_tasks
    ADD CONSTRAINT cross_docking_tasks_pkey PRIMARY KEY (id);


--
-- Name: currencies currencies_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.currencies
    ADD CONSTRAINT currencies_pkey PRIMARY KEY (id);


--
-- Name: currency_rates currency_rates_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.currency_rates
    ADD CONSTRAINT currency_rates_pkey PRIMARY KEY (id);


--
-- Name: customs_declarations customs_declarations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customs_declarations
    ADD CONSTRAINT customs_declarations_pkey PRIMARY KEY (id);


--
-- Name: data_migration_rows data_migration_rows_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.data_migration_rows
    ADD CONSTRAINT data_migration_rows_pkey PRIMARY KEY (id);


--
-- Name: data_migration_runs data_migration_runs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.data_migration_runs
    ADD CONSTRAINT data_migration_runs_pkey PRIMARY KEY (id);


--
-- Name: delivery_schedules delivery_schedules_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.delivery_schedules
    ADD CONSTRAINT delivery_schedules_pkey PRIMARY KEY (id);


--
-- Name: demand_forecasts demand_forecasts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.demand_forecasts
    ADD CONSTRAINT demand_forecasts_pkey PRIMARY KEY (id);


--
-- Name: demand_plan_item_months demand_plan_item_months_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.demand_plan_item_months
    ADD CONSTRAINT demand_plan_item_months_pkey PRIMARY KEY (id);


--
-- Name: demand_plan_item_pr_allocations demand_plan_item_pr_allocations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.demand_plan_item_pr_allocations
    ADD CONSTRAINT demand_plan_item_pr_allocations_pkey PRIMARY KEY (id);


--
-- Name: demand_plan_items demand_plan_items_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.demand_plan_items
    ADD CONSTRAINT demand_plan_items_pkey PRIMARY KEY (id);


--
-- Name: demand_planning_cycles demand_planning_cycles_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.demand_planning_cycles
    ADD CONSTRAINT demand_planning_cycles_pkey PRIMARY KEY (id);


--
-- Name: demand_plans demand_plans_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.demand_plans
    ADD CONSTRAINT demand_plans_pkey PRIMARY KEY (id);


--
-- Name: department_types department_types_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.department_types
    ADD CONSTRAINT department_types_pkey PRIMARY KEY (id);


--
-- Name: departments departments_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.departments
    ADD CONSTRAINT departments_pkey PRIMARY KEY (id);


--
-- Name: employee_roles employee_roles_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.employee_roles
    ADD CONSTRAINT employee_roles_pkey PRIMARY KEY (employee_id, role);


--
-- Name: employees employees_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.employees
    ADD CONSTRAINT employees_pkey PRIMARY KEY (id);


--
-- Name: enum_translations enum_translations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.enum_translations
    ADD CONSTRAINT enum_translations_pkey PRIMARY KEY (id);


--
-- Name: equipment equipment_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.equipment
    ADD CONSTRAINT equipment_pkey PRIMARY KEY (id);


--
-- Name: exchange_rates exchange_rates_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.exchange_rates
    ADD CONSTRAINT exchange_rates_pkey PRIMARY KEY (id);


--
-- Name: external_inbound_records external_inbound_records_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.external_inbound_records
    ADD CONSTRAINT external_inbound_records_pkey PRIMARY KEY (id);


--
-- Name: fcm_tokens fcm_tokens_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fcm_tokens
    ADD CONSTRAINT fcm_tokens_pkey PRIMARY KEY (id);


--
-- Name: fixed_assets fixed_assets_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fixed_assets
    ADD CONSTRAINT fixed_assets_pkey PRIMARY KEY (id);


--
-- Name: goods_receipt_batches goods_receipt_batches_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.goods_receipt_batches
    ADD CONSTRAINT goods_receipt_batches_pkey PRIMARY KEY (id);


--
-- Name: goods_receipt_evidence goods_receipt_evidence_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.goods_receipt_evidence
    ADD CONSTRAINT goods_receipt_evidence_pkey PRIMARY KEY (id);


--
-- Name: goods_receipt_lines goods_receipt_lines_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.goods_receipt_lines
    ADD CONSTRAINT goods_receipt_lines_pkey PRIMARY KEY (id);


--
-- Name: goods_receipt_units goods_receipt_units_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.goods_receipt_units
    ADD CONSTRAINT goods_receipt_units_pkey PRIMARY KEY (id);


--
-- Name: goods_receipts goods_receipts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.goods_receipts
    ADD CONSTRAINT goods_receipts_pkey PRIMARY KEY (id);


--
-- Name: idempotency_records idempotency_records_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.idempotency_records
    ADD CONSTRAINT idempotency_records_pkey PRIMARY KEY (id);


--
-- Name: ikpu_codes ikpu_codes_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ikpu_codes
    ADD CONSTRAINT ikpu_codes_pkey PRIMARY KEY (id);


--
-- Name: illiquid_stock_policies illiquid_stock_policies_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.illiquid_stock_policies
    ADD CONSTRAINT illiquid_stock_policies_pkey PRIMARY KEY (id);


--
-- Name: import_deliveries import_deliveries_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_deliveries
    ADD CONSTRAINT import_deliveries_pkey PRIMARY KEY (id);


--
-- Name: import_freight_bookings import_freight_bookings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_freight_bookings
    ADD CONSTRAINT import_freight_bookings_pkey PRIMARY KEY (id);


--
-- Name: import_insurance_policies import_insurance_policies_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_insurance_policies
    ADD CONSTRAINT import_insurance_policies_pkey PRIMARY KEY (id);


--
-- Name: incoterms incoterms_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.incoterms
    ADD CONSTRAINT incoterms_pkey PRIMARY KEY (id);


--
-- Name: integration_events integration_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.integration_events
    ADD CONSTRAINT integration_events_pkey PRIMARY KEY (id);


--
-- Name: integration_settings integration_settings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.integration_settings
    ADD CONSTRAINT integration_settings_pkey PRIMARY KEY (id);


--
-- Name: inventory_accounting_reconciliations inventory_accounting_reconciliations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inventory_accounting_reconciliations
    ADD CONSTRAINT inventory_accounting_reconciliations_pkey PRIMARY KEY (id);


--
-- Name: inventory_accounting_settings inventory_accounting_settings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inventory_accounting_settings
    ADD CONSTRAINT inventory_accounting_settings_pkey PRIMARY KEY (id);


--
-- Name: inventory_commission_member_settings inventory_commission_member_settings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inventory_commission_member_settings
    ADD CONSTRAINT inventory_commission_member_settings_pkey PRIMARY KEY (id);


--
-- Name: inventory_cost_layers inventory_cost_layers_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inventory_cost_layers
    ADD CONSTRAINT inventory_cost_layers_pkey PRIMARY KEY (id);


--
-- Name: inventory_document_material_groups inventory_document_material_groups_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inventory_document_material_groups
    ADD CONSTRAINT inventory_document_material_groups_pkey PRIMARY KEY (id);


--
-- Name: inventory_document_materials inventory_document_materials_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inventory_document_materials
    ADD CONSTRAINT inventory_document_materials_pkey PRIMARY KEY (id);


--
-- Name: inventory_documents inventory_documents_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inventory_documents
    ADD CONSTRAINT inventory_documents_pkey PRIMARY KEY (id);


--
-- Name: inventory_fx_differences inventory_fx_differences_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inventory_fx_differences
    ADD CONSTRAINT inventory_fx_differences_pkey PRIMARY KEY (id);


--
-- Name: inventory_items inventory_items_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inventory_items
    ADD CONSTRAINT inventory_items_pkey PRIMARY KEY (id);


--
-- Name: inventory_line_history inventory_line_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inventory_line_history
    ADD CONSTRAINT inventory_line_history_pkey PRIMARY KEY (id);


--
-- Name: inventory_optimization_recommendations inventory_optimization_recommendations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inventory_optimization_recommendations
    ADD CONSTRAINT inventory_optimization_recommendations_pkey PRIMARY KEY (id);


--
-- Name: inventory_period_closes inventory_period_closes_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inventory_period_closes
    ADD CONSTRAINT inventory_period_closes_pkey PRIMARY KEY (id);


--
-- Name: issue_request_lines issue_request_lines_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.issue_request_lines
    ADD CONSTRAINT issue_request_lines_pkey PRIMARY KEY (id);


--
-- Name: issue_requests issue_requests_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.issue_requests
    ADD CONSTRAINT issue_requests_pkey PRIMARY KEY (id);


--
-- Name: laboratory_tests laboratory_tests_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.laboratory_tests
    ADD CONSTRAINT laboratory_tests_pkey PRIMARY KEY (id);


--
-- Name: landed_cost_allocation_lines landed_cost_allocation_lines_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.landed_cost_allocation_lines
    ADD CONSTRAINT landed_cost_allocation_lines_pkey PRIMARY KEY (id);


--
-- Name: landed_cost_allocations landed_cost_allocations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.landed_cost_allocations
    ADD CONSTRAINT landed_cost_allocations_pkey PRIMARY KEY (id);


--
-- Name: landed_cost_components landed_cost_components_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.landed_cost_components
    ADD CONSTRAINT landed_cost_components_pkey PRIMARY KEY (id);


--
-- Name: landed_logistics_events landed_logistics_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.landed_logistics_events
    ADD CONSTRAINT landed_logistics_events_pkey PRIMARY KEY (id);


--
-- Name: management_opportunities management_opportunities_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.management_opportunities
    ADD CONSTRAINT management_opportunities_pkey PRIMARY KEY (id);


--
-- Name: management_opportunity_evidence management_opportunity_evidence_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.management_opportunity_evidence
    ADD CONSTRAINT management_opportunity_evidence_pkey PRIMARY KEY (id);


--
-- Name: management_opportunity_policies management_opportunity_policies_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.management_opportunity_policies
    ADD CONSTRAINT management_opportunity_policies_pkey PRIMARY KEY (id);


--
-- Name: management_opportunity_runs management_opportunity_runs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.management_opportunity_runs
    ADD CONSTRAINT management_opportunity_runs_pkey PRIMARY KEY (id);


--
-- Name: manufacturers manufacturers_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.manufacturers
    ADD CONSTRAINT manufacturers_pkey PRIMARY KEY (id);


--
-- Name: material_analogs material_analogs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_analogs
    ADD CONSTRAINT material_analogs_pkey PRIMARY KEY (id);


--
-- Name: material_attribute_templates material_attribute_templates_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_attribute_templates
    ADD CONSTRAINT material_attribute_templates_pkey PRIMARY KEY (id);


--
-- Name: material_attributes material_attributes_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_attributes
    ADD CONSTRAINT material_attributes_pkey PRIMARY KEY (id);


--
-- Name: material_consumption_norms material_consumption_norms_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_consumption_norms
    ADD CONSTRAINT material_consumption_norms_pkey PRIMARY KEY (id);


--
-- Name: material_cost_observations material_cost_observations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_cost_observations
    ADD CONSTRAINT material_cost_observations_pkey PRIMARY KEY (id);


--
-- Name: material_duplicate_candidates material_duplicate_candidates_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_duplicate_candidates
    ADD CONSTRAINT material_duplicate_candidates_pkey PRIMARY KEY (id);


--
-- Name: material_equipment_compatibilities material_equipment_compatibilities_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_equipment_compatibilities
    ADD CONSTRAINT material_equipment_compatibilities_pkey PRIMARY KEY (id);


--
-- Name: material_groups material_groups_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_groups
    ADD CONSTRAINT material_groups_pkey PRIMARY KEY (id);


--
-- Name: material_market_prices material_market_prices_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_market_prices
    ADD CONSTRAINT material_market_prices_pkey PRIMARY KEY (id);


--
-- Name: material_master_requests material_master_requests_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_master_requests
    ADD CONSTRAINT material_master_requests_pkey PRIMARY KEY (id);


--
-- Name: material_quality_issues material_quality_issues_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_quality_issues
    ADD CONSTRAINT material_quality_issues_pkey PRIMARY KEY (id);


--
-- Name: material_reservation_events material_reservation_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_reservation_events
    ADD CONSTRAINT material_reservation_events_pkey PRIMARY KEY (id);


--
-- Name: material_reservations material_reservations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_reservations
    ADD CONSTRAINT material_reservations_pkey PRIMARY KEY (id);


--
-- Name: material_unit_events material_unit_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_unit_events
    ADD CONSTRAINT material_unit_events_pkey PRIMARY KEY (id);


--
-- Name: material_unit_operations material_unit_operations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_unit_operations
    ADD CONSTRAINT material_unit_operations_pkey PRIMARY KEY (id);


--
-- Name: material_units material_units_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_units
    ADD CONSTRAINT material_units_pkey PRIMARY KEY (id);


--
-- Name: material_valuation_method_history material_valuation_method_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_valuation_method_history
    ADD CONSTRAINT material_valuation_method_history_pkey PRIMARY KEY (id);


--
-- Name: materials materials_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.materials
    ADD CONSTRAINT materials_pkey PRIMARY KEY (id);


--
-- Name: mobile_warehouse_operations mobile_warehouse_operations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mobile_warehouse_operations
    ADD CONSTRAINT mobile_warehouse_operations_pkey PRIMARY KEY (id);


--
-- Name: mxik mxik_kod_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mxik
    ADD CONSTRAINT mxik_kod_key UNIQUE (kod);


--
-- Name: mxik mxik_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mxik
    ADD CONSTRAINT mxik_pkey PRIMARY KEY (id);


--
-- Name: non_moving_stock_case_lines non_moving_stock_case_lines_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.non_moving_stock_case_lines
    ADD CONSTRAINT non_moving_stock_case_lines_pkey PRIMARY KEY (id);


--
-- Name: non_moving_stock_cases non_moving_stock_cases_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.non_moving_stock_cases
    ADD CONSTRAINT non_moving_stock_cases_pkey PRIMARY KEY (id);


--
-- Name: non_moving_stock_decision_lines non_moving_stock_decision_lines_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.non_moving_stock_decision_lines
    ADD CONSTRAINT non_moving_stock_decision_lines_pkey PRIMARY KEY (id);


--
-- Name: non_moving_stock_decisions non_moving_stock_decisions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.non_moving_stock_decisions
    ADD CONSTRAINT non_moving_stock_decisions_pkey PRIMARY KEY (id);


--
-- Name: non_moving_stock_execution_outputs non_moving_stock_execution_outputs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.non_moving_stock_execution_outputs
    ADD CONSTRAINT non_moving_stock_execution_outputs_pkey PRIMARY KEY (id);


--
-- Name: non_moving_stock_executions non_moving_stock_executions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.non_moving_stock_executions
    ADD CONSTRAINT non_moving_stock_executions_pkey PRIMARY KEY (id);


--
-- Name: non_moving_stock_saved_views non_moving_stock_saved_views_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.non_moving_stock_saved_views
    ADD CONSTRAINT non_moving_stock_saved_views_pkey PRIMARY KEY (id);


--
-- Name: non_moving_stocks non_moving_stocks_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.non_moving_stocks
    ADD CONSTRAINT non_moving_stocks_pkey PRIMARY KEY (id);


--
-- Name: notifications notifications_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notifications
    ADD CONSTRAINT notifications_pkey PRIMARY KEY (id);


--
-- Name: stock_valuation_cost_job_warehouses pk_stock_valuation_cost_job_warehouses; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_valuation_cost_job_warehouses
    ADD CONSTRAINT pk_stock_valuation_cost_job_warehouses PRIMARY KEY (job_id, warehouse_id);


--
-- Name: stock_valuation_run_warehouses pk_stock_valuation_run_warehouses; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_valuation_run_warehouses
    ADD CONSTRAINT pk_stock_valuation_run_warehouses PRIMARY KEY (valuation_run_id, warehouse_id);


--
-- Name: positions positions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.positions
    ADD CONSTRAINT positions_pkey PRIMARY KEY (id);


--
-- Name: procurement_award_outcome_lines procurement_award_outcome_lines_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_award_outcome_lines
    ADD CONSTRAINT procurement_award_outcome_lines_pkey PRIMARY KEY (id);


--
-- Name: procurement_award_outcomes procurement_award_outcomes_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_award_outcomes
    ADD CONSTRAINT procurement_award_outcomes_pkey PRIMARY KEY (id);


--
-- Name: procurement_campaigns procurement_campaigns_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_campaigns
    ADD CONSTRAINT procurement_campaigns_pkey PRIMARY KEY (id);


--
-- Name: procurement_category_strategies procurement_category_strategies_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_category_strategies
    ADD CONSTRAINT procurement_category_strategies_pkey PRIMARY KEY (id);


--
-- Name: procurement_closeout_audit_events procurement_closeout_audit_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_closeout_audit_events
    ADD CONSTRAINT procurement_closeout_audit_events_pkey PRIMARY KEY (id);


--
-- Name: procurement_closeout_blockers procurement_closeout_blockers_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_closeout_blockers
    ADD CONSTRAINT procurement_closeout_blockers_pkey PRIMARY KEY (id);


--
-- Name: procurement_closeout_readiness_snapshots procurement_closeout_readiness_snapshots_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_closeout_readiness_snapshots
    ADD CONSTRAINT procurement_closeout_readiness_snapshots_pkey PRIMARY KEY (id);


--
-- Name: procurement_closeout_reopen_history procurement_closeout_reopen_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_closeout_reopen_history
    ADD CONSTRAINT procurement_closeout_reopen_history_pkey PRIMARY KEY (id);


--
-- Name: procurement_closeouts procurement_closeouts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_closeouts
    ADD CONSTRAINT procurement_closeouts_pkey PRIMARY KEY (id);


--
-- Name: procurement_direct_awards procurement_direct_awards_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_direct_awards
    ADD CONSTRAINT procurement_direct_awards_pkey PRIMARY KEY (id);


--
-- Name: procurement_framework_ledger procurement_framework_ledger_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_framework_ledger
    ADD CONSTRAINT procurement_framework_ledger_pkey PRIMARY KEY (id);


--
-- Name: procurement_frameworks procurement_frameworks_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_frameworks
    ADD CONSTRAINT procurement_frameworks_pkey PRIMARY KEY (id);


--
-- Name: procurement_line_allocations procurement_line_allocations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_line_allocations
    ADD CONSTRAINT procurement_line_allocations_pkey PRIMARY KEY (id);


--
-- Name: procurement_method_capabilities procurement_method_capabilities_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_method_capabilities
    ADD CONSTRAINT procurement_method_capabilities_pkey PRIMARY KEY (id);


--
-- Name: procurement_method_decisions procurement_method_decisions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_method_decisions
    ADD CONSTRAINT procurement_method_decisions_pkey PRIMARY KEY (id);


--
-- Name: procurement_plans procurement_plans_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_plans
    ADD CONSTRAINT procurement_plans_pkey PRIMARY KEY (id);


--
-- Name: procurement_procedures procurement_procedures_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_procedures
    ADD CONSTRAINT procurement_procedures_pkey PRIMARY KEY (id);


--
-- Name: procurement_work_assignments procurement_work_assignments_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_work_assignments
    ADD CONSTRAINT procurement_work_assignments_pkey PRIMARY KEY (id);


--
-- Name: production_evidence production_evidence_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.production_evidence
    ADD CONSTRAINT production_evidence_pkey PRIMARY KEY (id);


--
-- Name: purchase_order_items purchase_order_items_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.purchase_order_items
    ADD CONSTRAINT purchase_order_items_pkey PRIMARY KEY (id);


--
-- Name: purchase_order_revisions purchase_order_revisions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.purchase_order_revisions
    ADD CONSTRAINT purchase_order_revisions_pkey PRIMARY KEY (id);


--
-- Name: purchase_order_source_allocations purchase_order_source_allocations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.purchase_order_source_allocations
    ADD CONSTRAINT purchase_order_source_allocations_pkey PRIMARY KEY (id);


--
-- Name: purchase_orders purchase_orders_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.purchase_orders
    ADD CONSTRAINT purchase_orders_pkey PRIMARY KEY (id);


--
-- Name: purchase_request_items purchase_request_items_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.purchase_request_items
    ADD CONSTRAINT purchase_request_items_pkey PRIMARY KEY (id);


--
-- Name: purchase_requests purchase_requests_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.purchase_requests
    ADD CONSTRAINT purchase_requests_pkey PRIMARY KEY (id);


--
-- Name: putaway_rules putaway_rules_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.putaway_rules
    ADD CONSTRAINT putaway_rules_pkey PRIMARY KEY (id);


--
-- Name: qms_assessment_details qms_assessment_details_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.qms_assessment_details
    ADD CONSTRAINT qms_assessment_details_pkey PRIMARY KEY (id);


--
-- Name: qms_assessments qms_assessments_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.qms_assessments
    ADD CONSTRAINT qms_assessments_pkey PRIMARY KEY (id);


--
-- Name: qms_criteria qms_criteria_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.qms_criteria
    ADD CONSTRAINT qms_criteria_pkey PRIMARY KEY (id);


--
-- Name: quality_claims quality_claims_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quality_claims
    ADD CONSTRAINT quality_claims_pkey PRIMARY KEY (id);


--
-- Name: quality_inspections quality_inspections_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quality_inspections
    ADD CONSTRAINT quality_inspections_pkey PRIMARY KEY (id);


--
-- Name: quality_nonconformances quality_nonconformances_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quality_nonconformances
    ADD CONSTRAINT quality_nonconformances_pkey PRIMARY KEY (id);


--
-- Name: quality_quarantine_lots quality_quarantine_lots_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quality_quarantine_lots
    ADD CONSTRAINT quality_quarantine_lots_pkey PRIMARY KEY (id);


--
-- Name: quality_quarantine_lots quality_quarantine_lots_transfer_clearance_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quality_quarantine_lots
    ADD CONSTRAINT quality_quarantine_lots_transfer_clearance_id_key UNIQUE (transfer_clearance_id);


--
-- Name: reference_mappings reference_mappings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.reference_mappings
    ADD CONSTRAINT reference_mappings_pkey PRIMARY KEY (id);


--
-- Name: return_document_lines return_document_lines_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.return_document_lines
    ADD CONSTRAINT return_document_lines_pkey PRIMARY KEY (id);


--
-- Name: return_documents return_documents_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.return_documents
    ADD CONSTRAINT return_documents_pkey PRIMARY KEY (id);


--
-- Name: rfid_tags rfid_tags_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rfid_tags
    ADD CONSTRAINT rfid_tags_pkey PRIMARY KEY (id);


--
-- Name: rfq_delivery_attempts rfq_delivery_attempts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rfq_delivery_attempts
    ADD CONSTRAINT rfq_delivery_attempts_pkey PRIMARY KEY (id);


--
-- Name: rfq_invitation_deliveries rfq_invitation_deliveries_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rfq_invitation_deliveries
    ADD CONSTRAINT rfq_invitation_deliveries_pkey PRIMARY KEY (id);


--
-- Name: rfq_items rfq_items_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rfq_items
    ADD CONSTRAINT rfq_items_pkey PRIMARY KEY (id);


--
-- Name: rfq_published_versions rfq_published_versions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rfq_published_versions
    ADD CONSTRAINT rfq_published_versions_pkey PRIMARY KEY (id);


--
-- Name: rfq_purchase_request_sources rfq_purchase_request_sources_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rfq_purchase_request_sources
    ADD CONSTRAINT rfq_purchase_request_sources_pkey PRIMARY KEY (id);


--
-- Name: rfq_supplier_invitations rfq_supplier_invitations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rfq_supplier_invitations
    ADD CONSTRAINT rfq_supplier_invitations_pkey PRIMARY KEY (id);


--
-- Name: rfqs rfqs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rfqs
    ADD CONSTRAINT rfqs_pkey PRIMARY KEY (id);


--
-- Name: roles roles_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.roles
    ADD CONSTRAINT roles_pkey PRIMARY KEY (id);


--
-- Name: segregation_of_duty_rules segregation_of_duty_rules_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.segregation_of_duty_rules
    ADD CONSTRAINT segregation_of_duty_rules_pkey PRIMARY KEY (id);


--
-- Name: segregation_of_duty_violations segregation_of_duty_violations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.segregation_of_duty_violations
    ADD CONSTRAINT segregation_of_duty_violations_pkey PRIMARY KEY (id);


--
-- Name: service_acceptance_acts service_acceptance_acts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_acceptance_acts
    ADD CONSTRAINT service_acceptance_acts_pkey PRIMARY KEY (id);


--
-- Name: service_claims service_claims_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_claims
    ADD CONSTRAINT service_claims_pkey PRIMARY KEY (id);


--
-- Name: service_executions service_executions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_executions
    ADD CONSTRAINT service_executions_pkey PRIMARY KEY (id);


--
-- Name: service_orders service_orders_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_orders
    ADD CONSTRAINT service_orders_pkey PRIMARY KEY (id);


--
-- Name: service_request_milestones service_request_milestones_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_request_milestones
    ADD CONSTRAINT service_request_milestones_pkey PRIMARY KEY (id);


--
-- Name: service_request_remarks service_request_remarks_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_request_remarks
    ADD CONSTRAINT service_request_remarks_pkey PRIMARY KEY (id);


--
-- Name: service_requests service_requests_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_requests
    ADD CONSTRAINT service_requests_pkey PRIMARY KEY (id);


--
-- Name: service_review_history service_review_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_review_history
    ADD CONSTRAINT service_review_history_pkey PRIMARY KEY (id);


--
-- Name: sms_gateway_settings sms_gateway_settings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sms_gateway_settings
    ADD CONSTRAINT sms_gateway_settings_pkey PRIMARY KEY (id);


--
-- Name: sso_settings sso_settings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sso_settings
    ADD CONSTRAINT sso_settings_pkey PRIMARY KEY (id);


--
-- Name: stock_balances stock_balances_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_balances
    ADD CONSTRAINT stock_balances_pkey PRIMARY KEY (id);


--
-- Name: stock_batches stock_batches_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_batches
    ADD CONSTRAINT stock_batches_pkey PRIMARY KEY (id);


--
-- Name: stock_cost_movements stock_cost_movements_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_cost_movements
    ADD CONSTRAINT stock_cost_movements_pkey PRIMARY KEY (id);


--
-- Name: stock_movement_allocation_cost_layers stock_movement_allocation_cost_layers_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_movement_allocation_cost_layers
    ADD CONSTRAINT stock_movement_allocation_cost_layers_pkey PRIMARY KEY (id);


--
-- Name: stock_movement_allocations stock_movement_allocations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_movement_allocations
    ADD CONSTRAINT stock_movement_allocations_pkey PRIMARY KEY (id);


--
-- Name: stock_movement_documents stock_movement_documents_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_movement_documents
    ADD CONSTRAINT stock_movement_documents_pkey PRIMARY KEY (id);


--
-- Name: stock_movements stock_movements_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_movements
    ADD CONSTRAINT stock_movements_pkey PRIMARY KEY (id);


--
-- Name: stock_valuation_cost_job_results stock_valuation_cost_job_results_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_valuation_cost_job_results
    ADD CONSTRAINT stock_valuation_cost_job_results_pkey PRIMARY KEY (id);


--
-- Name: stock_valuation_cost_jobs stock_valuation_cost_jobs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_valuation_cost_jobs
    ADD CONSTRAINT stock_valuation_cost_jobs_pkey PRIMARY KEY (id);


--
-- Name: stock_valuation_documents stock_valuation_documents_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_valuation_documents
    ADD CONSTRAINT stock_valuation_documents_pkey PRIMARY KEY (id);


--
-- Name: stock_valuation_history stock_valuation_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_valuation_history
    ADD CONSTRAINT stock_valuation_history_pkey PRIMARY KEY (id);


--
-- Name: stock_valuation_run_items stock_valuation_run_items_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_valuation_run_items
    ADD CONSTRAINT stock_valuation_run_items_pkey PRIMARY KEY (id);


--
-- Name: stock_valuation_runs stock_valuation_runs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_valuation_runs
    ADD CONSTRAINT stock_valuation_runs_pkey PRIMARY KEY (id);


--
-- Name: stock_valuations stock_valuations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_valuations
    ADD CONSTRAINT stock_valuations_pkey PRIMARY KEY (id);


--
-- Name: storage_bins storage_bins_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.storage_bins
    ADD CONSTRAINT storage_bins_pkey PRIMARY KEY (id);


--
-- Name: storage_locations storage_locations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.storage_locations
    ADD CONSTRAINT storage_locations_pkey PRIMARY KEY (id);


--
-- Name: supplier_accreditation_applications supplier_accreditation_applications_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_accreditation_applications
    ADD CONSTRAINT supplier_accreditation_applications_pkey PRIMARY KEY (id);


--
-- Name: supplier_accreditation_decisions supplier_accreditation_decisions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_accreditation_decisions
    ADD CONSTRAINT supplier_accreditation_decisions_pkey PRIMARY KEY (id);


--
-- Name: supplier_accreditation_documents supplier_accreditation_documents_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_accreditation_documents
    ADD CONSTRAINT supplier_accreditation_documents_pkey PRIMARY KEY (id);


--
-- Name: supplier_accreditation_evidence_requirements supplier_accreditation_evidence_requirements_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_accreditation_evidence_requirements
    ADD CONSTRAINT supplier_accreditation_evidence_requirements_pkey PRIMARY KEY (id);


--
-- Name: supplier_activation_records supplier_activation_records_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_activation_records
    ADD CONSTRAINT supplier_activation_records_pkey PRIMARY KEY (id);


--
-- Name: supplier_bank_accounts supplier_bank_accounts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_bank_accounts
    ADD CONSTRAINT supplier_bank_accounts_pkey PRIMARY KEY (id);


--
-- Name: supplier_certificates supplier_certificates_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_certificates
    ADD CONSTRAINT supplier_certificates_pkey PRIMARY KEY (id);


--
-- Name: supplier_corrective_actions supplier_corrective_actions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_corrective_actions
    ADD CONSTRAINT supplier_corrective_actions_pkey PRIMARY KEY (id);


--
-- Name: supplier_evaluations supplier_evaluations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_evaluations
    ADD CONSTRAINT supplier_evaluations_pkey PRIMARY KEY (id);


--
-- Name: supplier_evidence_requirements supplier_evidence_requirements_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_evidence_requirements
    ADD CONSTRAINT supplier_evidence_requirements_pkey PRIMARY KEY (id);


--
-- Name: supplier_evidence_template_versions supplier_evidence_template_versions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_evidence_template_versions
    ADD CONSTRAINT supplier_evidence_template_versions_pkey PRIMARY KEY (id);


--
-- Name: supplier_kyc_checks supplier_kyc_checks_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_kyc_checks
    ADD CONSTRAINT supplier_kyc_checks_pkey PRIMARY KEY (id);


--
-- Name: supplier_letters supplier_letters_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_letters
    ADD CONSTRAINT supplier_letters_pkey PRIMARY KEY (id);


--
-- Name: supplier_licenses supplier_licenses_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_licenses
    ADD CONSTRAINT supplier_licenses_pkey PRIMARY KEY (id);


--
-- Name: supplier_magic_link_requests supplier_magic_link_requests_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_magic_link_requests
    ADD CONSTRAINT supplier_magic_link_requests_pkey PRIMARY KEY (id);


--
-- Name: supplier_magic_links supplier_magic_links_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_magic_links
    ADD CONSTRAINT supplier_magic_links_pkey PRIMARY KEY (id);


--
-- Name: supplier_po_commitments supplier_po_commitments_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_po_commitments
    ADD CONSTRAINT supplier_po_commitments_pkey PRIMARY KEY (id);


--
-- Name: supplier_po_dispatches supplier_po_dispatches_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_po_dispatches
    ADD CONSTRAINT supplier_po_dispatches_pkey PRIMARY KEY (id);


--
-- Name: supplier_po_reconfirmation_notifications supplier_po_reconfirmation_notifications_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_po_reconfirmation_notifications
    ADD CONSTRAINT supplier_po_reconfirmation_notifications_pkey PRIMARY KEY (id);


--
-- Name: supplier_po_response_lines supplier_po_response_lines_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_po_response_lines
    ADD CONSTRAINT supplier_po_response_lines_pkey PRIMARY KEY (id);


--
-- Name: supplier_po_responses supplier_po_responses_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_po_responses
    ADD CONSTRAINT supplier_po_responses_pkey PRIMARY KEY (id);


--
-- Name: supplier_portal_accounts supplier_portal_accounts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_portal_accounts
    ADD CONSTRAINT supplier_portal_accounts_pkey PRIMARY KEY (id);


--
-- Name: supplier_portal_submissions supplier_portal_submissions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_portal_submissions
    ADD CONSTRAINT supplier_portal_submissions_pkey PRIMARY KEY (id);


--
-- Name: supplier_quotation_items supplier_quotation_items_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_quotation_items
    ADD CONSTRAINT supplier_quotation_items_pkey PRIMARY KEY (id);


--
-- Name: supplier_quotation_threads supplier_quotation_threads_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_quotation_threads
    ADD CONSTRAINT supplier_quotation_threads_pkey PRIMARY KEY (id);


--
-- Name: supplier_quotations supplier_quotations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_quotations
    ADD CONSTRAINT supplier_quotations_pkey PRIMARY KEY (id);


--
-- Name: supplier_related_parties supplier_related_parties_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_related_parties
    ADD CONSTRAINT supplier_related_parties_pkey PRIMARY KEY (id);


--
-- Name: supplier_unblock_requests supplier_unblock_requests_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_unblock_requests
    ADD CONSTRAINT supplier_unblock_requests_pkey PRIMARY KEY (id);


--
-- Name: suppliers suppliers_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.suppliers
    ADD CONSTRAINT suppliers_pkey PRIMARY KEY (id);


--
-- Name: supply_work_assignments supply_work_assignments_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supply_work_assignments
    ADD CONSTRAINT supply_work_assignments_pkey PRIMARY KEY (id);


--
-- Name: tender_bid_opening_lines tender_bid_opening_lines_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tender_bid_opening_lines
    ADD CONSTRAINT tender_bid_opening_lines_pkey PRIMARY KEY (id);


--
-- Name: tender_bid_opening_protocols tender_bid_opening_protocols_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tender_bid_opening_protocols
    ADD CONSTRAINT tender_bid_opening_protocols_pkey PRIMARY KEY (id);


--
-- Name: tender_commission_member_settings tender_commission_member_settings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tender_commission_member_settings
    ADD CONSTRAINT tender_commission_member_settings_pkey PRIMARY KEY (id);


--
-- Name: tender_commission_members tender_commission_members_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tender_commission_members
    ADD CONSTRAINT tender_commission_members_pkey PRIMARY KEY (id);


--
-- Name: tender_commissions tender_commissions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tender_commissions
    ADD CONSTRAINT tender_commissions_pkey PRIMARY KEY (id);


--
-- Name: tender_conflict_checks tender_conflict_checks_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tender_conflict_checks
    ADD CONSTRAINT tender_conflict_checks_pkey PRIMARY KEY (id);


--
-- Name: tender_evaluation_protocols tender_evaluation_protocols_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tender_evaluation_protocols
    ADD CONSTRAINT tender_evaluation_protocols_pkey PRIMARY KEY (id);


--
-- Name: tender_price_calculation_quotations tender_price_calculation_quotations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tender_price_calculation_quotations
    ADD CONSTRAINT tender_price_calculation_quotations_pkey PRIMARY KEY (id);


--
-- Name: tender_price_calculations tender_price_calculations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tender_price_calculations
    ADD CONSTRAINT tender_price_calculations_pkey PRIMARY KEY (id);


--
-- Name: tnved_codes tnved_codes_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tnved_codes
    ADD CONSTRAINT tnved_codes_pkey PRIMARY KEY (id);


--
-- Name: transfer_allocation_clearances transfer_allocation_clearances_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transfer_allocation_clearances
    ADD CONSTRAINT transfer_allocation_clearances_pkey PRIMARY KEY (id);


--
-- Name: transfer_document_lines transfer_document_lines_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transfer_document_lines
    ADD CONSTRAINT transfer_document_lines_pkey PRIMARY KEY (id);


--
-- Name: transfer_documents transfer_documents_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transfer_documents
    ADD CONSTRAINT transfer_documents_pkey PRIMARY KEY (id);


--
-- Name: transfer_exceptions transfer_exceptions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transfer_exceptions
    ADD CONSTRAINT transfer_exceptions_pkey PRIMARY KEY (id);


--
-- Name: transfer_shipment_allocations transfer_shipment_allocations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transfer_shipment_allocations
    ADD CONSTRAINT transfer_shipment_allocations_pkey PRIMARY KEY (id);


--
-- Name: transfer_shipments transfer_shipments_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transfer_shipments
    ADD CONSTRAINT transfer_shipments_pkey PRIMARY KEY (id);


--
-- Name: transfer_transit_entries transfer_transit_entries_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transfer_transit_entries
    ADD CONSTRAINT transfer_transit_entries_pkey PRIMARY KEY (id);


--
-- Name: tsd_offline_operations tsd_offline_operations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tsd_offline_operations
    ADD CONSTRAINT tsd_offline_operations_pkey PRIMARY KEY (id);


--
-- Name: approval_flow_steps uk_approval_flow_steps_order; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.approval_flow_steps
    ADD CONSTRAINT uk_approval_flow_steps_order UNIQUE (approval_flow_id, step_order);


--
-- Name: asset_management_settings uk_asset_management_settings_tenant_company; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.asset_management_settings
    ADD CONSTRAINT uk_asset_management_settings_tenant_company UNIQUE (tenant_id, company_id);


--
-- Name: asset_number_registry uk_asset_number_registry_inventory; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.asset_number_registry
    ADD CONSTRAINT uk_asset_number_registry_inventory UNIQUE (tenant_id, company_id, inventory_number);


--
-- Name: asset_number_registry uk_asset_number_registry_normalized; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.asset_number_registry
    ADD CONSTRAINT uk_asset_number_registry_normalized UNIQUE (tenant_id, company_id, normalized_number);


--
-- Name: chart_of_accounts uk_chart_of_accounts_tenant_company_code; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chart_of_accounts
    ADD CONSTRAINT uk_chart_of_accounts_tenant_company_code UNIQUE (tenant_id, company_id, account_code);


--
-- Name: companies uk_companies_tenant_code; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.companies
    ADD CONSTRAINT uk_companies_tenant_code UNIQUE (tenant_id, code);


--
-- Name: enum_translations uk_enum_translations_enum_code_locale; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.enum_translations
    ADD CONSTRAINT uk_enum_translations_enum_code_locale UNIQUE (enum_name, code, locale);


--
-- Name: exchange_rates uk_exchange_rates_currency_rate_date_source; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.exchange_rates
    ADD CONSTRAINT uk_exchange_rates_currency_rate_date_source UNIQUE (currency_id, rate_date, source);


--
-- Name: external_inbound_records uk_external_inbound_record_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.external_inbound_records
    ADD CONSTRAINT uk_external_inbound_record_key UNIQUE (tenant_id, company_id, source_system, dataset, external_id);


--
-- Name: goods_receipt_evidence uk_goods_receipt_evidence_line_attachment; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.goods_receipt_evidence
    ADD CONSTRAINT uk_goods_receipt_evidence_line_attachment UNIQUE (goods_receipt_line_id, attachment_id);


--
-- Name: idempotency_records uk_idempotency_records_scope; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.idempotency_records
    ADD CONSTRAINT uk_idempotency_records_scope UNIQUE (tenant_id, company_id, operation, resource_id, idempotency_key);


--
-- Name: ikpu_codes uk_ikpu_codes_tenant_company_code; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ikpu_codes
    ADD CONSTRAINT uk_ikpu_codes_tenant_company_code UNIQUE (tenant_id, company_id, ikpu_code);


--
-- Name: integration_events uk_integration_events_tenant_company_idempotency; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.integration_events
    ADD CONSTRAINT uk_integration_events_tenant_company_idempotency UNIQUE (tenant_id, company_id, idempotency_key);


--
-- Name: integration_settings uk_integration_settings_tenant_company_system; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.integration_settings
    ADD CONSTRAINT uk_integration_settings_tenant_company_system UNIQUE (tenant_id, company_id, system_code);


--
-- Name: inventory_accounting_settings uk_inventory_accounting_settings_tenant_company; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inventory_accounting_settings
    ADD CONSTRAINT uk_inventory_accounting_settings_tenant_company UNIQUE (tenant_id, company_id);


--
-- Name: material_reservation_events uk_material_reservation_event_idempotency; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_reservation_events
    ADD CONSTRAINT uk_material_reservation_event_idempotency UNIQUE (tenant_id, company_id, reservation_id, event_type, idempotency_key);


--
-- Name: material_reservation_events uk_material_reservation_event_sequence; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_reservation_events
    ADD CONSTRAINT uk_material_reservation_event_sequence UNIQUE (reservation_id, sequence_no);


--
-- Name: procurement_closeout_audit_events uk_procurement_closeout_audit_idempotency; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_closeout_audit_events
    ADD CONSTRAINT uk_procurement_closeout_audit_idempotency UNIQUE (tenant_id, company_id, idempotency_key);


--
-- Name: procurement_closeout_reopen_history uk_procurement_closeout_reopen_idempotency; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_closeout_reopen_history
    ADD CONSTRAINT uk_procurement_closeout_reopen_idempotency UNIQUE (tenant_id, company_id, idempotency_key);


--
-- Name: qms_assessment_details uk_qms_assessment_details_assessment_criteria; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.qms_assessment_details
    ADD CONSTRAINT uk_qms_assessment_details_assessment_criteria UNIQUE (assessment_id, criteria_id);


--
-- Name: qms_criteria uk_qms_criteria_tenant_company_code; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.qms_criteria
    ADD CONSTRAINT uk_qms_criteria_tenant_company_code UNIQUE (tenant_id, company_id, code);


--
-- Name: roles uk_roles_tenant_company_role_name; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.roles
    ADD CONSTRAINT uk_roles_tenant_company_role_name UNIQUE (tenant_id, company_id, role_name);


--
-- Name: sms_gateway_settings uk_sms_gateway_settings_tenant_company; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sms_gateway_settings
    ADD CONSTRAINT uk_sms_gateway_settings_tenant_company UNIQUE (tenant_id, company_id);


--
-- Name: sso_settings uk_sso_settings_tenant_company; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sso_settings
    ADD CONSTRAINT uk_sso_settings_tenant_company UNIQUE (tenant_id, company_id);


--
-- Name: storage_bins uk_storage_bins_tenant_location_bin; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.storage_bins
    ADD CONSTRAINT uk_storage_bins_tenant_location_bin UNIQUE (tenant_id, storage_location_id, bin_code);


--
-- Name: supplier_accreditation_evidence_requirements uk_supplier_application_evidence_requirement; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_accreditation_evidence_requirements
    ADD CONSTRAINT uk_supplier_application_evidence_requirement UNIQUE (tenant_id, company_id, application_id, requirement_code);


--
-- Name: supplier_evidence_requirements uk_supplier_evidence_requirement; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_evidence_requirements
    ADD CONSTRAINT uk_supplier_evidence_requirement UNIQUE (tenant_id, company_id, template_version_id, requirement_code);


--
-- Name: supplier_evidence_template_versions uk_supplier_evidence_template_version; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_evidence_template_versions
    ADD CONSTRAINT uk_supplier_evidence_template_version UNIQUE (tenant_id, company_id, accreditation_type, template_version);


--
-- Name: tnved_codes uk_tnved_codes_tenant_company_code; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tnved_codes
    ADD CONSTRAINT uk_tnved_codes_tenant_company_code UNIQUE (tenant_id, company_id, tnved_code);


--
-- Name: users uk_users_tenant_company_username; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT uk_users_tenant_company_username UNIQUE (tenant_id, company_id, username);


--
-- Name: unit_of_measures unit_of_measures_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.unit_of_measures
    ADD CONSTRAINT unit_of_measures_pkey PRIMARY KEY (id);


--
-- Name: stock_valuation_cost_job_results uq_stock_valuation_job_result_group; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_valuation_cost_job_results
    ADD CONSTRAINT uq_stock_valuation_job_result_group UNIQUE (job_id, material_id, warehouse_id);


--
-- Name: stock_valuation_cost_job_results uq_stock_valuation_job_result_sequence; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_valuation_cost_job_results
    ADD CONSTRAINT uq_stock_valuation_job_result_sequence UNIQUE (job_id, sequence_no);


--
-- Name: stock_valuation_run_items uq_stock_valuation_run_item_group; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_valuation_run_items
    ADD CONSTRAINT uq_stock_valuation_run_item_group UNIQUE (valuation_run_id, material_id, warehouse_id);


--
-- Name: stock_valuation_run_items uq_stock_valuation_run_item_sequence; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_valuation_run_items
    ADD CONSTRAINT uq_stock_valuation_run_item_sequence UNIQUE (valuation_run_id, sequence_no);


--
-- Name: transfer_shipment_allocations uq_transfer_allocation_document_scope; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transfer_shipment_allocations
    ADD CONSTRAINT uq_transfer_allocation_document_scope UNIQUE (tenant_id, company_id, transfer_document_id, id);


--
-- Name: transfer_shipment_allocations uq_transfer_allocation_scope; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transfer_shipment_allocations
    ADD CONSTRAINT uq_transfer_allocation_scope UNIQUE (tenant_id, company_id, id);


--
-- Name: transfer_allocation_clearances uq_transfer_clearance_operation; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transfer_allocation_clearances
    ADD CONSTRAINT uq_transfer_clearance_operation UNIQUE (tenant_id, company_id, allocation_id, operation_key, kind);


--
-- Name: transfer_document_lines uq_transfer_line_scope; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transfer_document_lines
    ADD CONSTRAINT uq_transfer_line_scope UNIQUE (tenant_id, company_id, transfer_document_id, id, material_id);


--
-- Name: transfer_documents uq_transfer_scope; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transfer_documents
    ADD CONSTRAINT uq_transfer_scope UNIQUE (tenant_id, company_id, id);


--
-- Name: transfer_shipments uq_transfer_shipment_operation; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transfer_shipments
    ADD CONSTRAINT uq_transfer_shipment_operation UNIQUE (tenant_id, company_id, transfer_document_id, operation_key);


--
-- Name: transfer_shipments uq_transfer_shipment_scope; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transfer_shipments
    ADD CONSTRAINT uq_transfer_shipment_scope UNIQUE (tenant_id, company_id, transfer_document_id, id);


--
-- Name: transfer_shipment_allocations uq_transfer_source_lineage; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transfer_shipment_allocations
    ADD CONSTRAINT uq_transfer_source_lineage UNIQUE (shipment_id, source_movement_allocation_id, allocation_sequence);


--
-- Name: transfer_transit_entries uq_transfer_transit_operation; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transfer_transit_entries
    ADD CONSTRAINT uq_transfer_transit_operation UNIQUE (allocation_id, operation_id, kind);


--
-- Name: user_feedback user_feedback_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_feedback
    ADD CONSTRAINT user_feedback_pkey PRIMARY KEY (id);


--
-- Name: user_roles user_roles_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_roles
    ADD CONSTRAINT user_roles_pkey PRIMARY KEY (user_id, role);


--
-- Name: users users_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);


--
-- Name: rfq_delivery_attempts ux_rfq_delivery_attempt_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rfq_delivery_attempts
    ADD CONSTRAINT ux_rfq_delivery_attempt_key UNIQUE (tenant_id, company_id, idempotency_key);


--
-- Name: rfq_delivery_attempts ux_rfq_delivery_attempt_number; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rfq_delivery_attempts
    ADD CONSTRAINT ux_rfq_delivery_attempt_number UNIQUE (delivery_id, attempt_number);


--
-- Name: rfq_invitation_deliveries ux_rfq_delivery_version_supplier; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rfq_invitation_deliveries
    ADD CONSTRAINT ux_rfq_delivery_version_supplier UNIQUE (tenant_id, company_id, published_version_id, supplier_id);


--
-- Name: rfq_published_versions ux_rfq_published_version_number; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rfq_published_versions
    ADD CONSTRAINT ux_rfq_published_version_number UNIQUE (tenant_id, company_id, rfq_id, version_number);


--
-- Name: supplier_magic_links ux_supplier_magic_link_hash; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_magic_links
    ADD CONSTRAINT ux_supplier_magic_link_hash UNIQUE (token_hash);


--
-- Name: supplier_quotation_threads ux_supplier_quotation_thread; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_quotation_threads
    ADD CONSTRAINT ux_supplier_quotation_thread UNIQUE (tenant_id, company_id, rfq_id, supplier_id);


--
-- Name: vmi_stock_agreements vmi_stock_agreements_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.vmi_stock_agreements
    ADD CONSTRAINT vmi_stock_agreements_pkey PRIMARY KEY (id);


--
-- Name: warehouses warehouses_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.warehouses
    ADD CONSTRAINT warehouses_pkey PRIMARY KEY (id);


--
-- Name: work_completion_acts work_completion_acts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.work_completion_acts
    ADD CONSTRAINT work_completion_acts_pkey PRIMARY KEY (id);


--
-- Name: idx_ai_analysis_jobs_entity_requested; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ai_analysis_jobs_entity_requested ON public.ai_analysis_jobs USING btree (tenant_id, company_id, analysis_type, entity_type, entity_id, requested_at DESC);


--
-- Name: idx_ai_analysis_jobs_scope_requested; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ai_analysis_jobs_scope_requested ON public.ai_analysis_jobs USING btree (tenant_id, company_id, requested_at DESC);


--
-- Name: idx_ai_analysis_jobs_status_requested; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ai_analysis_jobs_status_requested ON public.ai_analysis_jobs USING btree (status, requested_at);


--
-- Name: idx_approval_flow_parallel_assignees_flow; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_approval_flow_parallel_assignees_flow ON public.approval_flow_parallel_assignees USING btree (tenant_id, approval_flow_id, assignee_username);


--
-- Name: idx_approval_flow_steps_department; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_approval_flow_steps_department ON public.approval_flow_steps USING btree (tenant_id, company_id, department_id);


--
-- Name: idx_approval_flow_steps_warehouse; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_approval_flow_steps_warehouse ON public.approval_flow_steps USING btree (tenant_id, company_id, warehouse_id);


--
-- Name: idx_approval_flows_department_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_approval_flows_department_scope ON public.approval_flows USING btree (tenant_id, company_id, module_key, department_id, active, created_at);


--
-- Name: idx_approval_flows_lookup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_approval_flows_lookup ON public.approval_flows USING btree (tenant_id, company_id, module_key, active, created_at);


--
-- Name: idx_approval_history_instance; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_approval_history_instance ON public.approval_history USING btree (tenant_id, approval_instance_id, action_at);


--
-- Name: idx_approval_instances_lookup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_approval_instances_lookup ON public.approval_instances USING btree (tenant_id, company_id, module_key, entity_id);


--
-- Name: idx_approval_tasks_role; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_approval_tasks_role ON public.approval_tasks USING btree (tenant_id, company_id, task_status, assignee_role);


--
-- Name: idx_approval_tasks_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_approval_tasks_user ON public.approval_tasks USING btree (tenant_id, company_id, task_status, assignee_username);


--
-- Name: idx_approval_workflows_module_request; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_approval_workflows_module_request ON public.approval_workflows USING btree (tenant_id, company_id, module_key, request_type, created_at DESC);


--
-- Name: idx_asn_item_commitment; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_asn_item_commitment ON public.advance_shipping_notice_items USING btree (tenant_id, company_id, supplier_po_commitment_id) WHERE (is_delete = false);


--
-- Name: idx_asn_items_asn; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_asn_items_asn ON public.advance_shipping_notice_items USING btree (tenant_id, company_id, asn_id);


--
-- Name: idx_asn_items_material; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_asn_items_material ON public.advance_shipping_notice_items USING btree (tenant_id, company_id, material_id);


--
-- Name: idx_asn_po; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_asn_po ON public.advance_shipping_notices USING btree (tenant_id, company_id, purchase_order_id);


--
-- Name: idx_asn_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_asn_status ON public.advance_shipping_notices USING btree (tenant_id, company_id, status);


--
-- Name: idx_asn_supplier; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_asn_supplier ON public.advance_shipping_notices USING btree (tenant_id, company_id, supplier_id);


--
-- Name: idx_asset_acceptance_lines_acceptance; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_asset_acceptance_lines_acceptance ON public.asset_acceptance_lines USING btree (tenant_id, company_id, asset_acceptance_id, is_delete);


--
-- Name: idx_asset_acceptance_units_line; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_asset_acceptance_units_line ON public.asset_acceptance_units USING btree (tenant_id, company_id, asset_acceptance_line_id, is_delete);


--
-- Name: idx_asset_acceptances_purchase_order; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_asset_acceptances_purchase_order ON public.asset_acceptances USING btree (tenant_id, company_id, purchase_order_id, is_delete);


--
-- Name: idx_asset_depr_entries_asset; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_asset_depr_entries_asset ON public.asset_depreciation_entries USING btree (tenant_id, company_id, fixed_asset_id) WHERE (is_delete = false);


--
-- Name: idx_asset_depr_entries_period; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_asset_depr_entries_period ON public.asset_depreciation_entries USING btree (tenant_id, company_id, period_year, period_month) WHERE (is_delete = false);


--
-- Name: idx_asset_events_fixed_asset; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_asset_events_fixed_asset ON public.asset_events USING btree (tenant_id, company_id, fixed_asset_id, recorded_at DESC, is_delete);


--
-- Name: idx_asset_handover_lines_handover; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_asset_handover_lines_handover ON public.asset_handover_lines USING btree (tenant_id, company_id, asset_handover_id, is_delete);


--
-- Name: idx_asset_handovers_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_asset_handovers_status ON public.asset_handovers USING btree (tenant_id, company_id, status, is_delete);


--
-- Name: idx_asset_management_settings_central_warehouse; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_asset_management_settings_central_warehouse ON public.asset_management_settings USING btree (tenant_id, company_id, central_warehouse_id, is_delete);


--
-- Name: idx_asset_number_registry_fixed_asset; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_asset_number_registry_fixed_asset ON public.asset_number_registry USING btree (tenant_id, company_id, fixed_asset_id, is_delete) WHERE (fixed_asset_id IS NOT NULL);


--
-- Name: idx_asset_opening_import_lines_import; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_asset_opening_import_lines_import ON public.asset_opening_import_lines USING btree (tenant_id, company_id, asset_opening_import_id, is_delete);


--
-- Name: idx_asset_verification_lines_verification; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_asset_verification_lines_verification ON public.asset_verification_lines USING btree (tenant_id, company_id, asset_verification_id, is_delete);


--
-- Name: idx_asset_verifications_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_asset_verifications_scope ON public.asset_verifications USING btree (tenant_id, company_id, warehouse_id, department_id, status, is_delete);


--
-- Name: idx_attachments_checksum; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_attachments_checksum ON public.attachments USING btree (tenant_id, company_id, checksum_sha256);


--
-- Name: idx_attachments_entity; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_attachments_entity ON public.attachments USING btree (tenant_id, company_id, entity_type, entity_id);


--
-- Name: idx_audit_logs_entity; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_audit_logs_entity ON public.audit_logs USING btree (entity_type, entity_id);


--
-- Name: idx_audit_logs_tenant_entity; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_audit_logs_tenant_entity ON public.audit_logs USING btree (tenant_id, entity_type, entity_id);


--
-- Name: idx_banks_mfo; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_banks_mfo ON public.banks USING btree (mfo);


--
-- Name: idx_banks_sort_order; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_banks_sort_order ON public.banks USING btree (sort_order);


--
-- Name: idx_budget_items_tenant_code; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_budget_items_tenant_code ON public.budget_items USING btree (tenant_id, code);


--
-- Name: idx_budget_transactions_budget_item; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_budget_transactions_budget_item ON public.budget_transactions USING btree (tenant_id, company_id, budget_item_id, transaction_type, created_at) WHERE (is_delete = false);


--
-- Name: idx_budget_transactions_original; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_budget_transactions_original ON public.budget_transactions USING btree (tenant_id, company_id, original_transaction_id) WHERE (is_delete = false);


--
-- Name: idx_chart_of_accounts_parent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chart_of_accounts_parent ON public.chart_of_accounts USING btree (tenant_id, company_id, parent_code);


--
-- Name: idx_chart_of_accounts_search; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chart_of_accounts_search ON public.chart_of_accounts USING btree (tenant_id, company_id, account_code, status);


--
-- Name: idx_classifier_types_tenant_code; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_classifier_types_tenant_code ON public.classifier_types USING btree (tenant_id, code);


--
-- Name: idx_classifier_types_tenant_module; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_classifier_types_tenant_module ON public.classifier_types USING btree (tenant_id, module);


--
-- Name: idx_commission_members_commission; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_commission_members_commission ON public.commission_members USING btree (tenant_id, company_id, commission_id);


--
-- Name: idx_commission_members_employee; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_commission_members_employee ON public.commission_members USING btree (tenant_id, company_id, employee_id);


--
-- Name: idx_commission_members_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_commission_members_user ON public.commission_members USING btree (tenant_id, company_id, user_id);


--
-- Name: idx_commissions_tenant_company_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_commissions_tenant_company_type ON public.commissions USING btree (tenant_id, company_id, commission_type);


--
-- Name: idx_companies_tenant_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_companies_tenant_status ON public.companies USING btree (tenant_id, status);


--
-- Name: idx_consignment_stock_transactions_agreement; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_consignment_stock_transactions_agreement ON public.consignment_stock_transactions USING btree (tenant_id, company_id, vmi_agreement_id, transaction_at) WHERE (is_delete = false);


--
-- Name: idx_consolidated_demands_material; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_consolidated_demands_material ON public.consolidated_demands USING btree (tenant_id, company_id, material_id);


--
-- Name: idx_consolidated_demands_plan; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_consolidated_demands_plan ON public.consolidated_demands USING btree (tenant_id, company_id, demand_plan_id);


--
-- Name: idx_consolidated_demands_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_consolidated_demands_status ON public.consolidated_demands USING btree (tenant_id, company_id, status);


--
-- Name: idx_consumption_norms_department; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_consumption_norms_department ON public.material_consumption_norms USING btree (tenant_id, company_id, department_id);


--
-- Name: idx_consumption_norms_equipment; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_consumption_norms_equipment ON public.material_consumption_norms USING btree (tenant_id, company_id, equipment_id);


--
-- Name: idx_consumption_norms_material; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_consumption_norms_material ON public.material_consumption_norms USING btree (tenant_id, company_id, material_id);


--
-- Name: idx_consumption_norms_product; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_consumption_norms_product ON public.material_consumption_norms USING btree (tenant_id, company_id, product_code, operation_code);


--
-- Name: idx_consumption_norms_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_consumption_norms_status ON public.material_consumption_norms USING btree (tenant_id, company_id, status);


--
-- Name: idx_contract_addendums_contract; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_contract_addendums_contract ON public.contract_addendums USING btree (tenant_id, company_id, contract_id);


--
-- Name: idx_contract_attorneys_contract; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_contract_attorneys_contract ON public.contract_powers_of_attorney USING btree (tenant_id, contract_id);


--
-- Name: idx_contract_attorneys_supplier; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_contract_attorneys_supplier ON public.contract_powers_of_attorney USING btree (tenant_id, supplier_id);


--
-- Name: idx_contract_invoice_evidence_snapshot_po; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_contract_invoice_evidence_snapshot_po ON public.contract_invoice_evidence_snapshots USING btree (tenant_id, company_id, purchase_order_id, recorded_at);


--
-- Name: idx_contract_invoices_commercial_instrument; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_contract_invoices_commercial_instrument ON public.contract_invoices USING btree (tenant_id, company_id, commercial_instrument_type);


--
-- Name: idx_contract_invoices_contract; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_contract_invoices_contract ON public.contract_invoices USING btree (tenant_id, contract_id);


--
-- Name: idx_contract_invoices_purchase_order; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_contract_invoices_purchase_order ON public.contract_invoices USING btree (tenant_id, company_id, purchase_order_id);


--
-- Name: idx_contract_invoices_supplier; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_contract_invoices_supplier ON public.contract_invoices USING btree (tenant_id, supplier_id);


--
-- Name: idx_contract_payments_commercial_instrument; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_contract_payments_commercial_instrument ON public.contract_payments USING btree (tenant_id, company_id, commercial_instrument_type);


--
-- Name: idx_contract_payments_contract; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_contract_payments_contract ON public.contract_payments USING btree (tenant_id, contract_id);


--
-- Name: idx_contract_payments_invoice; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_contract_payments_invoice ON public.contract_payments USING btree (tenant_id, company_id, contract_invoice_id);


--
-- Name: idx_contract_payments_purchase_order; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_contract_payments_purchase_order ON public.contract_payments USING btree (tenant_id, company_id, purchase_order_id);


--
-- Name: idx_contract_payments_source_document; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_contract_payments_source_document ON public.contract_payments USING btree (tenant_id, company_id, source_document_type, source_document_id);


--
-- Name: idx_contract_payments_supplier; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_contract_payments_supplier ON public.contract_payments USING btree (tenant_id, supplier_id);


--
-- Name: idx_contract_reconciliation_contract; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_contract_reconciliation_contract ON public.contract_reconciliation_acts USING btree (tenant_id, contract_id);


--
-- Name: idx_contract_reconciliation_supplier; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_contract_reconciliation_supplier ON public.contract_reconciliation_acts USING btree (tenant_id, supplier_id);


--
-- Name: idx_contract_spec_source_purchase_request_item; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_contract_spec_source_purchase_request_item ON public.contract_specifications USING btree (tenant_id, company_id, source_purchase_request_item_id) WHERE ((is_delete = false) AND (source_purchase_request_item_id IS NOT NULL));


--
-- Name: idx_cost_layers_transfer_transaction; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_cost_layers_transfer_transaction ON public.inventory_cost_layers USING btree (tenant_id, company_id, transfer_transaction_id) WHERE ((is_delete = false) AND (transfer_transaction_id IS NOT NULL));


--
-- Name: idx_cost_movements_document; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_cost_movements_document ON public.stock_cost_movements USING btree (tenant_id, company_id, source_document_type, source_document_id) WHERE (is_delete = false);


--
-- Name: idx_cost_movements_transfer; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_cost_movements_transfer ON public.stock_cost_movements USING btree (tenant_id, company_id, transfer_transaction_id) WHERE ((is_delete = false) AND (transfer_transaction_id IS NOT NULL));


--
-- Name: idx_country_code_short_name; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_country_code_short_name ON public.country_code USING btree (short_name);


--
-- Name: idx_cross_docking_tasks_po; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_cross_docking_tasks_po ON public.cross_docking_tasks USING btree (tenant_id, company_id, purchase_order_id) WHERE (is_delete = false);


--
-- Name: idx_cross_docking_tasks_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_cross_docking_tasks_status ON public.cross_docking_tasks USING btree (tenant_id, company_id, status, planned_ship_at) WHERE (is_delete = false);


--
-- Name: idx_currencies_iso_code; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_currencies_iso_code ON public.currencies USING btree (iso_code);


--
-- Name: idx_currency_rates_tenant_currency_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_currency_rates_tenant_currency_date ON public.currency_rates USING btree (tenant_id, currency_id, rate_date);


--
-- Name: idx_currency_rates_tenant_pair_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_currency_rates_tenant_pair_date ON public.currency_rates USING btree (tenant_id, currency_code, base_currency_code, rate_date);


--
-- Name: idx_customs_declarations_delivery; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_customs_declarations_delivery ON public.customs_declarations USING btree (tenant_id, company_id, import_delivery_id);


--
-- Name: idx_customs_declarations_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_customs_declarations_status ON public.customs_declarations USING btree (tenant_id, company_id, status);


--
-- Name: idx_data_migration_rows_run; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_data_migration_rows_run ON public.data_migration_rows USING btree (tenant_id, company_id, run_id, row_number);


--
-- Name: idx_data_migration_rows_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_data_migration_rows_status ON public.data_migration_rows USING btree (tenant_id, company_id, row_status);


--
-- Name: idx_data_migration_runs_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_data_migration_runs_scope ON public.data_migration_runs USING btree (tenant_id, company_id, migration_type, status);


--
-- Name: idx_delivery_schedule_po_item; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_delivery_schedule_po_item ON public.delivery_schedules USING btree (tenant_id, company_id, purchase_order_item_id) WHERE (is_delete = false);


--
-- Name: idx_delivery_schedules_contract; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_delivery_schedules_contract ON public.delivery_schedules USING btree (tenant_id, company_id, contract_id);


--
-- Name: idx_delivery_schedules_due; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_delivery_schedules_due ON public.delivery_schedules USING btree (tenant_id, schedule_date, status);


--
-- Name: idx_delivery_schedules_po; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_delivery_schedules_po ON public.delivery_schedules USING btree (tenant_id, company_id, purchase_order_id);


--
-- Name: idx_demand_forecasts_material; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_demand_forecasts_material ON public.demand_forecasts USING btree (tenant_id, company_id, material_id, warehouse_id, generated_at) WHERE (is_delete = false);


--
-- Name: idx_demand_item_pr_allocations_demand_item; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_demand_item_pr_allocations_demand_item ON public.demand_plan_item_pr_allocations USING btree (tenant_id, company_id, demand_plan_item_id, is_delete);


--
-- Name: idx_demand_item_pr_allocations_pr_item; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_demand_item_pr_allocations_pr_item ON public.demand_plan_item_pr_allocations USING btree (tenant_id, company_id, purchase_request_item_id, is_delete);


--
-- Name: idx_demand_plan_item_months_item; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_demand_plan_item_months_item ON public.demand_plan_item_months USING btree (demand_plan_item_id);


--
-- Name: idx_demand_plan_item_months_plan; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_demand_plan_item_months_plan ON public.demand_plan_item_months USING btree (demand_plan_id);


--
-- Name: idx_demand_plan_item_months_tenant_comp_plan; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_demand_plan_item_months_tenant_comp_plan ON public.demand_plan_item_months USING btree (tenant_id, company_id, demand_plan_id);


--
-- Name: idx_demand_plan_items_consolidated; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_demand_plan_items_consolidated ON public.demand_plan_items USING btree (tenant_id, company_id, consolidated_demand_id);


--
-- Name: idx_demand_plan_items_material; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_demand_plan_items_material ON public.demand_plan_items USING btree (tenant_id, company_id, material_id);


--
-- Name: idx_demand_plan_items_plan; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_demand_plan_items_plan ON public.demand_plan_items USING btree (tenant_id, company_id, demand_plan_id);


--
-- Name: idx_demand_plan_items_project; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_demand_plan_items_project ON public.demand_plan_items USING btree (tenant_id, company_id, project_code);


--
-- Name: idx_demand_plan_items_repair; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_demand_plan_items_repair ON public.demand_plan_items USING btree (tenant_id, company_id, repair_order_code);


--
-- Name: idx_demand_plan_items_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_demand_plan_items_status ON public.demand_plan_items USING btree (tenant_id, company_id, status);


--
-- Name: idx_demand_planning_cycles_owner_employee; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_demand_planning_cycles_owner_employee ON public.demand_planning_cycles USING btree (owner_employee_id);


--
-- Name: idx_demand_planning_cycles_period; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_demand_planning_cycles_period ON public.demand_planning_cycles USING btree (tenant_id, company_id, plan_year, period_type, quarter_no);


--
-- Name: idx_demand_planning_cycles_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_demand_planning_cycles_status ON public.demand_planning_cycles USING btree (tenant_id, company_id, status);


--
-- Name: idx_demand_plans_cycle; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_demand_plans_cycle ON public.demand_plans USING btree (tenant_id, company_id, planning_cycle_id);


--
-- Name: idx_demand_plans_department; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_demand_plans_department ON public.demand_plans USING btree (tenant_id, company_id, department_id);


--
-- Name: idx_demand_plans_period; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_demand_plans_period ON public.demand_plans USING btree (tenant_id, company_id, plan_year, period_type, quarter_no);


--
-- Name: idx_demand_plans_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_demand_plans_status ON public.demand_plans USING btree (tenant_id, company_id, status);


--
-- Name: idx_department_types_tenant_code; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_department_types_tenant_code ON public.department_types USING btree (tenant_id, code);


--
-- Name: idx_department_types_tenant_level; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_department_types_tenant_level ON public.department_types USING btree (tenant_id, hierarchy_level);


--
-- Name: idx_departments_tenant_code; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_departments_tenant_code ON public.departments USING btree (tenant_id, code);


--
-- Name: idx_departments_tenant_external; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_departments_tenant_external ON public.departments USING btree (tenant_id, external_id);


--
-- Name: idx_departments_tenant_parent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_departments_tenant_parent ON public.departments USING btree (tenant_id, parent_department_id);


--
-- Name: idx_departments_tenant_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_departments_tenant_type ON public.departments USING btree (tenant_id, department_type);


--
-- Name: idx_employee_roles_employee; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_employee_roles_employee ON public.employee_roles USING btree (employee_id);


--
-- Name: idx_employees_tenant_company_warehouse; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_employees_tenant_company_warehouse ON public.employees USING btree (tenant_id, company_id, warehouse_id);


--
-- Name: idx_employees_tenant_department; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_employees_tenant_department ON public.employees USING btree (tenant_id, department_id);


--
-- Name: idx_employees_tenant_external; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_employees_tenant_external ON public.employees USING btree (tenant_id, external_id);


--
-- Name: idx_employees_tenant_manager; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_employees_tenant_manager ON public.employees USING btree (tenant_id, manager_employee_id);


--
-- Name: idx_employees_tenant_personnel; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_employees_tenant_personnel ON public.employees USING btree (tenant_id, personnel_number);


--
-- Name: idx_enum_translations_enum_name; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_enum_translations_enum_name ON public.enum_translations USING btree (enum_name);


--
-- Name: idx_equipment_department; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_equipment_department ON public.equipment USING btree (tenant_id, company_id, department_id);


--
-- Name: idx_equipment_external; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_equipment_external ON public.equipment USING btree (tenant_id, company_id, external_equipment_id);


--
-- Name: idx_equipment_manufacturer; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_equipment_manufacturer ON public.equipment USING btree (tenant_id, company_id, manufacturer_id) WHERE ((is_delete = false) AND (manufacturer_id IS NOT NULL));


--
-- Name: idx_equipment_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_equipment_status ON public.equipment USING btree (tenant_id, company_id, status);


--
-- Name: idx_exchange_rates_tenant_currency_rate_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_exchange_rates_tenant_currency_rate_date ON public.exchange_rates USING btree (tenant_id, currency_id, rate_date);


--
-- Name: idx_external_inbound_records_applied; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_external_inbound_records_applied ON public.external_inbound_records USING btree (tenant_id, company_id, applied_entity_type, applied_entity_id);


--
-- Name: idx_external_inbound_records_source; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_external_inbound_records_source ON public.external_inbound_records USING btree (tenant_id, company_id, source_system, dataset, synced_at DESC);


--
-- Name: idx_fcm_tokens_last_seen; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_fcm_tokens_last_seen ON public.fcm_tokens USING btree (last_seen_at DESC) WHERE (is_delete = false);


--
-- Name: idx_fcm_tokens_tenant_company; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_fcm_tokens_tenant_company ON public.fcm_tokens USING btree (tenant_id, company_id) WHERE (is_delete = false);


--
-- Name: idx_fcm_tokens_user_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_fcm_tokens_user_active ON public.fcm_tokens USING btree (user_id, active) WHERE (is_delete = false);


--
-- Name: idx_fixed_assets_category; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_fixed_assets_category ON public.fixed_assets USING btree (tenant_id, company_id, category) WHERE (is_delete = false);


--
-- Name: idx_fixed_assets_depr_method; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_fixed_assets_depr_method ON public.fixed_assets USING btree (tenant_id, company_id, depreciation_method) WHERE (is_delete = false);


--
-- Name: idx_goods_receipt_evidence_receipt; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_goods_receipt_evidence_receipt ON public.goods_receipt_evidence USING btree (tenant_id, company_id, goods_receipt_id, created_at);


--
-- Name: idx_goods_receipt_lines_movement; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_goods_receipt_lines_movement ON public.goods_receipt_lines USING btree (tenant_id, company_id, stock_movement_id) WHERE (stock_movement_id IS NOT NULL);


--
-- Name: idx_goods_receipt_lines_receipt; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_goods_receipt_lines_receipt ON public.goods_receipt_lines USING btree (tenant_id, company_id, goods_receipt_id, created_at);


--
-- Name: idx_goods_receipts_po_posted; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_goods_receipts_po_posted ON public.goods_receipts USING btree (tenant_id, company_id, purchase_order_id, posted_at DESC);


--
-- Name: idx_idempotency_records_lookup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_idempotency_records_lookup ON public.idempotency_records USING btree (tenant_id, company_id, resource_id, operation);


--
-- Name: idx_ikpu_codes_search; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ikpu_codes_search ON public.ikpu_codes USING btree (tenant_id, company_id, ikpu_code, status);


--
-- Name: idx_illiquid_stock_policies_company; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_illiquid_stock_policies_company ON public.illiquid_stock_policies USING btree (tenant_id, company_id, active);


--
-- Name: idx_illiquid_stock_policies_effective; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_illiquid_stock_policies_effective ON public.illiquid_stock_policies USING btree (tenant_id, effective_from, effective_to);


--
-- Name: idx_import_deliveries_eta; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_import_deliveries_eta ON public.import_deliveries USING btree (tenant_id, company_id, eta);


--
-- Name: idx_import_deliveries_po; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_import_deliveries_po ON public.import_deliveries USING btree (tenant_id, company_id, purchase_order_id);


--
-- Name: idx_import_deliveries_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_import_deliveries_status ON public.import_deliveries USING btree (tenant_id, company_id, status);


--
-- Name: idx_import_deliveries_supplier; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_import_deliveries_supplier ON public.import_deliveries USING btree (tenant_id, company_id, supplier_id);


--
-- Name: idx_import_freight_delivery; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_import_freight_delivery ON public.import_freight_bookings USING btree (tenant_id, company_id, import_delivery_id);


--
-- Name: idx_import_freight_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_import_freight_status ON public.import_freight_bookings USING btree (tenant_id, company_id, status);


--
-- Name: idx_import_insurance_delivery; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_import_insurance_delivery ON public.import_insurance_policies USING btree (tenant_id, company_id, import_delivery_id);


--
-- Name: idx_import_insurance_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_import_insurance_status ON public.import_insurance_policies USING btree (tenant_id, company_id, status);


--
-- Name: idx_integration_events_external_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_integration_events_external_id ON public.integration_events USING btree (tenant_id, company_id, external_id);


--
-- Name: idx_integration_events_replay_lineage; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_integration_events_replay_lineage ON public.integration_events USING btree (tenant_id, company_id, replay_of_event_id) WHERE ((is_delete = false) AND (replay_of_event_id IS NOT NULL));


--
-- Name: idx_integration_events_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_integration_events_status ON public.integration_events USING btree (status, next_retry_at);


--
-- Name: idx_integration_events_tenant_aggregate; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_integration_events_tenant_aggregate ON public.integration_events USING btree (tenant_id, aggregate_type, created_at DESC);


--
-- Name: idx_integration_events_tenant_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_integration_events_tenant_status ON public.integration_events USING btree (tenant_id, status, next_retry_at);


--
-- Name: idx_integration_events_workspace_queue; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_integration_events_workspace_queue ON public.integration_events USING btree (tenant_id, company_id, status, next_retry_at, retry_count, created_at DESC) WHERE (is_delete = false);


--
-- Name: idx_integration_settings_tenant_company; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_integration_settings_tenant_company ON public.integration_settings USING btree (tenant_id, company_id);


--
-- Name: idx_inventory_accounting_reconciliation_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_inventory_accounting_reconciliation_scope ON public.inventory_accounting_reconciliations USING btree (tenant_id, company_id, warehouse_id, period_start, period_end, accounting_system, status, is_delete);


--
-- Name: idx_inventory_accounting_reconciliations_period; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_inventory_accounting_reconciliations_period ON public.inventory_accounting_reconciliations USING btree (tenant_id, company_id, warehouse_id, period_start, period_end, status) WHERE (is_delete = false);


--
-- Name: idx_inventory_active_freeze_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_inventory_active_freeze_scope ON public.inventory_documents USING btree (tenant_id, company_id, warehouse_id) WHERE ((is_delete = false) AND (freeze_operations = true) AND (snapshot_at IS NOT NULL) AND ((status)::text = ANY ((ARRAY['IN_PROGRESS'::character varying, 'COUNTING_COMPLETED'::character varying, 'DISCREPANCY_REVIEW'::character varying, 'WAITING_APPROVAL'::character varying, 'APPROVED'::character varying, 'REJECTED'::character varying])::text[])));


--
-- Name: idx_inventory_commission_member_settings_commission; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_inventory_commission_member_settings_commission ON public.inventory_commission_member_settings USING btree (tenant_id, company_id, commission_id);


--
-- Name: idx_inventory_commission_member_settings_member; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_inventory_commission_member_settings_member ON public.inventory_commission_member_settings USING btree (tenant_id, company_id, commission_member_id);


--
-- Name: idx_inventory_cost_layers_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_inventory_cost_layers_active ON public.inventory_cost_layers USING btree (tenant_id, company_id, material_id, warehouse_id, stock_batch_id, received_at, status) WHERE (is_delete = false);


--
-- Name: idx_inventory_cost_layers_source; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_inventory_cost_layers_source ON public.inventory_cost_layers USING btree (tenant_id, company_id, source_movement_id) WHERE (is_delete = false);


--
-- Name: idx_inventory_document_material_groups_document; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_inventory_document_material_groups_document ON public.inventory_document_material_groups USING btree (tenant_id, company_id, inventory_document_id);


--
-- Name: idx_inventory_document_material_groups_group; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_inventory_document_material_groups_group ON public.inventory_document_material_groups USING btree (tenant_id, company_id, material_group_id);


--
-- Name: idx_inventory_document_materials_document; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_inventory_document_materials_document ON public.inventory_document_materials USING btree (tenant_id, company_id, inventory_document_id);


--
-- Name: idx_inventory_document_materials_material; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_inventory_document_materials_material ON public.inventory_document_materials USING btree (tenant_id, company_id, material_id);


--
-- Name: idx_inventory_documents_commission; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_inventory_documents_commission ON public.inventory_documents USING btree (tenant_id, company_id, commission_id);


--
-- Name: idx_inventory_documents_operational_window; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_inventory_documents_operational_window ON public.inventory_documents USING btree (tenant_id, company_id, warehouse_id, status, planned_start_date, planned_end_date);


--
-- Name: idx_inventory_found_serial; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_inventory_found_serial ON public.inventory_items USING btree (tenant_id, company_id, observed_serial_number) WHERE ((is_delete = false) AND (found_stock = true) AND (observed_serial_number IS NOT NULL));


--
-- Name: idx_inventory_fx_differences_period; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_inventory_fx_differences_period ON public.inventory_fx_differences USING btree (tenant_id, company_id, warehouse_id, period_start, period_end, status) WHERE (is_delete = false);


--
-- Name: idx_inventory_fx_differences_reference; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_inventory_fx_differences_reference ON public.inventory_fx_differences USING btree (tenant_id, company_id, reference_type, reference_id) WHERE (is_delete = false);


--
-- Name: idx_inventory_items_linked_movement; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_inventory_items_linked_movement ON public.inventory_items USING btree (tenant_id, company_id, linked_stock_movement_id) WHERE (linked_stock_movement_id IS NOT NULL);


--
-- Name: idx_inventory_items_stock_key; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_inventory_items_stock_key ON public.inventory_items USING btree (tenant_id, company_id, inventory_document_id, material_id, warehouse_id, storage_location_id, storage_bin_id, stock_batch_id);


--
-- Name: idx_inventory_optimization_material; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_inventory_optimization_material ON public.inventory_optimization_recommendations USING btree (tenant_id, company_id, material_id, warehouse_id, generated_at) WHERE (is_delete = false);


--
-- Name: idx_inventory_period_close_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_inventory_period_close_scope ON public.inventory_period_closes USING btree (tenant_id, company_id, warehouse_id, period_start, period_end, status, is_delete);


--
-- Name: idx_inventory_period_closes_period; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_inventory_period_closes_period ON public.inventory_period_closes USING btree (tenant_id, company_id, warehouse_id, period_start, period_end, status) WHERE (is_delete = false);


--
-- Name: idx_issue_request_lines_source_demand_item; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_issue_request_lines_source_demand_item ON public.issue_request_lines USING btree (tenant_id, company_id, source_demand_plan_item_id, is_delete);


--
-- Name: idx_issue_request_lines_tenant_header; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_issue_request_lines_tenant_header ON public.issue_request_lines USING btree (tenant_id, issue_request_id);


--
-- Name: idx_issue_requests_tenant_company_requester; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_issue_requests_tenant_company_requester ON public.issue_requests USING btree (tenant_id, company_id, requester_user_id);


--
-- Name: idx_issue_requests_tenant_company_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_issue_requests_tenant_company_status ON public.issue_requests USING btree (tenant_id, company_id, status);


--
-- Name: idx_issue_requests_tenant_company_warehouse; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_issue_requests_tenant_company_warehouse ON public.issue_requests USING btree (tenant_id, company_id, warehouse_id);


--
-- Name: idx_laboratory_tests_inspection; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_laboratory_tests_inspection ON public.laboratory_tests USING btree (tenant_id, company_id, quality_inspection_id) WHERE (is_delete = false);


--
-- Name: idx_laboratory_tests_lims_sample; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_laboratory_tests_lims_sample ON public.laboratory_tests USING btree (tenant_id, company_id, lims_sample_id);


--
-- Name: idx_landed_cost_allocation_lines_balance; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_landed_cost_allocation_lines_balance ON public.landed_cost_allocation_lines USING btree (tenant_id, company_id, material_id, warehouse_id, stock_batch_id) WHERE (is_delete = false);


--
-- Name: idx_landed_cost_allocation_lines_document; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_landed_cost_allocation_lines_document ON public.landed_cost_allocation_lines USING btree (tenant_id, company_id, allocation_document_id) WHERE (is_delete = false);


--
-- Name: idx_landed_cost_allocations_delivery; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_landed_cost_allocations_delivery ON public.landed_cost_allocations USING btree (tenant_id, company_id, import_delivery_id, status) WHERE (is_delete = false);


--
-- Name: idx_landed_cost_allocations_period; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_landed_cost_allocations_period ON public.landed_cost_allocations USING btree (tenant_id, company_id, document_date, status) WHERE (is_delete = false);


--
-- Name: idx_landed_cost_components_delivery; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_landed_cost_components_delivery ON public.landed_cost_components USING btree (tenant_id, company_id, import_delivery_id);


--
-- Name: idx_landed_cost_components_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_landed_cost_components_type ON public.landed_cost_components USING btree (tenant_id, company_id, component_type);


--
-- Name: idx_landed_logistics_events_delivery; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_landed_logistics_events_delivery ON public.landed_logistics_events USING btree (tenant_id, company_id, import_delivery_id);


--
-- Name: idx_landed_logistics_events_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_landed_logistics_events_type ON public.landed_logistics_events USING btree (tenant_id, company_id, event_type);


--
-- Name: idx_manufacturers_tenant_company_country_code; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_manufacturers_tenant_company_country_code ON public.manufacturers USING btree (tenant_id, company_id, country_code);


--
-- Name: idx_manufacturers_tenant_company_name; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_manufacturers_tenant_company_name ON public.manufacturers USING btree (tenant_id, company_id, name);


--
-- Name: idx_material_analogs_analog; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_material_analogs_analog ON public.material_analogs USING btree (tenant_id, company_id, analog_material_id);


--
-- Name: idx_material_analogs_material; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_material_analogs_material ON public.material_analogs USING btree (tenant_id, company_id, material_id);


--
-- Name: idx_material_analogs_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_material_analogs_status ON public.material_analogs USING btree (tenant_id, company_id, status);


--
-- Name: idx_material_attribute_templates_group; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_material_attribute_templates_group ON public.material_attribute_templates USING btree (tenant_id, company_id, material_group_id) WHERE (is_delete = false);


--
-- Name: idx_material_attributes_material; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_material_attributes_material ON public.material_attributes USING btree (material_id, sort_order) WHERE (is_deleted = false);


--
-- Name: idx_material_consumption_norm_business_interval; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_material_consumption_norm_business_interval ON public.material_consumption_norms USING btree (tenant_id, company_id, material_id, norm_type, equipment_id, department_id, product_code, operation_code, effective_from, effective_to) WHERE (is_delete = false);


--
-- Name: idx_material_cost_observations_material; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_material_cost_observations_material ON public.material_cost_observations USING btree (tenant_id, company_id, material_id, observed_at DESC) WHERE (is_delete = false);


--
-- Name: idx_material_cost_observations_mxik; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_material_cost_observations_mxik ON public.material_cost_observations USING btree (tenant_id, company_id, mxik_code, currency_code, observed_at DESC) WHERE (is_delete = false);


--
-- Name: idx_material_duplicate_candidate_ai_source; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_material_duplicate_candidate_ai_source ON public.material_duplicate_candidates USING btree (tenant_id, company_id, ai_source_job_id) WHERE ((is_delete = false) AND (ai_source_job_id IS NOT NULL));


--
-- Name: idx_material_duplicate_candidates_material; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_material_duplicate_candidates_material ON public.material_duplicate_candidates USING btree (tenant_id, company_id, material_id) WHERE (is_delete = false);


--
-- Name: idx_material_duplicate_work_queue; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_material_duplicate_work_queue ON public.material_duplicate_candidates USING btree (tenant_id, company_id, status, updated_at DESC) WHERE (is_delete = false);


--
-- Name: idx_material_equipment_equipment; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_material_equipment_equipment ON public.material_equipment_compatibilities USING btree (tenant_id, company_id, equipment_id);


--
-- Name: idx_material_equipment_material; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_material_equipment_material ON public.material_equipment_compatibilities USING btree (tenant_id, company_id, material_id);


--
-- Name: idx_material_equipment_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_material_equipment_status ON public.material_equipment_compatibilities USING btree (tenant_id, company_id, status);


--
-- Name: idx_material_groups_tenant_code; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_material_groups_tenant_code ON public.material_groups USING btree (tenant_id, code);


--
-- Name: idx_material_market_prices_effective; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_material_market_prices_effective ON public.material_market_prices USING btree (tenant_id, company_id, material_id, warehouse_id, status, effective_date DESC) WHERE (is_delete = false);


--
-- Name: idx_material_master_request_target; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_material_master_request_target ON public.material_master_requests USING btree (tenant_id, company_id, target_material_id) WHERE ((is_delete = false) AND (target_material_id IS NOT NULL));


--
-- Name: idx_material_master_request_work_queue; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_material_master_request_work_queue ON public.material_master_requests USING btree (tenant_id, company_id, status, updated_at DESC) WHERE (is_delete = false);


--
-- Name: idx_material_master_requests_company; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_material_master_requests_company ON public.material_master_requests USING btree (tenant_id, company_id) WHERE (is_delete = false);


--
-- Name: idx_material_quality_issue_work_queue; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_material_quality_issue_work_queue ON public.material_quality_issues USING btree (tenant_id, company_id, status, assigned_to, updated_at DESC) WHERE (is_delete = false);


--
-- Name: idx_material_quality_issues_material; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_material_quality_issues_material ON public.material_quality_issues USING btree (tenant_id, company_id, material_id) WHERE (is_delete = false);


--
-- Name: idx_material_reservation_events_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_material_reservation_events_scope ON public.material_reservation_events USING btree (tenant_id, company_id, reservation_id, sequence_no);


--
-- Name: idx_material_reservation_material_company_open; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_material_reservation_material_company_open ON public.material_reservations USING btree (tenant_id, company_id, material_id, status) WHERE (is_delete = false);


--
-- Name: idx_material_reservations_complete_stock_key; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_material_reservations_complete_stock_key ON public.material_reservations USING btree (tenant_id, company_id, material_id, warehouse_id, storage_location_id, storage_bin_id, stock_batch_id, status);


--
-- Name: idx_material_reservations_expiry_work; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_material_reservations_expiry_work ON public.material_reservations USING btree (tenant_id, company_id, expires_at, id) WHERE ((is_delete = false) AND ((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'PARTIALLY_RELEASED'::character varying])::text[])));


--
-- Name: idx_material_reservations_material; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_material_reservations_material ON public.material_reservations USING btree (tenant_id, company_id, material_id);


--
-- Name: idx_material_reservations_plan_item; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_material_reservations_plan_item ON public.material_reservations USING btree (tenant_id, company_id, demand_plan_item_id);


--
-- Name: idx_material_reservations_project; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_material_reservations_project ON public.material_reservations USING btree (tenant_id, company_id, project_code);


--
-- Name: idx_material_reservations_repair; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_material_reservations_repair ON public.material_reservations USING btree (tenant_id, company_id, repair_order_code);


--
-- Name: idx_material_reservations_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_material_reservations_status ON public.material_reservations USING btree (tenant_id, company_id, status);


--
-- Name: idx_material_reservations_warehouse; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_material_reservations_warehouse ON public.material_reservations USING btree (tenant_id, company_id, warehouse_id);


--
-- Name: idx_material_valuation_method_history_material; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_material_valuation_method_history_material ON public.material_valuation_method_history USING btree (tenant_id, company_id, material_id, effective_at DESC) WHERE (is_delete = false);


--
-- Name: idx_materials_article; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_materials_article ON public.materials USING btree (article);


--
-- Name: idx_materials_code; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_materials_code ON public.materials USING btree (code);


--
-- Name: idx_materials_ikpu_code; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_materials_ikpu_code ON public.materials USING btree (tenant_id, company_id, ikpu_code);


--
-- Name: idx_materials_ikpu_code_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_materials_ikpu_code_id ON public.materials USING btree (ikpu_code_id);


--
-- Name: idx_materials_lifecycle; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_materials_lifecycle ON public.materials USING btree (tenant_id, company_id, status, lifecycle_changed_at);


--
-- Name: idx_materials_manufacturer; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_materials_manufacturer ON public.materials USING btree (tenant_id, company_id, manufacturer_id) WHERE ((is_delete = false) AND (manufacturer_id IS NOT NULL));


--
-- Name: idx_materials_material_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_materials_material_type ON public.materials USING btree (tenant_id, company_id, material_type, is_delete);


--
-- Name: idx_materials_name; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_materials_name ON public.materials USING btree (name);


--
-- Name: idx_materials_replacement; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_materials_replacement ON public.materials USING btree (tenant_id, company_id, replacement_material_id);


--
-- Name: idx_materials_tenant_code; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_materials_tenant_code ON public.materials USING btree (tenant_id, code);


--
-- Name: idx_materials_tenant_company_code; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_materials_tenant_company_code ON public.materials USING btree (tenant_id, company_id, code);


--
-- Name: idx_materials_tnved_code; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_materials_tnved_code ON public.materials USING btree (tenant_id, company_id, tnved_code);


--
-- Name: idx_materials_tnved_code_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_materials_tnved_code_id ON public.materials USING btree (tnved_code_id);


--
-- Name: idx_mobile_ops_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mobile_ops_type ON public.mobile_warehouse_operations USING btree (tenant_id, company_id, operation_type, status);


--
-- Name: idx_mxik_catalog_class_position; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mxik_catalog_class_position ON public.mxik USING btree (class_name, position_name) WHERE ((class_name IS NOT NULL) AND (position_name IS NOT NULL));


--
-- Name: idx_mxik_catalog_group_class; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mxik_catalog_group_class ON public.mxik USING btree (group_name, class_name) WHERE ((group_name IS NOT NULL) AND (class_name IS NOT NULL));


--
-- Name: idx_mxik_catalog_group_name; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mxik_catalog_group_name ON public.mxik USING btree (group_name) WHERE (group_name IS NOT NULL);


--
-- Name: idx_mxik_catalog_position_sub_position; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mxik_catalog_position_sub_position ON public.mxik USING btree (position_name, sub_position_name) WHERE ((position_name IS NOT NULL) AND (sub_position_name IS NOT NULL));


--
-- Name: idx_mxik_catalog_sub_position_name; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mxik_catalog_sub_position_name ON public.mxik USING btree (sub_position_name, name, kod) WHERE (sub_position_name IS NOT NULL);


--
-- Name: idx_mxik_created_at_id_desc; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mxik_created_at_id_desc ON public.mxik USING btree (created_at DESC, id DESC);


--
-- Name: idx_mxik_name_trgm; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mxik_name_trgm ON public.mxik USING gin (lower((name)::text) public.gin_trgm_ops);


--
-- Name: idx_mxik_search_attribute_name_trgm; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mxik_search_attribute_name_trgm ON public.mxik USING gin (((attribute_name)::text) public.gin_trgm_ops);


--
-- Name: idx_mxik_search_barcode_trgm; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mxik_search_barcode_trgm ON public.mxik USING gin (((barcode)::text) public.gin_trgm_ops);


--
-- Name: idx_mxik_search_brand_name_trgm; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mxik_search_brand_name_trgm ON public.mxik USING gin (((brand_name)::text) public.gin_trgm_ops);


--
-- Name: idx_mxik_search_class_name_trgm; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mxik_search_class_name_trgm ON public.mxik USING gin (((class_name)::text) public.gin_trgm_ops);


--
-- Name: idx_mxik_search_group_name_trgm; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mxik_search_group_name_trgm ON public.mxik USING gin (((group_name)::text) public.gin_trgm_ops);


--
-- Name: idx_mxik_search_kod_trgm; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mxik_search_kod_trgm ON public.mxik USING gin (((kod)::text) public.gin_trgm_ops);


--
-- Name: idx_mxik_search_name_trgm; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mxik_search_name_trgm ON public.mxik USING gin (((name)::text) public.gin_trgm_ops);


--
-- Name: idx_mxik_search_position_name_trgm; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mxik_search_position_name_trgm ON public.mxik USING gin (((position_name)::text) public.gin_trgm_ops);


--
-- Name: idx_mxik_search_sub_position_name_trgm; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mxik_search_sub_position_name_trgm ON public.mxik USING gin (((sub_position_name)::text) public.gin_trgm_ops);


--
-- Name: idx_mxik_type_created_at_id_desc; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mxik_type_created_at_id_desc ON public.mxik USING btree (upper((type)::text), created_at DESC, id DESC);


--
-- Name: idx_nms_case_queue; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_nms_case_queue ON public.non_moving_stock_cases USING btree (tenant_id, company_id, status, review_due_date, responsible_username) WHERE (is_delete = false);


--
-- Name: idx_nms_decision_line_decision; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_nms_decision_line_decision ON public.non_moving_stock_decision_lines USING btree (tenant_id, decision_id, line_order) WHERE (is_delete = false);


--
-- Name: idx_nms_detection_queue; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_nms_detection_queue ON public.non_moving_stocks USING btree (tenant_id, company_id, status, warehouse_id, material_id, detected_at) WHERE (is_delete = false);


--
-- Name: idx_nms_execution_output_execution; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_nms_execution_output_execution ON public.non_moving_stock_execution_outputs USING btree (tenant_id, execution_id, output_status) WHERE (is_delete = false);


--
-- Name: idx_nms_saved_view_owner; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_nms_saved_view_owner ON public.non_moving_stock_saved_views USING btree (tenant_id, company_id, owner_username, workspace_mode, default_view) WHERE (is_delete = false);


--
-- Name: idx_non_moving_stock_case_lines_case; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_non_moving_stock_case_lines_case ON public.non_moving_stock_case_lines USING btree (tenant_id, case_id);


--
-- Name: idx_non_moving_stock_cases_commission; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_non_moving_stock_cases_commission ON public.non_moving_stock_cases USING btree (tenant_id, company_id, commission_id);


--
-- Name: idx_non_moving_stock_cases_company_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_non_moving_stock_cases_company_status ON public.non_moving_stock_cases USING btree (tenant_id, company_id, status);


--
-- Name: idx_non_moving_stock_cases_tenant_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_non_moving_stock_cases_tenant_status ON public.non_moving_stock_cases USING btree (tenant_id, status);


--
-- Name: idx_non_moving_stock_decisions_case; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_non_moving_stock_decisions_case ON public.non_moving_stock_decisions USING btree (tenant_id, case_id);


--
-- Name: idx_non_moving_stock_executions_case; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_non_moving_stock_executions_case ON public.non_moving_stock_executions USING btree (tenant_id, case_id);


--
-- Name: idx_non_moving_stocks_tenant_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_non_moving_stocks_tenant_status ON public.non_moving_stocks USING btree (tenant_id, status);


--
-- Name: idx_notifications_entity_type_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_notifications_entity_type_id ON public.notifications USING btree (tenant_id, company_id, entity_type, entity_id) WHERE (is_delete = false);


--
-- Name: idx_po_revisions_po; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_po_revisions_po ON public.purchase_order_revisions USING btree (tenant_id, company_id, purchase_order_id);


--
-- Name: idx_po_source_allocations_po_item; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_po_source_allocations_po_item ON public.purchase_order_source_allocations USING btree (tenant_id, company_id, purchase_order_item_id, is_delete);


--
-- Name: idx_po_source_allocations_pr_item; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_po_source_allocations_pr_item ON public.purchase_order_source_allocations USING btree (tenant_id, company_id, purchase_request_item_id, is_delete);


--
-- Name: idx_positions_tenant_department; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_positions_tenant_department ON public.positions USING btree (tenant_id, department_id);


--
-- Name: idx_positions_tenant_external; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_positions_tenant_external ON public.positions USING btree (tenant_id, external_id);


--
-- Name: idx_proc_method_capability_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_proc_method_capability_scope ON public.procurement_method_capabilities USING btree (tenant_id, company_id, capability_state, effective_from, effective_to);


--
-- Name: idx_procurement_assignment_due; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_procurement_assignment_due ON public.procurement_work_assignments USING btree (tenant_id, company_id, due_date, is_delete);


--
-- Name: idx_procurement_assignment_owner; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_procurement_assignment_owner ON public.procurement_work_assignments USING btree (tenant_id, company_id, owner_user_id, is_delete);


--
-- Name: idx_procurement_assignment_severity; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_procurement_assignment_severity ON public.procurement_work_assignments USING btree (tenant_id, company_id, severity, is_delete);


--
-- Name: idx_procurement_award_outcome_contract; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_procurement_award_outcome_contract ON public.procurement_award_outcomes USING btree (tenant_id, company_id, contract_id) WHERE ((is_delete = false) AND (contract_id IS NOT NULL));


--
-- Name: idx_procurement_award_outcome_line_contract; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_procurement_award_outcome_line_contract ON public.procurement_award_outcome_lines USING btree (tenant_id, company_id, contract_id) WHERE ((is_delete = false) AND (contract_id IS NOT NULL));


--
-- Name: idx_procurement_award_outcome_line_po; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_procurement_award_outcome_line_po ON public.procurement_award_outcome_lines USING btree (tenant_id, company_id, purchase_order_id) WHERE ((is_delete = false) AND (purchase_order_id IS NOT NULL));


--
-- Name: idx_procurement_award_outcome_line_pr; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_procurement_award_outcome_line_pr ON public.procurement_award_outcome_lines USING btree (tenant_id, company_id, purchase_request_id) WHERE (is_delete = false);


--
-- Name: idx_procurement_award_outcome_line_pr_item; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_procurement_award_outcome_line_pr_item ON public.procurement_award_outcome_lines USING btree (tenant_id, company_id, purchase_request_item_id) WHERE (is_delete = false);


--
-- Name: idx_procurement_award_outcome_po; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_procurement_award_outcome_po ON public.procurement_award_outcomes USING btree (tenant_id, company_id, purchase_order_id) WHERE ((is_delete = false) AND (purchase_order_id IS NOT NULL));


--
-- Name: idx_procurement_award_outcome_procedure; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_procurement_award_outcome_procedure ON public.procurement_award_outcomes USING btree (tenant_id, company_id, procurement_procedure_id, finalized_at);


--
-- Name: idx_procurement_campaign_method_decision; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_procurement_campaign_method_decision ON public.procurement_campaigns USING btree (tenant_id, company_id, method_decision_id);


--
-- Name: idx_procurement_campaign_procedure; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_procurement_campaign_procedure ON public.procurement_campaigns USING btree (tenant_id, company_id, procurement_procedure_id);


--
-- Name: idx_procurement_campaigns_plan; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_procurement_campaigns_plan ON public.procurement_campaigns USING btree (tenant_id, company_id, procurement_plan_id);


--
-- Name: idx_procurement_campaigns_rfq; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_procurement_campaigns_rfq ON public.procurement_campaigns USING btree (tenant_id, company_id, rfq_id);


--
-- Name: idx_procurement_campaigns_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_procurement_campaigns_status ON public.procurement_campaigns USING btree (tenant_id, company_id, status);


--
-- Name: idx_procurement_campaigns_strategy; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_procurement_campaigns_strategy ON public.procurement_campaigns USING btree (tenant_id, company_id, category_strategy_id);


--
-- Name: idx_procurement_category_strategies_owner; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_procurement_category_strategies_owner ON public.procurement_category_strategies USING btree (tenant_id, company_id, owner_employee_id);


--
-- Name: idx_procurement_category_strategies_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_procurement_category_strategies_status ON public.procurement_category_strategies USING btree (tenant_id, company_id, status);


--
-- Name: idx_procurement_closeout_audit_closeout; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_procurement_closeout_audit_closeout ON public.procurement_closeout_audit_events USING btree (tenant_id, company_id, closeout_id, event_at) WHERE (is_delete = false);


--
-- Name: idx_procurement_closeouts_purchase_order; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_procurement_closeouts_purchase_order ON public.procurement_closeouts USING btree (tenant_id, company_id, purchase_order_id) WHERE (is_delete = false);


--
-- Name: idx_procurement_direct_award_supplier; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_procurement_direct_award_supplier ON public.procurement_direct_awards USING btree (tenant_id, company_id, supplier_id, is_delete);


--
-- Name: idx_procurement_method_decision_commission; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_procurement_method_decision_commission ON public.procurement_method_decisions USING btree (tenant_id, company_id, commission_id) WHERE (commission_id IS NOT NULL);


--
-- Name: idx_procurement_method_decision_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_procurement_method_decision_status ON public.procurement_method_decisions USING btree (tenant_id, company_id, status);


--
-- Name: idx_procurement_plans_cycle; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_procurement_plans_cycle ON public.procurement_plans USING btree (tenant_id, company_id, demand_planning_cycle_id);


--
-- Name: idx_procurement_plans_period_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_procurement_plans_period_status ON public.procurement_plans USING btree (tenant_id, company_id, plan_year, period_type, period_quarter, status);


--
-- Name: idx_procurement_plans_strategy; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_procurement_plans_strategy ON public.procurement_plans USING btree (tenant_id, company_id, category_strategy_id);


--
-- Name: idx_procurement_procedure_awarded_supplier; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_procurement_procedure_awarded_supplier ON public.procurement_procedures USING btree (tenant_id, company_id, awarded_supplier_id, is_delete);


--
-- Name: idx_procurement_procedure_contract; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_procurement_procedure_contract ON public.procurement_procedures USING btree (tenant_id, company_id, contract_id, is_delete);


--
-- Name: idx_procurement_procedure_purchase_order; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_procurement_procedure_purchase_order ON public.procurement_procedures USING btree (tenant_id, company_id, purchase_order_id, is_delete);


--
-- Name: idx_procurement_procedure_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_procurement_procedure_status ON public.procurement_procedures USING btree (tenant_id, company_id, status);


--
-- Name: idx_production_evidence_run_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_production_evidence_run_id ON public.production_evidence USING btree (tenant_id, company_id, run_id);


--
-- Name: idx_production_evidence_tenant_company_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_production_evidence_tenant_company_created ON public.production_evidence USING btree (tenant_id, company_id, created_at DESC);


--
-- Name: idx_production_evidence_type_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_production_evidence_type_status ON public.production_evidence USING btree (tenant_id, company_id, evidence_type, overall_status);


--
-- Name: idx_purchase_order_item_material_company_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_purchase_order_item_material_company_active ON public.purchase_order_items USING btree (tenant_id, company_id, material_id, is_delete, purchase_order_id);


--
-- Name: idx_purchase_order_items_budget_item_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_purchase_order_items_budget_item_id ON public.purchase_order_items USING btree (budget_item_id);


--
-- Name: idx_purchase_order_items_material_type_snapshot; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_purchase_order_items_material_type_snapshot ON public.purchase_order_items USING btree (tenant_id, company_id, purchase_order_id, material_type_snapshot, is_delete);


--
-- Name: idx_purchase_order_items_service_act_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_purchase_order_items_service_act_id ON public.purchase_order_items USING btree (service_act_id);


--
-- Name: idx_purchase_order_items_service_order_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_purchase_order_items_service_order_id ON public.purchase_order_items USING btree (service_order_id);


--
-- Name: idx_purchase_orders_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_purchase_orders_status ON public.purchase_orders USING btree (status);


--
-- Name: idx_purchase_orders_tenant_company_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_purchase_orders_tenant_company_status ON public.purchase_orders USING btree (tenant_id, company_id, status);


--
-- Name: idx_purchase_orders_tenant_overdue; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_purchase_orders_tenant_overdue ON public.purchase_orders USING btree (tenant_id, status, expected_delivery_date);


--
-- Name: idx_purchase_orders_tenant_pr; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_purchase_orders_tenant_pr ON public.purchase_orders USING btree (tenant_id, purchase_request_id);


--
-- Name: idx_purchase_orders_tenant_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_purchase_orders_tenant_status ON public.purchase_orders USING btree (tenant_id, status);


--
-- Name: idx_purchase_request_item_material_company_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_purchase_request_item_material_company_active ON public.purchase_request_items USING btree (tenant_id, company_id, material_id, is_delete, purchase_request_id);


--
-- Name: idx_purchase_request_items_material_type_snapshot; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_purchase_request_items_material_type_snapshot ON public.purchase_request_items USING btree (tenant_id, company_id, purchase_request_id, material_type_snapshot, is_delete);


--
-- Name: idx_purchase_requests_demand_plan; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_purchase_requests_demand_plan ON public.purchase_requests USING btree (tenant_id, company_id, demand_plan_id) WHERE (is_delete = false);


--
-- Name: idx_purchase_requests_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_purchase_requests_status ON public.purchase_requests USING btree (status);


--
-- Name: idx_purchase_requests_tenant_company_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_purchase_requests_tenant_company_status ON public.purchase_requests USING btree (tenant_id, company_id, status);


--
-- Name: idx_purchase_requests_tenant_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_purchase_requests_tenant_status ON public.purchase_requests USING btree (tenant_id, status);


--
-- Name: idx_purchase_requests_warehouse; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_purchase_requests_warehouse ON public.purchase_requests USING btree (tenant_id, company_id, warehouse_id) WHERE (is_delete = false);


--
-- Name: idx_putaway_rules_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_putaway_rules_active ON public.putaway_rules USING btree (tenant_id, company_id, warehouse_id, status, priority) WHERE (is_delete = false);


--
-- Name: idx_qms_assessment_details_tenant_company_assessment; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_qms_assessment_details_tenant_company_assessment ON public.qms_assessment_details USING btree (tenant_id, company_id, assessment_id);


--
-- Name: idx_qms_assessment_details_tenant_company_criteria; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_qms_assessment_details_tenant_company_criteria ON public.qms_assessment_details USING btree (tenant_id, company_id, criteria_id);


--
-- Name: idx_qms_assessments_tenant_company_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_qms_assessments_tenant_company_date ON public.qms_assessments USING btree (tenant_id, company_id, assessment_date);


--
-- Name: idx_qms_assessments_tenant_company_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_qms_assessments_tenant_company_status ON public.qms_assessments USING btree (tenant_id, company_id, status);


--
-- Name: idx_qms_assessments_tenant_company_supplier; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_qms_assessments_tenant_company_supplier ON public.qms_assessments USING btree (tenant_id, company_id, supplier_id);


--
-- Name: idx_qms_criteria_tenant_company_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_qms_criteria_tenant_company_active ON public.qms_criteria USING btree (tenant_id, company_id, is_active);


--
-- Name: idx_qms_criteria_tenant_company_name; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_qms_criteria_tenant_company_name ON public.qms_criteria USING btree (tenant_id, company_id, name);


--
-- Name: idx_quality_claims_nc; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_quality_claims_nc ON public.quality_claims USING btree (tenant_id, company_id, quality_nonconformance_id) WHERE (is_delete = false);


--
-- Name: idx_quality_claims_supplier; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_quality_claims_supplier ON public.quality_claims USING btree (tenant_id, company_id, supplier_id) WHERE (is_delete = false);


--
-- Name: idx_quality_inspections_material; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_quality_inspections_material ON public.quality_inspections USING btree (tenant_id, company_id, material_id) WHERE (is_delete = false);


--
-- Name: idx_quality_inspections_po; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_quality_inspections_po ON public.quality_inspections USING btree (tenant_id, company_id, purchase_order_id) WHERE (is_delete = false);


--
-- Name: idx_quality_inspections_supplier; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_quality_inspections_supplier ON public.quality_inspections USING btree (tenant_id, company_id, supplier_id) WHERE (is_delete = false);


--
-- Name: idx_quality_nc_inspection; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_quality_nc_inspection ON public.quality_nonconformances USING btree (tenant_id, company_id, quality_inspection_id) WHERE (is_delete = false);


--
-- Name: idx_quality_nc_supplier; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_quality_nc_supplier ON public.quality_nonconformances USING btree (tenant_id, company_id, supplier_id) WHERE (is_delete = false);


--
-- Name: idx_quality_quarantine_inspection; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_quality_quarantine_inspection ON public.quality_quarantine_lots USING btree (tenant_id, company_id, quality_inspection_id) WHERE (is_delete = false);


--
-- Name: idx_quality_quarantine_material; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_quality_quarantine_material ON public.quality_quarantine_lots USING btree (tenant_id, company_id, material_id) WHERE (is_delete = false);


--
-- Name: idx_quality_quarantine_nc; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_quality_quarantine_nc ON public.quality_quarantine_lots USING btree (tenant_id, company_id, quality_nonconformance_id) WHERE (is_delete = false);


--
-- Name: idx_quality_quarantine_warehouse; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_quality_quarantine_warehouse ON public.quality_quarantine_lots USING btree (tenant_id, company_id, warehouse_id) WHERE (is_delete = false);


--
-- Name: idx_reference_mapping_code; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_reference_mapping_code ON public.reference_mappings USING btree (tenant_id, company_id, reference_entity_type, reference_code, external_system);


--
-- Name: idx_reference_mapping_internal; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_reference_mapping_internal ON public.reference_mappings USING btree (tenant_id, company_id, reference_entity_type, reference_id, external_system, accounting_area);


--
-- Name: idx_return_document_lines_header; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_return_document_lines_header ON public.return_document_lines USING btree (tenant_id, return_document_id);


--
-- Name: idx_return_document_lines_issue_line; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_return_document_lines_issue_line ON public.return_document_lines USING btree (tenant_id, company_id, issue_request_line_id);


--
-- Name: idx_return_documents_issue; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_return_documents_issue ON public.return_documents USING btree (tenant_id, company_id, issue_request_id);


--
-- Name: idx_return_documents_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_return_documents_status ON public.return_documents USING btree (tenant_id, company_id, status);


--
-- Name: idx_rfid_tags_entity; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_rfid_tags_entity ON public.rfid_tags USING btree (tenant_id, company_id, entity_type, entity_id);


--
-- Name: idx_rfq_delivery_due; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_rfq_delivery_due ON public.rfq_invitation_deliveries USING btree (status, next_retry_at, created_at) WHERE (is_delete = false);


--
-- Name: idx_rfq_delivery_provider_message; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_rfq_delivery_provider_message ON public.rfq_invitation_deliveries USING btree (provider_message_id) WHERE ((is_delete = false) AND (provider_message_id IS NOT NULL));


--
-- Name: idx_rfq_invitation_deliveries_invitation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_rfq_invitation_deliveries_invitation ON public.rfq_invitation_deliveries USING btree (tenant_id, company_id, invitation_id, created_at DESC) WHERE (is_delete = false);


--
-- Name: idx_rfq_pr_sources_pr; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_rfq_pr_sources_pr ON public.rfq_purchase_request_sources USING btree (tenant_id, company_id, purchase_request_id, status, is_delete);


--
-- Name: idx_rfq_pr_sources_rfq; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_rfq_pr_sources_rfq ON public.rfq_purchase_request_sources USING btree (tenant_id, company_id, rfq_id, is_delete);


--
-- Name: idx_rfq_pr_sources_rfq_item; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_rfq_pr_sources_rfq_item ON public.rfq_purchase_request_sources USING btree (tenant_id, company_id, rfq_item_id, status, is_delete);


--
-- Name: idx_rfq_published_versions_rfq; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_rfq_published_versions_rfq ON public.rfq_published_versions USING btree (tenant_id, company_id, rfq_id, version_number DESC) WHERE (is_delete = false);


--
-- Name: idx_rfq_supplier_invitations_rfq; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_rfq_supplier_invitations_rfq ON public.rfq_supplier_invitations USING btree (tenant_id, company_id, rfq_id, status, is_delete);


--
-- Name: idx_rfq_supplier_invitations_supplier; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_rfq_supplier_invitations_supplier ON public.rfq_supplier_invitations USING btree (tenant_id, company_id, supplier_id, status, is_delete);


--
-- Name: idx_rfqs_procurement_procedure; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_rfqs_procurement_procedure ON public.rfqs USING btree (tenant_id, company_id, procurement_procedure_id, purpose, is_delete);


--
-- Name: idx_service_acceptance_acts_service_order_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_service_acceptance_acts_service_order_id ON public.service_acceptance_acts USING btree (service_order_id);


--
-- Name: idx_service_acts_contract; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_service_acts_contract ON public.service_acceptance_acts USING btree (tenant_id, company_id, contract_id);


--
-- Name: idx_service_acts_purchase_order; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_service_acts_purchase_order ON public.service_acceptance_acts USING btree (tenant_id, company_id, purchase_order_id);


--
-- Name: idx_service_acts_service_request; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_service_acts_service_request ON public.service_acceptance_acts USING btree (tenant_id, company_id, service_request_id);


--
-- Name: idx_service_acts_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_service_acts_status ON public.service_acceptance_acts USING btree (tenant_id, company_id, status);


--
-- Name: idx_service_acts_tenant_company_created_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_service_acts_tenant_company_created_at ON public.service_acceptance_acts USING btree (tenant_id, company_id, created_at DESC);


--
-- Name: idx_service_claims_origin; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_service_claims_origin ON public.service_claims USING btree (tenant_id, company_id, origin_type, status) WHERE (is_delete = false);


--
-- Name: idx_service_claims_purchase_order_item; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_service_claims_purchase_order_item ON public.service_claims USING btree (tenant_id, company_id, purchase_order_item_id, status) WHERE ((is_delete = false) AND (purchase_order_item_id IS NOT NULL));


--
-- Name: idx_service_claims_request; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_service_claims_request ON public.service_claims USING btree (tenant_id, company_id, service_request_id);


--
-- Name: idx_service_claims_service_act; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_service_claims_service_act ON public.service_claims USING btree (tenant_id, company_id, service_act_id);


--
-- Name: idx_service_claims_service_order; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_service_claims_service_order ON public.service_claims USING btree (tenant_id, company_id, service_order_id, status) WHERE ((is_delete = false) AND (service_order_id IS NOT NULL));


--
-- Name: idx_service_claims_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_service_claims_status ON public.service_claims USING btree (tenant_id, company_id, status);


--
-- Name: idx_service_claims_supplier; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_service_claims_supplier ON public.service_claims USING btree (tenant_id, company_id, supplier_id);


--
-- Name: idx_service_claims_tenant_company_created_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_service_claims_tenant_company_created_at ON public.service_claims USING btree (tenant_id, company_id, created_at DESC);


--
-- Name: idx_service_claims_work_completion_act; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_service_claims_work_completion_act ON public.service_claims USING btree (tenant_id, company_id, work_completion_act_id, status) WHERE ((is_delete = false) AND (work_completion_act_id IS NOT NULL));


--
-- Name: idx_service_executions_planned_end; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_service_executions_planned_end ON public.service_executions USING btree (tenant_id, company_id, planned_end_date);


--
-- Name: idx_service_executions_request; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_service_executions_request ON public.service_executions USING btree (tenant_id, company_id, service_request_id);


--
-- Name: idx_service_executions_responsible_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_service_executions_responsible_user ON public.service_executions USING btree (tenant_id, company_id, responsible_user_id);


--
-- Name: idx_service_executions_service_order; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_service_executions_service_order ON public.service_executions USING btree (tenant_id, company_id, service_order_id);


--
-- Name: idx_service_executions_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_service_executions_status ON public.service_executions USING btree (tenant_id, company_id, status);


--
-- Name: idx_service_executions_tenant_company_created_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_service_executions_tenant_company_created_at ON public.service_executions USING btree (tenant_id, company_id, created_at DESC);


--
-- Name: idx_service_orders_active_service_po_supplier; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_service_orders_active_service_po_supplier ON public.service_orders USING btree (tenant_id, company_id, purchase_order_id, supplier_id, created_at DESC) WHERE (is_delete = false);


--
-- Name: idx_service_orders_contract_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_service_orders_contract_id ON public.service_orders USING btree (contract_id);


--
-- Name: idx_service_orders_purchase_order_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_service_orders_purchase_order_id ON public.service_orders USING btree (purchase_order_id);


--
-- Name: idx_service_orders_service_request; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_service_orders_service_request ON public.service_orders USING btree (tenant_id, company_id, service_request_id);


--
-- Name: idx_service_orders_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_service_orders_status ON public.service_orders USING btree (tenant_id, company_id, status);


--
-- Name: idx_service_orders_supplier; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_service_orders_supplier ON public.service_orders USING btree (tenant_id, company_id, supplier_id);


--
-- Name: idx_service_orders_tenant_company_created_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_service_orders_tenant_company_created_at ON public.service_orders USING btree (tenant_id, company_id, created_at DESC);


--
-- Name: idx_service_request_milestones_request_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_service_request_milestones_request_status ON public.service_request_milestones USING btree (tenant_id, company_id, service_request_id, is_delete, status, due_date);


--
-- Name: idx_service_request_remarks_request; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_service_request_remarks_request ON public.service_request_remarks USING btree (tenant_id, company_id, service_request_id, status);


--
-- Name: idx_service_requests_budget_item; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_service_requests_budget_item ON public.service_requests USING btree (tenant_id, company_id, budget_item_id);


--
-- Name: idx_service_requests_contract; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_service_requests_contract ON public.service_requests USING btree (tenant_id, company_id, contract_id);


--
-- Name: idx_service_requests_department_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_service_requests_department_status ON public.service_requests USING btree (tenant_id, company_id, department_id, status);


--
-- Name: idx_service_requests_purchase_order; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_service_requests_purchase_order ON public.service_requests USING btree (tenant_id, company_id, purchase_order_id);


--
-- Name: idx_service_requests_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_service_requests_status ON public.service_requests USING btree (tenant_id, company_id, status);


--
-- Name: idx_service_requests_supplier; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_service_requests_supplier ON public.service_requests USING btree (tenant_id, company_id, supplier_id);


--
-- Name: idx_service_requests_tenant_company_created_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_service_requests_tenant_company_created_at ON public.service_requests USING btree (tenant_id, company_id, created_at DESC);


--
-- Name: idx_service_review_history_act; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_service_review_history_act ON public.service_review_history USING btree (tenant_id, company_id, service_act_id);


--
-- Name: idx_service_review_history_claim; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_service_review_history_claim ON public.service_review_history USING btree (tenant_id, company_id, service_claim_id, acted_at);


--
-- Name: idx_service_review_history_claim_origin; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_service_review_history_claim_origin ON public.service_review_history USING btree (tenant_id, company_id, claim_origin_type, claim_origin_id, acted_at);


--
-- Name: idx_service_review_history_request; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_service_review_history_request ON public.service_review_history USING btree (tenant_id, company_id, service_request_id);


--
-- Name: idx_services_cases_acceptance_queue; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_services_cases_acceptance_queue ON public.service_acceptance_acts USING btree (tenant_id, company_id, is_delete, service_request_id, service_order_id, status, created_at DESC);


--
-- Name: idx_services_cases_claim_assignee; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_services_cases_claim_assignee ON public.service_claims USING btree (tenant_id, company_id, is_delete, assigned_to, status);


--
-- Name: idx_services_cases_claim_queue; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_services_cases_claim_queue ON public.service_claims USING btree (tenant_id, company_id, is_delete, service_request_id, status);


--
-- Name: idx_services_cases_execution_queue; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_services_cases_execution_queue ON public.service_executions USING btree (tenant_id, company_id, is_delete, service_request_id, status, planned_end_date);


--
-- Name: idx_services_cases_integration_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_services_cases_integration_status ON public.integration_events USING btree (tenant_id, company_id, is_delete, aggregate_id, status);


--
-- Name: idx_services_cases_order_queue; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_services_cases_order_queue ON public.service_orders USING btree (tenant_id, company_id, is_delete, service_request_id, purchase_order_id, supplier_id, created_at DESC);


--
-- Name: idx_services_cases_po_service_lines; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_services_cases_po_service_lines ON public.purchase_order_items USING btree (tenant_id, company_id, is_delete, purchase_order_id, line_type);


--
-- Name: idx_services_cases_request_queue; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_services_cases_request_queue ON public.service_requests USING btree (tenant_id, company_id, is_delete, status, planned_end_date, responsible_user_id, supplier_id);


--
-- Name: idx_services_cases_work_act_queue; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_services_cases_work_act_queue ON public.work_completion_acts USING btree (tenant_id, company_id, is_delete, purchase_order_item_id, status, created_at DESC);


--
-- Name: idx_sms_gateway_settings_provider; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sms_gateway_settings_provider ON public.sms_gateway_settings USING btree (provider_code);


--
-- Name: idx_sms_gateway_settings_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sms_gateway_settings_tenant ON public.sms_gateway_settings USING btree (tenant_id, company_id);


--
-- Name: idx_sod_rule_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sod_rule_active ON public.segregation_of_duty_rules USING btree (tenant_id, company_id, entity_type, action_code, active_flag);


--
-- Name: idx_sod_violation_entity; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sod_violation_entity ON public.segregation_of_duty_violations USING btree (tenant_id, company_id, entity_type, entity_id, occurred_at, is_delete);


--
-- Name: idx_sod_violation_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sod_violation_user ON public.segregation_of_duty_violations USING btree (tenant_id, company_id, username, decision, occurred_at, is_delete);


--
-- Name: idx_sso_settings_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sso_settings_tenant ON public.sso_settings USING btree (tenant_id, company_id);


--
-- Name: idx_stock_balance_material_company_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stock_balance_material_company_active ON public.stock_balances USING btree (tenant_id, company_id, material_id, is_delete, warehouse_id);


--
-- Name: idx_stock_balances_balance_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stock_balances_balance_at ON public.stock_balances USING btree (tenant_id, company_id, balance_at);


--
-- Name: idx_stock_balances_lookup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stock_balances_lookup ON public.stock_balances USING btree (material_id, warehouse_id);


--
-- Name: idx_stock_balances_storage_bin; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stock_balances_storage_bin ON public.stock_balances USING btree (tenant_id, company_id, storage_bin_id) WHERE ((is_delete = false) AND (storage_bin_id IS NOT NULL));


--
-- Name: idx_stock_balances_tenant_lookup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stock_balances_tenant_lookup ON public.stock_balances USING btree (tenant_id, material_id, warehouse_id);


--
-- Name: idx_stock_balances_tenant_quantity; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stock_balances_tenant_quantity ON public.stock_balances USING btree (tenant_id, quantity);


--
-- Name: idx_stock_cost_movements_layer; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stock_cost_movements_layer ON public.stock_cost_movements USING btree (tenant_id, company_id, cost_layer_id) WHERE (is_delete = false);


--
-- Name: idx_stock_cost_movements_movement; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stock_cost_movements_movement ON public.stock_cost_movements USING btree (tenant_id, company_id, stock_movement_id) WHERE (is_delete = false);


--
-- Name: idx_stock_movement_allocation_cost_layers_allocation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stock_movement_allocation_cost_layers_allocation ON public.stock_movement_allocation_cost_layers USING btree (tenant_id, company_id, allocation_id, lineage_sequence);


--
-- Name: idx_stock_movement_allocation_cost_layers_layer; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stock_movement_allocation_cost_layers_layer ON public.stock_movement_allocation_cost_layers USING btree (tenant_id, company_id, cost_layer_id) WHERE (cost_layer_id IS NOT NULL);


--
-- Name: idx_stock_movement_allocations_balance; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stock_movement_allocations_balance ON public.stock_movement_allocations USING btree (tenant_id, company_id, balance_id) WHERE (balance_id IS NOT NULL);


--
-- Name: idx_stock_movement_allocations_inventory_item; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stock_movement_allocations_inventory_item ON public.stock_movement_allocations USING btree (tenant_id, company_id, source_inventory_item_id) WHERE (source_inventory_item_id IS NOT NULL);


--
-- Name: idx_stock_movement_allocations_movement_sequence; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stock_movement_allocations_movement_sequence ON public.stock_movement_allocations USING btree (tenant_id, company_id, movement_id, allocation_sequence);


--
-- Name: idx_stock_movement_documents_reference; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stock_movement_documents_reference ON public.stock_movement_documents USING btree (tenant_id, company_id, reference_type, reference_id);


--
-- Name: idx_stock_movement_documents_reversal; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stock_movement_documents_reversal ON public.stock_movement_documents USING btree (tenant_id, company_id, reversal_of_id);


--
-- Name: idx_stock_movement_documents_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stock_movement_documents_scope ON public.stock_movement_documents USING btree (tenant_id, company_id, warehouse_id, movement_date DESC, status);


--
-- Name: idx_stock_movement_material_company_posted; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stock_movement_material_company_posted ON public.stock_movements USING btree (tenant_id, company_id, material_id, is_delete, posted_at DESC, id DESC);


--
-- Name: idx_stock_movements_period_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stock_movements_period_status ON public.stock_movements USING btree (tenant_id, company_id, warehouse_id, target_warehouse_id, movement_date, status, is_delete);


--
-- Name: idx_stock_movements_posted; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stock_movements_posted ON public.stock_movements USING btree (material_id, warehouse_id, status, posted_at);


--
-- Name: idx_stock_movements_request_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stock_movements_request_date ON public.stock_movements USING btree (tenant_id, company_id, request_date);


--
-- Name: idx_stock_movements_source_inventory_item; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stock_movements_source_inventory_item ON public.stock_movements USING btree (tenant_id, company_id, source_inventory_item_id) WHERE (source_inventory_item_id IS NOT NULL);


--
-- Name: idx_stock_movements_storage_bin; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stock_movements_storage_bin ON public.stock_movements USING btree (tenant_id, company_id, storage_bin_id) WHERE ((is_delete = false) AND (storage_bin_id IS NOT NULL));


--
-- Name: idx_stock_movements_tenant_company_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stock_movements_tenant_company_created ON public.stock_movements USING btree (tenant_id, company_id, created_at DESC);


--
-- Name: idx_stock_movements_tenant_company_document; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stock_movements_tenant_company_document ON public.stock_movements USING btree (tenant_id, company_id, document_id);


--
-- Name: idx_stock_movements_tenant_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stock_movements_tenant_created ON public.stock_movements USING btree (tenant_id, created_at DESC);


--
-- Name: idx_stock_movements_tenant_posted; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stock_movements_tenant_posted ON public.stock_movements USING btree (tenant_id, material_id, warehouse_id, status, posted_at);


--
-- Name: idx_stock_movements_tenant_type_posted; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stock_movements_tenant_type_posted ON public.stock_movements USING btree (tenant_id, status, movement_type, posted_at);


--
-- Name: idx_stock_valuation_cost_job_results_job_sequence; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stock_valuation_cost_job_results_job_sequence ON public.stock_valuation_cost_job_results USING btree (job_id, sequence_no);


--
-- Name: idx_stock_valuation_cost_jobs_scope_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stock_valuation_cost_jobs_scope_created ON public.stock_valuation_cost_jobs USING btree (tenant_id, company_id, created_at DESC);


--
-- Name: idx_stock_valuation_cost_jobs_status_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stock_valuation_cost_jobs_status_created ON public.stock_valuation_cost_jobs USING btree (status, created_at);


--
-- Name: idx_stock_valuation_documents_material_warehouse; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stock_valuation_documents_material_warehouse ON public.stock_valuation_documents USING btree (tenant_id, company_id, material_id, warehouse_id);


--
-- Name: idx_stock_valuation_history_material_calculated; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stock_valuation_history_material_calculated ON public.stock_valuation_history USING btree (tenant_id, company_id, material_id, calculated_at DESC);


--
-- Name: idx_stock_valuation_history_scope_calculated; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stock_valuation_history_scope_calculated ON public.stock_valuation_history USING btree (tenant_id, company_id, calculated_at DESC);


--
-- Name: idx_stock_valuation_history_source; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stock_valuation_history_source ON public.stock_valuation_history USING btree (source_type, source_id);


--
-- Name: idx_stock_valuation_history_warehouse_calculated; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stock_valuation_history_warehouse_calculated ON public.stock_valuation_history USING btree (tenant_id, company_id, warehouse_id, calculated_at DESC);


--
-- Name: idx_stock_valuation_run_items_run_sequence; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stock_valuation_run_items_run_sequence ON public.stock_valuation_run_items USING btree (valuation_run_id, sequence_no);


--
-- Name: idx_stock_valuation_runs_job; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stock_valuation_runs_job ON public.stock_valuation_runs USING btree (job_id);


--
-- Name: idx_stock_valuation_runs_scope_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stock_valuation_runs_scope_created ON public.stock_valuation_runs USING btree (tenant_id, company_id, created_at DESC);


--
-- Name: idx_stock_valuation_runs_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stock_valuation_runs_status ON public.stock_valuation_runs USING btree (tenant_id, company_id, status);


--
-- Name: idx_storage_bins_tenant_company_location; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_storage_bins_tenant_company_location ON public.storage_bins USING btree (tenant_id, company_id, storage_location_id) WHERE (is_delete = false);


--
-- Name: idx_storage_bins_tenant_company_warehouse; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_storage_bins_tenant_company_warehouse ON public.storage_bins USING btree (tenant_id, company_id, warehouse_id) WHERE (is_delete = false);


--
-- Name: idx_supplier_accreditation_applications_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supplier_accreditation_applications_status ON public.supplier_accreditation_applications USING btree (tenant_id, company_id, status);


--
-- Name: idx_supplier_accreditation_applications_supplier; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supplier_accreditation_applications_supplier ON public.supplier_accreditation_applications USING btree (tenant_id, company_id, supplier_id);


--
-- Name: idx_supplier_accreditation_applications_validity; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supplier_accreditation_applications_validity ON public.supplier_accreditation_applications USING btree (tenant_id, company_id, valid_to);


--
-- Name: idx_supplier_accreditation_decisions_application; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supplier_accreditation_decisions_application ON public.supplier_accreditation_decisions USING btree (tenant_id, company_id, application_id);


--
-- Name: idx_supplier_accreditation_decisions_decision; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supplier_accreditation_decisions_decision ON public.supplier_accreditation_decisions USING btree (tenant_id, company_id, decision);


--
-- Name: idx_supplier_accreditation_documents_application; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supplier_accreditation_documents_application ON public.supplier_accreditation_documents USING btree (tenant_id, company_id, application_id);


--
-- Name: idx_supplier_accreditation_documents_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supplier_accreditation_documents_status ON public.supplier_accreditation_documents USING btree (tenant_id, company_id, status);


--
-- Name: idx_supplier_activation_supplier; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supplier_activation_supplier ON public.supplier_activation_records USING btree (tenant_id, company_id, supplier_id, activated_at);


--
-- Name: idx_supplier_application_requirement; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supplier_application_requirement ON public.supplier_accreditation_evidence_requirements USING btree (tenant_id, company_id, application_id, display_order);


--
-- Name: idx_supplier_bank_accounts_supplier; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supplier_bank_accounts_supplier ON public.supplier_bank_accounts USING btree (tenant_id, company_id, supplier_id);


--
-- Name: idx_supplier_corrective_actions_due; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supplier_corrective_actions_due ON public.supplier_corrective_actions USING btree (tenant_id, company_id, due_date);


--
-- Name: idx_supplier_corrective_actions_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supplier_corrective_actions_status ON public.supplier_corrective_actions USING btree (tenant_id, company_id, status);


--
-- Name: idx_supplier_corrective_actions_supplier; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supplier_corrective_actions_supplier ON public.supplier_corrective_actions USING btree (tenant_id, company_id, supplier_id);


--
-- Name: idx_supplier_evaluations_material; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supplier_evaluations_material ON public.supplier_evaluations USING btree (tenant_id, company_id, material_id);


--
-- Name: idx_supplier_evaluations_stock_movement; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supplier_evaluations_stock_movement ON public.supplier_evaluations USING btree (tenant_id, company_id, stock_movement_id);


--
-- Name: idx_supplier_kyc_checks_review; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supplier_kyc_checks_review ON public.supplier_kyc_checks USING btree (tenant_id, company_id, next_review_date);


--
-- Name: idx_supplier_kyc_checks_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supplier_kyc_checks_status ON public.supplier_kyc_checks USING btree (tenant_id, company_id, status);


--
-- Name: idx_supplier_kyc_checks_supplier; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supplier_kyc_checks_supplier ON public.supplier_kyc_checks USING btree (tenant_id, company_id, supplier_id);


--
-- Name: idx_supplier_letters_supplier; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supplier_letters_supplier ON public.supplier_letters USING btree (tenant_id, supplier_id);


--
-- Name: idx_supplier_licenses_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supplier_licenses_status ON public.supplier_licenses USING btree (tenant_id, company_id, status);


--
-- Name: idx_supplier_licenses_supplier; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supplier_licenses_supplier ON public.supplier_licenses USING btree (tenant_id, company_id, supplier_id);


--
-- Name: idx_supplier_licenses_valid_to; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supplier_licenses_valid_to ON public.supplier_licenses USING btree (tenant_id, company_id, valid_to);


--
-- Name: idx_supplier_magic_link_expiry; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supplier_magic_link_expiry ON public.supplier_magic_links USING btree (expires_at) WHERE (((status)::text = 'ISSUED'::text) AND (is_delete = false));


--
-- Name: idx_supplier_magic_link_purchase_order; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supplier_magic_link_purchase_order ON public.supplier_magic_links USING btree (tenant_id, company_id, purchase_order_id) WHERE ((is_delete = false) AND (purchase_order_id IS NOT NULL));


--
-- Name: idx_supplier_magic_link_request_email; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supplier_magic_link_request_email ON public.supplier_magic_link_requests USING btree (tenant_id, company_id, normalized_email_hash, requested_at DESC) WHERE (is_delete = false);


--
-- Name: idx_supplier_magic_link_request_ip; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supplier_magic_link_request_ip ON public.supplier_magic_link_requests USING btree (tenant_id, company_id, request_ip_hash, requested_at DESC) WHERE (is_delete = false);


--
-- Name: idx_supplier_po_commitment_po_item; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supplier_po_commitment_po_item ON public.supplier_po_commitments USING btree (tenant_id, company_id, purchase_order_item_id) WHERE (is_delete = false);


--
-- Name: idx_supplier_po_commitment_response_line; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supplier_po_commitment_response_line ON public.supplier_po_commitments USING btree (tenant_id, company_id, response_line_id) WHERE (is_delete = false);


--
-- Name: idx_supplier_po_commitment_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supplier_po_commitment_status ON public.supplier_po_commitments USING btree (tenant_id, company_id, status) WHERE (is_delete = false);


--
-- Name: idx_supplier_po_dispatch_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supplier_po_dispatch_active ON public.supplier_po_dispatches USING btree (tenant_id, company_id, dispatch_status, is_current) WHERE (is_delete = false);


--
-- Name: idx_supplier_po_dispatch_due_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supplier_po_dispatch_due_date ON public.supplier_po_dispatches USING btree (tenant_id, company_id, response_due_date) WHERE (is_delete = false);


--
-- Name: idx_supplier_po_dispatch_supplier; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supplier_po_dispatch_supplier ON public.supplier_po_dispatches USING btree (tenant_id, company_id, supplier_id) WHERE (is_delete = false);


--
-- Name: idx_supplier_po_reconfirmation_notification_commitment; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supplier_po_reconfirmation_notification_commitment ON public.supplier_po_reconfirmation_notifications USING btree (tenant_id, company_id, commitment_id) WHERE (is_delete = false);


--
-- Name: idx_supplier_po_reconfirmation_notification_due; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supplier_po_reconfirmation_notification_due ON public.supplier_po_reconfirmation_notifications USING btree (status, next_retry_at, queued_at) WHERE (is_delete = false);


--
-- Name: idx_supplier_po_response_line_item; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supplier_po_response_line_item ON public.supplier_po_response_lines USING btree (tenant_id, company_id, purchase_order_item_id) WHERE (is_delete = false);


--
-- Name: idx_supplier_po_response_line_response; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supplier_po_response_line_response ON public.supplier_po_response_lines USING btree (tenant_id, company_id, response_id) WHERE (is_delete = false);


--
-- Name: idx_supplier_po_response_po; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supplier_po_response_po ON public.supplier_po_responses USING btree (tenant_id, company_id, purchase_order_id) WHERE (is_delete = false);


--
-- Name: idx_supplier_po_response_reconfirmation_origin; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supplier_po_response_reconfirmation_origin ON public.supplier_po_responses USING btree (tenant_id, company_id, reconfirmation_origin_commitment_id) WHERE ((reconfirmation_origin_commitment_id IS NOT NULL) AND (is_delete = false));


--
-- Name: idx_supplier_po_response_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supplier_po_response_status ON public.supplier_po_responses USING btree (tenant_id, company_id, response_status) WHERE (is_delete = false);


--
-- Name: idx_supplier_portal_accounts_session_token; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supplier_portal_accounts_session_token ON public.supplier_portal_accounts USING btree (portal_token_hash) WHERE ((is_delete = false) AND (portal_token_hash IS NOT NULL));


--
-- Name: idx_supplier_portal_accounts_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supplier_portal_accounts_status ON public.supplier_portal_accounts USING btree (tenant_id, company_id, status);


--
-- Name: idx_supplier_portal_accounts_supplier; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supplier_portal_accounts_supplier ON public.supplier_portal_accounts USING btree (tenant_id, company_id, supplier_id);


--
-- Name: idx_supplier_portal_accounts_supplier_role; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supplier_portal_accounts_supplier_role ON public.supplier_portal_accounts USING btree (tenant_id, company_id, supplier_id, portal_role) WHERE (is_delete = false);


--
-- Name: idx_supplier_portal_invitation_expiry; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supplier_portal_invitation_expiry ON public.supplier_portal_accounts USING btree (tenant_id, company_id, invite_token_expires_at) WHERE (((status)::text = 'INVITED'::text) AND (is_delete = false));


--
-- Name: idx_supplier_portal_submissions_account; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supplier_portal_submissions_account ON public.supplier_portal_submissions USING btree (tenant_id, company_id, portal_account_id);


--
-- Name: idx_supplier_portal_submissions_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supplier_portal_submissions_status ON public.supplier_portal_submissions USING btree (tenant_id, company_id, status);


--
-- Name: idx_supplier_portal_submissions_supplier; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supplier_portal_submissions_supplier ON public.supplier_portal_submissions USING btree (tenant_id, company_id, supplier_id);


--
-- Name: idx_supplier_related_parties_risk; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supplier_related_parties_risk ON public.supplier_related_parties USING btree (tenant_id, company_id, risk_level);


--
-- Name: idx_supplier_related_parties_supplier; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supplier_related_parties_supplier ON public.supplier_related_parties USING btree (tenant_id, company_id, supplier_id);


--
-- Name: idx_supplier_requirement_template; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supplier_requirement_template ON public.supplier_evidence_requirements USING btree (tenant_id, company_id, template_version_id, display_order);


--
-- Name: idx_supplier_template_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supplier_template_active ON public.supplier_evidence_template_versions USING btree (tenant_id, company_id, accreditation_type, status, effective_from);


--
-- Name: idx_supplier_unblock_open; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supplier_unblock_open ON public.supplier_unblock_requests USING btree (tenant_id, company_id, supplier_id, status, is_delete);


--
-- Name: idx_supplier_unblock_requests_supplier; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supplier_unblock_requests_supplier ON public.supplier_unblock_requests USING btree (tenant_id, company_id, supplier_id, status);


--
-- Name: idx_supplier_workspace_owner; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supplier_workspace_owner ON public.suppliers USING btree (tenant_id, company_id, owner_employee_id) WHERE (is_delete = false);


--
-- Name: idx_suppliers_parent_supplier; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_suppliers_parent_supplier ON public.suppliers USING btree (tenant_id, company_id, parent_supplier_id);


--
-- Name: idx_suppliers_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_suppliers_status ON public.suppliers USING btree (status);


--
-- Name: idx_suppliers_tenant_code; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_suppliers_tenant_code ON public.suppliers USING btree (tenant_id, code);


--
-- Name: idx_suppliers_tenant_company_base_tax_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_suppliers_tenant_company_base_tax_id ON public.suppliers USING btree (tenant_id, company_id, base_tax_id);


--
-- Name: idx_suppliers_tenant_company_code; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_suppliers_tenant_company_code ON public.suppliers USING btree (tenant_id, company_id, code);


--
-- Name: idx_suppliers_tenant_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_suppliers_tenant_status ON public.suppliers USING btree (tenant_id, status);


--
-- Name: idx_suppliers_tenant_tax_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_suppliers_tenant_tax_id ON public.suppliers USING btree (tenant_id, tax_id);


--
-- Name: idx_supply_work_assignment_due; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supply_work_assignment_due ON public.supply_work_assignments USING btree (tenant_id, company_id, due_date, is_delete);


--
-- Name: idx_supply_work_assignment_owner; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supply_work_assignment_owner ON public.supply_work_assignments USING btree (tenant_id, company_id, owner_user_id, is_delete);


--
-- Name: idx_supply_work_assignment_severity; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supply_work_assignment_severity ON public.supply_work_assignments USING btree (tenant_id, company_id, severity, is_delete);


--
-- Name: idx_supply_workspace_delivery_schedules; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supply_workspace_delivery_schedules ON public.delivery_schedules USING btree (tenant_id, company_id, purchase_order_id, is_delete, status, schedule_date, id);


--
-- Name: idx_supply_workspace_purchase_order_items; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supply_workspace_purchase_order_items ON public.purchase_order_items USING btree (tenant_id, company_id, purchase_order_id, is_delete, line_type);


--
-- Name: idx_supply_workspace_purchase_orders; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_supply_workspace_purchase_orders ON public.purchase_orders USING btree (tenant_id, company_id, request_type, status, is_delete, expected_delivery_date, id);


--
-- Name: idx_svcj_warehouses_job; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_svcj_warehouses_job ON public.stock_valuation_cost_job_warehouses USING btree (job_id);


--
-- Name: idx_svr_warehouses_run; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_svr_warehouses_run ON public.stock_valuation_run_warehouses USING btree (valuation_run_id);


--
-- Name: idx_tender_bid_opening_lines_protocol; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tender_bid_opening_lines_protocol ON public.tender_bid_opening_lines USING btree (tenant_id, company_id, protocol_id);


--
-- Name: idx_tender_bid_opening_lines_quotation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tender_bid_opening_lines_quotation ON public.tender_bid_opening_lines USING btree (tenant_id, company_id, quotation_id);


--
-- Name: idx_tender_bid_opening_lines_rfq; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tender_bid_opening_lines_rfq ON public.tender_bid_opening_lines USING btree (tenant_id, company_id, rfq_id);


--
-- Name: idx_tender_bid_opening_protocols_rfq; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tender_bid_opening_protocols_rfq ON public.tender_bid_opening_protocols USING btree (tenant_id, company_id, rfq_id, opened_at DESC);


--
-- Name: idx_tender_commission_member_settings_assignment; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tender_commission_member_settings_assignment ON public.tender_commission_member_settings USING btree (tenant_id, tender_commission_id, created_at);


--
-- Name: idx_tender_commission_member_settings_member; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tender_commission_member_settings_member ON public.tender_commission_member_settings USING btree (tenant_id, company_id, commission_member_id);


--
-- Name: idx_tender_commission_members_employee; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tender_commission_members_employee ON public.tender_commission_members USING btree (tenant_id, company_id, employee_id);


--
-- Name: idx_tender_commission_members_rfq; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tender_commission_members_rfq ON public.tender_commission_members USING btree (tenant_id, company_id, rfq_id);


--
-- Name: idx_tender_commissions_commission; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tender_commissions_commission ON public.tender_commissions USING btree (tenant_id, company_id, commission_id);


--
-- Name: idx_tender_commissions_rfq; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tender_commissions_rfq ON public.tender_commissions USING btree (tenant_id, company_id, rfq_id);


--
-- Name: idx_tender_conflict_checks_rfq; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tender_conflict_checks_rfq ON public.tender_conflict_checks USING btree (tenant_id, company_id, rfq_id);


--
-- Name: idx_tender_conflict_checks_supplier; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tender_conflict_checks_supplier ON public.tender_conflict_checks USING btree (tenant_id, company_id, supplier_id);


--
-- Name: idx_tender_conflict_checks_unresolved; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tender_conflict_checks_unresolved ON public.tender_conflict_checks USING btree (tenant_id, company_id, rfq_id, conflict_found, resolved_flag);


--
-- Name: idx_tender_evaluation_protocols_recommendation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tender_evaluation_protocols_recommendation ON public.tender_evaluation_protocols USING btree (tenant_id, company_id, recommended_quotation_id);


--
-- Name: idx_tender_evaluation_protocols_rfq; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tender_evaluation_protocols_rfq ON public.tender_evaluation_protocols USING btree (tenant_id, company_id, rfq_id, evaluated_at DESC);


--
-- Name: idx_tender_price_calculation_procedure; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tender_price_calculation_procedure ON public.tender_price_calculations USING btree (tenant_id, company_id, procurement_procedure_id, is_delete);


--
-- Name: idx_tnved_codes_search; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tnved_codes_search ON public.tnved_codes USING btree (tenant_id, company_id, tnved_code, status);


--
-- Name: idx_transfer_allocation_document; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_transfer_allocation_document ON public.transfer_shipment_allocations USING btree (tenant_id, company_id, transfer_document_id);


--
-- Name: idx_transfer_clearance_allocation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_transfer_clearance_allocation ON public.transfer_allocation_clearances USING btree (tenant_id, company_id, allocation_id);


--
-- Name: idx_transfer_document_lines_header; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_transfer_document_lines_header ON public.transfer_document_lines USING btree (tenant_id, transfer_document_id);


--
-- Name: idx_transfer_documents_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_transfer_documents_status ON public.transfer_documents USING btree (tenant_id, company_id, status);


--
-- Name: idx_transfer_documents_warehouses; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_transfer_documents_warehouses ON public.transfer_documents USING btree (tenant_id, company_id, source_warehouse_id, destination_warehouse_id);


--
-- Name: idx_transfer_transit_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_transfer_transit_scope ON public.transfer_transit_entries USING btree (tenant_id, company_id, allocation_id);


--
-- Name: idx_tsd_offline_operations_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tsd_offline_operations_status ON public.tsd_offline_operations USING btree (tenant_id, company_id, status, created_at) WHERE (is_delete = false);


--
-- Name: idx_unit_of_measures_tenant_code; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_unit_of_measures_tenant_code ON public.unit_of_measures USING btree (tenant_id, code);


--
-- Name: idx_user_feedback_tenant_company_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_feedback_tenant_company_created ON public.user_feedback USING btree (tenant_id, company_id, created_at DESC) WHERE (is_delete = false);


--
-- Name: idx_users_employee_active; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_users_employee_active ON public.users USING btree (tenant_id, employee_id) WHERE ((employee_id IS NOT NULL) AND (is_delete = false));


--
-- Name: idx_vmi_stock_agreements_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_vmi_stock_agreements_active ON public.vmi_stock_agreements USING btree (tenant_id, company_id, supplier_id, material_id, warehouse_id, status) WHERE (is_delete = false);


--
-- Name: idx_warehouses_tenant_code; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_warehouses_tenant_code ON public.warehouses USING btree (tenant_id, code);


--
-- Name: idx_work_completion_acts_tenant_company_po; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_work_completion_acts_tenant_company_po ON public.work_completion_acts USING btree (tenant_id, company_id, purchase_order_id);


--
-- Name: idx_work_completion_acts_tenant_contract; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_work_completion_acts_tenant_contract ON public.work_completion_acts USING btree (tenant_id, contract_id);


--
-- Name: idx_work_completion_acts_tenant_item_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_work_completion_acts_tenant_item_status ON public.work_completion_acts USING btree (tenant_id, purchase_order_item_id, status);


--
-- Name: ix_asn_batches_item; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_asn_batches_item ON public.advance_shipping_notice_batches USING btree (tenant_id, company_id, asn_item_id) WHERE (is_delete = false);


--
-- Name: ix_asn_serials_batch; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_asn_serials_batch ON public.advance_shipping_notice_serials USING btree (tenant_id, company_id, asn_batch_id) WHERE ((is_delete = false) AND (asn_batch_id IS NOT NULL));


--
-- Name: ix_asn_serials_item; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_asn_serials_item ON public.advance_shipping_notice_serials USING btree (tenant_id, company_id, asn_item_id) WHERE (is_delete = false);


--
-- Name: ix_delivery_schedule_delay_source; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_delivery_schedule_delay_source ON public.delivery_schedules USING btree (tenant_id, company_id, status, overdue_flag, schedule_date);


--
-- Name: ix_goods_receipt_batches_line; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_goods_receipt_batches_line ON public.goods_receipt_batches USING btree (tenant_id, company_id, goods_receipt_line_id) WHERE (is_delete = false);


--
-- Name: ix_goods_receipt_batches_stock_batch; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_goods_receipt_batches_stock_batch ON public.goods_receipt_batches USING btree (tenant_id, company_id, stock_batch_id) WHERE ((is_delete = false) AND (stock_batch_id IS NOT NULL));


--
-- Name: ix_goods_receipt_units_batch; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_goods_receipt_units_batch ON public.goods_receipt_units USING btree (tenant_id, company_id, goods_receipt_batch_id) WHERE ((is_delete = false) AND (goods_receipt_batch_id IS NOT NULL));


--
-- Name: ix_goods_receipt_units_line; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_goods_receipt_units_line ON public.goods_receipt_units USING btree (tenant_id, company_id, goods_receipt_line_id) WHERE (is_delete = false);


--
-- Name: ix_material_unit_events_unit; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_material_unit_events_unit ON public.material_unit_events USING btree (tenant_id, company_id, material_unit_id) WHERE (is_delete = false);


--
-- Name: ix_material_unit_operations_reference; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_material_unit_operations_reference ON public.material_unit_operations USING btree (tenant_id, company_id, reference_type, reference_id) WHERE (is_delete = false);


--
-- Name: ix_material_unit_operations_unit; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_material_unit_operations_unit ON public.material_unit_operations USING btree (tenant_id, company_id, material_unit_id, occurred_at) WHERE (is_delete = false);


--
-- Name: ix_mo_case_reporting; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_mo_case_reporting ON public.management_opportunities USING btree (tenant_id, company_id, status, data_completeness, normalized_currency_code);


--
-- Name: ix_mo_case_search; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_mo_case_search ON public.management_opportunities USING btree (tenant_id, company_id, status, opportunity_type, as_of_date);


--
-- Name: ix_mo_case_source; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_mo_case_source ON public.management_opportunities USING btree (tenant_id, company_id, source_aggregate_type, source_aggregate_id, source_line_id);


--
-- Name: ix_mo_evidence_case; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_mo_evidence_case ON public.management_opportunity_evidence USING btree (tenant_id, company_id, opportunity_id);


--
-- Name: ix_mo_policy_dimensions; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_mo_policy_dimensions ON public.management_opportunity_policies USING btree (tenant_id, company_id, supplier_id, material_group_id, warehouse_id);


--
-- Name: ix_mo_policy_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_mo_policy_scope ON public.management_opportunity_policies USING btree (tenant_id, company_id, opportunity_type, active, effective_from, effective_to);


--
-- Name: ix_mo_run_search; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_mo_run_search ON public.management_opportunity_runs USING btree (tenant_id, company_id, status, as_of_date);


--
-- Name: ix_nms_decision_recovery_source; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_nms_decision_recovery_source ON public.non_moving_stock_decisions USING btree (tenant_id, company_id, status, decision_type);


--
-- Name: ix_proc_framework_ledger_framework; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_proc_framework_ledger_framework ON public.procurement_framework_ledger USING btree (tenant_id, company_id, framework_id, occurred_at) WHERE (is_delete = false);


--
-- Name: ix_proc_framework_source_pr; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_proc_framework_source_pr ON public.procurement_frameworks USING btree (tenant_id, company_id, purchase_request_id) WHERE (is_delete = false);


--
-- Name: ix_procurement_line_allocations_source; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_procurement_line_allocations_source ON public.procurement_line_allocations USING btree (tenant_id, company_id, source_document_type, source_document_id, source_line_id) WHERE (is_delete = false);


--
-- Name: ix_procurement_line_allocations_supplier; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_procurement_line_allocations_supplier ON public.procurement_line_allocations USING btree (tenant_id, company_id, supplier_id) WHERE ((supplier_id IS NOT NULL) AND (is_delete = false));


--
-- Name: ix_procurement_line_allocations_target; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_procurement_line_allocations_target ON public.procurement_line_allocations USING btree (tenant_id, company_id, target_document_type, target_document_id, target_line_id) WHERE (is_delete = false);


--
-- Name: ix_stock_batches_external_batch_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_stock_batches_external_batch_scope ON public.stock_batches USING btree (tenant_id, company_id, material_id, manufacturer_batch_number) WHERE ((is_delete = false) AND (manufacturer_batch_number IS NOT NULL));


--
-- Name: ix_transfer_exception_allocation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_transfer_exception_allocation ON public.transfer_exceptions USING btree (tenant_id, company_id, allocation_id);


--
-- Name: ix_transfer_open_reconciliation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_transfer_open_reconciliation ON public.transfer_documents USING btree (tenant_id, company_id, reconciliation_status) WHERE ((NOT is_delete) AND ((status)::text <> ALL ((ARRAY['CLOSED'::character varying, 'CANCELLED'::character varying])::text[])));


--
-- Name: uk_approval_flow_parallel_assignee_user_active; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_approval_flow_parallel_assignee_user_active ON public.approval_flow_parallel_assignees USING btree (approval_flow_id, assignee_username) WHERE (is_delete = false);


--
-- Name: uk_approval_instances_entity; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_approval_instances_entity ON public.approval_instances USING btree (tenant_id, company_id, module_key, entity_id) WHERE (is_delete = false);


--
-- Name: uk_asset_acceptance_units_posted_asset; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_asset_acceptance_units_posted_asset ON public.asset_acceptance_units USING btree (tenant_id, company_id, posted_fixed_asset_id) WHERE ((posted_fixed_asset_id IS NOT NULL) AND (is_delete = false));


--
-- Name: uk_asset_acceptance_units_receiving_number; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_asset_acceptance_units_receiving_number ON public.asset_acceptance_units USING btree (tenant_id, company_id, upper((receiving_unit_number)::text)) WHERE (is_delete = false);


--
-- Name: uk_asset_allocations_active_asset; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_asset_allocations_active_asset ON public.asset_allocations USING btree (tenant_id, company_id, fixed_asset_id) WHERE (((status)::text = 'ACTIVE'::text) AND (is_delete = false));


--
-- Name: uk_budget_transactions_idempotency; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_budget_transactions_idempotency ON public.budget_transactions USING btree (tenant_id, company_id, idempotency_key) WHERE (is_delete = false);


--
-- Name: uk_budget_transactions_reversal_action; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_budget_transactions_reversal_action ON public.budget_transactions USING btree (tenant_id, company_id, reversal_of_transaction_id, action_key) WHERE ((is_delete = false) AND (reversal_of_transaction_id IS NOT NULL));


--
-- Name: uk_budget_transactions_source_action; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_budget_transactions_source_action ON public.budget_transactions USING btree (tenant_id, company_id, source_key, action_key) WHERE (is_delete = false);


--
-- Name: uk_consolidated_demand_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_consolidated_demand_key ON public.consolidated_demands USING btree (tenant_id, company_id, demand_plan_id, material_id, budget_item_id, period_start, period_end, highest_priority);


--
-- Name: uk_contract_active_source_quotation; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_contract_active_source_quotation ON public.contracts USING btree (tenant_id, company_id, source_quotation_id) WHERE ((source_quotation_id IS NOT NULL) AND (is_delete = false));


--
-- Name: uk_contract_active_source_rfq; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_contract_active_source_rfq ON public.contracts USING btree (tenant_id, company_id, source_rfq_id) WHERE ((source_rfq_id IS NOT NULL) AND (is_delete = false));


--
-- Name: uk_contract_invoice_evidence_snapshot_invoice; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_contract_invoice_evidence_snapshot_invoice ON public.contract_invoice_evidence_snapshots USING btree (tenant_id, company_id, contract_invoice_id) WHERE (is_delete = false);


--
-- Name: uk_contract_spec_source_quote_item; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_contract_spec_source_quote_item ON public.contract_specifications USING btree (tenant_id, company_id, source_quotation_item_id) WHERE ((source_quotation_item_id IS NOT NULL) AND (is_delete = false));


--
-- Name: uk_country_code_code; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_country_code_code ON public.country_code USING btree (code) WHERE (code IS NOT NULL);


--
-- Name: uk_currencies_code; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_currencies_code ON public.currencies USING btree (code);


--
-- Name: uk_demand_item_pr_allocation_active; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_demand_item_pr_allocation_active ON public.demand_plan_item_pr_allocations USING btree (tenant_id, company_id, demand_plan_item_id, purchase_request_item_id) WHERE (is_delete = false);


--
-- Name: uk_demand_plan_code; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_demand_plan_code ON public.demand_plans USING btree (tenant_id, company_id, code);


--
-- Name: uk_demand_plan_item_months_active; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_demand_plan_item_months_active ON public.demand_plan_item_months USING btree (tenant_id, company_id, demand_plan_item_id, month) WHERE (is_delete = false);


--
-- Name: uk_demand_planning_cycle_code; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_demand_planning_cycle_code ON public.demand_planning_cycles USING btree (tenant_id, company_id, code);


--
-- Name: uk_equipment_code; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_equipment_code ON public.equipment USING btree (tenant_id, company_id, code);


--
-- Name: uk_fixed_assets_equipment; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_fixed_assets_equipment ON public.fixed_assets USING btree (tenant_id, company_id, equipment_id) WHERE ((equipment_id IS NOT NULL) AND (is_delete = false));


--
-- Name: uk_fixed_assets_inventory_number; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_fixed_assets_inventory_number ON public.fixed_assets USING btree (tenant_id, company_id, inventory_number) WHERE ((inventory_number IS NOT NULL) AND (is_delete = false));


--
-- Name: uk_fixed_assets_opening_source; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_fixed_assets_opening_source ON public.fixed_assets USING btree (tenant_id, company_id, opening_source_type, opening_source_id) WHERE ((opening_source_id IS NOT NULL) AND (is_delete = false));


--
-- Name: uk_fixed_assets_source_acceptance_unit; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_fixed_assets_source_acceptance_unit ON public.fixed_assets USING btree (tenant_id, company_id, source_acceptance_unit_id) WHERE ((source_acceptance_unit_id IS NOT NULL) AND (is_delete = false));


--
-- Name: uk_goods_receipts_idempotency; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_goods_receipts_idempotency ON public.goods_receipts USING btree (tenant_id, company_id, purchase_order_id, idempotency_key) WHERE ((idempotency_key IS NOT NULL) AND (is_delete = false));


--
-- Name: uk_inventory_items_linked_stock_movement; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_inventory_items_linked_stock_movement ON public.inventory_items USING btree (tenant_id, company_id, linked_stock_movement_id) WHERE ((is_delete = false) AND (linked_stock_movement_id IS NOT NULL));


--
-- Name: uk_manufacturers_tenant_company_code_deleted; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_manufacturers_tenant_company_code_deleted ON public.manufacturers USING btree (tenant_id, company_id, code, is_delete);


--
-- Name: uk_manufacturers_tenant_company_name_deleted; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_manufacturers_tenant_company_name_deleted ON public.manufacturers USING btree (tenant_id, company_id, name, is_delete);


--
-- Name: uk_material_analog_pair; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_material_analog_pair ON public.material_analogs USING btree (tenant_id, company_id, material_id, analog_material_id) WHERE (is_delete = false);


--
-- Name: uk_material_attributes_material_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_material_attributes_material_key ON public.material_attributes USING btree (material_id, lower((attribute_key)::text)) WHERE (is_deleted = false);


--
-- Name: uk_material_cost_observations_source_line; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_material_cost_observations_source_line ON public.material_cost_observations USING btree (tenant_id, company_id, source_type, source_line_id) WHERE (is_delete = false);


--
-- Name: uk_material_duplicate_candidate_pair_open; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_material_duplicate_candidate_pair_open ON public.material_duplicate_candidates USING btree (tenant_id, company_id, material_id, candidate_material_id) WHERE ((is_delete = false) AND ((status)::text = 'OPEN'::text));


--
-- Name: uk_material_equipment_compatibility; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_material_equipment_compatibility ON public.material_equipment_compatibilities USING btree (tenant_id, company_id, material_id, equipment_id, compatibility_type) WHERE (is_delete = false);


--
-- Name: uk_material_market_prices_approved_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_material_market_prices_approved_scope ON public.material_market_prices USING btree (tenant_id, company_id, material_id, warehouse_id, upper(btrim((currency_code)::text)), effective_date) NULLS NOT DISTINCT WHERE ((is_delete = false) AND ((status)::text = 'APPROVED'::text));


--
-- Name: uk_material_unit_operations_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_material_unit_operations_key ON public.material_unit_operations USING btree (tenant_id, company_id, operation_type, material_unit_id, operation_key) WHERE ((is_delete = false) AND (operation_key IS NOT NULL));


--
-- Name: uk_material_units_company_material_serial; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_material_units_company_material_serial ON public.material_units USING btree (tenant_id, company_id, material_id, normalized_serial_number) WHERE (is_delete = false);


--
-- Name: uk_material_units_receipt_unit; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_material_units_receipt_unit ON public.material_units USING btree (tenant_id, company_id, goods_receipt_unit_id) WHERE (is_delete = false);


--
-- Name: uk_nms_active_detection_case; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_nms_active_detection_case ON public.non_moving_stock_case_lines USING btree (tenant_id, non_moving_stock_id) WHERE ((active_membership = true) AND (is_delete = false));


--
-- Name: uk_nms_execution_idempotency; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_nms_execution_idempotency ON public.non_moving_stock_executions USING btree (tenant_id, company_id, idempotency_key) WHERE ((idempotency_key IS NOT NULL) AND (is_delete = false));


--
-- Name: uk_nms_saved_view_owner_name; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_nms_saved_view_owner_name ON public.non_moving_stock_saved_views USING btree (tenant_id, company_id, owner_username, workspace_mode, name) WHERE (is_delete = false);


--
-- Name: uk_po_source_allocation_direct; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_po_source_allocation_direct ON public.purchase_order_source_allocations USING btree (tenant_id, company_id, purchase_order_item_id, purchase_request_item_id) WHERE ((rfq_purchase_request_source_id IS NULL) AND (is_delete = false));


--
-- Name: uk_po_source_allocation_rfq; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_po_source_allocation_rfq ON public.purchase_order_source_allocations USING btree (tenant_id, company_id, purchase_order_item_id, purchase_request_item_id, rfq_purchase_request_source_id) WHERE ((rfq_purchase_request_source_id IS NOT NULL) AND (is_delete = false));


--
-- Name: uk_proc_framework_ledger_idempotent_type; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_proc_framework_ledger_idempotent_type ON public.procurement_framework_ledger USING btree (tenant_id, company_id, framework_id, idempotency_key, transaction_type) WHERE (is_delete = false);


--
-- Name: uk_proc_framework_procedure_active; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_proc_framework_procedure_active ON public.procurement_frameworks USING btree (tenant_id, company_id, procedure_id) WHERE ((is_delete = false) AND (procedure_id IS NOT NULL));


--
-- Name: uk_proc_method_capability_active; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_proc_method_capability_active ON public.procurement_method_capabilities USING btree (tenant_id, company_id, method_code) WHERE (is_delete = false);


--
-- Name: uk_procurement_assignment_active; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_procurement_assignment_active ON public.procurement_work_assignments USING btree (tenant_id, company_id, purchase_request_id) WHERE (is_delete = false);


--
-- Name: uk_procurement_award_outcome_idempotency; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_procurement_award_outcome_idempotency ON public.procurement_award_outcomes USING btree (tenant_id, company_id, procurement_procedure_id, source_type, idempotency_key) WHERE (is_delete = false);


--
-- Name: uk_procurement_award_outcome_line_idempotency; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_procurement_award_outcome_line_idempotency ON public.procurement_award_outcome_lines USING btree (tenant_id, company_id, procurement_award_outcome_id, idempotency_key) WHERE (is_delete = false);


--
-- Name: uk_procurement_closeouts_active_procedure; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_procurement_closeouts_active_procedure ON public.procurement_closeouts USING btree (tenant_id, company_id, procurement_procedure_id) WHERE (is_delete = false);


--
-- Name: uk_reference_mapping_external; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_reference_mapping_external ON public.reference_mappings USING btree (tenant_id, company_id, reference_entity_type, external_system, external_code, accounting_area, is_delete);


--
-- Name: uk_rfid_tags_value; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_rfid_tags_value ON public.rfid_tags USING btree (tenant_id, company_id, tag_value);


--
-- Name: uk_rfq_pr_source_active_item; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_rfq_pr_source_active_item ON public.rfq_purchase_request_sources USING btree (tenant_id, company_id, purchase_request_item_id) WHERE (((status)::text = 'ACTIVE'::text) AND (is_delete = false));


--
-- Name: uk_roles_tenant_global_role_name; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_roles_tenant_global_role_name ON public.roles USING btree (tenant_id, role_name) WHERE (company_id IS NULL);


--
-- Name: uk_stock_balance_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_stock_balance_key ON public.stock_balances USING btree (tenant_id, company_id, material_id, warehouse_id, storage_location_id, storage_bin_id, stock_batch_id) NULLS NOT DISTINCT WHERE (is_delete = false);


--
-- Name: uk_stock_movements_inventory_source_line; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_stock_movements_inventory_source_line ON public.stock_movements USING btree (tenant_id, company_id, source_inventory_item_id) WHERE ((is_delete = false) AND (source_inventory_item_id IS NOT NULL));


--
-- Name: uk_supplier_bank_accounts_tenant_company_bank_account_hash_acti; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_supplier_bank_accounts_tenant_company_bank_account_hash_acti ON public.supplier_bank_accounts USING btree (tenant_id, company_id, bank_account_hash) WHERE ((is_delete = false) AND (bank_account_hash IS NOT NULL));


--
-- Name: uk_supplier_quotation_items_active_rfq_line; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_supplier_quotation_items_active_rfq_line ON public.supplier_quotation_items USING btree (quotation_id, rfq_item_id) WHERE ((is_delete = false) AND (rfq_item_id IS NOT NULL));


--
-- Name: uk_suppliers_tenant_company_bank_account_hash_active; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_suppliers_tenant_company_bank_account_hash_active ON public.suppliers USING btree (tenant_id, company_id, bank_account_hash) WHERE ((is_delete = false) AND (bank_account_hash IS NOT NULL));


--
-- Name: uk_supply_work_assignment_active; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_supply_work_assignment_active ON public.supply_work_assignments USING btree (tenant_id, company_id, commitment_key) WHERE (is_delete = false);


--
-- Name: uq_ai_analysis_jobs_active_entity_hash; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_ai_analysis_jobs_active_entity_hash ON public.ai_analysis_jobs USING btree (tenant_id, company_id, analysis_type, entity_type, entity_id, request_hash, requested_language) WHERE ((entity_id IS NOT NULL) AND ((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'RUNNING'::character varying])::text[])));


--
-- Name: uq_ai_analysis_jobs_active_null_entity_hash; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_ai_analysis_jobs_active_null_entity_hash ON public.ai_analysis_jobs USING btree (tenant_id, company_id, analysis_type, entity_type, request_hash, requested_language) WHERE ((entity_id IS NULL) AND ((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'RUNNING'::character varying])::text[])));


--
-- Name: uq_asn_number_deleted; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_asn_number_deleted ON public.advance_shipping_notices USING btree (tenant_id, company_id, asn_number, is_delete);


--
-- Name: uq_cross_docking_tasks_number; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_cross_docking_tasks_number ON public.cross_docking_tasks USING btree (tenant_id, company_id, task_number) WHERE ((is_delete = false) AND (task_number IS NOT NULL));


--
-- Name: uq_customs_declarations_number_deleted; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_customs_declarations_number_deleted ON public.customs_declarations USING btree (tenant_id, company_id, declaration_number, is_delete);


--
-- Name: uq_delivery_schedule_commitment; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_delivery_schedule_commitment ON public.delivery_schedules USING btree (supplier_po_commitment_id) WHERE ((supplier_po_commitment_id IS NOT NULL) AND (is_delete = false));


--
-- Name: uq_import_deliveries_shipment_deleted; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_import_deliveries_shipment_deleted ON public.import_deliveries USING btree (tenant_id, company_id, shipment_number, is_delete);


--
-- Name: uq_import_freight_booking_number_deleted; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_import_freight_booking_number_deleted ON public.import_freight_bookings USING btree (tenant_id, company_id, booking_number, is_delete);


--
-- Name: uq_import_insurance_policy_number_deleted; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_import_insurance_policy_number_deleted ON public.import_insurance_policies USING btree (tenant_id, company_id, policy_number, is_delete);


--
-- Name: uq_incoterms_code_deleted; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_incoterms_code_deleted ON public.incoterms USING btree (tenant_id, company_id, code, is_delete);


--
-- Name: uq_laboratory_tests_sample_deleted; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_laboratory_tests_sample_deleted ON public.laboratory_tests USING btree (tenant_id, company_id, sample_number) WHERE ((is_delete = false) AND (sample_number IS NOT NULL));


--
-- Name: uq_procurement_campaign_number_deleted; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_procurement_campaign_number_deleted ON public.procurement_campaigns USING btree (tenant_id, company_id, campaign_number, is_delete);


--
-- Name: uq_procurement_category_strategy_year_deleted; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_procurement_category_strategy_year_deleted ON public.procurement_category_strategies USING btree (tenant_id, company_id, material_group_id, strategy_year, is_delete);


--
-- Name: uq_procurement_plan_number_deleted; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_procurement_plan_number_deleted ON public.procurement_plans USING btree (tenant_id, company_id, plan_number, is_delete);


--
-- Name: uq_quality_claims_number_deleted; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_quality_claims_number_deleted ON public.quality_claims USING btree (tenant_id, company_id, claim_number) WHERE ((is_delete = false) AND (claim_number IS NOT NULL));


--
-- Name: uq_quality_inspections_number_deleted; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_quality_inspections_number_deleted ON public.quality_inspections USING btree (tenant_id, company_id, inspection_number) WHERE ((is_delete = false) AND (inspection_number IS NOT NULL));


--
-- Name: uq_quality_quarantine_inspection_active; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_quality_quarantine_inspection_active ON public.quality_quarantine_lots USING btree (tenant_id, company_id, quality_inspection_id) WHERE ((is_delete = false) AND ((status)::text <> 'CANCELLED'::text));


--
-- Name: uq_quality_quarantine_number_deleted; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_quality_quarantine_number_deleted ON public.quality_quarantine_lots USING btree (tenant_id, company_id, quarantine_number) WHERE ((is_delete = false) AND (quarantine_number IS NOT NULL));


--
-- Name: uq_sod_rule_entity_action_type_deleted; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_sod_rule_entity_action_type_deleted ON public.segregation_of_duty_rules USING btree (tenant_id, company_id, entity_type, action_code, rule_type, is_delete);


--
-- Name: uq_stock_movement_allocation_cost_layers_sequence; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_stock_movement_allocation_cost_layers_sequence ON public.stock_movement_allocation_cost_layers USING btree (tenant_id, company_id, allocation_id, lineage_sequence);


--
-- Name: uq_stock_movement_allocations_sequence; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_stock_movement_allocations_sequence ON public.stock_movement_allocations USING btree (tenant_id, company_id, movement_id, allocation_sequence);


--
-- Name: uq_stock_movement_documents_active_reversal; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_stock_movement_documents_active_reversal ON public.stock_movement_documents USING btree (tenant_id, company_id, reversal_of_id) WHERE ((is_delete = false) AND (reversal_of_id IS NOT NULL));


--
-- Name: uq_stock_movements_active_reversal; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_stock_movements_active_reversal ON public.stock_movements USING btree (tenant_id, company_id, reversal_of_id) WHERE ((is_delete = false) AND (reversal_of_id IS NOT NULL));


--
-- Name: uq_stock_valuation_cost_jobs_active_all_warehouses; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_stock_valuation_cost_jobs_active_all_warehouses ON public.stock_valuation_cost_jobs USING btree (tenant_id, company_id) WHERE ((warehouse_id IS NULL) AND ((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'RUNNING'::character varying])::text[])));


--
-- Name: uq_stock_valuation_cost_jobs_active_warehouse; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_stock_valuation_cost_jobs_active_warehouse ON public.stock_valuation_cost_jobs USING btree (tenant_id, company_id, warehouse_id) WHERE ((warehouse_id IS NOT NULL) AND ((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'RUNNING'::character varying])::text[])));


--
-- Name: uq_supplier_licenses_number_deleted; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_supplier_licenses_number_deleted ON public.supplier_licenses USING btree (tenant_id, company_id, supplier_id, license_number, is_delete);


--
-- Name: uq_supplier_po_commitment_current_replacement; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_supplier_po_commitment_current_replacement ON public.supplier_po_commitments USING btree (tenant_id, company_id, superseded_commitment_id) WHERE ((superseded_commitment_id IS NOT NULL) AND (is_current = true) AND (is_delete = false) AND ((status)::text = 'CONFIRMED'::text));


--
-- Name: uq_supplier_po_commitment_schedule; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_supplier_po_commitment_schedule ON public.supplier_po_commitments USING btree (delivery_schedule_id) WHERE ((delivery_schedule_id IS NOT NULL) AND (is_delete = false));


--
-- Name: uq_supplier_po_dispatch_current; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_supplier_po_dispatch_current ON public.supplier_po_dispatches USING btree (tenant_id, company_id, purchase_order_id) WHERE ((is_current = true) AND (is_delete = false));


--
-- Name: uq_supplier_po_dispatch_po_version; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_supplier_po_dispatch_po_version ON public.supplier_po_dispatches USING btree (purchase_order_id, purchase_order_version);


--
-- Name: uq_supplier_po_reconfirmation_notification_response; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_supplier_po_reconfirmation_notification_response ON public.supplier_po_reconfirmation_notifications USING btree (tenant_id, company_id, reconfirmation_response_id, event_type) WHERE (is_delete = false);


--
-- Name: uq_supplier_po_response_active_reconfirmation; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_supplier_po_response_active_reconfirmation ON public.supplier_po_responses USING btree (tenant_id, company_id, reconfirmation_origin_commitment_id) WHERE ((reconfirmation_origin_commitment_id IS NOT NULL) AND (is_current = true) AND (is_delete = false) AND ((response_status)::text = ANY ((ARRAY['DRAFT'::character varying, 'SUBMITTED'::character varying, 'REVISION_REQUESTED'::character varying])::text[])));


--
-- Name: uq_supplier_po_response_current; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_supplier_po_response_current ON public.supplier_po_responses USING btree (tenant_id, company_id, dispatch_id) WHERE ((is_current = true) AND (is_delete = false));


--
-- Name: uq_supplier_po_response_dispatch_revision; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_supplier_po_response_dispatch_revision ON public.supplier_po_responses USING btree (dispatch_id, response_revision);


--
-- Name: uq_supplier_portal_accounts_email_deleted; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_supplier_portal_accounts_email_deleted ON public.supplier_portal_accounts USING btree (tenant_id, company_id, login_email, is_delete);


--
-- Name: uq_transfer_transit_dispatch; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_transfer_transit_dispatch ON public.transfer_transit_entries USING btree (allocation_id) WHERE ((kind)::text = 'DISPATCH'::text);


--
-- Name: uq_tsd_offline_operation_idempotency; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_tsd_offline_operation_idempotency ON public.tsd_offline_operations USING btree (tenant_id, company_id, device_id, client_operation_id) WHERE (is_delete = false);


--
-- Name: ux_fcm_tokens_token; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_fcm_tokens_token ON public.fcm_tokens USING btree (token) WHERE (is_delete = false);


--
-- Name: ux_procurement_direct_award_procedure_active; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_procurement_direct_award_procedure_active ON public.procurement_direct_awards USING btree (tenant_id, company_id, procurement_procedure_id) WHERE (is_delete = false);


--
-- Name: ux_procurement_direct_award_purchase_order; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_procurement_direct_award_purchase_order ON public.procurement_direct_awards USING btree (tenant_id, company_id, purchase_order_id) WHERE ((is_delete = false) AND (purchase_order_id IS NOT NULL));


--
-- Name: ux_procurement_line_allocations_idempotency; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_procurement_line_allocations_idempotency ON public.procurement_line_allocations USING btree (tenant_id, company_id, idempotency_key) WHERE (is_delete = false);


--
-- Name: ux_procurement_method_decision_number_active; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_procurement_method_decision_number_active ON public.procurement_method_decisions USING btree (tenant_id, company_id, decision_number) WHERE (is_delete = false);


--
-- Name: ux_procurement_procedure_decision_active; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_procurement_procedure_decision_active ON public.procurement_procedures USING btree (tenant_id, company_id, method_decision_id) WHERE (is_delete = false);


--
-- Name: ux_procurement_procedure_number_active; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_procurement_procedure_number_active ON public.procurement_procedures USING btree (tenant_id, company_id, procedure_number) WHERE (is_delete = false);


--
-- Name: ux_rfq_supplier_invitations_active; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_rfq_supplier_invitations_active ON public.rfq_supplier_invitations USING btree (tenant_id, company_id, rfq_id, supplier_id) WHERE (((status)::text = 'INVITED'::text) AND (is_delete = false));


--
-- Name: ux_service_acts_active_order_stage_period_company; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_service_acts_active_order_stage_period_company ON public.service_acceptance_acts USING btree (tenant_id, company_id, service_order_id, upper(btrim((stage)::text)), COALESCE(period_start, '1900-01-01'::date), COALESCE(period_end, '1900-01-01'::date)) WHERE ((is_delete = false) AND (service_order_id IS NOT NULL) AND ((status)::text <> ALL ((ARRAY['REJECTED'::character varying, 'CANCELLED'::character varying])::text[])));


--
-- Name: ux_service_request_milestones_active_sequence; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_service_request_milestones_active_sequence ON public.service_request_milestones USING btree (tenant_id, company_id, service_request_id, milestone_sequence) WHERE (is_delete = false);


--
-- Name: ux_supplier_quotation_current_revision; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_supplier_quotation_current_revision ON public.supplier_quotations USING btree (tenant_id, company_id, thread_id) WHERE ((current_revision = true) AND (is_delete = false) AND ((status)::text = ANY ((ARRAY['SUBMITTED'::character varying, 'SELECTED'::character varying, 'REJECTED'::character varying])::text[])));


--
-- Name: ux_supplier_unblock_one_open_request; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_supplier_unblock_one_open_request ON public.supplier_unblock_requests USING btree (tenant_id, company_id, supplier_id) WHERE (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'UNDER_REVIEW'::character varying])::text[])) AND (is_delete = false));


--
-- Name: ux_tender_price_calc_quote_active; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_tender_price_calc_quote_active ON public.tender_price_calculation_quotations USING btree (tenant_id, company_id, calculation_id, quotation_id) WHERE (is_delete = false);


--
-- Name: ux_tender_price_calculation_procedure_active; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_tender_price_calculation_procedure_active ON public.tender_price_calculations USING btree (tenant_id, company_id, procurement_procedure_id) WHERE (is_delete = false);


--
-- Name: ux_tender_price_calculation_survey_active; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_tender_price_calculation_survey_active ON public.tender_price_calculations USING btree (tenant_id, company_id, price_discovery_rfq_id) WHERE (is_delete = false);


--
-- Name: purchase_order_items ck_purchase_orders_no_mixed_tmb_fixed_assets; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER ck_purchase_orders_no_mixed_tmb_fixed_assets BEFORE INSERT OR UPDATE OF material_type_snapshot, purchase_order_id, is_delete ON public.purchase_order_items FOR EACH ROW EXECUTE FUNCTION public.reject_mixed_purchase_order_material_types();


--
-- Name: purchase_request_items ck_purchase_requests_no_mixed_tmb_fixed_assets; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER ck_purchase_requests_no_mixed_tmb_fixed_assets BEFORE INSERT OR UPDATE OF material_type_snapshot, purchase_request_id, is_delete ON public.purchase_request_items FOR EACH ROW EXECUTE FUNCTION public.reject_mixed_purchase_request_material_types();


--
-- Name: transfer_shipment_allocations transfer_allocations_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER transfer_allocations_immutable BEFORE DELETE OR UPDATE ON public.transfer_shipment_allocations FOR EACH ROW EXECUTE FUNCTION public.reject_transfer_fact_mutation();


--
-- Name: transfer_allocation_clearances transfer_clearances_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER transfer_clearances_immutable BEFORE DELETE OR UPDATE ON public.transfer_allocation_clearances FOR EACH ROW EXECUTE FUNCTION public.reject_transfer_fact_mutation();


--
-- Name: transfer_shipments transfer_shipments_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER transfer_shipments_immutable BEFORE DELETE OR UPDATE ON public.transfer_shipments FOR EACH ROW EXECUTE FUNCTION public.reject_transfer_fact_mutation();


--
-- Name: transfer_transit_entries transfer_transit_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER transfer_transit_immutable BEFORE DELETE OR UPDATE ON public.transfer_transit_entries FOR EACH ROW EXECUTE FUNCTION public.reject_transfer_fact_mutation();


--
-- Name: budget_transactions trg_budget_transactions_append_only; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_budget_transactions_append_only BEFORE DELETE OR UPDATE ON public.budget_transactions FOR EACH ROW EXECUTE FUNCTION public.prevent_budget_transaction_mutation();


--
-- Name: budget_transactions trg_budget_transactions_ownership; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_budget_transactions_ownership BEFORE INSERT ON public.budget_transactions FOR EACH ROW EXECUTE FUNCTION public.validate_budget_transaction_ownership();


--
-- Name: contract_invoice_evidence_snapshots trg_contract_invoice_evidence_snapshots_append_only; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_contract_invoice_evidence_snapshots_append_only BEFORE DELETE OR UPDATE ON public.contract_invoice_evidence_snapshots FOR EACH ROW EXECUTE FUNCTION public.reject_contract_invoice_evidence_snapshot_mutation();


--
-- Name: goods_receipt_evidence trg_goods_receipt_evidence_append_only; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_goods_receipt_evidence_append_only BEFORE DELETE OR UPDATE ON public.goods_receipt_evidence FOR EACH ROW EXECUTE FUNCTION public.reject_goods_receipt_evidence_mutation();


--
-- Name: goods_receipt_lines trg_goods_receipt_lines_append_only; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_goods_receipt_lines_append_only BEFORE DELETE OR UPDATE ON public.goods_receipt_lines FOR EACH ROW EXECUTE FUNCTION public.reject_goods_receipt_evidence_mutation();


--
-- Name: goods_receipts trg_goods_receipts_append_only; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_goods_receipts_append_only BEFORE DELETE OR UPDATE ON public.goods_receipts FOR EACH ROW EXECUTE FUNCTION public.reject_goods_receipt_evidence_mutation();


--
-- Name: material_reservation_events trg_material_reservation_events_append_only; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_material_reservation_events_append_only BEFORE DELETE OR UPDATE ON public.material_reservation_events FOR EACH ROW EXECUTE FUNCTION public.reject_material_reservation_event_mutation();


--
-- Name: advance_shipping_notice_items trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.advance_shipping_notice_items FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: advance_shipping_notices trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.advance_shipping_notices FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: approval_flow_parallel_assignees trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.approval_flow_parallel_assignees FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: approval_flow_steps trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.approval_flow_steps FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: approval_flows trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.approval_flows FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: approval_history trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.approval_history FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: approval_instances trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.approval_instances FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: approval_steps trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.approval_steps FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: approval_tasks trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.approval_tasks FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: approval_workflows trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.approval_workflows FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: attachments trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.attachments FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: audit_logs trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.audit_logs FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: banks trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.banks FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: budget_items trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.budget_items FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: chart_of_accounts trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.chart_of_accounts FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: classifier_types trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.classifier_types FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: classifiers trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.classifiers FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: commission_members trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.commission_members FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: commissions trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.commissions FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: companies trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.companies FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: consignment_stock_transactions trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.consignment_stock_transactions FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: consolidated_demands trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.consolidated_demands FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: contract_addendums trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.contract_addendums FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: contract_invoices trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.contract_invoices FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: contract_payments trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.contract_payments FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: contract_powers_of_attorney trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.contract_powers_of_attorney FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: contract_reconciliation_acts trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.contract_reconciliation_acts FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: contract_specifications trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.contract_specifications FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: contracts trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.contracts FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: cross_docking_tasks trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.cross_docking_tasks FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: currencies trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.currencies FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: currency_rates trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.currency_rates FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: customs_declarations trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.customs_declarations FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: data_migration_rows trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.data_migration_rows FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: data_migration_runs trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.data_migration_runs FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: delivery_schedules trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.delivery_schedules FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: demand_forecasts trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.demand_forecasts FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: demand_plan_items trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.demand_plan_items FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: demand_planning_cycles trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.demand_planning_cycles FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: demand_plans trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.demand_plans FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: department_types trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.department_types FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: departments trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.departments FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: employees trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.employees FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: equipment trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.equipment FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: exchange_rates trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.exchange_rates FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: external_inbound_records trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.external_inbound_records FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: fcm_tokens trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.fcm_tokens FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: goods_receipt_evidence trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.goods_receipt_evidence FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: goods_receipt_lines trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.goods_receipt_lines FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: goods_receipts trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.goods_receipts FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: idempotency_records trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.idempotency_records FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: ikpu_codes trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.ikpu_codes FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: illiquid_stock_policies trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.illiquid_stock_policies FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: import_deliveries trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.import_deliveries FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: import_freight_bookings trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.import_freight_bookings FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: import_insurance_policies trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.import_insurance_policies FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: incoterms trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.incoterms FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: integration_events trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.integration_events FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: integration_settings trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.integration_settings FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: inventory_accounting_reconciliations trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.inventory_accounting_reconciliations FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: inventory_accounting_settings trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.inventory_accounting_settings FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: inventory_cost_layers trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.inventory_cost_layers FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: inventory_document_material_groups trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.inventory_document_material_groups FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: inventory_document_materials trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.inventory_document_materials FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: inventory_documents trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.inventory_documents FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: inventory_fx_differences trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.inventory_fx_differences FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: inventory_items trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.inventory_items FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: inventory_optimization_recommendations trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.inventory_optimization_recommendations FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: inventory_period_closes trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.inventory_period_closes FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: issue_request_lines trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.issue_request_lines FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: issue_requests trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.issue_requests FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: laboratory_tests trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.laboratory_tests FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: landed_cost_allocation_lines trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.landed_cost_allocation_lines FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: landed_cost_allocations trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.landed_cost_allocations FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: landed_cost_components trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.landed_cost_components FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: landed_logistics_events trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.landed_logistics_events FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: management_opportunities trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.management_opportunities FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: management_opportunity_evidence trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.management_opportunity_evidence FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: management_opportunity_policies trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.management_opportunity_policies FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: management_opportunity_runs trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.management_opportunity_runs FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: manufacturers trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.manufacturers FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: material_analogs trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.material_analogs FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: material_attribute_templates trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.material_attribute_templates FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: material_consumption_norms trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.material_consumption_norms FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: material_cost_observations trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.material_cost_observations FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: material_duplicate_candidates trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.material_duplicate_candidates FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: material_equipment_compatibilities trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.material_equipment_compatibilities FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: material_groups trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.material_groups FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: material_market_prices trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.material_market_prices FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: material_master_requests trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.material_master_requests FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: material_quality_issues trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.material_quality_issues FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: material_reservations trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.material_reservations FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: material_valuation_method_history trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.material_valuation_method_history FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: materials trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.materials FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: mobile_warehouse_operations trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.mobile_warehouse_operations FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: non_moving_stock_case_lines trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.non_moving_stock_case_lines FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: non_moving_stock_cases trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.non_moving_stock_cases FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: non_moving_stock_decision_lines trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.non_moving_stock_decision_lines FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: non_moving_stock_decisions trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.non_moving_stock_decisions FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: non_moving_stock_execution_outputs trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.non_moving_stock_execution_outputs FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: non_moving_stock_executions trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.non_moving_stock_executions FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: non_moving_stock_saved_views trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.non_moving_stock_saved_views FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: non_moving_stocks trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.non_moving_stocks FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: notifications trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.notifications FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: positions trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.positions FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: procurement_campaigns trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.procurement_campaigns FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: procurement_category_strategies trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.procurement_category_strategies FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: procurement_method_decisions trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.procurement_method_decisions FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: procurement_plans trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.procurement_plans FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: procurement_procedures trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.procurement_procedures FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: procurement_work_assignments trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.procurement_work_assignments FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: production_evidence trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.production_evidence FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: purchase_order_items trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.purchase_order_items FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: purchase_order_revisions trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.purchase_order_revisions FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: purchase_orders trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.purchase_orders FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: purchase_request_items trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.purchase_request_items FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: purchase_requests trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.purchase_requests FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: putaway_rules trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.putaway_rules FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: qms_assessment_details trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.qms_assessment_details FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: qms_assessments trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.qms_assessments FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: qms_criteria trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.qms_criteria FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: quality_claims trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.quality_claims FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: quality_inspections trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.quality_inspections FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: quality_nonconformances trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.quality_nonconformances FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: quality_quarantine_lots trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.quality_quarantine_lots FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: reference_mappings trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.reference_mappings FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: return_document_lines trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.return_document_lines FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: return_documents trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.return_documents FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: rfid_tags trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.rfid_tags FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: rfq_delivery_attempts trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.rfq_delivery_attempts FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: rfq_invitation_deliveries trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.rfq_invitation_deliveries FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: rfq_items trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.rfq_items FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: rfq_published_versions trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.rfq_published_versions FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: rfq_purchase_request_sources trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.rfq_purchase_request_sources FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: rfq_supplier_invitations trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.rfq_supplier_invitations FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: rfqs trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.rfqs FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: roles trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.roles FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: segregation_of_duty_rules trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.segregation_of_duty_rules FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: segregation_of_duty_violations trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.segregation_of_duty_violations FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: service_acceptance_acts trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.service_acceptance_acts FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: service_claims trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.service_claims FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: service_executions trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.service_executions FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: service_orders trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.service_orders FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: service_request_milestones trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.service_request_milestones FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: service_request_remarks trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.service_request_remarks FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: service_requests trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.service_requests FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: service_review_history trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.service_review_history FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: sms_gateway_settings trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.sms_gateway_settings FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: sso_settings trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.sso_settings FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: stock_balances trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.stock_balances FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: stock_batches trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.stock_batches FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: stock_cost_movements trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.stock_cost_movements FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: stock_movement_documents trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.stock_movement_documents FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: stock_movements trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.stock_movements FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: stock_valuation_documents trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.stock_valuation_documents FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: stock_valuation_runs trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.stock_valuation_runs FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: stock_valuations trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.stock_valuations FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: storage_bins trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.storage_bins FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: storage_locations trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.storage_locations FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: supplier_accreditation_applications trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.supplier_accreditation_applications FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: supplier_accreditation_decisions trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.supplier_accreditation_decisions FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: supplier_accreditation_documents trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.supplier_accreditation_documents FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: supplier_bank_accounts trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.supplier_bank_accounts FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: supplier_certificates trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.supplier_certificates FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: supplier_corrective_actions trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.supplier_corrective_actions FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: supplier_evaluations trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.supplier_evaluations FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: supplier_kyc_checks trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.supplier_kyc_checks FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: supplier_letters trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.supplier_letters FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: supplier_licenses trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.supplier_licenses FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: supplier_magic_link_requests trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.supplier_magic_link_requests FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: supplier_magic_links trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.supplier_magic_links FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: supplier_portal_accounts trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.supplier_portal_accounts FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: supplier_portal_submissions trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.supplier_portal_submissions FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: supplier_quotation_items trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.supplier_quotation_items FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: supplier_quotation_threads trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.supplier_quotation_threads FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: supplier_quotations trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.supplier_quotations FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: supplier_related_parties trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.supplier_related_parties FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: supplier_unblock_requests trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.supplier_unblock_requests FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: suppliers trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.suppliers FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: supply_work_assignments trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.supply_work_assignments FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: tender_bid_opening_lines trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.tender_bid_opening_lines FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: tender_bid_opening_protocols trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.tender_bid_opening_protocols FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: tender_commission_members trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.tender_commission_members FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: tender_conflict_checks trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.tender_conflict_checks FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: tender_evaluation_protocols trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.tender_evaluation_protocols FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: tnved_codes trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.tnved_codes FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: transfer_document_lines trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.transfer_document_lines FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: transfer_documents trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.transfer_documents FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: tsd_offline_operations trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.tsd_offline_operations FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: unit_of_measures trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.unit_of_measures FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: user_feedback trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.user_feedback FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: users trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.users FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: vmi_stock_agreements trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.vmi_stock_agreements FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: warehouses trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.warehouses FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: work_completion_acts trg_normalize_multilingual_name; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_normalize_multilingual_name BEFORE INSERT OR UPDATE OF name ON public.work_completion_acts FOR EACH ROW EXECUTE FUNCTION public.normalize_multilingual_name();


--
-- Name: stock_cost_movements trg_stock_cost_movements_no_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_stock_cost_movements_no_delete BEFORE DELETE ON public.stock_cost_movements FOR EACH ROW EXECUTE FUNCTION public.prevent_stock_cost_movements_mutation();


--
-- Name: stock_cost_movements trg_stock_cost_movements_no_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_stock_cost_movements_no_update BEFORE UPDATE ON public.stock_cost_movements FOR EACH ROW EXECUTE FUNCTION public.prevent_stock_cost_movements_mutation();


--
-- Name: stock_movement_allocation_cost_layers trg_stock_movement_allocation_cost_layers_append_only; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_stock_movement_allocation_cost_layers_append_only BEFORE DELETE OR UPDATE ON public.stock_movement_allocation_cost_layers FOR EACH ROW EXECUTE FUNCTION public.reject_stock_movement_allocation_evidence_mutation();


--
-- Name: stock_movement_allocations trg_stock_movement_allocations_append_only; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_stock_movement_allocations_append_only BEFORE DELETE OR UPDATE ON public.stock_movement_allocations FOR EACH ROW EXECUTE FUNCTION public.reject_stock_movement_allocation_evidence_mutation();


--
-- Name: advance_shipping_notice_items advance_shipping_notice_items_supplier_po_commitment_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.advance_shipping_notice_items
    ADD CONSTRAINT advance_shipping_notice_items_supplier_po_commitment_id_fkey FOREIGN KEY (supplier_po_commitment_id) REFERENCES public.supplier_po_commitments(id);


--
-- Name: advance_shipping_notices advance_shipping_notices_portal_account_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.advance_shipping_notices
    ADD CONSTRAINT advance_shipping_notices_portal_account_id_fkey FOREIGN KEY (portal_account_id) REFERENCES public.supplier_portal_accounts(id);


--
-- Name: approval_flow_parallel_assignees approval_flow_parallel_assignees_approval_flow_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.approval_flow_parallel_assignees
    ADD CONSTRAINT approval_flow_parallel_assignees_approval_flow_id_fkey FOREIGN KEY (approval_flow_id) REFERENCES public.approval_flows(id);


--
-- Name: approval_flow_steps approval_flow_steps_approval_flow_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.approval_flow_steps
    ADD CONSTRAINT approval_flow_steps_approval_flow_id_fkey FOREIGN KEY (approval_flow_id) REFERENCES public.approval_flows(id);


--
-- Name: approval_history approval_history_approval_instance_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.approval_history
    ADD CONSTRAINT approval_history_approval_instance_id_fkey FOREIGN KEY (approval_instance_id) REFERENCES public.approval_instances(id);


--
-- Name: approval_instances approval_instances_approval_flow_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.approval_instances
    ADD CONSTRAINT approval_instances_approval_flow_id_fkey FOREIGN KEY (approval_flow_id) REFERENCES public.approval_flows(id);


--
-- Name: approval_tasks approval_tasks_approval_flow_step_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.approval_tasks
    ADD CONSTRAINT approval_tasks_approval_flow_step_id_fkey FOREIGN KEY (approval_flow_step_id) REFERENCES public.approval_flow_steps(id);


--
-- Name: approval_tasks approval_tasks_approval_instance_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.approval_tasks
    ADD CONSTRAINT approval_tasks_approval_instance_id_fkey FOREIGN KEY (approval_instance_id) REFERENCES public.approval_instances(id);


--
-- Name: asset_depreciation_entries asset_depreciation_entries_fixed_asset_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.asset_depreciation_entries
    ADD CONSTRAINT asset_depreciation_entries_fixed_asset_id_fkey FOREIGN KEY (fixed_asset_id) REFERENCES public.fixed_assets(id);


--
-- Name: budget_transactions budget_transactions_budget_item_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.budget_transactions
    ADD CONSTRAINT budget_transactions_budget_item_id_fkey FOREIGN KEY (budget_item_id) REFERENCES public.budget_items(id);


--
-- Name: budget_transactions budget_transactions_original_transaction_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.budget_transactions
    ADD CONSTRAINT budget_transactions_original_transaction_id_fkey FOREIGN KEY (original_transaction_id) REFERENCES public.budget_transactions(id);


--
-- Name: budget_transactions budget_transactions_reversal_of_transaction_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.budget_transactions
    ADD CONSTRAINT budget_transactions_reversal_of_transaction_id_fkey FOREIGN KEY (reversal_of_transaction_id) REFERENCES public.budget_transactions(id);


--
-- Name: commission_members commission_members_employee_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.commission_members
    ADD CONSTRAINT commission_members_employee_id_fkey FOREIGN KEY (employee_id) REFERENCES public.employees(id);


--
-- Name: contract_specifications contract_specifications_source_quotation_item_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.contract_specifications
    ADD CONSTRAINT contract_specifications_source_quotation_item_id_fkey FOREIGN KEY (source_quotation_item_id) REFERENCES public.supplier_quotation_items(id);


--
-- Name: contract_specifications contract_specifications_source_rfq_item_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.contract_specifications
    ADD CONSTRAINT contract_specifications_source_rfq_item_id_fkey FOREIGN KEY (source_rfq_item_id) REFERENCES public.rfq_items(id);


--
-- Name: contracts contracts_source_quotation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.contracts
    ADD CONSTRAINT contracts_source_quotation_id_fkey FOREIGN KEY (source_quotation_id) REFERENCES public.supplier_quotations(id);


--
-- Name: contracts contracts_source_rfq_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.contracts
    ADD CONSTRAINT contracts_source_rfq_id_fkey FOREIGN KEY (source_rfq_id) REFERENCES public.rfqs(id);


--
-- Name: data_migration_rows data_migration_rows_run_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.data_migration_rows
    ADD CONSTRAINT data_migration_rows_run_id_fkey FOREIGN KEY (run_id) REFERENCES public.data_migration_runs(id);


--
-- Name: delivery_schedules delivery_schedules_purchase_order_item_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.delivery_schedules
    ADD CONSTRAINT delivery_schedules_purchase_order_item_id_fkey FOREIGN KEY (purchase_order_item_id) REFERENCES public.purchase_order_items(id);


--
-- Name: delivery_schedules delivery_schedules_supplier_po_commitment_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.delivery_schedules
    ADD CONSTRAINT delivery_schedules_supplier_po_commitment_id_fkey FOREIGN KEY (supplier_po_commitment_id) REFERENCES public.supplier_po_commitments(id);


--
-- Name: advance_shipping_notice_batches fk_asn_batches_item; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.advance_shipping_notice_batches
    ADD CONSTRAINT fk_asn_batches_item FOREIGN KEY (asn_item_id) REFERENCES public.advance_shipping_notice_items(id);


--
-- Name: advance_shipping_notice_serials fk_asn_serials_batch; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.advance_shipping_notice_serials
    ADD CONSTRAINT fk_asn_serials_batch FOREIGN KEY (asn_batch_id) REFERENCES public.advance_shipping_notice_batches(id);


--
-- Name: advance_shipping_notice_serials fk_asn_serials_item; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.advance_shipping_notice_serials
    ADD CONSTRAINT fk_asn_serials_item FOREIGN KEY (asn_item_id) REFERENCES public.advance_shipping_notice_items(id);


--
-- Name: contract_invoice_evidence_snapshots fk_contract_invoice_evidence_snapshot_invoice; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.contract_invoice_evidence_snapshots
    ADD CONSTRAINT fk_contract_invoice_evidence_snapshot_invoice FOREIGN KEY (contract_invoice_id) REFERENCES public.contract_invoices(id) NOT VALID;


--
-- Name: contract_invoice_evidence_snapshots fk_contract_invoice_evidence_snapshot_po; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.contract_invoice_evidence_snapshots
    ADD CONSTRAINT fk_contract_invoice_evidence_snapshot_po FOREIGN KEY (purchase_order_id) REFERENCES public.purchase_orders(id) NOT VALID;


--
-- Name: contract_invoices fk_contract_invoices_purchase_order; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.contract_invoices
    ADD CONSTRAINT fk_contract_invoices_purchase_order FOREIGN KEY (purchase_order_id) REFERENCES public.purchase_orders(id);


--
-- Name: contract_payments fk_contract_payments_invoice; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.contract_payments
    ADD CONSTRAINT fk_contract_payments_invoice FOREIGN KEY (contract_invoice_id) REFERENCES public.contract_invoices(id);


--
-- Name: contract_payments fk_contract_payments_purchase_order; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.contract_payments
    ADD CONSTRAINT fk_contract_payments_purchase_order FOREIGN KEY (purchase_order_id) REFERENCES public.purchase_orders(id);


--
-- Name: contract_specifications fk_contract_spec_source_purchase_request_item; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.contract_specifications
    ADD CONSTRAINT fk_contract_spec_source_purchase_request_item FOREIGN KEY (source_purchase_request_item_id) REFERENCES public.purchase_request_items(id) NOT VALID;


--
-- Name: demand_plan_item_pr_allocations fk_demand_item_pr_allocations_demand_item; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.demand_plan_item_pr_allocations
    ADD CONSTRAINT fk_demand_item_pr_allocations_demand_item FOREIGN KEY (demand_plan_item_id) REFERENCES public.demand_plan_items(id);


--
-- Name: demand_plan_item_pr_allocations fk_demand_item_pr_allocations_pr_item; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.demand_plan_item_pr_allocations
    ADD CONSTRAINT fk_demand_item_pr_allocations_pr_item FOREIGN KEY (purchase_request_item_id) REFERENCES public.purchase_request_items(id);


--
-- Name: demand_plan_item_months fk_demand_plan_item_months_item; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.demand_plan_item_months
    ADD CONSTRAINT fk_demand_plan_item_months_item FOREIGN KEY (demand_plan_item_id) REFERENCES public.demand_plan_items(id);


--
-- Name: demand_plan_item_months fk_demand_plan_item_months_plan; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.demand_plan_item_months
    ADD CONSTRAINT fk_demand_plan_item_months_plan FOREIGN KEY (demand_plan_id) REFERENCES public.demand_plans(id);


--
-- Name: demand_planning_cycles fk_demand_planning_cycles_owner_employee; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.demand_planning_cycles
    ADD CONSTRAINT fk_demand_planning_cycles_owner_employee FOREIGN KEY (owner_employee_id) REFERENCES public.employees(id);


--
-- Name: equipment fk_equipment_manufacturer; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.equipment
    ADD CONSTRAINT fk_equipment_manufacturer FOREIGN KEY (manufacturer_id) REFERENCES public.manufacturers(id) NOT VALID;


--
-- Name: transfer_exceptions fk_exception_allocation_scope; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transfer_exceptions
    ADD CONSTRAINT fk_exception_allocation_scope FOREIGN KEY (tenant_id, company_id, transfer_document_id, allocation_id) REFERENCES public.transfer_shipment_allocations(tenant_id, company_id, transfer_document_id, id);


--
-- Name: transfer_exceptions fk_exception_quarantine; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transfer_exceptions
    ADD CONSTRAINT fk_exception_quarantine FOREIGN KEY (quarantine_clearance_id) REFERENCES public.transfer_allocation_clearances(id);


--
-- Name: transfer_exceptions fk_exception_transfer; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transfer_exceptions
    ADD CONSTRAINT fk_exception_transfer FOREIGN KEY (tenant_id, company_id, transfer_document_id) REFERENCES public.transfer_documents(tenant_id, company_id, id);


--
-- Name: exchange_rates fk_exchange_rates_currency; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.exchange_rates
    ADD CONSTRAINT fk_exchange_rates_currency FOREIGN KEY (currency_id) REFERENCES public.currencies(id);


--
-- Name: fcm_tokens fk_fcm_tokens_user; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fcm_tokens
    ADD CONSTRAINT fk_fcm_tokens_user FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: goods_receipt_batches fk_goods_receipt_batches_line; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.goods_receipt_batches
    ADD CONSTRAINT fk_goods_receipt_batches_line FOREIGN KEY (goods_receipt_line_id) REFERENCES public.goods_receipt_lines(id);


--
-- Name: goods_receipt_batches fk_goods_receipt_batches_stock_batch; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.goods_receipt_batches
    ADD CONSTRAINT fk_goods_receipt_batches_stock_batch FOREIGN KEY (stock_batch_id) REFERENCES public.stock_batches(id);


--
-- Name: goods_receipt_evidence fk_goods_receipt_evidence_attachment; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.goods_receipt_evidence
    ADD CONSTRAINT fk_goods_receipt_evidence_attachment FOREIGN KEY (attachment_id) REFERENCES public.attachments(id) NOT VALID;


--
-- Name: goods_receipt_evidence fk_goods_receipt_evidence_line; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.goods_receipt_evidence
    ADD CONSTRAINT fk_goods_receipt_evidence_line FOREIGN KEY (goods_receipt_line_id) REFERENCES public.goods_receipt_lines(id) NOT VALID;


--
-- Name: goods_receipt_evidence fk_goods_receipt_evidence_receipt; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.goods_receipt_evidence
    ADD CONSTRAINT fk_goods_receipt_evidence_receipt FOREIGN KEY (goods_receipt_id) REFERENCES public.goods_receipts(id) NOT VALID;


--
-- Name: goods_receipt_lines fk_goods_receipt_lines_po_item; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.goods_receipt_lines
    ADD CONSTRAINT fk_goods_receipt_lines_po_item FOREIGN KEY (purchase_order_item_id) REFERENCES public.purchase_order_items(id) NOT VALID;


--
-- Name: goods_receipt_lines fk_goods_receipt_lines_receipt; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.goods_receipt_lines
    ADD CONSTRAINT fk_goods_receipt_lines_receipt FOREIGN KEY (goods_receipt_id) REFERENCES public.goods_receipts(id) NOT VALID;


--
-- Name: goods_receipt_lines fk_goods_receipt_lines_stock_movement; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.goods_receipt_lines
    ADD CONSTRAINT fk_goods_receipt_lines_stock_movement FOREIGN KEY (stock_movement_id) REFERENCES public.stock_movements(id) NOT VALID;


--
-- Name: goods_receipt_units fk_goods_receipt_units_batch; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.goods_receipt_units
    ADD CONSTRAINT fk_goods_receipt_units_batch FOREIGN KEY (goods_receipt_batch_id) REFERENCES public.goods_receipt_batches(id);


--
-- Name: goods_receipts fk_goods_receipts_purchase_order; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.goods_receipts
    ADD CONSTRAINT fk_goods_receipts_purchase_order FOREIGN KEY (purchase_order_id) REFERENCES public.purchase_orders(id) NOT VALID;


--
-- Name: goods_receipts fk_goods_receipts_supplier; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.goods_receipts
    ADD CONSTRAINT fk_goods_receipts_supplier FOREIGN KEY (supplier_id) REFERENCES public.suppliers(id) NOT VALID;


--
-- Name: goods_receipts fk_goods_receipts_warehouse; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.goods_receipts
    ADD CONSTRAINT fk_goods_receipts_warehouse FOREIGN KEY (warehouse_id) REFERENCES public.warehouses(id) NOT VALID;


--
-- Name: inventory_line_history fk_inventory_line_history_inventory_item; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inventory_line_history
    ADD CONSTRAINT fk_inventory_line_history_inventory_item FOREIGN KEY (inventory_item_id) REFERENCES public.inventory_items(id);


--
-- Name: issue_request_lines fk_issue_request_lines_header; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.issue_request_lines
    ADD CONSTRAINT fk_issue_request_lines_header FOREIGN KEY (issue_request_id) REFERENCES public.issue_requests(id);


--
-- Name: issue_request_lines fk_issue_request_lines_material; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.issue_request_lines
    ADD CONSTRAINT fk_issue_request_lines_material FOREIGN KEY (material_id) REFERENCES public.materials(id);


--
-- Name: issue_request_lines fk_issue_request_lines_reservation; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.issue_request_lines
    ADD CONSTRAINT fk_issue_request_lines_reservation FOREIGN KEY (reservation_id) REFERENCES public.material_reservations(id);


--
-- Name: issue_request_lines fk_issue_request_lines_source_demand_plan_item; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.issue_request_lines
    ADD CONSTRAINT fk_issue_request_lines_source_demand_plan_item FOREIGN KEY (source_demand_plan_item_id) REFERENCES public.demand_plan_items(id);


--
-- Name: issue_request_lines fk_issue_request_lines_stock_batch; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.issue_request_lines
    ADD CONSTRAINT fk_issue_request_lines_stock_batch FOREIGN KEY (stock_batch_id) REFERENCES public.stock_batches(id);


--
-- Name: issue_request_lines fk_issue_request_lines_stock_movement; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.issue_request_lines
    ADD CONSTRAINT fk_issue_request_lines_stock_movement FOREIGN KEY (stock_movement_id) REFERENCES public.stock_movements(id);


--
-- Name: issue_request_lines fk_issue_request_lines_storage_location; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.issue_request_lines
    ADD CONSTRAINT fk_issue_request_lines_storage_location FOREIGN KEY (storage_location_id) REFERENCES public.storage_locations(id);


--
-- Name: issue_requests fk_issue_requests_receiver; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.issue_requests
    ADD CONSTRAINT fk_issue_requests_receiver FOREIGN KEY (receiver_user_id) REFERENCES public.users(id);


--
-- Name: issue_requests fk_issue_requests_requester; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.issue_requests
    ADD CONSTRAINT fk_issue_requests_requester FOREIGN KEY (requester_user_id) REFERENCES public.users(id);


--
-- Name: issue_requests fk_issue_requests_stock_movement_document; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.issue_requests
    ADD CONSTRAINT fk_issue_requests_stock_movement_document FOREIGN KEY (stock_movement_document_id) REFERENCES public.stock_movement_documents(id);


--
-- Name: issue_requests fk_issue_requests_warehouse; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.issue_requests
    ADD CONSTRAINT fk_issue_requests_warehouse FOREIGN KEY (warehouse_id) REFERENCES public.warehouses(id);


--
-- Name: material_attributes fk_material_attributes_material; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_attributes
    ADD CONSTRAINT fk_material_attributes_material FOREIGN KEY (material_id) REFERENCES public.materials(id);


--
-- Name: material_master_requests fk_material_master_requests_target_material; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_master_requests
    ADD CONSTRAINT fk_material_master_requests_target_material FOREIGN KEY (target_material_id) REFERENCES public.materials(id) NOT VALID;


--
-- Name: material_reservation_events fk_material_reservation_events_balance; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_reservation_events
    ADD CONSTRAINT fk_material_reservation_events_balance FOREIGN KEY (stock_balance_id) REFERENCES public.stock_balances(id) NOT VALID;


--
-- Name: material_reservation_events fk_material_reservation_events_reservation; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_reservation_events
    ADD CONSTRAINT fk_material_reservation_events_reservation FOREIGN KEY (reservation_id) REFERENCES public.material_reservations(id) NOT VALID;


--
-- Name: material_reservations fk_material_reservations_storage_bin; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_reservations
    ADD CONSTRAINT fk_material_reservations_storage_bin FOREIGN KEY (storage_bin_id) REFERENCES public.storage_bins(id) NOT VALID;


--
-- Name: material_reservations fk_material_reservations_uom_snapshot; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_reservations
    ADD CONSTRAINT fk_material_reservations_uom_snapshot FOREIGN KEY (uom_id_snapshot) REFERENCES public.unit_of_measures(id) NOT VALID;


--
-- Name: material_unit_operations fk_material_unit_operations_unit; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_unit_operations
    ADD CONSTRAINT fk_material_unit_operations_unit FOREIGN KEY (material_unit_id) REFERENCES public.material_units(id);


--
-- Name: materials fk_materials_manufacturer; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.materials
    ADD CONSTRAINT fk_materials_manufacturer FOREIGN KEY (manufacturer_id) REFERENCES public.manufacturers(id) NOT VALID;


--
-- Name: non_moving_stock_decision_lines fk_nms_decision_line_case_line; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.non_moving_stock_decision_lines
    ADD CONSTRAINT fk_nms_decision_line_case_line FOREIGN KEY (case_line_id) REFERENCES public.non_moving_stock_case_lines(id);


--
-- Name: non_moving_stock_decision_lines fk_nms_decision_line_decision; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.non_moving_stock_decision_lines
    ADD CONSTRAINT fk_nms_decision_line_decision FOREIGN KEY (decision_id) REFERENCES public.non_moving_stock_decisions(id);


--
-- Name: non_moving_stock_execution_outputs fk_nms_execution_output_case_line; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.non_moving_stock_execution_outputs
    ADD CONSTRAINT fk_nms_execution_output_case_line FOREIGN KEY (case_line_id) REFERENCES public.non_moving_stock_case_lines(id);


--
-- Name: non_moving_stock_execution_outputs fk_nms_execution_output_decision_line; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.non_moving_stock_execution_outputs
    ADD CONSTRAINT fk_nms_execution_output_decision_line FOREIGN KEY (decision_line_id) REFERENCES public.non_moving_stock_decision_lines(id);


--
-- Name: non_moving_stock_execution_outputs fk_nms_execution_output_execution; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.non_moving_stock_execution_outputs
    ADD CONSTRAINT fk_nms_execution_output_execution FOREIGN KEY (execution_id) REFERENCES public.non_moving_stock_executions(id);


--
-- Name: purchase_order_source_allocations fk_po_source_allocations_po_item; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.purchase_order_source_allocations
    ADD CONSTRAINT fk_po_source_allocations_po_item FOREIGN KEY (purchase_order_item_id) REFERENCES public.purchase_order_items(id);


--
-- Name: purchase_order_source_allocations fk_po_source_allocations_pr_item; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.purchase_order_source_allocations
    ADD CONSTRAINT fk_po_source_allocations_pr_item FOREIGN KEY (purchase_request_item_id) REFERENCES public.purchase_request_items(id);


--
-- Name: purchase_order_source_allocations fk_po_source_allocations_rfq_source; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.purchase_order_source_allocations
    ADD CONSTRAINT fk_po_source_allocations_rfq_source FOREIGN KEY (rfq_purchase_request_source_id) REFERENCES public.rfq_purchase_request_sources(id);


--
-- Name: procurement_work_assignments fk_procurement_assignment_pr; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_work_assignments
    ADD CONSTRAINT fk_procurement_assignment_pr FOREIGN KEY (purchase_request_id) REFERENCES public.purchase_requests(id);


--
-- Name: procurement_award_outcomes fk_procurement_award_outcome_contract; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_award_outcomes
    ADD CONSTRAINT fk_procurement_award_outcome_contract FOREIGN KEY (contract_id) REFERENCES public.contracts(id) NOT VALID;


--
-- Name: procurement_award_outcome_lines fk_procurement_award_outcome_line_contract; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_award_outcome_lines
    ADD CONSTRAINT fk_procurement_award_outcome_line_contract FOREIGN KEY (contract_id) REFERENCES public.contracts(id) NOT VALID;


--
-- Name: procurement_award_outcome_lines fk_procurement_award_outcome_line_contract_spec; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_award_outcome_lines
    ADD CONSTRAINT fk_procurement_award_outcome_line_contract_spec FOREIGN KEY (contract_specification_id) REFERENCES public.contract_specifications(id) NOT VALID;


--
-- Name: procurement_award_outcome_lines fk_procurement_award_outcome_line_outcome; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_award_outcome_lines
    ADD CONSTRAINT fk_procurement_award_outcome_line_outcome FOREIGN KEY (procurement_award_outcome_id) REFERENCES public.procurement_award_outcomes(id) NOT VALID;


--
-- Name: procurement_award_outcome_lines fk_procurement_award_outcome_line_po; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_award_outcome_lines
    ADD CONSTRAINT fk_procurement_award_outcome_line_po FOREIGN KEY (purchase_order_id) REFERENCES public.purchase_orders(id) NOT VALID;


--
-- Name: procurement_award_outcome_lines fk_procurement_award_outcome_line_po_item; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_award_outcome_lines
    ADD CONSTRAINT fk_procurement_award_outcome_line_po_item FOREIGN KEY (purchase_order_item_id) REFERENCES public.purchase_order_items(id) NOT VALID;


--
-- Name: procurement_award_outcome_lines fk_procurement_award_outcome_line_pr; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_award_outcome_lines
    ADD CONSTRAINT fk_procurement_award_outcome_line_pr FOREIGN KEY (purchase_request_id) REFERENCES public.purchase_requests(id) NOT VALID;


--
-- Name: procurement_award_outcome_lines fk_procurement_award_outcome_line_pr_item; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_award_outcome_lines
    ADD CONSTRAINT fk_procurement_award_outcome_line_pr_item FOREIGN KEY (purchase_request_item_id) REFERENCES public.purchase_request_items(id) NOT VALID;


--
-- Name: procurement_award_outcome_lines fk_procurement_award_outcome_line_procedure; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_award_outcome_lines
    ADD CONSTRAINT fk_procurement_award_outcome_line_procedure FOREIGN KEY (procurement_procedure_id) REFERENCES public.procurement_procedures(id) NOT VALID;


--
-- Name: procurement_award_outcomes fk_procurement_award_outcome_po; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_award_outcomes
    ADD CONSTRAINT fk_procurement_award_outcome_po FOREIGN KEY (purchase_order_id) REFERENCES public.purchase_orders(id) NOT VALID;


--
-- Name: procurement_award_outcomes fk_procurement_award_outcome_procedure; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_award_outcomes
    ADD CONSTRAINT fk_procurement_award_outcome_procedure FOREIGN KEY (procurement_procedure_id) REFERENCES public.procurement_procedures(id) NOT VALID;


--
-- Name: procurement_campaigns fk_procurement_campaign_price_basis; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_campaigns
    ADD CONSTRAINT fk_procurement_campaign_price_basis FOREIGN KEY (price_basis_calculation_id) REFERENCES public.tender_price_calculations(id);


--
-- Name: procurement_procedures fk_procurement_procedure_campaign; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_procedures
    ADD CONSTRAINT fk_procurement_procedure_campaign FOREIGN KEY (campaign_id) REFERENCES public.procurement_campaigns(id);


--
-- Name: procurement_procedures fk_procurement_procedure_direct_award; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_procedures
    ADD CONSTRAINT fk_procurement_procedure_direct_award FOREIGN KEY (direct_award_id) REFERENCES public.procurement_direct_awards(id);


--
-- Name: procurement_procedures fk_procurement_procedure_price_basis; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_procedures
    ADD CONSTRAINT fk_procurement_procedure_price_basis FOREIGN KEY (price_basis_calculation_id) REFERENCES public.tender_price_calculations(id);


--
-- Name: purchase_order_items fk_purchase_order_items_budget_item; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.purchase_order_items
    ADD CONSTRAINT fk_purchase_order_items_budget_item FOREIGN KEY (budget_item_id) REFERENCES public.budget_items(id);


--
-- Name: purchase_order_items fk_purchase_order_items_service_act; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.purchase_order_items
    ADD CONSTRAINT fk_purchase_order_items_service_act FOREIGN KEY (service_act_id) REFERENCES public.service_acceptance_acts(id);


--
-- Name: purchase_order_items fk_purchase_order_items_service_order; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.purchase_order_items
    ADD CONSTRAINT fk_purchase_order_items_service_order FOREIGN KEY (service_order_id) REFERENCES public.service_orders(id);


--
-- Name: purchase_requests fk_purchase_requests_demand_plan; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.purchase_requests
    ADD CONSTRAINT fk_purchase_requests_demand_plan FOREIGN KEY (demand_plan_id) REFERENCES public.demand_plans(id);


--
-- Name: purchase_requests fk_purchase_requests_warehouse; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.purchase_requests
    ADD CONSTRAINT fk_purchase_requests_warehouse FOREIGN KEY (warehouse_id) REFERENCES public.warehouses(id) NOT VALID;


--
-- Name: qms_assessment_details fk_qms_assessment_details_assessment; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.qms_assessment_details
    ADD CONSTRAINT fk_qms_assessment_details_assessment FOREIGN KEY (assessment_id) REFERENCES public.qms_assessments(id) ON DELETE CASCADE;


--
-- Name: qms_assessment_details fk_qms_assessment_details_company; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.qms_assessment_details
    ADD CONSTRAINT fk_qms_assessment_details_company FOREIGN KEY (company_id) REFERENCES public.companies(id);


--
-- Name: qms_assessment_details fk_qms_assessment_details_criteria; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.qms_assessment_details
    ADD CONSTRAINT fk_qms_assessment_details_criteria FOREIGN KEY (criteria_id) REFERENCES public.qms_criteria(id);


--
-- Name: qms_assessments fk_qms_assessments_company; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.qms_assessments
    ADD CONSTRAINT fk_qms_assessments_company FOREIGN KEY (company_id) REFERENCES public.companies(id);


--
-- Name: qms_assessments fk_qms_assessments_supplier; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.qms_assessments
    ADD CONSTRAINT fk_qms_assessments_supplier FOREIGN KEY (supplier_id) REFERENCES public.suppliers(id);


--
-- Name: qms_criteria fk_qms_criteria_company; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.qms_criteria
    ADD CONSTRAINT fk_qms_criteria_company FOREIGN KEY (company_id) REFERENCES public.companies(id);


--
-- Name: return_documents fk_return_document_issue_request; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.return_documents
    ADD CONSTRAINT fk_return_document_issue_request FOREIGN KEY (issue_request_id) REFERENCES public.issue_requests(id);


--
-- Name: return_documents fk_return_document_stock_movement; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.return_documents
    ADD CONSTRAINT fk_return_document_stock_movement FOREIGN KEY (stock_movement_document_id) REFERENCES public.stock_movement_documents(id);


--
-- Name: return_documents fk_return_document_warehouse; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.return_documents
    ADD CONSTRAINT fk_return_document_warehouse FOREIGN KEY (warehouse_id) REFERENCES public.warehouses(id);


--
-- Name: return_document_lines fk_return_line_batch; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.return_document_lines
    ADD CONSTRAINT fk_return_line_batch FOREIGN KEY (stock_batch_id) REFERENCES public.stock_batches(id);


--
-- Name: return_document_lines fk_return_line_header; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.return_document_lines
    ADD CONSTRAINT fk_return_line_header FOREIGN KEY (return_document_id) REFERENCES public.return_documents(id);


--
-- Name: return_document_lines fk_return_line_issue_line; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.return_document_lines
    ADD CONSTRAINT fk_return_line_issue_line FOREIGN KEY (issue_request_line_id) REFERENCES public.issue_request_lines(id);


--
-- Name: return_document_lines fk_return_line_location; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.return_document_lines
    ADD CONSTRAINT fk_return_line_location FOREIGN KEY (storage_location_id) REFERENCES public.storage_locations(id);


--
-- Name: return_document_lines fk_return_line_material; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.return_document_lines
    ADD CONSTRAINT fk_return_line_material FOREIGN KEY (material_id) REFERENCES public.materials(id);


--
-- Name: return_document_lines fk_return_line_original_movement; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.return_document_lines
    ADD CONSTRAINT fk_return_line_original_movement FOREIGN KEY (original_stock_movement_id) REFERENCES public.stock_movements(id);


--
-- Name: rfqs fk_rfqs_active_published_version; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rfqs
    ADD CONSTRAINT fk_rfqs_active_published_version FOREIGN KEY (active_published_version_id) REFERENCES public.rfq_published_versions(id);


--
-- Name: rfqs fk_rfqs_price_basis_calculation; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rfqs
    ADD CONSTRAINT fk_rfqs_price_basis_calculation FOREIGN KEY (price_basis_calculation_id) REFERENCES public.tender_price_calculations(id);


--
-- Name: service_acceptance_acts fk_service_acceptance_acts_contract; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_acceptance_acts
    ADD CONSTRAINT fk_service_acceptance_acts_contract FOREIGN KEY (contract_id) REFERENCES public.contracts(id);


--
-- Name: service_acceptance_acts fk_service_acceptance_acts_purchase_order; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_acceptance_acts
    ADD CONSTRAINT fk_service_acceptance_acts_purchase_order FOREIGN KEY (purchase_order_id) REFERENCES public.purchase_orders(id);


--
-- Name: service_acceptance_acts fk_service_acceptance_acts_service_order; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_acceptance_acts
    ADD CONSTRAINT fk_service_acceptance_acts_service_order FOREIGN KEY (service_order_id) REFERENCES public.service_orders(id);


--
-- Name: service_acceptance_acts fk_service_acceptance_acts_service_request; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_acceptance_acts
    ADD CONSTRAINT fk_service_acceptance_acts_service_request FOREIGN KEY (service_request_id) REFERENCES public.service_requests(id);


--
-- Name: service_claims fk_service_claims_assigned_to; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_claims
    ADD CONSTRAINT fk_service_claims_assigned_to FOREIGN KEY (assigned_to) REFERENCES public.users(id);


--
-- Name: service_claims fk_service_claims_purchase_order_item; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_claims
    ADD CONSTRAINT fk_service_claims_purchase_order_item FOREIGN KEY (purchase_order_item_id) REFERENCES public.purchase_order_items(id);


--
-- Name: service_claims fk_service_claims_service_act; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_claims
    ADD CONSTRAINT fk_service_claims_service_act FOREIGN KEY (service_act_id) REFERENCES public.service_acceptance_acts(id);


--
-- Name: service_claims fk_service_claims_service_order; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_claims
    ADD CONSTRAINT fk_service_claims_service_order FOREIGN KEY (service_order_id) REFERENCES public.service_orders(id);


--
-- Name: service_claims fk_service_claims_service_request; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_claims
    ADD CONSTRAINT fk_service_claims_service_request FOREIGN KEY (service_request_id) REFERENCES public.service_requests(id);


--
-- Name: service_claims fk_service_claims_supplier; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_claims
    ADD CONSTRAINT fk_service_claims_supplier FOREIGN KEY (supplier_id) REFERENCES public.suppliers(id);


--
-- Name: service_claims fk_service_claims_work_completion_act; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_claims
    ADD CONSTRAINT fk_service_claims_work_completion_act FOREIGN KEY (work_completion_act_id) REFERENCES public.work_completion_acts(id);


--
-- Name: service_executions fk_service_executions_service_order; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_executions
    ADD CONSTRAINT fk_service_executions_service_order FOREIGN KEY (service_order_id) REFERENCES public.service_orders(id);


--
-- Name: service_executions fk_service_executions_service_request; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_executions
    ADD CONSTRAINT fk_service_executions_service_request FOREIGN KEY (service_request_id) REFERENCES public.service_requests(id);


--
-- Name: service_orders fk_service_orders_contract; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_orders
    ADD CONSTRAINT fk_service_orders_contract FOREIGN KEY (contract_id) REFERENCES public.contracts(id);


--
-- Name: service_orders fk_service_orders_purchase_order; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_orders
    ADD CONSTRAINT fk_service_orders_purchase_order FOREIGN KEY (purchase_order_id) REFERENCES public.purchase_orders(id);


--
-- Name: service_orders fk_service_orders_service_request; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_orders
    ADD CONSTRAINT fk_service_orders_service_request FOREIGN KEY (service_request_id) REFERENCES public.service_requests(id);


--
-- Name: service_request_milestones fk_service_request_milestones_request; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_request_milestones
    ADD CONSTRAINT fk_service_request_milestones_request FOREIGN KEY (service_request_id) REFERENCES public.service_requests(id);


--
-- Name: service_request_remarks fk_service_request_remark_assignee; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_request_remarks
    ADD CONSTRAINT fk_service_request_remark_assignee FOREIGN KEY (assigned_to) REFERENCES public.users(id);


--
-- Name: service_request_remarks fk_service_request_remark_request; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_request_remarks
    ADD CONSTRAINT fk_service_request_remark_request FOREIGN KEY (service_request_id) REFERENCES public.service_requests(id);


--
-- Name: service_requests fk_service_requests_budget_item; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_requests
    ADD CONSTRAINT fk_service_requests_budget_item FOREIGN KEY (budget_item_id) REFERENCES public.budget_items(id);


--
-- Name: service_requests fk_service_requests_contract; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_requests
    ADD CONSTRAINT fk_service_requests_contract FOREIGN KEY (contract_id) REFERENCES public.contracts(id);


--
-- Name: service_requests fk_service_requests_department; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_requests
    ADD CONSTRAINT fk_service_requests_department FOREIGN KEY (department_id) REFERENCES public.departments(id);


--
-- Name: service_requests fk_service_requests_purchase_order; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_requests
    ADD CONSTRAINT fk_service_requests_purchase_order FOREIGN KEY (purchase_order_id) REFERENCES public.purchase_orders(id);


--
-- Name: service_requests fk_service_requests_supplier; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_requests
    ADD CONSTRAINT fk_service_requests_supplier FOREIGN KEY (supplier_id) REFERENCES public.suppliers(id);


--
-- Name: service_review_history fk_service_review_history_claim; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_review_history
    ADD CONSTRAINT fk_service_review_history_claim FOREIGN KEY (service_claim_id) REFERENCES public.service_claims(id);


--
-- Name: stock_balances fk_stock_balances_storage_bin; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_balances
    ADD CONSTRAINT fk_stock_balances_storage_bin FOREIGN KEY (storage_bin_id) REFERENCES public.storage_bins(id) NOT VALID;


--
-- Name: stock_movement_allocation_cost_layers fk_stock_movement_allocation_cost_layers_allocation; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_movement_allocation_cost_layers
    ADD CONSTRAINT fk_stock_movement_allocation_cost_layers_allocation FOREIGN KEY (allocation_id) REFERENCES public.stock_movement_allocations(id) NOT VALID;


--
-- Name: stock_movement_allocation_cost_layers fk_stock_movement_allocation_cost_layers_cost_movement; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_movement_allocation_cost_layers
    ADD CONSTRAINT fk_stock_movement_allocation_cost_layers_cost_movement FOREIGN KEY (source_cost_movement_id) REFERENCES public.stock_cost_movements(id) NOT VALID;


--
-- Name: stock_movement_allocation_cost_layers fk_stock_movement_allocation_cost_layers_layer; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_movement_allocation_cost_layers
    ADD CONSTRAINT fk_stock_movement_allocation_cost_layers_layer FOREIGN KEY (cost_layer_id) REFERENCES public.inventory_cost_layers(id) NOT VALID;


--
-- Name: stock_movement_allocations fk_stock_movement_allocations_balance; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_movement_allocations
    ADD CONSTRAINT fk_stock_movement_allocations_balance FOREIGN KEY (balance_id) REFERENCES public.stock_balances(id) NOT VALID;


--
-- Name: stock_movement_allocations fk_stock_movement_allocations_inventory_item; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_movement_allocations
    ADD CONSTRAINT fk_stock_movement_allocations_inventory_item FOREIGN KEY (source_inventory_item_id) REFERENCES public.inventory_items(id) NOT VALID;


--
-- Name: stock_movement_allocations fk_stock_movement_allocations_material; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_movement_allocations
    ADD CONSTRAINT fk_stock_movement_allocations_material FOREIGN KEY (material_id) REFERENCES public.materials(id) NOT VALID;


--
-- Name: stock_movement_allocations fk_stock_movement_allocations_movement; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_movement_allocations
    ADD CONSTRAINT fk_stock_movement_allocations_movement FOREIGN KEY (movement_id) REFERENCES public.stock_movements(id) NOT VALID;


--
-- Name: stock_movement_documents fk_stock_movement_documents_reversal; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_movement_documents
    ADD CONSTRAINT fk_stock_movement_documents_reversal FOREIGN KEY (reversal_of_id) REFERENCES public.stock_movement_documents(id) NOT VALID;


--
-- Name: stock_movement_documents fk_stock_movement_documents_target_warehouse; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_movement_documents
    ADD CONSTRAINT fk_stock_movement_documents_target_warehouse FOREIGN KEY (target_warehouse_id) REFERENCES public.warehouses(id) NOT VALID;


--
-- Name: stock_movement_documents fk_stock_movement_documents_warehouse; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_movement_documents
    ADD CONSTRAINT fk_stock_movement_documents_warehouse FOREIGN KEY (warehouse_id) REFERENCES public.warehouses(id) NOT VALID;


--
-- Name: stock_movements fk_stock_movements_document; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_movements
    ADD CONSTRAINT fk_stock_movements_document FOREIGN KEY (document_id) REFERENCES public.stock_movement_documents(id) NOT VALID;


--
-- Name: stock_movements fk_stock_movements_source_inventory_item; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_movements
    ADD CONSTRAINT fk_stock_movements_source_inventory_item FOREIGN KEY (source_inventory_item_id) REFERENCES public.inventory_items(id) NOT VALID;


--
-- Name: stock_movements fk_stock_movements_storage_bin; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_movements
    ADD CONSTRAINT fk_stock_movements_storage_bin FOREIGN KEY (storage_bin_id) REFERENCES public.storage_bins(id) NOT VALID;


--
-- Name: stock_movements fk_stock_movements_target_stock_batch; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_movements
    ADD CONSTRAINT fk_stock_movements_target_stock_batch FOREIGN KEY (target_stock_batch_id) REFERENCES public.stock_batches(id) NOT VALID;


--
-- Name: stock_movements fk_stock_movements_target_storage_bin; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_movements
    ADD CONSTRAINT fk_stock_movements_target_storage_bin FOREIGN KEY (target_storage_bin_id) REFERENCES public.storage_bins(id) NOT VALID;


--
-- Name: stock_movements fk_stock_movements_uom_snapshot; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_movements
    ADD CONSTRAINT fk_stock_movements_uom_snapshot FOREIGN KEY (uom_id_snapshot) REFERENCES public.unit_of_measures(id) NOT VALID;


--
-- Name: storage_bins fk_storage_bins_location; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.storage_bins
    ADD CONSTRAINT fk_storage_bins_location FOREIGN KEY (storage_location_id) REFERENCES public.storage_locations(id) NOT VALID;


--
-- Name: storage_bins fk_storage_bins_warehouse; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.storage_bins
    ADD CONSTRAINT fk_storage_bins_warehouse FOREIGN KEY (warehouse_id) REFERENCES public.warehouses(id) NOT VALID;


--
-- Name: supplier_po_commitments fk_supplier_po_commitment_delivery_schedule; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_po_commitments
    ADD CONSTRAINT fk_supplier_po_commitment_delivery_schedule FOREIGN KEY (delivery_schedule_id) REFERENCES public.delivery_schedules(id);


--
-- Name: supplier_unblock_requests fk_supplier_unblock_decided_by; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_unblock_requests
    ADD CONSTRAINT fk_supplier_unblock_decided_by FOREIGN KEY (decided_by) REFERENCES public.users(id);


--
-- Name: supplier_unblock_requests fk_supplier_unblock_purchase_order; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_unblock_requests
    ADD CONSTRAINT fk_supplier_unblock_purchase_order FOREIGN KEY (purchase_order_id) REFERENCES public.purchase_orders(id);


--
-- Name: supplier_unblock_requests fk_supplier_unblock_requested_by; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_unblock_requests
    ADD CONSTRAINT fk_supplier_unblock_requested_by FOREIGN KEY (requested_by) REFERENCES public.users(id);


--
-- Name: supplier_unblock_requests fk_supplier_unblock_reviewed_by; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_unblock_requests
    ADD CONSTRAINT fk_supplier_unblock_reviewed_by FOREIGN KEY (reviewed_by) REFERENCES public.users(id);


--
-- Name: supplier_unblock_requests fk_supplier_unblock_supplier; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_unblock_requests
    ADD CONSTRAINT fk_supplier_unblock_supplier FOREIGN KEY (supplier_id) REFERENCES public.suppliers(id);


--
-- Name: transfer_shipment_allocations fk_transfer_allocation_line; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transfer_shipment_allocations
    ADD CONSTRAINT fk_transfer_allocation_line FOREIGN KEY (tenant_id, company_id, transfer_document_id, transfer_line_id, material_id) REFERENCES public.transfer_document_lines(tenant_id, company_id, transfer_document_id, id, material_id);


--
-- Name: transfer_shipment_allocations fk_transfer_allocation_movement; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transfer_shipment_allocations
    ADD CONSTRAINT fk_transfer_allocation_movement FOREIGN KEY (source_movement_allocation_id) REFERENCES public.stock_movement_allocations(id);


--
-- Name: transfer_shipment_allocations fk_transfer_allocation_shipment; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transfer_shipment_allocations
    ADD CONSTRAINT fk_transfer_allocation_shipment FOREIGN KEY (tenant_id, company_id, transfer_document_id, shipment_id) REFERENCES public.transfer_shipments(tenant_id, company_id, transfer_document_id, id);


--
-- Name: transfer_shipment_allocations fk_transfer_allocation_source_batch; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transfer_shipment_allocations
    ADD CONSTRAINT fk_transfer_allocation_source_batch FOREIGN KEY (source_stock_batch_id) REFERENCES public.stock_batches(id);


--
-- Name: transfer_shipment_allocations fk_transfer_allocation_source_bin; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transfer_shipment_allocations
    ADD CONSTRAINT fk_transfer_allocation_source_bin FOREIGN KEY (source_storage_bin_id) REFERENCES public.storage_bins(id);


--
-- Name: transfer_shipment_allocations fk_transfer_allocation_source_location; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transfer_shipment_allocations
    ADD CONSTRAINT fk_transfer_allocation_source_location FOREIGN KEY (source_storage_location_id) REFERENCES public.storage_locations(id);


--
-- Name: transfer_shipment_allocations fk_transfer_allocation_source_warehouse; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transfer_shipment_allocations
    ADD CONSTRAINT fk_transfer_allocation_source_warehouse FOREIGN KEY (source_warehouse_id) REFERENCES public.warehouses(id);


--
-- Name: transfer_allocation_clearances fk_transfer_clearance_allocation; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transfer_allocation_clearances
    ADD CONSTRAINT fk_transfer_clearance_allocation FOREIGN KEY (tenant_id, company_id, allocation_id) REFERENCES public.transfer_shipment_allocations(tenant_id, company_id, id);


--
-- Name: transfer_documents fk_transfer_destination_location; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transfer_documents
    ADD CONSTRAINT fk_transfer_destination_location FOREIGN KEY (destination_location_id) REFERENCES public.storage_locations(id);


--
-- Name: transfer_documents fk_transfer_destination_warehouse; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transfer_documents
    ADD CONSTRAINT fk_transfer_destination_warehouse FOREIGN KEY (destination_warehouse_id) REFERENCES public.warehouses(id);


--
-- Name: transfer_documents fk_transfer_dispatch_movement; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transfer_documents
    ADD CONSTRAINT fk_transfer_dispatch_movement FOREIGN KEY (dispatch_movement_document_id) REFERENCES public.stock_movement_documents(id);


--
-- Name: transfer_document_lines fk_transfer_line_destination_batch; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transfer_document_lines
    ADD CONSTRAINT fk_transfer_line_destination_batch FOREIGN KEY (destination_stock_batch_id) REFERENCES public.stock_batches(id);


--
-- Name: transfer_document_lines fk_transfer_line_destination_location; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transfer_document_lines
    ADD CONSTRAINT fk_transfer_line_destination_location FOREIGN KEY (destination_storage_location_id) REFERENCES public.storage_locations(id);


--
-- Name: transfer_document_lines fk_transfer_line_header; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transfer_document_lines
    ADD CONSTRAINT fk_transfer_line_header FOREIGN KEY (transfer_document_id) REFERENCES public.transfer_documents(id);


--
-- Name: transfer_document_lines fk_transfer_line_material; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transfer_document_lines
    ADD CONSTRAINT fk_transfer_line_material FOREIGN KEY (material_id) REFERENCES public.materials(id);


--
-- Name: transfer_document_lines fk_transfer_line_source_batch; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transfer_document_lines
    ADD CONSTRAINT fk_transfer_line_source_batch FOREIGN KEY (source_stock_batch_id) REFERENCES public.stock_batches(id);


--
-- Name: transfer_document_lines fk_transfer_line_source_location; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transfer_document_lines
    ADD CONSTRAINT fk_transfer_line_source_location FOREIGN KEY (source_storage_location_id) REFERENCES public.storage_locations(id);


--
-- Name: transfer_documents fk_transfer_receive_movement; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transfer_documents
    ADD CONSTRAINT fk_transfer_receive_movement FOREIGN KEY (receive_movement_document_id) REFERENCES public.stock_movement_documents(id);


--
-- Name: transfer_shipments fk_transfer_shipment_document; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transfer_shipments
    ADD CONSTRAINT fk_transfer_shipment_document FOREIGN KEY (tenant_id, company_id, transfer_document_id) REFERENCES public.transfer_documents(tenant_id, company_id, id);


--
-- Name: transfer_documents fk_transfer_source_location; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transfer_documents
    ADD CONSTRAINT fk_transfer_source_location FOREIGN KEY (source_location_id) REFERENCES public.storage_locations(id);


--
-- Name: transfer_documents fk_transfer_source_warehouse; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transfer_documents
    ADD CONSTRAINT fk_transfer_source_warehouse FOREIGN KEY (source_warehouse_id) REFERENCES public.warehouses(id);


--
-- Name: transfer_transit_entries fk_transfer_transit_allocation; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transfer_transit_entries
    ADD CONSTRAINT fk_transfer_transit_allocation FOREIGN KEY (tenant_id, company_id, allocation_id) REFERENCES public.transfer_shipment_allocations(tenant_id, company_id, id);


--
-- Name: users fk_users_department; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT fk_users_department FOREIGN KEY (department_id) REFERENCES public.departments(id) ON DELETE SET NULL;


--
-- Name: users fk_users_employee; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT fk_users_employee FOREIGN KEY (employee_id) REFERENCES public.employees(id);


--
-- Name: work_completion_acts fk_work_completion_acts_contract; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.work_completion_acts
    ADD CONSTRAINT fk_work_completion_acts_contract FOREIGN KEY (contract_id) REFERENCES public.contracts(id);


--
-- Name: work_completion_acts fk_work_completion_acts_purchase_order; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.work_completion_acts
    ADD CONSTRAINT fk_work_completion_acts_purchase_order FOREIGN KEY (purchase_order_id) REFERENCES public.purchase_orders(id);


--
-- Name: work_completion_acts fk_work_completion_acts_purchase_order_item; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.work_completion_acts
    ADD CONSTRAINT fk_work_completion_acts_purchase_order_item FOREIGN KEY (purchase_order_item_id) REFERENCES public.purchase_order_items(id);


--
-- Name: inventory_commission_member_settings inventory_commission_member_settings_commission_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inventory_commission_member_settings
    ADD CONSTRAINT inventory_commission_member_settings_commission_id_fkey FOREIGN KEY (commission_id) REFERENCES public.commissions(id);


--
-- Name: inventory_commission_member_settings inventory_commission_member_settings_commission_member_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inventory_commission_member_settings
    ADD CONSTRAINT inventory_commission_member_settings_commission_member_id_fkey FOREIGN KEY (commission_member_id) REFERENCES public.commission_members(id);


--
-- Name: inventory_documents inventory_documents_approval_instance_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inventory_documents
    ADD CONSTRAINT inventory_documents_approval_instance_id_fkey FOREIGN KEY (approval_instance_id) REFERENCES public.approval_instances(id);


--
-- Name: inventory_documents inventory_documents_storage_bin_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inventory_documents
    ADD CONSTRAINT inventory_documents_storage_bin_id_fkey FOREIGN KEY (storage_bin_id) REFERENCES public.storage_bins(id);


--
-- Name: inventory_documents inventory_documents_storage_location_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inventory_documents
    ADD CONSTRAINT inventory_documents_storage_location_id_fkey FOREIGN KEY (storage_location_id) REFERENCES public.storage_locations(id);


--
-- Name: inventory_items inventory_items_reconciled_source_movement_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inventory_items
    ADD CONSTRAINT inventory_items_reconciled_source_movement_id_fkey FOREIGN KEY (reconciled_source_movement_id) REFERENCES public.stock_movements(id);


--
-- Name: inventory_items inventory_items_source_correction_movement_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inventory_items
    ADD CONSTRAINT inventory_items_source_correction_movement_id_fkey FOREIGN KEY (source_correction_movement_id) REFERENCES public.stock_movements(id);


--
-- Name: management_opportunities management_opportunities_calculation_run_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.management_opportunities
    ADD CONSTRAINT management_opportunities_calculation_run_id_fkey FOREIGN KEY (calculation_run_id) REFERENCES public.management_opportunity_runs(id);


--
-- Name: management_opportunities management_opportunities_policy_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.management_opportunities
    ADD CONSTRAINT management_opportunities_policy_id_fkey FOREIGN KEY (policy_id) REFERENCES public.management_opportunity_policies(id);


--
-- Name: management_opportunity_evidence management_opportunity_evidence_opportunity_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.management_opportunity_evidence
    ADD CONSTRAINT management_opportunity_evidence_opportunity_id_fkey FOREIGN KEY (opportunity_id) REFERENCES public.management_opportunities(id);


--
-- Name: procurement_campaigns procurement_campaigns_method_decision_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_campaigns
    ADD CONSTRAINT procurement_campaigns_method_decision_id_fkey FOREIGN KEY (method_decision_id) REFERENCES public.procurement_method_decisions(id);


--
-- Name: procurement_campaigns procurement_campaigns_price_discovery_rfq_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_campaigns
    ADD CONSTRAINT procurement_campaigns_price_discovery_rfq_id_fkey FOREIGN KEY (price_discovery_rfq_id) REFERENCES public.rfqs(id);


--
-- Name: procurement_campaigns procurement_campaigns_procurement_procedure_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_campaigns
    ADD CONSTRAINT procurement_campaigns_procurement_procedure_id_fkey FOREIGN KEY (procurement_procedure_id) REFERENCES public.procurement_procedures(id);


--
-- Name: procurement_closeout_audit_events procurement_closeout_audit_events_closeout_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_closeout_audit_events
    ADD CONSTRAINT procurement_closeout_audit_events_closeout_id_fkey FOREIGN KEY (closeout_id) REFERENCES public.procurement_closeouts(id);


--
-- Name: procurement_closeout_blockers procurement_closeout_blockers_closeout_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_closeout_blockers
    ADD CONSTRAINT procurement_closeout_blockers_closeout_id_fkey FOREIGN KEY (closeout_id) REFERENCES public.procurement_closeouts(id);


--
-- Name: procurement_closeout_readiness_snapshots procurement_closeout_readiness_snapshots_closeout_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_closeout_readiness_snapshots
    ADD CONSTRAINT procurement_closeout_readiness_snapshots_closeout_id_fkey FOREIGN KEY (closeout_id) REFERENCES public.procurement_closeouts(id);


--
-- Name: procurement_closeout_reopen_history procurement_closeout_reopen_history_approval_instance_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_closeout_reopen_history
    ADD CONSTRAINT procurement_closeout_reopen_history_approval_instance_id_fkey FOREIGN KEY (approval_instance_id) REFERENCES public.approval_instances(id);


--
-- Name: procurement_closeout_reopen_history procurement_closeout_reopen_history_closeout_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_closeout_reopen_history
    ADD CONSTRAINT procurement_closeout_reopen_history_closeout_id_fkey FOREIGN KEY (closeout_id) REFERENCES public.procurement_closeouts(id);


--
-- Name: procurement_closeouts procurement_closeouts_approval_instance_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_closeouts
    ADD CONSTRAINT procurement_closeouts_approval_instance_id_fkey FOREIGN KEY (approval_instance_id) REFERENCES public.approval_instances(id);


--
-- Name: procurement_closeouts procurement_closeouts_procurement_procedure_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_closeouts
    ADD CONSTRAINT procurement_closeouts_procurement_procedure_id_fkey FOREIGN KEY (procurement_procedure_id) REFERENCES public.procurement_procedures(id);


--
-- Name: procurement_closeouts procurement_closeouts_purchase_order_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_closeouts
    ADD CONSTRAINT procurement_closeouts_purchase_order_id_fkey FOREIGN KEY (purchase_order_id) REFERENCES public.purchase_orders(id);


--
-- Name: procurement_closeouts procurement_closeouts_purchase_request_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_closeouts
    ADD CONSTRAINT procurement_closeouts_purchase_request_id_fkey FOREIGN KEY (purchase_request_id) REFERENCES public.purchase_requests(id);


--
-- Name: procurement_direct_awards procurement_direct_awards_approved_supplier_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_direct_awards
    ADD CONSTRAINT procurement_direct_awards_approved_supplier_id_fkey FOREIGN KEY (approved_supplier_id) REFERENCES public.suppliers(id);


--
-- Name: procurement_direct_awards procurement_direct_awards_method_decision_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_direct_awards
    ADD CONSTRAINT procurement_direct_awards_method_decision_id_fkey FOREIGN KEY (method_decision_id) REFERENCES public.procurement_method_decisions(id);


--
-- Name: procurement_direct_awards procurement_direct_awards_procurement_procedure_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_direct_awards
    ADD CONSTRAINT procurement_direct_awards_procurement_procedure_id_fkey FOREIGN KEY (procurement_procedure_id) REFERENCES public.procurement_procedures(id);


--
-- Name: procurement_direct_awards procurement_direct_awards_purchase_order_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_direct_awards
    ADD CONSTRAINT procurement_direct_awards_purchase_order_id_fkey FOREIGN KEY (purchase_order_id) REFERENCES public.purchase_orders(id);


--
-- Name: procurement_direct_awards procurement_direct_awards_purchase_request_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_direct_awards
    ADD CONSTRAINT procurement_direct_awards_purchase_request_id_fkey FOREIGN KEY (purchase_request_id) REFERENCES public.purchase_requests(id);


--
-- Name: procurement_direct_awards procurement_direct_awards_supplier_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_direct_awards
    ADD CONSTRAINT procurement_direct_awards_supplier_id_fkey FOREIGN KEY (supplier_id) REFERENCES public.suppliers(id);


--
-- Name: procurement_method_decisions procurement_method_decisions_commission_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_method_decisions
    ADD CONSTRAINT procurement_method_decisions_commission_id_fkey FOREIGN KEY (commission_id) REFERENCES public.commissions(id);


--
-- Name: procurement_method_decisions procurement_method_decisions_market_research_attachment_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_method_decisions
    ADD CONSTRAINT procurement_method_decisions_market_research_attachment_id_fkey FOREIGN KEY (market_research_attachment_id) REFERENCES public.attachments(id);


--
-- Name: procurement_method_decisions procurement_method_decisions_purchase_request_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_method_decisions
    ADD CONSTRAINT procurement_method_decisions_purchase_request_id_fkey FOREIGN KEY (purchase_request_id) REFERENCES public.purchase_requests(id);


--
-- Name: procurement_method_decisions procurement_method_decisions_technical_specification_attac_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_method_decisions
    ADD CONSTRAINT procurement_method_decisions_technical_specification_attac_fkey FOREIGN KEY (technical_specification_attachment_id) REFERENCES public.attachments(id);


--
-- Name: procurement_procedures procurement_procedures_awarded_supplier_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_procedures
    ADD CONSTRAINT procurement_procedures_awarded_supplier_id_fkey FOREIGN KEY (awarded_supplier_id) REFERENCES public.suppliers(id);


--
-- Name: procurement_procedures procurement_procedures_contract_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_procedures
    ADD CONSTRAINT procurement_procedures_contract_id_fkey FOREIGN KEY (contract_id) REFERENCES public.contracts(id);


--
-- Name: procurement_procedures procurement_procedures_method_decision_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_procedures
    ADD CONSTRAINT procurement_procedures_method_decision_id_fkey FOREIGN KEY (method_decision_id) REFERENCES public.procurement_method_decisions(id);


--
-- Name: procurement_procedures procurement_procedures_price_discovery_rfq_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_procedures
    ADD CONSTRAINT procurement_procedures_price_discovery_rfq_id_fkey FOREIGN KEY (price_discovery_rfq_id) REFERENCES public.rfqs(id);


--
-- Name: procurement_procedures procurement_procedures_procurement_plan_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_procedures
    ADD CONSTRAINT procurement_procedures_procurement_plan_id_fkey FOREIGN KEY (procurement_plan_id) REFERENCES public.procurement_plans(id);


--
-- Name: procurement_procedures procurement_procedures_purchase_order_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_procedures
    ADD CONSTRAINT procurement_procedures_purchase_order_id_fkey FOREIGN KEY (purchase_order_id) REFERENCES public.purchase_orders(id);


--
-- Name: procurement_procedures procurement_procedures_purchase_request_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_procedures
    ADD CONSTRAINT procurement_procedures_purchase_request_id_fkey FOREIGN KEY (purchase_request_id) REFERENCES public.purchase_requests(id);


--
-- Name: procurement_procedures procurement_procedures_rfq_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.procurement_procedures
    ADD CONSTRAINT procurement_procedures_rfq_id_fkey FOREIGN KEY (rfq_id) REFERENCES public.rfqs(id);


--
-- Name: quality_quarantine_lots quality_quarantine_lots_transfer_allocation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quality_quarantine_lots
    ADD CONSTRAINT quality_quarantine_lots_transfer_allocation_id_fkey FOREIGN KEY (transfer_allocation_id) REFERENCES public.transfer_shipment_allocations(id);


--
-- Name: quality_quarantine_lots quality_quarantine_lots_transfer_clearance_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quality_quarantine_lots
    ADD CONSTRAINT quality_quarantine_lots_transfer_clearance_id_fkey FOREIGN KEY (transfer_clearance_id) REFERENCES public.transfer_allocation_clearances(id);


--
-- Name: rfq_delivery_attempts rfq_delivery_attempts_delivery_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rfq_delivery_attempts
    ADD CONSTRAINT rfq_delivery_attempts_delivery_id_fkey FOREIGN KEY (delivery_id) REFERENCES public.rfq_invitation_deliveries(id);


--
-- Name: rfq_invitation_deliveries rfq_invitation_deliveries_invitation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rfq_invitation_deliveries
    ADD CONSTRAINT rfq_invitation_deliveries_invitation_id_fkey FOREIGN KEY (invitation_id) REFERENCES public.rfq_supplier_invitations(id);


--
-- Name: rfq_invitation_deliveries rfq_invitation_deliveries_published_version_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rfq_invitation_deliveries
    ADD CONSTRAINT rfq_invitation_deliveries_published_version_id_fkey FOREIGN KEY (published_version_id) REFERENCES public.rfq_published_versions(id);


--
-- Name: rfq_invitation_deliveries rfq_invitation_deliveries_supplier_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rfq_invitation_deliveries
    ADD CONSTRAINT rfq_invitation_deliveries_supplier_id_fkey FOREIGN KEY (supplier_id) REFERENCES public.suppliers(id);


--
-- Name: rfq_published_versions rfq_published_versions_excel_attachment_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rfq_published_versions
    ADD CONSTRAINT rfq_published_versions_excel_attachment_id_fkey FOREIGN KEY (excel_attachment_id) REFERENCES public.attachments(id);


--
-- Name: rfq_published_versions rfq_published_versions_pdf_attachment_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rfq_published_versions
    ADD CONSTRAINT rfq_published_versions_pdf_attachment_id_fkey FOREIGN KEY (pdf_attachment_id) REFERENCES public.attachments(id);


--
-- Name: rfq_published_versions rfq_published_versions_rfq_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rfq_published_versions
    ADD CONSTRAINT rfq_published_versions_rfq_id_fkey FOREIGN KEY (rfq_id) REFERENCES public.rfqs(id);


--
-- Name: rfq_purchase_request_sources rfq_purchase_request_sources_purchase_request_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rfq_purchase_request_sources
    ADD CONSTRAINT rfq_purchase_request_sources_purchase_request_id_fkey FOREIGN KEY (purchase_request_id) REFERENCES public.purchase_requests(id);


--
-- Name: rfq_purchase_request_sources rfq_purchase_request_sources_purchase_request_item_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rfq_purchase_request_sources
    ADD CONSTRAINT rfq_purchase_request_sources_purchase_request_item_id_fkey FOREIGN KEY (purchase_request_item_id) REFERENCES public.purchase_request_items(id);


--
-- Name: rfq_purchase_request_sources rfq_purchase_request_sources_rfq_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rfq_purchase_request_sources
    ADD CONSTRAINT rfq_purchase_request_sources_rfq_id_fkey FOREIGN KEY (rfq_id) REFERENCES public.rfqs(id);


--
-- Name: rfq_purchase_request_sources rfq_purchase_request_sources_rfq_item_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rfq_purchase_request_sources
    ADD CONSTRAINT rfq_purchase_request_sources_rfq_item_id_fkey FOREIGN KEY (rfq_item_id) REFERENCES public.rfq_items(id);


--
-- Name: rfq_supplier_invitations rfq_supplier_invitations_rfq_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rfq_supplier_invitations
    ADD CONSTRAINT rfq_supplier_invitations_rfq_id_fkey FOREIGN KEY (rfq_id) REFERENCES public.rfqs(id);


--
-- Name: rfq_supplier_invitations rfq_supplier_invitations_supplier_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rfq_supplier_invitations
    ADD CONSTRAINT rfq_supplier_invitations_supplier_id_fkey FOREIGN KEY (supplier_id) REFERENCES public.suppliers(id);


--
-- Name: rfqs rfqs_procurement_procedure_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rfqs
    ADD CONSTRAINT rfqs_procurement_procedure_id_fkey FOREIGN KEY (procurement_procedure_id) REFERENCES public.procurement_procedures(id);


--
-- Name: rfqs rfqs_source_rfq_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rfqs
    ADD CONSTRAINT rfqs_source_rfq_id_fkey FOREIGN KEY (source_rfq_id) REFERENCES public.rfqs(id);


--
-- Name: stock_valuation_cost_job_results stock_valuation_cost_job_results_job_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_valuation_cost_job_results
    ADD CONSTRAINT stock_valuation_cost_job_results_job_id_fkey FOREIGN KEY (job_id) REFERENCES public.stock_valuation_cost_jobs(id) ON DELETE CASCADE;


--
-- Name: stock_valuation_cost_job_warehouses stock_valuation_cost_job_warehouses_job_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_valuation_cost_job_warehouses
    ADD CONSTRAINT stock_valuation_cost_job_warehouses_job_id_fkey FOREIGN KEY (job_id) REFERENCES public.stock_valuation_cost_jobs(id) ON DELETE CASCADE;


--
-- Name: stock_valuation_run_items stock_valuation_run_items_valuation_run_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_valuation_run_items
    ADD CONSTRAINT stock_valuation_run_items_valuation_run_id_fkey FOREIGN KEY (valuation_run_id) REFERENCES public.stock_valuation_runs(id) ON DELETE CASCADE;


--
-- Name: stock_valuation_run_warehouses stock_valuation_run_warehouses_valuation_run_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_valuation_run_warehouses
    ADD CONSTRAINT stock_valuation_run_warehouses_valuation_run_id_fkey FOREIGN KEY (valuation_run_id) REFERENCES public.stock_valuation_runs(id) ON DELETE CASCADE;


--
-- Name: supplier_accreditation_evidence_requirements supplier_accreditation_evidence_requir_template_version_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_accreditation_evidence_requirements
    ADD CONSTRAINT supplier_accreditation_evidence_requir_template_version_id_fkey FOREIGN KEY (template_version_id) REFERENCES public.supplier_evidence_template_versions(id);


--
-- Name: supplier_accreditation_evidence_requirements supplier_accreditation_evidence_requirement_application_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_accreditation_evidence_requirements
    ADD CONSTRAINT supplier_accreditation_evidence_requirement_application_id_fkey FOREIGN KEY (application_id) REFERENCES public.supplier_accreditation_applications(id);


--
-- Name: supplier_activation_records supplier_activation_records_supplier_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_activation_records
    ADD CONSTRAINT supplier_activation_records_supplier_id_fkey FOREIGN KEY (supplier_id) REFERENCES public.suppliers(id);


--
-- Name: supplier_bank_accounts supplier_bank_accounts_supplier_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_bank_accounts
    ADD CONSTRAINT supplier_bank_accounts_supplier_id_fkey FOREIGN KEY (supplier_id) REFERENCES public.suppliers(id);


--
-- Name: supplier_evidence_requirements supplier_evidence_requirements_template_version_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_evidence_requirements
    ADD CONSTRAINT supplier_evidence_requirements_template_version_id_fkey FOREIGN KEY (template_version_id) REFERENCES public.supplier_evidence_template_versions(id);


--
-- Name: supplier_magic_links supplier_magic_links_invitation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_magic_links
    ADD CONSTRAINT supplier_magic_links_invitation_id_fkey FOREIGN KEY (invitation_id) REFERENCES public.rfq_supplier_invitations(id);


--
-- Name: supplier_magic_links supplier_magic_links_portal_account_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_magic_links
    ADD CONSTRAINT supplier_magic_links_portal_account_id_fkey FOREIGN KEY (portal_account_id) REFERENCES public.supplier_portal_accounts(id);


--
-- Name: supplier_magic_links supplier_magic_links_purchase_order_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_magic_links
    ADD CONSTRAINT supplier_magic_links_purchase_order_id_fkey FOREIGN KEY (purchase_order_id) REFERENCES public.purchase_orders(id);


--
-- Name: supplier_magic_links supplier_magic_links_supplier_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_magic_links
    ADD CONSTRAINT supplier_magic_links_supplier_id_fkey FOREIGN KEY (supplier_id) REFERENCES public.suppliers(id);


--
-- Name: supplier_po_commitments supplier_po_commitments_purchase_order_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_po_commitments
    ADD CONSTRAINT supplier_po_commitments_purchase_order_id_fkey FOREIGN KEY (purchase_order_id) REFERENCES public.purchase_orders(id);


--
-- Name: supplier_po_commitments supplier_po_commitments_purchase_order_item_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_po_commitments
    ADD CONSTRAINT supplier_po_commitments_purchase_order_item_id_fkey FOREIGN KEY (purchase_order_item_id) REFERENCES public.purchase_order_items(id);


--
-- Name: supplier_po_commitments supplier_po_commitments_response_line_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_po_commitments
    ADD CONSTRAINT supplier_po_commitments_response_line_id_fkey FOREIGN KEY (response_line_id) REFERENCES public.supplier_po_response_lines(id);


--
-- Name: supplier_po_commitments supplier_po_commitments_superseded_commitment_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_po_commitments
    ADD CONSTRAINT supplier_po_commitments_superseded_commitment_id_fkey FOREIGN KEY (superseded_commitment_id) REFERENCES public.supplier_po_commitments(id);


--
-- Name: supplier_po_commitments supplier_po_commitments_supplier_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_po_commitments
    ADD CONSTRAINT supplier_po_commitments_supplier_id_fkey FOREIGN KEY (supplier_id) REFERENCES public.suppliers(id);


--
-- Name: supplier_po_dispatches supplier_po_dispatches_portal_account_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_po_dispatches
    ADD CONSTRAINT supplier_po_dispatches_portal_account_id_fkey FOREIGN KEY (portal_account_id) REFERENCES public.supplier_portal_accounts(id);


--
-- Name: supplier_po_dispatches supplier_po_dispatches_purchase_order_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_po_dispatches
    ADD CONSTRAINT supplier_po_dispatches_purchase_order_id_fkey FOREIGN KEY (purchase_order_id) REFERENCES public.purchase_orders(id);


--
-- Name: supplier_po_dispatches supplier_po_dispatches_purchase_order_revision_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_po_dispatches
    ADD CONSTRAINT supplier_po_dispatches_purchase_order_revision_id_fkey FOREIGN KEY (purchase_order_revision_id) REFERENCES public.purchase_order_revisions(id);


--
-- Name: supplier_po_dispatches supplier_po_dispatches_supplier_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_po_dispatches
    ADD CONSTRAINT supplier_po_dispatches_supplier_id_fkey FOREIGN KEY (supplier_id) REFERENCES public.suppliers(id);


--
-- Name: supplier_po_reconfirmation_notifications supplier_po_reconfirmation_noti_reconfirmation_response_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_po_reconfirmation_notifications
    ADD CONSTRAINT supplier_po_reconfirmation_noti_reconfirmation_response_id_fkey FOREIGN KEY (reconfirmation_response_id) REFERENCES public.supplier_po_responses(id);


--
-- Name: supplier_po_reconfirmation_notifications supplier_po_reconfirmation_notifica_purchase_order_item_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_po_reconfirmation_notifications
    ADD CONSTRAINT supplier_po_reconfirmation_notifica_purchase_order_item_id_fkey FOREIGN KEY (purchase_order_item_id) REFERENCES public.purchase_order_items(id);


--
-- Name: supplier_po_reconfirmation_notifications supplier_po_reconfirmation_notifications_commitment_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_po_reconfirmation_notifications
    ADD CONSTRAINT supplier_po_reconfirmation_notifications_commitment_id_fkey FOREIGN KEY (commitment_id) REFERENCES public.supplier_po_commitments(id);


--
-- Name: supplier_po_reconfirmation_notifications supplier_po_reconfirmation_notifications_portal_account_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_po_reconfirmation_notifications
    ADD CONSTRAINT supplier_po_reconfirmation_notifications_portal_account_id_fkey FOREIGN KEY (portal_account_id) REFERENCES public.supplier_portal_accounts(id);


--
-- Name: supplier_po_reconfirmation_notifications supplier_po_reconfirmation_notifications_purchase_order_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_po_reconfirmation_notifications
    ADD CONSTRAINT supplier_po_reconfirmation_notifications_purchase_order_id_fkey FOREIGN KEY (purchase_order_id) REFERENCES public.purchase_orders(id);


--
-- Name: supplier_po_reconfirmation_notifications supplier_po_reconfirmation_notifications_supplier_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_po_reconfirmation_notifications
    ADD CONSTRAINT supplier_po_reconfirmation_notifications_supplier_id_fkey FOREIGN KEY (supplier_id) REFERENCES public.suppliers(id);


--
-- Name: supplier_po_response_lines supplier_po_response_lines_purchase_order_item_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_po_response_lines
    ADD CONSTRAINT supplier_po_response_lines_purchase_order_item_id_fkey FOREIGN KEY (purchase_order_item_id) REFERENCES public.purchase_order_items(id);


--
-- Name: supplier_po_response_lines supplier_po_response_lines_response_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_po_response_lines
    ADD CONSTRAINT supplier_po_response_lines_response_id_fkey FOREIGN KEY (response_id) REFERENCES public.supplier_po_responses(id);


--
-- Name: supplier_po_responses supplier_po_responses_dispatch_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_po_responses
    ADD CONSTRAINT supplier_po_responses_dispatch_id_fkey FOREIGN KEY (dispatch_id) REFERENCES public.supplier_po_dispatches(id);


--
-- Name: supplier_po_responses supplier_po_responses_portal_account_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_po_responses
    ADD CONSTRAINT supplier_po_responses_portal_account_id_fkey FOREIGN KEY (portal_account_id) REFERENCES public.supplier_portal_accounts(id);


--
-- Name: supplier_po_responses supplier_po_responses_purchase_order_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_po_responses
    ADD CONSTRAINT supplier_po_responses_purchase_order_id_fkey FOREIGN KEY (purchase_order_id) REFERENCES public.purchase_orders(id);


--
-- Name: supplier_po_responses supplier_po_responses_reconfirmation_origin_commitment_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_po_responses
    ADD CONSTRAINT supplier_po_responses_reconfirmation_origin_commitment_id_fkey FOREIGN KEY (reconfirmation_origin_commitment_id) REFERENCES public.supplier_po_commitments(id);


--
-- Name: supplier_po_responses supplier_po_responses_superseded_response_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_po_responses
    ADD CONSTRAINT supplier_po_responses_superseded_response_id_fkey FOREIGN KEY (superseded_response_id) REFERENCES public.supplier_po_responses(id);


--
-- Name: supplier_po_responses supplier_po_responses_supplier_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_po_responses
    ADD CONSTRAINT supplier_po_responses_supplier_id_fkey FOREIGN KEY (supplier_id) REFERENCES public.suppliers(id);


--
-- Name: supplier_quotation_threads supplier_quotation_threads_current_quotation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_quotation_threads
    ADD CONSTRAINT supplier_quotation_threads_current_quotation_id_fkey FOREIGN KEY (current_quotation_id) REFERENCES public.supplier_quotations(id);


--
-- Name: supplier_quotation_threads supplier_quotation_threads_rfq_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_quotation_threads
    ADD CONSTRAINT supplier_quotation_threads_rfq_id_fkey FOREIGN KEY (rfq_id) REFERENCES public.rfqs(id);


--
-- Name: supplier_quotation_threads supplier_quotation_threads_supplier_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_quotation_threads
    ADD CONSTRAINT supplier_quotation_threads_supplier_id_fkey FOREIGN KEY (supplier_id) REFERENCES public.suppliers(id);


--
-- Name: supplier_quotations supplier_quotations_original_attachment_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_quotations
    ADD CONSTRAINT supplier_quotations_original_attachment_id_fkey FOREIGN KEY (original_attachment_id) REFERENCES public.attachments(id);


--
-- Name: supplier_quotations supplier_quotations_published_version_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_quotations
    ADD CONSTRAINT supplier_quotations_published_version_id_fkey FOREIGN KEY (published_version_id) REFERENCES public.rfq_published_versions(id);


--
-- Name: supplier_quotations supplier_quotations_supersedes_quotation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_quotations
    ADD CONSTRAINT supplier_quotations_supersedes_quotation_id_fkey FOREIGN KEY (supersedes_quotation_id) REFERENCES public.supplier_quotations(id);


--
-- Name: supplier_quotations supplier_quotations_thread_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_quotations
    ADD CONSTRAINT supplier_quotations_thread_id_fkey FOREIGN KEY (thread_id) REFERENCES public.supplier_quotation_threads(id);


--
-- Name: tender_commission_member_settings tender_commission_member_settings_commission_member_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tender_commission_member_settings
    ADD CONSTRAINT tender_commission_member_settings_commission_member_id_fkey FOREIGN KEY (commission_member_id) REFERENCES public.commission_members(id);


--
-- Name: tender_commission_member_settings tender_commission_member_settings_tender_commission_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tender_commission_member_settings
    ADD CONSTRAINT tender_commission_member_settings_tender_commission_id_fkey FOREIGN KEY (tender_commission_id) REFERENCES public.tender_commissions(id);


--
-- Name: tender_commissions tender_commissions_commission_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tender_commissions
    ADD CONSTRAINT tender_commissions_commission_id_fkey FOREIGN KEY (commission_id) REFERENCES public.commissions(id);


--
-- Name: tender_commissions tender_commissions_rfq_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tender_commissions
    ADD CONSTRAINT tender_commissions_rfq_id_fkey FOREIGN KEY (rfq_id) REFERENCES public.rfqs(id);


--
-- Name: tender_price_calculation_quotations tender_price_calculation_quotations_calculation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tender_price_calculation_quotations
    ADD CONSTRAINT tender_price_calculation_quotations_calculation_id_fkey FOREIGN KEY (calculation_id) REFERENCES public.tender_price_calculations(id);


--
-- Name: tender_price_calculation_quotations tender_price_calculation_quotations_quotation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tender_price_calculation_quotations
    ADD CONSTRAINT tender_price_calculation_quotations_quotation_id_fkey FOREIGN KEY (quotation_id) REFERENCES public.supplier_quotations(id);


--
-- Name: tender_price_calculation_quotations tender_price_calculation_quotations_supplier_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tender_price_calculation_quotations
    ADD CONSTRAINT tender_price_calculation_quotations_supplier_id_fkey FOREIGN KEY (supplier_id) REFERENCES public.suppliers(id);


--
-- Name: tender_price_calculations tender_price_calculations_price_discovery_rfq_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tender_price_calculations
    ADD CONSTRAINT tender_price_calculations_price_discovery_rfq_id_fkey FOREIGN KEY (price_discovery_rfq_id) REFERENCES public.rfqs(id);


--
-- Name: tender_price_calculations tender_price_calculations_procurement_procedure_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tender_price_calculations
    ADD CONSTRAINT tender_price_calculations_procurement_procedure_id_fkey FOREIGN KEY (procurement_procedure_id) REFERENCES public.procurement_procedures(id);


--
-- Name: transfer_documents transfer_documents_return_of_transfer_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transfer_documents
    ADD CONSTRAINT transfer_documents_return_of_transfer_id_fkey FOREIGN KEY (return_of_transfer_id) REFERENCES public.transfer_documents(id);


--
-- Name: transfer_shipment_allocations transfer_shipment_allocations_source_stock_movement_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transfer_shipment_allocations
    ADD CONSTRAINT transfer_shipment_allocations_source_stock_movement_id_fkey FOREIGN KEY (source_stock_movement_id) REFERENCES public.stock_movements(id);


--
-- PostgreSQL database dump complete
--


