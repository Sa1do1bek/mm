package com.enterprise.mm.supply.workspace.repository;

import com.enterprise.mm.supply.workspace.domain.SupplyWorkspaceQuery;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Raw, scoped observations for Supply performance calculations. Statistics stay in
 * the service so the percentile definition is deterministic across databases.
 */
@Repository
public class SupplyPerformanceQueryRepository {
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public SupplyPerformanceQueryRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<DurationObservation> asnConfirmationObservations(SupplyWorkspaceQuery query) {
        requirePerformanceQuery(query);
        MapSqlParameterSource parameters = parameters(query);
        StringBuilder sql = new StringBuilder("""
            select asn.id as source_id,
                   asn.sent_at as started_at,
                   asn.confirmed_at as completed_at
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
            where asn.tenant_id = :tenantId
              and asn.is_delete = false
              and (
                (asn.confirmed_at >= :fromStart and asn.confirmed_at < :toStart)
                or (
                  asn.confirmed_at is null
                  and asn.sent_at >= :fromStart and asn.sent_at < :toStart
                )
              )
            """);
        appendCompanyScope(sql, "asn", query, parameters);
        appendPoWarehouseScope(sql, "po", query, parameters);
        appendAsnFilters(sql, query, parameters);
        sql.append(" order by case when asn.confirmed_at is null then 1 else 0 end,")
            .append(" coalesce(asn.confirmed_at, asn.sent_at), asn.id");
        return jdbcTemplate.query(sql.toString(), parameters, this::durationObservation);
    }

    public List<DurationObservation> exceptionResolutionObservations(SupplyWorkspaceQuery query) {
        requirePerformanceQuery(query);
        MapSqlParameterSource parameters = parameters(query);
        StringBuilder sql = new StringBuilder("""
            select event.id as source_id,
                   event.event_date as started_at,
                   event.resolved_at as completed_at
            from landed_logistics_events event
            join import_deliveries import_delivery
              on import_delivery.id = event.import_delivery_id
             and import_delivery.tenant_id = event.tenant_id
             and import_delivery.company_id = event.company_id
             and import_delivery.is_delete = false
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
            where event.tenant_id = :tenantId
              and event.is_delete = false
              and event.exception_flag = true
              and (
                (event.resolved_at >= :fromStart and event.resolved_at < :toStart)
                or (
                  event.resolved_at is null
                  and event.event_date >= :fromStart and event.event_date < :toStart
                )
              )
            """);
        appendCompanyScope(sql, "event", query, parameters);
        appendImportWarehouseScope(sql, "import_delivery", query, parameters);
        appendImportFilters(sql, query, parameters);
        appendImportSearch(sql, query, parameters,
            "event.code", "event.name", "event.event_type");
        sql.append(" order by case when event.resolved_at is null then 1 else 0 end,")
            .append(" coalesce(event.resolved_at, event.event_date), event.id");
        return jdbcTemplate.query(sql.toString(), parameters, this::durationObservation);
    }

    public List<LandedCostObservation> landedCostUpliftObservations(SupplyWorkspaceQuery query) {
        requirePerformanceQuery(query);
        MapSqlParameterSource parameters = parameters(query);
        StringBuilder sql = new StringBuilder("""
            select import_delivery.id as source_id,
                   import_delivery.actual_arrival_date as cohort_date,
                   import_delivery.base_goods_amount,
                   import_delivery.landed_cost_amount,
                   upper(nullif(trim(import_delivery.currency_code), '')) as currency_code,
                   case
                     when import_delivery.currency_code is null
                       or length(trim(import_delivery.currency_code)) <> 3
                       or exists (
                         select 1
                         from landed_cost_components component
                         where component.tenant_id = import_delivery.tenant_id
                           and component.company_id = import_delivery.company_id
                           and component.import_delivery_id = import_delivery.id
                           and component.is_delete = false
                           and component.included_in_inventory_cost = true
                           and (
                             component.currency_code is null
                             or length(trim(component.currency_code)) <> 3
                             or upper(trim(component.currency_code))
                               <> upper(trim(import_delivery.currency_code))
                           )
                       )
                       or exists (
                         select 1
                         from customs_declarations declaration
                         where declaration.tenant_id = import_delivery.tenant_id
                           and declaration.company_id = import_delivery.company_id
                           and declaration.import_delivery_id = import_delivery.id
                           and declaration.is_delete = false
                           and upper(declaration.status) = 'RELEASED'
                           and (
                             declaration.currency_code is null
                             or length(trim(declaration.currency_code)) <> 3
                             or upper(trim(declaration.currency_code))
                               <> upper(trim(import_delivery.currency_code))
                           )
                       )
                     then false else true
                   end as currency_consistent,
                   case
                     when exists (
                       select 1
                       from landed_cost_components component
                       where component.tenant_id = import_delivery.tenant_id
                         and component.company_id = import_delivery.company_id
                         and component.import_delivery_id = import_delivery.id
                         and component.is_delete = false
                         and component.included_in_inventory_cost = true
                         and (
                           component.status is null
                           or upper(component.status) <> 'POSTED'
                           or component.posted_at is null
                         )
                     )
                     then false else true
                   end as posted_cost_complete
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
            where import_delivery.tenant_id = :tenantId
              and import_delivery.is_delete = false
              and upper(import_delivery.status) in ('DELIVERED', 'CLOSED')
              and import_delivery.actual_arrival_date >= :fromDate
              and import_delivery.actual_arrival_date < :toDate
            """);
        appendCompanyScope(sql, "import_delivery", query, parameters);
        appendImportWarehouseScope(sql, "import_delivery", query, parameters);
        appendImportFilters(sql, query, parameters);
        appendImportSearch(sql, query, parameters);
        sql.append(" order by import_delivery.actual_arrival_date, import_delivery.id");
        return jdbcTemplate.query(sql.toString(), parameters, this::landedCostObservation);
    }

