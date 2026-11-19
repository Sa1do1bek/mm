package com.enterprise.mm.supply.workspace.repository;

import com.enterprise.mm.supply.workspace.domain.SupplyCommitmentKey;
import com.enterprise.mm.supply.workspace.domain.SupplyCommitmentKeyCodec;
import com.enterprise.mm.supply.workspace.domain.SupplyWorkspaceQuery;
import com.enterprise.mm.supply.workspace.enums.SupplyCalendarLane;
import com.enterprise.mm.supply.workspace.enums.SupplyDateBasis;
import com.enterprise.mm.supply.workspace.enums.SupplyDeliverySource;
import com.enterprise.mm.supply.workspace.enums.SupplyLens;
import com.enterprise.mm.supply.workspace.enums.SupplyQueueKey;
import com.enterprise.mm.supply.workspace.enums.SupplyRiskKey;
import com.enterprise.mm.supply.workspace.payload.SupplyCalendarDtos.CalendarEvent;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.AllowedAction;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.DeepLinkTarget;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.EntityRef;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.MoneyValue;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Five deliberately independent calendar queries. A failure in one source must not
 * prevent the service from returning events from any other source.
 */
@Repository
public class SupplyCalendarQueryRepository {
    private static final String ACTIVE_SCHEDULE_ABSENT = """
        not exists (
            select 1
            from delivery_schedules active_schedule
            where active_schedule.tenant_id = po.tenant_id
              and active_schedule.company_id = po.company_id
              and active_schedule.purchase_order_id = po.id
              and active_schedule.is_delete = false
              and (active_schedule.status is null
                or upper(active_schedule.status) not in ('RECEIVED', 'CANCELLED'))
        )
        """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final SupplyCommitmentKeyCodec keyCodec;

    public SupplyCalendarQueryRepository(
        NamedParameterJdbcTemplate jdbcTemplate,
        SupplyCommitmentKeyCodec keyCodec
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.keyCodec = keyCodec;
    }

    public List<CalendarEvent> deliveryEvents(SupplyWorkspaceQuery query) {
        requireCalendarQuery(query);
        MapSqlParameterSource parameters = parameters(query);
        StringBuilder sql = new StringBuilder("""
            select schedule.company_id,
                   coalesce(upper(nullif(trim(schedule.source), '')), 'MANUAL') as delivery_source,
                   'DELIVERY' as lane,
                   'PROMISED_DATE' as date_basis,
                   'DELIVERY_SCHEDULE' as document_type,
                   schedule.id as document_id,
                   schedule.schedule_date as operational_date,
                   coalesce(nullif(trim(schedule.code), ''), cast(schedule.id as varchar)) as document_code,
                   nullif(trim(schedule.name), '') as document_name,
                   case
                     when po.request_type = 'GOODS'
                       and upper(po.status) in ('SENT', 'PARTIALLY_RECEIVED', 'PARTIALLY_COMPLETED')
                       and (schedule.status is null
                         or upper(schedule.status) not in ('RECEIVED', 'CANCELLED'))
                     then concat('SCHEDULE:', lower(cast(schedule.id as varchar)))
                     else null
                   end as commitment_key,
                   supplier.id as supplier_id,
                   supplier.code as supplier_code,
                   coalesce(nullif(supplier.short_name, ''), nullif(supplier.legal_name, ''), supplier.name) as supplier_name,
                   supplier.status as supplier_status,
                   po.id as purchase_order_id,
                   po.code as purchase_order_code,
                   po.name as purchase_order_name,
                   po.status as purchase_order_status,
                   contract.id as contract_id,
                   coalesce(nullif(contract.contract_number, ''), contract.code) as contract_code,
                   contract.name as contract_name,
                   contract.status as contract_status,
                   case
                     when schedule.planned_amount is null or schedule.received_amount is null then null
                     else greatest(schedule.planned_amount - schedule.received_amount, 0)
                   end as event_amount,
                   upper(nullif(trim(po.currency_code), '')) as currency_code,
                   schedule.status as event_status,
                   case
                     when schedule.schedule_date < :asOfDate
                       and schedule.planned_quantity is not null
                       and schedule.received_quantity is not null
                       and schedule.planned_quantity > schedule.received_quantity
                       and (schedule.status is null
                         or upper(schedule.status) not in ('RECEIVED', 'CANCELLED'))
                     then 'OVERDUE'
                     else null
                   end as risk_key,
                   assignment.owner_user_id,
                   assignment.owner_username,
                   assignment.version as assignment_version
            from delivery_schedules schedule
            left join purchase_orders po
              on po.id = schedule.purchase_order_id
             and po.tenant_id = schedule.tenant_id
             and po.company_id = schedule.company_id
             and po.is_delete = false
            left join suppliers supplier
              on supplier.id = po.supplier_id
             and supplier.tenant_id = schedule.tenant_id
             and supplier.company_id = schedule.company_id
             and supplier.is_delete = false
            left join contracts contract
              on contract.id = coalesce(schedule.contract_id, po.contract_id)
             and contract.tenant_id = schedule.tenant_id
             and contract.company_id = schedule.company_id
             and contract.is_delete = false
            left join supply_work_assignments assignment
              on assignment.tenant_id = schedule.tenant_id
             and assignment.company_id = schedule.company_id
             and assignment.commitment_key = concat('SCHEDULE:', lower(cast(schedule.id as varchar)))
             and assignment.is_delete = false
            where schedule.tenant_id = :tenantId
              and schedule.is_delete = false
              and schedule.schedule_date >= :fromDate
              and schedule.schedule_date < :toDate
            """);
        appendCompanyScope(sql, "schedule", query, parameters);
        appendPoWarehouseScope(sql, "po", query, parameters);
        appendEntityFilters(
            sql, query, parameters, "po.supplier_id", "po.id",
            "coalesce(schedule.contract_id, po.contract_id)", "po.currency_code", "schedule.status");
        appendDeliveryAssignmentFilters(sql, query, parameters);
        appendRiskFilter(sql, query, """
            schedule.schedule_date < :asOfDate
              and schedule.planned_quantity is not null
              and schedule.received_quantity is not null
              and schedule.planned_quantity > schedule.received_quantity
              and (schedule.status is null
                or upper(schedule.status) not in ('RECEIVED', 'CANCELLED'))
            """, SupplyRiskKey.OVERDUE, SupplyQueueKey.OVERDUE_COMMITMENTS);
        appendSearch(sql, query, parameters,
            "schedule.code", "schedule.name", "po.code", "po.name", "supplier.code",
            "supplier.name", "supplier.legal_name", "supplier.short_name", "contract.contract_number");
        return jdbcTemplate.query(
            sql + stableOrder(), parameters, (rs, rowNumber) -> mapRow(rs, rowNumber, query));
    }

