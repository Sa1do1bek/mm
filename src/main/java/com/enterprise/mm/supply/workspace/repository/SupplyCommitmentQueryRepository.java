package com.enterprise.mm.supply.workspace.repository;

import com.enterprise.mm.supply.workspace.domain.SupplyAccessScope;
import com.enterprise.mm.supply.workspace.domain.SupplyCommitmentKey;
import com.enterprise.mm.supply.workspace.domain.SupplyWorkspaceQuery;
import com.enterprise.mm.supply.workspace.enums.SupplyCommitmentKind;
import com.enterprise.mm.supply.workspace.enums.SupplyCommitmentSort;
import com.enterprise.mm.supply.workspace.enums.SupplyDataQuality;
import com.enterprise.mm.supply.workspace.enums.SupplyQueueKey;
import com.enterprise.mm.supply.workspace.enums.SupplyRiskKey;
import com.enterprise.mm.supply.workspace.enums.SupplySeverity;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SupplyCommitmentQueryRepository {
    static final String BASE_COMMITMENTS_SQL = """
        with eligible_po as (
            select po.id as purchase_order_id,
                   po.tenant_id,
                   po.company_id,
                   po.code as purchase_order_code,
                   po.name as purchase_order_name,
                   po.supplier_id,
                   supplier.code as supplier_code,
                   coalesce(nullif(supplier.short_name, ''), nullif(supplier.legal_name, ''), supplier.name) as supplier_name,
                   po.contract_id,
                   po.expected_delivery_date,
                   po.status as purchase_order_status,
                   upper(nullif(trim(po.currency_code), '')) as currency_code,
                   po.total_amount
            from purchase_orders po
            left join suppliers supplier
              on supplier.id = po.supplier_id
             and supplier.tenant_id = po.tenant_id
             and supplier.company_id = po.company_id
             and supplier.is_delete = false
            where po.tenant_id = :tenantId
              /*COMPANY_SCOPE*/
              and po.is_delete = false
              and po.request_type = 'GOODS'
              and po.status in ('SENT', 'PARTIALLY_RECEIVED', 'PARTIALLY_COMPLETED')
        ),
        po_line_rollup as (
            select line.tenant_id,
                   line.company_id,
                   line.purchase_order_id,
                   count(*) as line_count,
                   count(distinct coalesce(nullif(upper(trim(line.unit)), ''), '__MISSING__')) as uom_count,
                   case
                     when count(distinct coalesce(nullif(upper(trim(line.unit)), ''), '__MISSING__')) = 1
                       then min(nullif(upper(trim(line.unit)), ''))
                     else null
                   end as uom_code,
                   case
                     when count(distinct coalesce(nullif(upper(trim(line.unit)), ''), '__MISSING__')) = 1
                       and min(nullif(upper(trim(line.unit)), '')) is not null
                       and sum(case when line.quantity is null or line.received_quantity is null then 1 else 0 end) = 0
                       then sum(greatest(line.quantity - line.received_quantity, 0))
                     else null
                   end as open_quantity,
                   case
                     when count(distinct coalesce(nullif(upper(trim(line.unit)), ''), '__MISSING__')) = 1
                       and min(nullif(upper(trim(line.unit)), '')) is not null
                       and sum(case when line.quantity is null then 1 else 0 end) = 0
                       then sum(line.quantity)
                     else null
                   end as planned_quantity,
                   case
                     when count(distinct coalesce(nullif(upper(trim(line.unit)), ''), '__MISSING__')) = 1
                       and min(nullif(upper(trim(line.unit)), '')) is not null
                       and sum(case when line.received_quantity is null then 1 else 0 end) = 0
                       then sum(line.received_quantity)
                     else null
                   end as accepted_quantity,
                   case
                     when sum(case
                       when line.quantity is not null and line.received_quantity is not null
                         and line.quantity > line.received_quantity then 1 else 0 end) > 0 then true
                     when sum(case when line.quantity is null or line.received_quantity is null then 1 else 0 end) > 0
                       then cast(null as boolean)
                     else false
                   end as has_open_quantity,
                   case
                     when sum(case when line.quantity is null or line.received_quantity is null or line.unit_price is null then 1 else 0 end) = 0
                       then sum(greatest(line.quantity - line.received_quantity, 0) * line.unit_price)
                     else null
                   end as open_amount,
                   case
                     when sum(case when line.quantity is null or line.received_quantity is null then 1 else 0 end) = 0 then 1
                     else 0
                   end as quantity_complete,
                   case
                     when sum(case when line.quantity is null or line.received_quantity is null or line.unit_price is null then 1 else 0 end) = 0 then 1
                     else 0
                   end as amount_complete
            from purchase_order_items line
            join eligible_po po
              on po.purchase_order_id = line.purchase_order_id
             and po.tenant_id = line.tenant_id
             and po.company_id = line.company_id
            where line.is_delete = false
              and line.line_type = 'MATERIAL'
              /*LINE_WAREHOUSE_SCOPE*/
            group by line.tenant_id, line.company_id, line.purchase_order_id
        ),
        active_schedule as (
            select schedule.id as schedule_id,
                   schedule.tenant_id,
                   schedule.company_id,
                   schedule.purchase_order_id,
                   schedule.schedule_date,
                   schedule.status as schedule_status,
                   schedule.planned_quantity,
                   schedule.received_quantity,
                   schedule.planned_amount,
                   schedule.received_amount
            from delivery_schedules schedule
            join eligible_po po
              on po.purchase_order_id = schedule.purchase_order_id
             and po.tenant_id = schedule.tenant_id
             and po.company_id = schedule.company_id
            where schedule.is_delete = false
              and (schedule.status is null
                or upper(schedule.status) not in ('RECEIVED', 'CANCELLED'))
        ),
        scheduled_commitments as (
            select 'SCHEDULE' as commitment_kind,
                   schedule.schedule_id as commitment_id,
                   concat('SCHEDULE:', lower(cast(schedule.schedule_id as varchar))) as canonical_key,
                   po.tenant_id,
                   po.company_id,
                   po.purchase_order_id,
                   po.purchase_order_code,
                   po.purchase_order_name,
                   po.supplier_id,
                   po.supplier_code,
                   po.supplier_name,
                   po.contract_id,
                   schedule.schedule_date as operational_date,
                   schedule.schedule_status as commitment_status,
                   po.currency_code,
                   case
                     when :warehouseLimited then cast(null as decimal(38, 8))
                     when schedule.planned_amount is null or schedule.received_amount is null then null
                     else greatest(schedule.planned_amount - schedule.received_amount, 0)
                   end as open_amount,
                   case
                     when :warehouseLimited then cast(null as decimal(38, 8))
                     when schedule.planned_quantity is null or schedule.received_quantity is null then null
                     else greatest(schedule.planned_quantity - schedule.received_quantity, 0)
                   end as open_quantity,
                   case
                     when :warehouseLimited then cast(null as decimal(38, 8))
                     else schedule.planned_quantity
                   end as planned_quantity,
                   case
                     when :warehouseLimited then cast(null as decimal(38, 8))
                     else schedule.received_quantity
                   end as accepted_quantity,
                   case
                     when :warehouseLimited
                       or schedule.planned_quantity is null or schedule.received_quantity is null
                       then cast(null as boolean)
                     when schedule.planned_quantity > schedule.received_quantity then true
                     else false
                   end as has_open_quantity,
                   cast(null as varchar) as uom_code,
                   case
                     when :warehouseLimited then 'UNAVAILABLE'
                     when schedule.planned_amount is null or schedule.received_amount is null
                       or schedule.planned_quantity is null or schedule.received_quantity is null then 'PARTIAL'
                     else 'HEADER_ONLY'
                   end as data_quality,
                   case
                     when schedule.schedule_date < :asOfDate
                       and not :warehouseLimited
                       and schedule.planned_quantity is not null
                       and schedule.received_quantity is not null
                       and schedule.planned_quantity > schedule.received_quantity then 2
                     else 0
                   end as date_severity_rank
            from active_schedule schedule
            join eligible_po po
              on po.purchase_order_id = schedule.purchase_order_id
             and po.tenant_id = schedule.tenant_id
             and po.company_id = schedule.company_id
        ),
        unscheduled_commitments as (
            select 'PO' as commitment_kind,
                   po.purchase_order_id as commitment_id,
                   concat('PO:', lower(cast(po.purchase_order_id as varchar))) as canonical_key,
                   po.tenant_id,
                   po.company_id,
                   po.purchase_order_id,
                   po.purchase_order_code,
                   po.purchase_order_name,
                   po.supplier_id,
                   po.supplier_code,
                   po.supplier_name,
                   po.contract_id,
                   po.expected_delivery_date as operational_date,
                   po.purchase_order_status as commitment_status,
                   po.currency_code,
                   case
                     when lines.line_count is null then case when :warehouseLimited then null else po.total_amount end
                     else lines.open_amount
                   end as open_amount,
                   lines.open_quantity,
                   lines.planned_quantity,
                   lines.accepted_quantity,
                   lines.has_open_quantity,
                   lines.uom_code,
                   case
                     when :warehouseLimited then 'PARTIAL'
                     when lines.line_count is null then 'HEADER_ONLY'
                     when lines.uom_count = 1 and lines.uom_code is not null
                       and lines.quantity_complete = 1 and lines.amount_complete = 1 then 'AUTHORITATIVE'
                     else 'PARTIAL'
                   end as data_quality,
                   case
                     when po.expected_delivery_date < :asOfDate and lines.has_open_quantity = true then 2
                     else 0
                   end as date_severity_rank
            from eligible_po po
            left join po_line_rollup lines
              on lines.purchase_order_id = po.purchase_order_id
             and lines.tenant_id = po.tenant_id
             and lines.company_id = po.company_id
            where not exists (
                select 1
                from active_schedule schedule
                where schedule.purchase_order_id = po.purchase_order_id
                  and schedule.tenant_id = po.tenant_id
                  and schedule.company_id = po.company_id
            )
        ),
        raw_commitments as (
            select * from scheduled_commitments
            union all
            select * from unscheduled_commitments
        ),
        commitment_facts as (
            select raw.*,
                   case
                     when raw.operational_date < :asOfDate and raw.has_open_quantity = true then true
                     else false
                   end
                     as is_overdue_commitment,
                   case
                     when raw.operational_date >= :asOfDate
                       and raw.operational_date < :asOfPlusEightDate
                       and raw.has_open_quantity = true
                       and not exists (
                           select 1
                           from advance_shipping_notices asn
                           where asn.tenant_id = raw.tenant_id
                             and asn.company_id = raw.company_id
                             and asn.purchase_order_id = raw.purchase_order_id
                             and asn.is_delete = false
                             and upper(asn.status) in ('CONFIRMED', 'PARTIALLY_RECEIVED', 'RECEIVED')
                       )
                     then true else false
                   end as is_arriving_7d_without_confirmed_asn,
                   case when exists (
                       select 1
                       from import_deliveries import_delivery
                       where import_delivery.tenant_id = raw.tenant_id
                         and import_delivery.company_id = raw.company_id
                         and import_delivery.purchase_order_id = raw.purchase_order_id
                         and import_delivery.is_delete = false
                         and upper(import_delivery.status) in ('BOOKED', 'IN_TRANSIT', 'AT_CUSTOMS', 'CLEARED')
                         and (
                           (import_delivery.eta < :asOfDate
                             and (import_delivery.actual_arrival_date is null
                               or import_delivery.actual_arrival_date > :asOfDate))
                           or exists (
                             select 1
                             from landed_logistics_events logistics_event
                             where logistics_event.tenant_id = import_delivery.tenant_id
                               and logistics_event.company_id = import_delivery.company_id
                               and logistics_event.import_delivery_id = import_delivery.id
                               and logistics_event.is_delete = false
                               and (logistics_event.exception_flag = true
                                 or logistics_event.resolved_at is not null)
                               and (logistics_event.event_date is null
                                 or logistics_event.event_date <= :asOfLocalTimestamp)
                               and (logistics_event.resolved_at is null
                                 or logistics_event.resolved_at > :asOfLocalTimestamp)
                           )
                         )
                   ) then true else false end as is_active_import_eta_risk,
                   case when exists (
                       select 1
                       from import_deliveries import_delivery
                       where import_delivery.tenant_id = raw.tenant_id
                         and import_delivery.company_id = raw.company_id
                         and import_delivery.purchase_order_id = raw.purchase_order_id
                         and import_delivery.is_delete = false
                         and import_delivery.import_flag = true
                         and upper(import_delivery.status) in ('BOOKED', 'IN_TRANSIT', 'AT_CUSTOMS', 'CLEARED')
                         and (
                           upper(import_delivery.status) in ('AT_CUSTOMS', 'CLEARED')
                           or exists (
                               select 1
                               from customs_declarations problematic_customs
                               where problematic_customs.tenant_id = import_delivery.tenant_id
                                 and problematic_customs.company_id = import_delivery.company_id
                                 and (problematic_customs.import_delivery_id = import_delivery.id
                                   or (problematic_customs.import_delivery_id is null
                                     and problematic_customs.purchase_order_id = raw.purchase_order_id))
                                 and problematic_customs.is_delete = false
                                 and upper(problematic_customs.status) = 'REJECTED'
                           )
                         )
                         and not exists (
                             select 1
                             from customs_declarations released_customs
                             where released_customs.tenant_id = import_delivery.tenant_id
                               and released_customs.company_id = import_delivery.company_id
                               and (released_customs.import_delivery_id = import_delivery.id
                                 or (released_customs.import_delivery_id is null
                                   and released_customs.purchase_order_id = raw.purchase_order_id))
                               and released_customs.is_delete = false
                               and upper(released_customs.status) = 'RELEASED'
                         )
                   ) then true else false end as is_customs_document_blocked,
                   case when exists (
                       select 1
                       from integration_events integration_event
                       where integration_event.tenant_id = raw.tenant_id
                         and integration_event.company_id = raw.company_id
                         and integration_event.is_delete = false
                         and (
                           upper(integration_event.status) in ('FAILED', 'DEAD', 'DEAD_LETTER')
                           or (upper(integration_event.status) = 'RETRY'
                             and (integration_event.next_retry_at is null
                               or integration_event.next_retry_at <= :asOfLocalTimestamp))
                         )
                         and (
                             integration_event.aggregate_id = raw.purchase_order_id
                             or integration_event.aggregate_id in (
                                 select asn.id
                                 from advance_shipping_notices asn
                                 where asn.tenant_id = raw.tenant_id
                                   and asn.company_id = raw.company_id
                                   and asn.purchase_order_id = raw.purchase_order_id
                                   and asn.is_delete = false
                             )
                             or integration_event.aggregate_id in (
                                 select import_delivery.id
                                 from import_deliveries import_delivery
                                 where import_delivery.tenant_id = raw.tenant_id
                                   and import_delivery.company_id = raw.company_id
                                   and import_delivery.purchase_order_id = raw.purchase_order_id
                                   and import_delivery.is_delete = false
                             )
                             or integration_event.aggregate_id in (
                                 select customs.id
                                 from customs_declarations customs
                                 where customs.tenant_id = raw.tenant_id
                                   and customs.company_id = raw.company_id
                                   and customs.purchase_order_id = raw.purchase_order_id
                                   and customs.is_delete = false
                             )
                         )
                   ) then true else false end as is_integration_failed,
                   case when assignment.owner_user_id is null then true else false end as is_unassigned,
                   assignment.owner_user_id,
                   assignment.owner_username,
                   assignment.owner_role,
                   assignment.due_date as assignment_due_date,
                   assignment.due_date_source as assignment_due_date_source,
                   assignment.severity as assignment_severity,
                   assignment.escalation_state as assignment_escalation_state,
                   assignment.escalation_level as assignment_escalation_level,
                   assignment.reason as assignment_reason,
                   assignment.version as assignment_version
            from raw_commitments raw
            left join supply_work_assignments assignment
              on assignment.tenant_id = raw.tenant_id
             and assignment.company_id = raw.company_id
             and assignment.commitment_key = raw.canonical_key
             and assignment.is_delete = false
        )
        select facts.*,
               greatest(
                   facts.date_severity_rank,
                   case when facts.is_integration_failed or facts.is_customs_document_blocked
                          or facts.is_active_import_eta_risk then 3 else 0 end,
                   case when facts.is_arriving_7d_without_confirmed_asn then 1 else 0 end,
                   case facts.assignment_severity
                     when 'BLOCKING' then 3
                     when 'WARNING' then 1
                     else 0
                   end
               ) as severity_rank
        from commitment_facts facts
        """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ZoneId timeZone;

    public SupplyCommitmentQueryRepository(
        NamedParameterJdbcTemplate jdbcTemplate,
        @Value("${app.supply-workspace.time-zone:Asia/Tashkent}") String timeZone
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.timeZone = ZoneId.of(timeZone);
    }

    public Page<SupplyCommitmentRecord> search(SupplyWorkspaceQuery query, Pageable pageable) {
        requireQuery(query);
        Pageable requestedPage = pageable == null ? Pageable.unpaged() : pageable;
        MapSqlParameterSource parameters = parameters(query);
        String source = "select * from (" + baseSql(query, parameters) + ") commitment" + where(query, parameters);
        String sql = source + orderBy(query);
        List<SupplyCommitmentRecord> rows;
        if (requestedPage.isPaged()) {
            parameters.addValue("limit", requestedPage.getPageSize()).addValue("offset", requestedPage.getOffset());
            rows = jdbcTemplate.query(sql + " limit :limit offset :offset", parameters, this::mapRow);
            return new PageImpl<>(rows, requestedPage, count(query));
        }
        rows = jdbcTemplate.query(sql, parameters, this::mapRow);
        return new PageImpl<>(rows);
    }

    public long count(SupplyWorkspaceQuery query) {
        requireQuery(query);
        MapSqlParameterSource parameters = parameters(query);
        Long value = jdbcTemplate.queryForObject(
            "select count(*) from (" + baseSql(query, parameters) + ") commitment" + where(query, parameters),
            parameters,
            Long.class);
        return value == null ? 0L : value;
    }

    public List<SupplyCommitmentRecord.MoneyValue> openExposureByCurrency(SupplyWorkspaceQuery query) {
        requireQuery(query);
        MapSqlParameterSource parameters = parameters(query);
        String sql = "select currency_code, "
            + "case when sum(case when open_amount is null then 1 else 0 end) > 0 "
            + "then null else sum(open_amount) end as amount from (" + baseSql(query, parameters)
            + ") commitment" + where(query, parameters)
            + " group by currency_code order by case when currency_code is null then 1 else 0 end, currency_code";
        return jdbcTemplate.query(sql, parameters, (rs, rowNumber) ->
            new SupplyCommitmentRecord.MoneyValue(rs.getString("currency_code"), rs.getBigDecimal("amount")));
    }

    public List<SupplyCommitmentRecord.QuantityValue> openQuantityByUom(SupplyWorkspaceQuery query) {
        requireQuery(query);
        MapSqlParameterSource parameters = parameters(query);
        String sql = "select upper(trim(line.unit)) as uom_code, "
            + "case when sum(case when line.quantity is null or line.received_quantity is null then 1 else 0 end) > 0 "
            + "then null else sum(greatest(line.quantity - line.received_quantity, 0)) end as quantity "
            + "from (" + baseSql(query, parameters) + ") commitment "
            + "join purchase_order_items line on line.purchase_order_id = commitment.purchase_order_id "
            + "and line.tenant_id = commitment.tenant_id and line.company_id = commitment.company_id "
            + where(query, parameters)
            + " and commitment.commitment_kind = 'PO' and line.is_delete = false "
            + "and line.line_type = 'MATERIAL' and nullif(trim(line.unit), '') is not null "
            + lineWarehouseScope("line", query, parameters)
            + "group by upper(trim(line.unit)) order by upper(trim(line.unit))";
        return jdbcTemplate.query(sql, parameters, (rs, rowNumber) ->
            new SupplyCommitmentRecord.QuantityValue(rs.getString("uom_code"), rs.getBigDecimal("quantity")));
    }

    public List<SupplyCommitmentRecord.AgendaDayCount> agendaByDay(
        SupplyWorkspaceQuery query,
        java.time.LocalDate from,
        java.time.LocalDate to
    ) {
        requireQuery(query);
        if (from == null || to == null || !to.isAfter(from)) {
            throw new IllegalArgumentException("A valid agenda range is required");
        }
        MapSqlParameterSource parameters = parameters(query)
            .addValue("agendaFrom", from)
            .addValue("agendaTo", to);
        String sql = "select operational_date, count(*) as commitment_count, max(severity_rank) as max_severity "
            + "from (" + baseSql(query, parameters) + ") commitment"
            + where(query, parameters)
            + " and commitment.operational_date >= :agendaFrom"
            + " and commitment.operational_date < :agendaTo"
            + " group by operational_date order by operational_date";
        return jdbcTemplate.query(sql, parameters, (rs, rowNumber) -> {
            int rank = rs.getInt("max_severity");
            SupplySeverity severity = rank >= 2
                ? SupplySeverity.BLOCKING
                : rank == 1 ? SupplySeverity.WARNING : SupplySeverity.INFO;
            return new SupplyCommitmentRecord.AgendaDayCount(
                rs.getObject("operational_date", java.time.LocalDate.class),
                rs.getLong("commitment_count"),
                severity);
        });
    }

    public long countUnlinkedImportRisks(SupplyWorkspaceQuery query) {
        requireQuery(query);
        MapSqlParameterSource parameters = parameters(query);
        StringBuilder sql = new StringBuilder("""
            select count(*)
            from import_deliveries import_delivery
            where import_delivery.tenant_id = :tenantId
              and import_delivery.is_delete = false
              and import_delivery.purchase_order_id is null
              and upper(import_delivery.status) in ('BOOKED', 'IN_TRANSIT', 'AT_CUSTOMS', 'CLEARED')
              and (
                (import_delivery.eta < :asOfDate
                  and (import_delivery.actual_arrival_date is null
                    or import_delivery.actual_arrival_date > :asOfDate))
                or exists (
                  select 1
                  from landed_logistics_events logistics_event
                  where logistics_event.tenant_id = import_delivery.tenant_id
                    and logistics_event.company_id = import_delivery.company_id
                    and logistics_event.import_delivery_id = import_delivery.id
                    and logistics_event.is_delete = false
                    and (logistics_event.exception_flag = true
                      or logistics_event.resolved_at is not null)
                    and (logistics_event.event_date is null
                      or logistics_event.event_date <= :asOfLocalTimestamp)
                    and (logistics_event.resolved_at is null
                      or logistics_event.resolved_at > :asOfLocalTimestamp)
                )
              )
            """);
        appendImportCoverageScope(query, parameters, sql);
        Long value = jdbcTemplate.queryForObject(sql.toString(), parameters, Long.class);
        return value == null ? 0L : value;
    }

    public Optional<SupplyCommitmentRecord> findScoped(
        SupplyCommitmentKey key,
        SupplyAccessScope scope,
        Instant asOf
    ) {
        if (key == null || scope == null || asOf == null) {
            return Optional.empty();
        }
        UUID companyId = scope.companyIds().size() == 1 ? scope.companyIds().iterator().next() : null;
        UUID warehouseId = scope.warehouseScoped() && scope.warehouseIds().size() == 1
            ? scope.warehouseIds().iterator().next() : null;
        SupplyWorkspaceQuery query = new SupplyWorkspaceQuery(
            scope.tenantId(), scope.companyIds(), scope.warehouseIds(), scope.warehouseScoped(),
            asOf, asOf.atZone(timeZone).toLocalDate(), timeZone,
            null, null, com.enterprise.mm.supply.workspace.enums.SupplyLens.TEAM, null,
            companyId, null, null, null, warehouseId, null, null, null, null, null, null,
            SupplyCommitmentSort.DEFAULT);
        MapSqlParameterSource parameters = parameters(query)
            .addValue("keyKind", key.kind().name())
            .addValue("keyId", key.id());
        String sql = "select * from (" + baseSql(query, parameters) + ") commitment"
            + where(query, parameters)
            + " and commitment_kind = :keyKind and commitment_id = :keyId";
        return jdbcTemplate.query(sql, parameters, this::mapRow).stream().findFirst();
    }

    private String where(SupplyWorkspaceQuery query, MapSqlParameterSource parameters) {
        StringBuilder where = new StringBuilder(" where 1 = 1");
        if (query.companyId() != null) {
            where.append(" and commitment.company_id = :companyId");
            parameters.addValue("companyId", query.companyId());
        } else if (!query.authorizedCompanyIds().isEmpty()) {
            where.append(" and commitment.company_id in (:authorizedCompanyIds)");
            parameters.addValue("authorizedCompanyIds", query.authorizedCompanyIds());
        }

        appendWarehouseScope(query, parameters, where);

        if (query.from() != null) {
            where.append(" and commitment.operational_date >= :fromDate");
            parameters.addValue("fromDate", query.from());
        }
        if (query.to() != null) {
            where.append(" and commitment.operational_date < :toDate");
            parameters.addValue("toDate", query.to());
        }
        if (query.supplierId() != null) {
            where.append(" and commitment.supplier_id = :supplierId");
            parameters.addValue("supplierId", query.supplierId());
        }
        if (query.purchaseOrderId() != null) {
            where.append(" and commitment.purchase_order_id = :purchaseOrderId");
            parameters.addValue("purchaseOrderId", query.purchaseOrderId());
        }
        if (query.contractId() != null) {
            where.append(" and commitment.contract_id = :contractId");
            parameters.addValue("contractId", query.contractId());
        }
        if (query.currencyCode() != null) {
            where.append(" and commitment.currency_code = :currencyCode");
            parameters.addValue("currencyCode", query.currencyCode());
        }
        if (query.status() != null) {
            where.append(" and upper(commitment.commitment_status) = :commitmentStatus");
            parameters.addValue("commitmentStatus", query.status());
        }
        if (query.search() != null) {
            where.append(" and (lower(commitment.purchase_order_code) like :search")
                .append(" or lower(commitment.purchase_order_name) like :search")
                .append(" or lower(commitment.supplier_code) like :search")
                .append(" or lower(commitment.supplier_name) like :search")
                .append(" or lower(commitment.canonical_key) like :search)");
            parameters.addValue("search", "%" + query.search().toLowerCase(java.util.Locale.ROOT) + "%");
        }
        if (query.transportMode() != null) {
            where.append(" and (exists (select 1 from advance_shipping_notices transport_asn")
                .append(" where transport_asn.tenant_id = commitment.tenant_id")
                .append(" and transport_asn.company_id = commitment.company_id")
                .append(" and transport_asn.purchase_order_id = commitment.purchase_order_id")
                .append(" and transport_asn.is_delete = false")
                .append(" and upper(transport_asn.transport_mode) = :transportMode)")
                .append(" or exists (select 1 from import_deliveries transport_import")
                .append(" where transport_import.tenant_id = commitment.tenant_id")
                .append(" and transport_import.company_id = commitment.company_id")
                .append(" and transport_import.purchase_order_id = commitment.purchase_order_id")
                .append(" and transport_import.is_delete = false")
                .append(" and upper(transport_import.transport_mode) = :transportMode))");
            parameters.addValue("transportMode", query.transportMode());
        }
        if (query.lens() == com.enterprise.mm.supply.workspace.enums.SupplyLens.MY_WORK) {
            if (query.authenticatedUserIds().isEmpty() && query.authenticatedUsername() == null) {
                where.append(" and 1 = 0");
            } else {
                where.append(" and (");
                if (!query.authenticatedUserIds().isEmpty()) {
                    where.append("commitment.owner_user_id in (:authenticatedUserIds)");
                    parameters.addValue("authenticatedUserIds", query.authenticatedUserIds());
                }
                if (query.authenticatedUsername() != null) {
                    if (!query.authenticatedUserIds().isEmpty()) {
                        where.append(" or ");
                    }
                    where.append("(commitment.owner_user_id is null")
                        .append(" and commitment.owner_username = :authenticatedUsername)");
                    parameters.addValue("authenticatedUsername", query.authenticatedUsername());
                }
                where.append(")");
            }
        } else if (query.ownerUserId() != null) {
            where.append(" and commitment.owner_user_id = :ownerUserId");
            parameters.addValue("ownerUserId", query.ownerUserId());
        }
        appendNamedQueueAndRisk(query, where);
        return where.toString();
    }

    private void appendWarehouseScope(
        SupplyWorkspaceQuery query,
        MapSqlParameterSource parameters,
        StringBuilder where
    ) {
        if (query.warehouseScoped() && query.authorizedWarehouseIds().isEmpty()) {
            where.append(" and 1 = 0");
            return;
        }
        if (query.warehouseId() != null) {
            where.append(" and exists (select 1 from purchase_order_items warehouse_line")
                .append(" where warehouse_line.purchase_order_id = commitment.purchase_order_id")
                .append(" and warehouse_line.tenant_id = commitment.tenant_id")
                .append(" and warehouse_line.company_id = commitment.company_id")
                .append(" and warehouse_line.is_delete = false")
                .append(" and warehouse_line.line_type = 'MATERIAL'")
                .append(" and warehouse_line.receipt_warehouse_id = :warehouseId)");
            parameters.addValue("warehouseId", query.warehouseId());
        } else if (query.warehouseScoped()) {
            where.append(" and exists (select 1 from purchase_order_items warehouse_line")
                .append(" where warehouse_line.purchase_order_id = commitment.purchase_order_id")
                .append(" and warehouse_line.tenant_id = commitment.tenant_id")
                .append(" and warehouse_line.company_id = commitment.company_id")
                .append(" and warehouse_line.is_delete = false")
                .append(" and warehouse_line.line_type = 'MATERIAL'")
                .append(" and warehouse_line.receipt_warehouse_id in (:authorizedWarehouseIds))");
            parameters.addValue("authorizedWarehouseIds", query.authorizedWarehouseIds());
        }
    }

    private void appendNamedQueueAndRisk(SupplyWorkspaceQuery query, StringBuilder where) {
        if (query.queue() != null) {
            where.append(" and commitment.").append(predicate(query.queue())).append(" = true");
        }
        if (query.risk() != null) {
            where.append(" and commitment.").append(predicate(query.risk())).append(" = true");
        }
    }

    private String baseSql(SupplyWorkspaceQuery query, MapSqlParameterSource parameters) {
        return BASE_COMMITMENTS_SQL
            .replace("/*COMPANY_SCOPE*/", companyScope(query, parameters))
            .replace("/*LINE_WAREHOUSE_SCOPE*/", lineWarehouseScope("line", query, parameters));
    }

    private String companyScope(SupplyWorkspaceQuery query, MapSqlParameterSource parameters) {
        if (query.companyId() != null) {
            parameters.addValue("companyId", query.companyId());
            return "and po.company_id = :companyId";
        }
        if (!query.authorizedCompanyIds().isEmpty()) {
            parameters.addValue("authorizedCompanyIds", query.authorizedCompanyIds());
            return "and po.company_id in (:authorizedCompanyIds)";
        }
        return "";
    }

    private String lineWarehouseScope(
        String alias,
        SupplyWorkspaceQuery query,
        MapSqlParameterSource parameters
    ) {
        if (query.warehouseId() != null) {
            parameters.addValue("warehouseId", query.warehouseId());
            return " and " + alias + ".receipt_warehouse_id = :warehouseId ";
        }
        if (query.warehouseScoped() && query.authorizedWarehouseIds().isEmpty()) {
            return " and 1 = 0 ";
        }
        if (query.warehouseScoped()) {
            parameters.addValue("authorizedWarehouseIds", query.authorizedWarehouseIds());
            return " and " + alias + ".receipt_warehouse_id in (:authorizedWarehouseIds) ";
        }
        return "";
    }

    private void appendImportCoverageScope(
        SupplyWorkspaceQuery query,
        MapSqlParameterSource parameters,
        StringBuilder sql
    ) {
        if (query.companyId() != null) {
            sql.append(" and import_delivery.company_id = :companyId");
            parameters.addValue("companyId", query.companyId());
        } else if (!query.authorizedCompanyIds().isEmpty()) {
            sql.append(" and import_delivery.company_id in (:authorizedCompanyIds)");
            parameters.addValue("authorizedCompanyIds", query.authorizedCompanyIds());
        }
        if (query.warehouseScoped() && query.authorizedWarehouseIds().isEmpty()) {
            sql.append(" and 1 = 0");
        } else if (query.warehouseId() != null) {
            sql.append(" and import_delivery.destination_warehouse_id = :warehouseId");
            parameters.addValue("warehouseId", query.warehouseId());
        } else if (query.warehouseScoped()) {
            sql.append(" and import_delivery.destination_warehouse_id in (:authorizedWarehouseIds)");
            parameters.addValue("authorizedWarehouseIds", query.authorizedWarehouseIds());
        }
        if (query.from() != null) {
            sql.append(" and import_delivery.eta >= :fromDate");
            parameters.addValue("fromDate", query.from());
        }
        if (query.to() != null) {
            sql.append(" and import_delivery.eta < :toDate");
            parameters.addValue("toDate", query.to());
        }
        if (query.supplierId() != null) {
            sql.append(" and import_delivery.supplier_id = :supplierId");
            parameters.addValue("supplierId", query.supplierId());
        }
        if (query.transportMode() != null) {
            sql.append(" and upper(import_delivery.transport_mode) = :transportMode");
            parameters.addValue("transportMode", query.transportMode());
        }
        if (query.currencyCode() != null) {
            sql.append(" and upper(nullif(trim(import_delivery.currency_code), '')) = :currencyCode");
            parameters.addValue("currencyCode", query.currencyCode());
        }
    }

    private String predicate(SupplyQueueKey queue) {
        return switch (queue) {
            case OVERDUE_COMMITMENTS -> "is_overdue_commitment";
            case ARRIVING_7D_WITHOUT_CONFIRMED_ASN -> "is_arriving_7d_without_confirmed_asn";
            case ACTIVE_IMPORT_ETA_RISK -> "is_active_import_eta_risk";
            case CUSTOMS_DOCUMENT_BLOCKED -> "is_customs_document_blocked";
            case INTEGRATION_FAILED -> "is_integration_failed";
            case UNASSIGNED -> "is_unassigned";
        };
    }

    private String predicate(SupplyRiskKey risk) {
        return switch (risk) {
            case OVERDUE -> "is_overdue_commitment";
            case ASN_MISSING -> "is_arriving_7d_without_confirmed_asn";
            case IMPORT_ETA_RISK -> "is_active_import_eta_risk";
            case CUSTOMS_BLOCKED -> "is_customs_document_blocked";
            case INTEGRATION_FAILURE -> "is_integration_failed";
            case UNASSIGNED -> "is_unassigned";
        };
    }

    private String orderBy(SupplyWorkspaceQuery query) {
        SupplyCommitmentSort sort = query.sort() == null ? SupplyCommitmentSort.DEFAULT : query.sort();
        return switch (sort) {
            case OPEN_AMOUNT -> " order by case when open_amount is null then 1 else 0 end, open_amount desc, canonical_key asc";
            case PROMISED_DATE -> " order by case when operational_date is null then 1 else 0 end, operational_date asc, canonical_key asc";
            case DEFAULT, SEVERITY -> " order by severity_rank desc,"
                + " case when is_overdue_commitment then 0 else 1 end,"
                + " case when is_overdue_commitment then operational_date else null end asc,"
                + " case when operational_date is null then 1 else 0 end, operational_date asc,"
                + (query.currencyCode() == null
                    ? " canonical_key asc"
                    : " case when open_amount is null then 1 else 0 end, open_amount desc, canonical_key asc");
        };
    }

    private MapSqlParameterSource parameters(SupplyWorkspaceQuery query) {
        return new MapSqlParameterSource()
            .addValue("tenantId", query.tenantId())
            .addValue("asOfDate", query.asOfDate())
            // These legacy columns are SQL timestamp mapped as LocalDateTime and are
            // populated with operational wall time. Date-only and timestamp predicates
            // therefore share the configured Supply workspace zone deliberately.
            .addValue(
                "asOfLocalTimestamp",
                java.time.LocalDateTime.ofInstant(query.asOf(), query.timeZone()))
            .addValue("asOfPlusEightDate", query.asOfDate().plusDays(8))
            .addValue("warehouseLimited", query.warehouseId() != null || query.warehouseScoped());
    }

    private SupplyCommitmentRecord mapRow(ResultSet rs, int rowNumber) throws SQLException {
        SupplyCommitmentKind kind = SupplyCommitmentKind.valueOf(rs.getString("commitment_kind"));
        int severityRank = rs.getInt("severity_rank");
        com.enterprise.mm.supply.workspace.enums.SupplyDueDateSource dueDateSource =
            com.enterprise.mm.supply.workspace.enums.SupplyDueDateSource
                .fromStoredValue(rs.getString("assignment_due_date_source"));
        return new SupplyCommitmentRecord(
            new SupplyCommitmentKey(kind, uuid(rs, "commitment_id")),
            uuid(rs, "company_id"),
            uuid(rs, "purchase_order_id"),
            rs.getString("purchase_order_code"),
            rs.getString("purchase_order_name"),
            uuid(rs, "supplier_id"),
            rs.getString("supplier_code"),
            rs.getString("supplier_name"),
            uuid(rs, "contract_id"),
            rs.getObject("operational_date", java.time.LocalDate.class),
            rs.getString("commitment_status"),
            rs.getString("currency_code"),
            rs.getBigDecimal("open_amount"),
            rs.getBigDecimal("open_quantity"),
            rs.getString("uom_code"),
            SupplyDataQuality.valueOf(rs.getString("data_quality")),
            severityRank >= 2
                ? SupplySeverity.BLOCKING
                : severityRank == 1 ? SupplySeverity.WARNING : SupplySeverity.INFO,
            rs.getBoolean("is_overdue_commitment"),
            rs.getBoolean("is_arriving_7d_without_confirmed_asn"),
            rs.getBoolean("is_active_import_eta_risk"),
            rs.getBoolean("is_customs_document_blocked"),
            rs.getBoolean("is_integration_failed"),
            rs.getBoolean("is_unassigned"),
            uuid(rs, "owner_user_id"),
            rs.getString("owner_username"),
            rs.getString("owner_role"),
            rs.getObject("assignment_due_date", java.time.LocalDate.class),
            rs.getObject("assignment_version", Long.class),
            dueDateSource == null ? null : dueDateSource.normalized(),
            enumValue(SupplySeverity.class, rs.getString("assignment_severity")),
            enumValue(
                com.enterprise.mm.supply.workspace.enums.SupplyEscalationState.class,
                rs.getString("assignment_escalation_state")),
            rs.getObject("assignment_escalation_level", Integer.class),
            rs.getString("assignment_reason"),
            rs.getBigDecimal("planned_quantity"),
            rs.getBigDecimal("accepted_quantity"),
            rs.getObject("has_open_quantity", Boolean.class));
    }

    private UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    private <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        return value == null || value.isBlank() ? null : Enum.valueOf(type, value);
    }

    private void requireQuery(SupplyWorkspaceQuery query) {
        if (query == null || query.tenantId() == null || query.asOfDate() == null) {
            throw new IllegalArgumentException("Normalized supply workspace query is required");
        }
    }
}