    private void appendAsnFilters(
        StringBuilder sql,
        SupplyWorkspaceQuery query,
        MapSqlParameterSource parameters
    ) {
        if (query.supplierId() != null) {
            sql.append(" and asn.supplier_id = :supplierId");
            parameters.addValue("supplierId", query.supplierId());
        }
        if (query.purchaseOrderId() != null) {
            sql.append(" and asn.purchase_order_id = :purchaseOrderId");
            parameters.addValue("purchaseOrderId", query.purchaseOrderId());
        }
        if (query.contractId() != null) {
            sql.append(" and coalesce(asn.contract_id, po.contract_id) = :contractId");
            parameters.addValue("contractId", query.contractId());
        }
        if (query.transportMode() != null) {
            sql.append(" and upper(asn.transport_mode) = :transportMode");
            parameters.addValue("transportMode", query.transportMode());
        }
        if (query.currencyCode() != null) {
            sql.append(" and upper(nullif(trim(asn.currency_code), '')) = :currencyCode");
            parameters.addValue("currencyCode", query.currencyCode());
        }
        if (query.status() != null) {
            sql.append(" and upper(asn.status) = :sourceStatus");
            parameters.addValue("sourceStatus", query.status());
        }
        if (query.search() != null) {
            appendSearch(sql, parameters, query.search(),
                "asn.code", "asn.name", "asn.asn_number", "po.code", "po.name",
                "supplier.code", "supplier.name", "supplier.legal_name", "supplier.short_name");
        }
    }

    private void appendImportFilters(
        StringBuilder sql,
        SupplyWorkspaceQuery query,
        MapSqlParameterSource parameters
    ) {
        if (query.supplierId() != null) {
            sql.append(" and import_delivery.supplier_id = :supplierId");
            parameters.addValue("supplierId", query.supplierId());
        }
        if (query.purchaseOrderId() != null) {
            sql.append(" and import_delivery.purchase_order_id = :purchaseOrderId");
            parameters.addValue("purchaseOrderId", query.purchaseOrderId());
        }
        if (query.contractId() != null) {
            sql.append(" and po.contract_id = :contractId");
            parameters.addValue("contractId", query.contractId());
        }
        if (query.transportMode() != null) {
            sql.append(" and upper(import_delivery.transport_mode) = :transportMode");
            parameters.addValue("transportMode", query.transportMode());
        }
        if (query.currencyCode() != null) {
            sql.append(" and upper(nullif(trim(import_delivery.currency_code), '')) = :currencyCode");
            parameters.addValue("currencyCode", query.currencyCode());
        }
        if (query.status() != null) {
            sql.append(" and upper(import_delivery.status) = :sourceStatus");
            parameters.addValue("sourceStatus", query.status());
        }
    }