    public List<CalendarEvent> asnEvents(SupplyWorkspaceQuery query) {
        requireCalendarQuery(query);
        MapSqlParameterSource parameters = parameters(query);
        StringBuilder sql = new StringBuilder("""
            select asn.company_id,
                   cast(null as varchar) as delivery_source,
                   'ASN' as lane,
                   'EXPECTED_ARRIVAL_DATE' as date_basis,
                   'ASN' as document_type,
                   asn.id as document_id,
                   asn.expected_arrival_date as operational_date,
                   coalesce(nullif(trim(asn.asn_number), ''), nullif(trim(asn.code), ''), cast(asn.id as varchar)) as document_code,
                   nullif(trim(asn.name), '') as document_name,
                   case when po.id is not null
                     and po.request_type = 'GOODS'
                     and upper(po.status) in ('SENT', 'PARTIALLY_RECEIVED', 'PARTIALLY_COMPLETED')
                     and
            """);
        sql.append(ACTIVE_SCHEDULE_ABSENT).append("""
                     then concat('PO:', lower(cast(po.id as varchar)))
                     else null
                   end as commitment_key,
                   supplier.id as supplier_id,
                   supplier.code as supplier_code,
                   coalesce(nullif(supplier.short_name, ''), nullif(supplier.legal_name, ''), supplier.name) as supplier_name,
                   supplier.status as supplier_status,
                   po.id as purchase_order_id,
                   po.code as purchase_order_code,
                   po.name as purchase_order_name,
                   po.status as purchase_order_status,
                   contract.id as contract_id,
                   coalesce(nullif(contract.contract_number, ''), contract.code) as contract_code,
                   contract.name as contract_name,
                   contract.status as contract_status,
                   asn.total_amount as event_amount,
                   upper(nullif(trim(asn.currency_code), '')) as currency_code,
                   asn.status as event_status,
                   case
                     when asn.expected_arrival_date < :asOfDate
                       and (asn.status is null or upper(asn.status) not in ('RECEIVED', 'CANCELLED'))
                     then 'OVERDUE'
                     else null
                   end as risk_key,
                   cast(null as uuid) as owner_user_id,
                   cast(null as varchar) as owner_username,
                   cast(null as bigint) as assignment_version
            from advance_shipping_notices asn
            left join purchase_orders po
              on po.id = asn.purchase_order_id
             and po.tenant_id = asn.tenant_id
             and po.company_id = asn.company_id
             and po.is_delete = false
            left join suppliers supplier
              on supplier.id = asn.supplier_id
             and supplier.tenant_id = asn.tenant_id
             and supplier.company_id = asn.company_id
             and supplier.is_delete = false
            left join contracts contract
              on contract.id = coalesce(asn.contract_id, po.contract_id)
             and contract.tenant_id = asn.tenant_id
             and contract.company_id = asn.company_id
             and contract.is_delete = false
            where asn.tenant_id = :tenantId
              and asn.is_delete = false
              and asn.expected_arrival_date >= :fromDate
              and asn.expected_arrival_date < :toDate
            """);
        appendCompanyScope(sql, "asn", query, parameters);
        appendPoWarehouseScope(sql, "po", query, parameters);
        appendEntityFilters(
            sql, query, parameters, "asn.supplier_id", "asn.purchase_order_id",
            "coalesce(asn.contract_id, po.contract_id)", "asn.currency_code", "asn.status");
        if (query.transportMode() != null) {
            sql.append(" and upper(asn.transport_mode) = :transportMode");
            parameters.addValue("transportMode", query.transportMode());
        }
        appendRiskFilter(sql, query, """
            asn.expected_arrival_date < :asOfDate
              and (asn.status is null or upper(asn.status) not in ('RECEIVED', 'CANCELLED'))
            """, SupplyRiskKey.OVERDUE, SupplyQueueKey.OVERDUE_COMMITMENTS);
        appendSearch(sql, query, parameters,
            "asn.asn_number", "asn.code", "asn.name", "po.code", "po.name", "supplier.code",
            "supplier.name", "supplier.legal_name", "supplier.short_name", "contract.contract_number");
        return jdbcTemplate.query(
            sql + stableOrder(), parameters, (rs, rowNumber) -> mapRow(rs, rowNumber, query));
    }