    private void appendImportSearch(
        StringBuilder sql,
        SupplyWorkspaceQuery query,
        MapSqlParameterSource parameters,
        String... extraColumns
    ) {
        if (query.search() == null) {
            return;
        }
        String[] base = {
            "import_delivery.code", "import_delivery.name", "import_delivery.shipment_number",
            "po.code", "po.name", "supplier.code", "supplier.name",
            "supplier.legal_name", "supplier.short_name"
        };
        String[] columns = new String[base.length + extraColumns.length];
        System.arraycopy(base, 0, columns, 0, base.length);
        System.arraycopy(extraColumns, 0, columns, base.length, extraColumns.length);
        appendSearch(sql, parameters, query.search(), columns);
    }

    private void appendSearch(
        StringBuilder sql,
        MapSqlParameterSource parameters,
        String search,
        String... columns
    ) {
        sql.append(" and (");
        for (int index = 0; index < columns.length; index++) {
            if (index > 0) {
                sql.append(" or ");
            }
            sql.append("lower(coalesce(").append(columns[index]).append(", '')) like :search");
        }
        sql.append(')');
        parameters.addValue("search", "%" + search.toLowerCase(Locale.ROOT) + "%");
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
        sql.append(" and exists (select 1 from purchase_order_items warehouse_line")
            .append(" where warehouse_line.purchase_order_id = ").append(poAlias).append(".id")
            .append(" and warehouse_line.tenant_id = ").append(poAlias).append(".tenant_id")
            .append(" and warehouse_line.company_id = ").append(poAlias).append(".company_id")
            .append(" and warehouse_line.is_delete = false")
            .append(" and warehouse_line.line_type = 'MATERIAL'");
        if (query.warehouseId() != null) {
            sql.append(" and warehouse_line.receipt_warehouse_id = :warehouseId");
            parameters.addValue("warehouseId", query.warehouseId());
        } else {
            sql.append(" and warehouse_line.receipt_warehouse_id in (:authorizedWarehouseIds)");
            parameters.addValue("authorizedWarehouseIds", query.authorizedWarehouseIds());
        }
        sql.append(')');
    }

    private void appendImportWarehouseScope(
        StringBuilder sql,
        String alias,
        SupplyWorkspaceQuery query,
        MapSqlParameterSource parameters
    ) {
        if (query.warehouseScoped() && query.authorizedWarehouseIds().isEmpty()) {
            sql.append(" and 1 = 0");
        } else if (query.warehouseId() != null) {
            sql.append(" and ").append(alias).append(".destination_warehouse_id = :warehouseId");
            parameters.addValue("warehouseId", query.warehouseId());
        } else if (query.warehouseScoped()) {
            sql.append(" and ").append(alias)
                .append(".destination_warehouse_id in (:authorizedWarehouseIds)");
            parameters.addValue("authorizedWarehouseIds", query.authorizedWarehouseIds());
        }
    }

    private MapSqlParameterSource parameters(SupplyWorkspaceQuery query) {
        return new MapSqlParameterSource()
            .addValue("tenantId", query.tenantId())
            .addValue("fromStart", query.from().atStartOfDay())
            .addValue("toStart", query.to().atStartOfDay())
            .addValue("fromDate", query.from())
            .addValue("toDate", query.to());
    }

    private DurationObservation durationObservation(ResultSet rs, int rowNumber) throws SQLException {
        return new DurationObservation(
            rs.getObject("source_id", UUID.class),
            rs.getObject("started_at", LocalDateTime.class),
            rs.getObject("completed_at", LocalDateTime.class));
    }

    private LandedCostObservation landedCostObservation(ResultSet rs, int rowNumber) throws SQLException {
        return new LandedCostObservation(
            rs.getObject("source_id", UUID.class),
            rs.getObject("cohort_date", LocalDate.class),
            rs.getBigDecimal("base_goods_amount"),
            rs.getBigDecimal("landed_cost_amount"),
            rs.getString("currency_code"),
            rs.getBoolean("currency_consistent"),
            rs.getBoolean("posted_cost_complete"));
    }

    private void requirePerformanceQuery(SupplyWorkspaceQuery query) {
        if (query == null || query.tenantId() == null || query.timeZone() == null
            || query.from() == null || query.to() == null || !query.to().isAfter(query.from())) {
            throw new IllegalArgumentException(
                "Normalized Supply performance query with timezone and [from,to) is required");
        }
    }

    public record DurationObservation(
        UUID sourceId,
        LocalDateTime startedAt,
        LocalDateTime completedAt
    ) {
    }

    public record LandedCostObservation(
        UUID sourceId,
        LocalDate cohortDate,
        BigDecimal baseGoodsAmount,
        BigDecimal landedCostAmount,
        String currencyCode,
        boolean currencyConsistent,
        boolean postedCostComplete
    ) {
    }
}