    public List<CalendarEvent> importAndCustomsEvents(SupplyWorkspaceQuery query) {
        requireCalendarQuery(query);
        MapSqlParameterSource parameters = parameters(query);
        String importSql = importSql(query, parameters);
        String customsSql = customsSql(query, parameters);
        String sql = "select * from (" + importSql + " union all " + customsSql + ") calendar_event"
            + stableOrder();
        return jdbcTemplate.query(sql, parameters, (rs, rowNumber) -> mapRow(rs, rowNumber, query));
    }

    public List<CalendarEvent> invoiceEvents(SupplyWorkspaceQuery query) {
        requireCalendarQuery(query);
        MapSqlParameterSource parameters = parameters(query);
        StringBuilder sql = new StringBuilder("""
            select invoice.company_id,
                   cast(null as varchar) as delivery_source,
                   'INVOICE' as lane,
                   'DUE_DATE' as date_basis,
                   'CONTRACT_INVOICE' as document_type,
                   invoice.id as document_id,
                   invoice.due_date as operational_date,
                   coalesce(nullif(trim(invoice.invoice_number), ''), nullif(trim(invoice.code), ''), cast(invoice.id as varchar)) as document_code,
                   nullif(trim(invoice.name), '') as document_name,
                   case when po.id is not null
                     and po.request_type = 'GOODS'
                     and upper(po.status) in ('SENT', 'PARTIALLY_RECEIVED', 'PARTIALLY_COMPLETED')
                     and
            """);
        sql.append(ACTIVE_SCHEDULE_ABSENT).append("""
                     then concat('PO:', lower(cast(po.id as varchar)))
                     else null
                   end as commitment_key,
                   supplier.id as supplier_id,
                   supplier.code as supplier_code,
                   coalesce(nullif(supplier.short_name, ''), nullif(supplier.legal_name, ''), supplier.name) as supplier_name,
                   supplier.status as supplier_status,
                   po.id as purchase_order_id,
                   po.code as purchase_order_code,
                   po.name as purchase_order_name,
                   po.status as purchase_order_status,
                   contract.id as contract_id,
                   coalesce(nullif(contract.contract_number, ''), contract.code) as contract_code,
                   contract.name as contract_name,
                   contract.status as contract_status,
                   case
                     when invoice.amount is null then null
                     else greatest(invoice.amount - coalesce(invoice.paid_amount, 0), 0)
                   end as event_amount,
                   upper(nullif(trim(invoice.currency_code), '')) as currency_code,
                   invoice.status as event_status,
                   case
                     when invoice.due_date < :asOfDate
                       and (invoice.status is null or upper(invoice.status) not in ('PAID', 'CANCELLED'))
                     then 'OVERDUE'
                     else null
                   end as risk_key,
                   cast(null as uuid) as owner_user_id,
                   cast(null as varchar) as owner_username,
                   cast(null as bigint) as assignment_version
            from contract_invoices invoice
            left join purchase_orders po
              on po.id = invoice.purchase_order_id
             and po.tenant_id = invoice.tenant_id
             and po.company_id = invoice.company_id
             and po.is_delete = false
            left join suppliers supplier
              on supplier.id = invoice.supplier_id
             and supplier.tenant_id = invoice.tenant_id
             and supplier.company_id = invoice.company_id
             and supplier.is_delete = false
            left join contracts contract
              on contract.id = invoice.contract_id
             and contract.tenant_id = invoice.tenant_id
             and contract.company_id = invoice.company_id
             and contract.is_delete = false
            where invoice.tenant_id = :tenantId
              and invoice.is_delete = false
              and invoice.due_date >= :fromDate
              and invoice.due_date < :toDate
            """);
        appendCompanyScope(sql, "invoice", query, parameters);
        appendPoWarehouseScope(sql, "po", query, parameters);
        appendEntityFilters(
            sql, query, parameters, "invoice.supplier_id", "invoice.purchase_order_id",
            "invoice.contract_id", "invoice.currency_code", "invoice.status");
        appendRiskFilter(sql, query, """
            invoice.due_date < :asOfDate
              and (invoice.status is null or upper(invoice.status) not in ('PAID', 'CANCELLED'))
            """, SupplyRiskKey.OVERDUE, SupplyQueueKey.OVERDUE_COMMITMENTS);
        appendSearch(sql, query, parameters,
            "invoice.invoice_number", "invoice.code", "invoice.name", "po.code", "po.name",
            "supplier.code", "supplier.name", "supplier.legal_name", "supplier.short_name",
            "contract.contract_number");
        return jdbcTemplate.query(
            sql + stableOrder(), parameters, (rs, rowNumber) -> mapRow(rs, rowNumber, query));
    }

    public List<CalendarEvent> paymentEvents(SupplyWorkspaceQuery query) {
        requireCalendarQuery(query);
        MapSqlParameterSource parameters = parameters(query);
        StringBuilder sql = new StringBuilder("""
            select payment.company_id,
                   cast(null as varchar) as delivery_source,
                   'PAYMENT' as lane,
                   'PAYMENT_DATE' as date_basis,
                   'CONTRACT_PAYMENT' as document_type,
                   payment.id as document_id,
                   payment.payment_date as operational_date,
                   coalesce(nullif(trim(payment.payment_number), ''), nullif(trim(payment.code), ''), cast(payment.id as varchar)) as document_code,
                   nullif(trim(payment.name), '') as document_name,
                   cast(null as varchar) as commitment_key,
                   supplier.id as supplier_id,
                   supplier.code as supplier_code,
                   coalesce(nullif(supplier.short_name, ''), nullif(supplier.legal_name, ''), supplier.name) as supplier_name,
                   supplier.status as supplier_status,
                   po.id as purchase_order_id,
                   po.code as purchase_order_code,
                   po.name as purchase_order_name,
                   po.status as purchase_order_status,
                   contract.id as contract_id,
                   coalesce(nullif(contract.contract_number, ''), contract.code) as contract_code,
                   contract.name as contract_name,
                   contract.status as contract_status,
                   payment.amount as event_amount,
                   upper(nullif(trim(payment.currency_code), '')) as currency_code,
                   payment.status as event_status,
                   case
                     when payment.payment_date < :asOfDate
                       and (payment.status is null or upper(payment.status) not in ('PAID', 'CANCELLED'))
                     then 'OVERDUE'
                     else null
                   end as risk_key,
                   cast(null as uuid) as owner_user_id,
                   cast(null as varchar) as owner_username,
                   cast(null as bigint) as assignment_version
            from contract_payments payment
            left join contract_invoices invoice
              on invoice.id = payment.contract_invoice_id
             and invoice.tenant_id = payment.tenant_id
             and invoice.company_id = payment.company_id
             and invoice.is_delete = false
            left join purchase_orders po
              on po.id = coalesce(payment.purchase_order_id, invoice.purchase_order_id)
             and po.tenant_id = payment.tenant_id
             and po.company_id = payment.company_id
             and po.is_delete = false
            left join suppliers supplier
              on supplier.id = payment.supplier_id
             and supplier.tenant_id = payment.tenant_id
             and supplier.company_id = payment.company_id
             and supplier.is_delete = false
            left join contracts contract
              on contract.id = coalesce(payment.contract_id, invoice.contract_id)
             and contract.tenant_id = payment.tenant_id
             and contract.company_id = payment.company_id
             and contract.is_delete = false
            where payment.tenant_id = :tenantId
              and payment.is_delete = false
              and payment.payment_date >= :fromDate
              and payment.payment_date < :toDate
            """);
        appendCompanyScope(sql, "payment", query, parameters);
        if (query.warehouseId() != null || query.warehouseScoped()) {
            // Contract payments have no authoritative warehouse allocation. Fail closed.
            sql.append(" and 1 = 0");
        }
        appendEntityFilters(
            sql, query, parameters, "payment.supplier_id",
            "coalesce(payment.purchase_order_id, invoice.purchase_order_id)",
            "coalesce(payment.contract_id, invoice.contract_id)",
            "payment.currency_code", "payment.status");
        appendRiskFilter(sql, query, """
            payment.payment_date < :asOfDate
              and (payment.status is null or upper(payment.status) not in ('PAID', 'CANCELLED'))
            """, SupplyRiskKey.OVERDUE, null);
        appendSearch(sql, query, parameters,
            "payment.payment_number", "payment.code", "payment.name", "supplier.code",
            "supplier.name", "supplier.legal_name", "supplier.short_name", "po.code", "po.name",
            "invoice.invoice_number", "invoice.code", "contract.contract_number");
        return jdbcTemplate.query(
            sql + stableOrder(), parameters, (rs, rowNumber) -> mapRow(rs, rowNumber, query));
    }

    private String importSql(SupplyWorkspaceQuery query, MapSqlParameterSource parameters) {
        StringBuilder sql = new StringBuilder("""
            select import_delivery.company_id,
                   cast(null as varchar) as delivery_source,
                   'IMPORT_CUSTOMS' as lane,
                   'ETA' as date_basis,
                   'IMPORT_DELIVERY' as document_type,
                   import_delivery.id as document_id,
                   import_delivery.eta as operational_date,
                   coalesce(nullif(trim(import_delivery.shipment_number), ''), nullif(trim(import_delivery.code), ''), cast(import_delivery.id as varchar)) as document_code,
                   nullif(trim(import_delivery.name), '') as document_name,
                   case when po.id is not null
                     and po.request_type = 'GOODS'
                     and upper(po.status) in ('SENT', 'PARTIALLY_RECEIVED', 'PARTIALLY_COMPLETED')
                     and
            """);
        sql.append(ACTIVE_SCHEDULE_ABSENT).append("""
                     then concat('PO:', lower(cast(po.id as varchar)))
                     else null
                   end as commitment_key,
                   supplier.id as supplier_id,
                   supplier.code as supplier_code,
                   coalesce(nullif(supplier.short_name, ''), nullif(supplier.legal_name, ''), supplier.name) as supplier_name,
                   supplier.status as supplier_status,
                   po.id as purchase_order_id,
                   po.code as purchase_order_code,
                   po.name as purchase_order_name,
                   po.status as purchase_order_status,
                   contract.id as contract_id,
                   coalesce(nullif(contract.contract_number, ''), contract.code) as contract_code,
                   contract.name as contract_name,
                   contract.status as contract_status,
                   import_delivery.landed_cost_amount as event_amount,
                   upper(nullif(trim(import_delivery.currency_code), '')) as currency_code,
                   import_delivery.status as event_status,
                   case
                     when import_delivery.risk_flag = true
                       or (import_delivery.eta < :asOfDate
                         and import_delivery.actual_arrival_date is null
                         and (import_delivery.status is null
                           or upper(import_delivery.status) not in ('ARRIVED', 'CANCELLED')))
                     then 'IMPORT_ETA_RISK'
                     else null
                   end as risk_key,
                   cast(null as uuid) as owner_user_id,
                   cast(null as varchar) as owner_username,
                   cast(null as bigint) as assignment_version
            from import_deliveries import_delivery
            left join purchase_orders po
              on po.id = import_delivery.purchase_order_id
             and po.tenant_id = import_delivery.tenant_id
             and po.company_id = import_delivery.company_id
             and po.is_delete = false
            left join suppliers supplier
              on supplier.id = import_delivery.supplier_id
             and supplier.tenant_id = import_delivery.tenant_id
             and supplier.company_id = import_delivery.company_id
             and supplier.is_delete = false
            left join contracts contract
              on contract.id = po.contract_id
             and contract.tenant_id = import_delivery.tenant_id
             and contract.company_id = import_delivery.company_id
             and contract.is_delete = false
            where import_delivery.tenant_id = :tenantId
              and import_delivery.is_delete = false
              and import_delivery.eta >= :fromDate
              and import_delivery.eta < :toDate
            """);
        appendCompanyScope(sql, "import_delivery", query, parameters);
        appendImportWarehouseScope(sql, "import_delivery", "po", query, parameters);
        appendEntityFilters(
            sql, query, parameters, "import_delivery.supplier_id", "import_delivery.purchase_order_id",
            "po.contract_id", "import_delivery.currency_code", "import_delivery.status");
        if (query.transportMode() != null) {
            sql.append(" and upper(import_delivery.transport_mode) = :transportMode");
            parameters.addValue("transportMode", query.transportMode());
        }
        appendRiskFilter(
            sql, query, """
                import_delivery.risk_flag = true
                  or (import_delivery.eta < :asOfDate
                    and import_delivery.actual_arrival_date is null
                    and (import_delivery.status is null
                      or upper(import_delivery.status) not in ('ARRIVED', 'CANCELLED')))
                """, SupplyRiskKey.IMPORT_ETA_RISK,
            SupplyQueueKey.ACTIVE_IMPORT_ETA_RISK);
        appendSearch(sql, query, parameters,
            "import_delivery.shipment_number", "import_delivery.code", "import_delivery.name",
            "po.code", "po.name", "supplier.code", "supplier.name", "supplier.legal_name",
            "supplier.short_name", "contract.contract_number");
        return sql.toString();
    }

    private String customsSql(SupplyWorkspaceQuery query, MapSqlParameterSource parameters) {
        StringBuilder sql = new StringBuilder("""
            select customs.company_id,
                   cast(null as varchar) as delivery_source,
                   'IMPORT_CUSTOMS' as lane,
                   'DECLARATION_DATE' as date_basis,
                   'CUSTOMS_DECLARATION' as document_type,
                   customs.id as document_id,
                   customs.declaration_date as operational_date,
                   coalesce(nullif(trim(customs.declaration_number), ''), nullif(trim(customs.code), ''), cast(customs.id as varchar)) as document_code,
                   nullif(trim(customs.name), '') as document_name,
                   case when po.id is not null
                     and po.request_type = 'GOODS'
                     and upper(po.status) in ('SENT', 'PARTIALLY_RECEIVED', 'PARTIALLY_COMPLETED')
                     and
            """);
        sql.append(ACTIVE_SCHEDULE_ABSENT).append("""
                     then concat('PO:', lower(cast(po.id as varchar)))
                     else null
                   end as commitment_key,
                   supplier.id as supplier_id,
                   supplier.code as supplier_code,
                   coalesce(nullif(supplier.short_name, ''), nullif(supplier.legal_name, ''), supplier.name) as supplier_name,
                   supplier.status as supplier_status,
                   po.id as purchase_order_id,
                   po.code as purchase_order_code,
                   po.name as purchase_order_name,
                   po.status as purchase_order_status,
                   contract.id as contract_id,
                   coalesce(nullif(contract.contract_number, ''), contract.code) as contract_code,
                   contract.name as contract_name,
                   contract.status as contract_status,
                   customs.total_customs_amount as event_amount,
                   upper(nullif(trim(customs.currency_code), '')) as currency_code,
                   customs.status as event_status,
                   case
                     when upper(customs.status) = 'REJECTED'
                       or (upper(customs.status) in ('SUBMITTED', 'ACCEPTED')
                         and customs.declaration_date < :asOfDate)
                     then 'CUSTOMS_BLOCKED'
                     else null
                   end as risk_key,
                   cast(null as uuid) as owner_user_id,
                   cast(null as varchar) as owner_username,
                   cast(null as bigint) as assignment_version
            from customs_declarations customs
            left join import_deliveries import_delivery
              on import_delivery.id = customs.import_delivery_id
             and import_delivery.tenant_id = customs.tenant_id
             and import_delivery.company_id = customs.company_id
             and import_delivery.is_delete = false
            left join purchase_orders po
              on po.id = coalesce(customs.purchase_order_id, import_delivery.purchase_order_id)
             and po.tenant_id = customs.tenant_id
             and po.company_id = customs.company_id
             and po.is_delete = false
            left join suppliers supplier
              on supplier.id = customs.supplier_id
             and supplier.tenant_id = customs.tenant_id
             and supplier.company_id = customs.company_id
             and supplier.is_delete = false
            left join contracts contract
              on contract.id = po.contract_id
             and contract.tenant_id = customs.tenant_id
             and contract.company_id = customs.company_id
             and contract.is_delete = false
            where customs.tenant_id = :tenantId
              and customs.is_delete = false
              and customs.declaration_date >= :fromDate
              and customs.declaration_date < :toDate
            """);
        appendCompanyScope(sql, "customs", query, parameters);
        appendImportWarehouseScope(sql, "import_delivery", "po", query, parameters);
        appendEntityFilters(
            sql, query, parameters, "customs.supplier_id",
            "coalesce(customs.purchase_order_id, import_delivery.purchase_order_id)",
            "po.contract_id", "customs.currency_code", "customs.status");
        if (query.transportMode() != null) {
            sql.append(" and upper(import_delivery.transport_mode) = :transportMode");
            parameters.addValue("transportMode", query.transportMode());
        }
        appendRiskFilter(
            sql, query, """
                upper(customs.status) = 'REJECTED'
                  or (upper(customs.status) in ('SUBMITTED', 'ACCEPTED')
                    and customs.declaration_date < :asOfDate)
                """, SupplyRiskKey.CUSTOMS_BLOCKED,
            SupplyQueueKey.CUSTOMS_DOCUMENT_BLOCKED);
        appendSearch(sql, query, parameters,
            "customs.declaration_number", "customs.code", "customs.name", "po.code", "po.name",
            "supplier.code", "supplier.name", "supplier.legal_name", "supplier.short_name",
            "contract.contract_number", "import_delivery.shipment_number");
        return sql.toString();
    }

    private void appendCompanyScope(
        StringBuilder sql,
        String alias,
        SupplyWorkspaceQuery query,
        MapSqlParameterSource parameters
    ) {
        if (query.companyId() != null) {
            sql.append(" and ").append(alias).append(".company_id = :companyId");
            parameters.addValue("companyId", query.companyId());
        } else if (!query.authorizedCompanyIds().isEmpty()) {
            sql.append(" and ").append(alias).append(".company_id in (:authorizedCompanyIds)");
            parameters.addValue("authorizedCompanyIds", query.authorizedCompanyIds());
        }
    }

    private void appendPoWarehouseScope(
        StringBuilder sql,
        String poAlias,
        SupplyWorkspaceQuery query,
        MapSqlParameterSource parameters
    ) {
        if (query.warehouseScoped() && query.authorizedWarehouseIds().isEmpty()) {
            sql.append(" and 1 = 0");
            return;
        }
        if (query.warehouseId() == null && !query.warehouseScoped()) {
            return;
        }
        String parameter = query.warehouseId() == null ? "authorizedWarehouseIds" : "warehouseId";
        Object value = query.warehouseId() == null ? query.authorizedWarehouseIds() : query.warehouseId();
        String operator = query.warehouseId() == null ? " in (:" + parameter + ")" : " = :" + parameter;
        sql.append(" and exists (select 1 from purchase_order_items calendar_line")
            .append(" where calendar_line.tenant_id = ").append(poAlias).append(".tenant_id")
            .append(" and calendar_line.company_id = ").append(poAlias).append(".company_id")
            .append(" and calendar_line.purchase_order_id = ").append(poAlias).append(".id")
            .append(" and calendar_line.is_delete = false")
            .append(" and calendar_line.line_type = 'MATERIAL'")
            .append(" and calendar_line.receipt_warehouse_id").append(operator).append(")");
        parameters.addValue(parameter, value);
    }

    private void appendImportWarehouseScope(
        StringBuilder sql,
        String importAlias,
        String poAlias,
        SupplyWorkspaceQuery query,
        MapSqlParameterSource parameters
    ) {
        if (query.warehouseScoped() && query.authorizedWarehouseIds().isEmpty()) {
            sql.append(" and 1 = 0");
            return;
        }
        if (query.warehouseId() == null && !query.warehouseScoped()) {
            return;
        }
        String parameter = query.warehouseId() == null ? "authorizedWarehouseIds" : "warehouseId";
        Object value = query.warehouseId() == null ? query.authorizedWarehouseIds() : query.warehouseId();
        String operator = query.warehouseId() == null ? " in (:" + parameter + ")" : " = :" + parameter;
        sql.append(" and (").append(importAlias).append(".destination_warehouse_id").append(operator)
            .append(" or (").append(importAlias).append(".destination_warehouse_id is null")
            .append(" and exists (select 1 from purchase_order_items calendar_line")
            .append(" where calendar_line.tenant_id = ").append(poAlias).append(".tenant_id")
            .append(" and calendar_line.company_id = ").append(poAlias).append(".company_id")
            .append(" and calendar_line.purchase_order_id = ").append(poAlias).append(".id")
            .append(" and calendar_line.is_delete = false")
            .append(" and calendar_line.line_type = 'MATERIAL'")
            .append(" and calendar_line.receipt_warehouse_id").append(operator).append(")))");
        parameters.addValue(parameter, value);
    }

    private void appendEntityFilters(
        StringBuilder sql,
        SupplyWorkspaceQuery query,
        MapSqlParameterSource parameters,
        String supplierExpression,
        String purchaseOrderExpression,
        String contractExpression,
        String currencyExpression,
        String statusExpression
    ) {
        if (query.supplierId() != null) {
            sql.append(" and ").append(supplierExpression).append(" = :supplierId");
            parameters.addValue("supplierId", query.supplierId());
        }
        if (query.purchaseOrderId() != null && purchaseOrderExpression != null) {
            sql.append(" and ").append(purchaseOrderExpression).append(" = :purchaseOrderId");
            parameters.addValue("purchaseOrderId", query.purchaseOrderId());
        }
        if (query.contractId() != null) {
            sql.append(" and ").append(contractExpression).append(" = :contractId");
            parameters.addValue("contractId", query.contractId());
        }
        if (query.currencyCode() != null) {
            sql.append(" and upper(nullif(trim(").append(currencyExpression)
                .append("), '')) = :currencyCode");
            parameters.addValue("currencyCode", query.currencyCode());
        }
        if (query.status() != null) {
            sql.append(" and upper(").append(statusExpression).append(") = :eventStatus");
            parameters.addValue("eventStatus", query.status());
        }
    }

    private void appendDeliveryAssignmentFilters(
        StringBuilder sql,
        SupplyWorkspaceQuery query,
        MapSqlParameterSource parameters
    ) {
        if (query.lens() == SupplyLens.MY_WORK) {
            if (!query.authenticatedUserIds().isEmpty() || query.authenticatedUsername() != null) {
                sql.append(" and (");
                if (!query.authenticatedUserIds().isEmpty()) {
                    sql.append("assignment.owner_user_id in (:authenticatedUserIds)");
                    parameters.addValue("authenticatedUserIds", query.authenticatedUserIds());
                }
                if (query.authenticatedUsername() != null) {
                    if (!query.authenticatedUserIds().isEmpty()) {
                        sql.append(" or ");
                    }
                    sql.append("(assignment.owner_user_id is null")
                        .append(" and assignment.owner_username = :authenticatedUsername)");
                    parameters.addValue("authenticatedUsername", query.authenticatedUsername());
                }
                sql.append(")");
            } else {
                sql.append(" and 1 = 0");
            }
        } else if (query.ownerUserId() != null) {
            sql.append(" and assignment.owner_user_id = :ownerUserId");
            parameters.addValue("ownerUserId", query.ownerUserId());
        }
    }

    private void appendRiskFilter(
        StringBuilder sql,
        SupplyWorkspaceQuery query,
        String riskPredicate,
        SupplyRiskKey supportedRisk,
        SupplyQueueKey supportedQueue
    ) {
        if (query.risk() != null) {
            if (query.risk() == supportedRisk) {
                sql.append(" and (").append(riskPredicate).append(")");
            } else {
                sql.append(" and 1 = 0");
            }
        }
        if (query.queue() != null) {
            if (query.queue() == supportedQueue) {
                sql.append(" and (").append(riskPredicate).append(")");
            } else {
                sql.append(" and 1 = 0");
            }
        }
    }

    private void appendSearch(
        StringBuilder sql,
        SupplyWorkspaceQuery query,
        MapSqlParameterSource parameters,
        String... expressions
    ) {
        if (query.search() == null) {
            return;
        }
        sql.append(" and (");
        for (int index = 0; index < expressions.length; index++) {
            if (index > 0) {
                sql.append(" or ");
            }
            sql.append("lower(coalesce(").append(expressions[index]).append(", '')) like :calendarSearch");
        }
        sql.append(")");
        parameters.addValue("calendarSearch", "%" + query.search().toLowerCase(Locale.ROOT) + "%");
    }

    private MapSqlParameterSource parameters(SupplyWorkspaceQuery query) {
        return new MapSqlParameterSource()
            .addValue("tenantId", query.tenantId())
            .addValue("fromDate", query.from())
            .addValue("toDate", query.to())
            .addValue("asOfDate", query.asOfDate());
    }

    private String stableOrder() {
        return " order by operational_date, document_type, document_id";
    }

    private CalendarEvent mapRow(
        ResultSet rs,
        int rowNumber,
        SupplyWorkspaceQuery query
    ) throws SQLException {
        String documentType = rs.getString("document_type");
        UUID documentId = uuid(rs, "document_id");
        LocalDate date = rs.getObject("operational_date", LocalDate.class);
        String documentCode = rs.getString("document_code");
        String commitmentKey = rs.getString("commitment_key");
        DeepLinkTarget commitmentTarget = commitmentTarget(commitmentKey, documentType, query);
        EntityRef owner = owner(rs);
        Long assignmentVersion = rs.getObject("assignment_version", Long.class);
        AllowedAction nextAction = commitmentTarget == null ? null : new AllowedAction(
            "OPEN_COMMITMENT",
            "supply.workspace.action.openCommitment",
            "default",
            false,
            false,
            assignmentVersion,
            commitmentTarget);
        return new CalendarEvent(
            documentType + ":" + documentId + ":" + date,
            enumValue(SupplyDeliverySource.class, rs.getString("delivery_source")),
            SupplyCalendarLane.valueOf(rs.getString("lane")),
            date,
            true,
            SupplyDateBasis.valueOf(rs.getString("date_basis")),
            documentType,
            documentId,
            documentCode,
            rs.getString("document_name"),
            commitmentKey,
            ref(rs, "supplier"),
            ref(rs, "purchase_order"),
            ref(rs, "contract"),
            money(rs),
            rs.getString("event_status"),
            enumValue(SupplyRiskKey.class, rs.getString("risk_key")),
            owner,
            nextAction,
            primaryTarget(rs, documentType, documentId, documentCode, query),
            commitmentTarget);
    }

    private DeepLinkTarget primaryTarget(
        ResultSet rs,
        String documentType,
        UUID documentId,
        String documentCode,
        SupplyWorkspaceQuery workspaceQuery
    ) throws SQLException {
        String moduleKey = switch (documentType) {
            case "DELIVERY_SCHEDULE" -> "delivery-schedules";
            case "ASN" -> "asn";
            case "IMPORT_DELIVERY" -> "import-deliveries";
            case "CUSTOMS_DECLARATION" -> "customs-declarations";
            case "CONTRACT_INVOICE" -> "contract-invoices";
            case "CONTRACT_PAYMENT" -> "contract-payments";
            default -> throw new IllegalStateException("Unsupported calendar document type " + documentType);
        };
        Map<String, String> query = new LinkedHashMap<>();
        query.put("search", documentCode);
        UUID companyId = uuid(rs, "company_id");
        if (companyId != null) {
            query.put("companyId", companyId.toString());
        }
        UUID warehouseId = targetWarehouse(workspaceQuery);
        if (warehouseId != null) {
            query.put("warehouseId", warehouseId.toString());
        }
        return new DeepLinkTarget(moduleKey, documentId, null, query);
    }

    private DeepLinkTarget commitmentTarget(
        String canonical,
        String documentType,
        SupplyWorkspaceQuery query
    ) {
        if (canonical == null) {
            return null;
        }
        SupplyCommitmentKey key = SupplyCommitmentKey.parse(canonical);
        String tab = switch (documentType) {
            case "DELIVERY_SCHEDULE" -> "SCHEDULE";
            case "ASN", "IMPORT_DELIVERY", "CUSTOMS_DECLARATION" -> "ASN_IMPORT";
            default -> "OVERVIEW";
        };
        Map<String, String> targetQuery = new LinkedHashMap<>();
        targetQuery.put("commitment", keyCodec.encode(key));
        targetQuery.put("tab", tab);
        if (query.companyId() != null) {
            targetQuery.put("companyId", query.companyId().toString());
        }
        UUID warehouseId = targetWarehouse(query);
        if (warehouseId != null) {
            targetQuery.put("warehouseId", warehouseId.toString());
        }
        return new DeepLinkTarget(
            "supply-control",
            key.id(),
            canonical,
            targetQuery);
    }

    private UUID targetWarehouse(SupplyWorkspaceQuery query) {
        if (query.warehouseId() != null) {
            return query.warehouseId();
        }
        return query.warehouseScoped() && query.authorizedWarehouseIds().size() == 1
            ? query.authorizedWarehouseIds().iterator().next()
            : null;
    }

    private MoneyValue money(ResultSet rs) throws SQLException {
        BigDecimal amount = rs.getBigDecimal("event_amount");
        String currency = rs.getString("currency_code");
        if (amount == null && currency == null) {
            return null;
        }
        return new MoneyValue(amount, currency, null, null, null, null, null, false);
    }

    private EntityRef owner(ResultSet rs) throws SQLException {
        UUID id = uuid(rs, "owner_user_id");
        String username = rs.getString("owner_username");
        if (id == null && username == null) {
            return null;
        }
        return new EntityRef(id, username, username, "ACTIVE");
    }

    private EntityRef ref(ResultSet rs, String prefix) throws SQLException {
        UUID id = uuid(rs, prefix + "_id");
        if (id == null) {
            return null;
        }
        return new EntityRef(
            id,
            rs.getString(prefix + "_code"),
            rs.getString(prefix + "_name"),
            rs.getString(prefix + "_status"));
    }

    private UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    private <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        return value == null || value.isBlank() ? null : Enum.valueOf(type, value);
    }

    private void requireCalendarQuery(SupplyWorkspaceQuery query) {
        if (query == null || query.tenantId() == null || query.asOfDate() == null
            || query.from() == null || query.to() == null || !query.to().isAfter(query.from())) {
            throw new IllegalArgumentException("Normalized Supply calendar query with [from,to) is required");
        }
    }
}
