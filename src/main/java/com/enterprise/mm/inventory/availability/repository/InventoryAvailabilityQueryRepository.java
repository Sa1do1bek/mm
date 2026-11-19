package com.enterprise.mm.inventory.availability.repository;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.inventory.availability.enums.DemandUrgencyBucket;
import com.enterprise.mm.inventory.availability.enums.InventoryAvailabilityQueueKey;
import com.enterprise.mm.inventory.availability.enums.WarehouseScopeMode;
import com.enterprise.mm.inventory.availability.payload.InventoryAvailabilityDtos.InventoryAvailabilityFilter;
import com.enterprise.mm.inventory.availability.payload.InventoryAvailabilityDtos.InventoryAvailabilityOverview;
import com.enterprise.mm.inventory.availability.payload.InventoryAvailabilityDtos.InventoryAvailabilityRow;
import com.enterprise.mm.security.AccessScope;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class InventoryAvailabilityQueryRepository {
    // A stock balance not refreshed within this window is treated as "stale" for
    // filtering (staleBalance filter) and for the overview KPI (staleDataCount).
    private static final long STALE_BALANCE_HOURS = 24;

    private static final String BASE_SQL = """
            with scoped_materials as (
                select balance.material_id
                from stock_balances balance
                where balance.tenant_id = :tenantId
                  and (:companyScoped = false or balance.company_id = :companyId)
                  and balance.is_delete = false
                  and (cast(:warehouseId as uuid) is null
                    or balance.warehouse_id = cast(:warehouseId as uuid))
                  and (:hasWarehouseIds = false or balance.warehouse_id in (:warehouseIds))
                union
                select line.material_id
                from purchase_request_items line
                join purchase_requests request on request.id = line.purchase_request_id
                where line.tenant_id = :tenantId
                  and (:companyScoped = false or line.company_id = :companyId)
                  and request.tenant_id = :tenantId
                  and (:companyScoped = false or request.company_id = :companyId)
                  and line.is_delete = false and request.is_delete = false
                  and line.line_type = 'MATERIAL' and line.material_id is not null
                  and request.status in ('APPROVAL_PENDING','APPROVED','RFQ_CREATED',
                    'PARTIALLY_SENT_TO_TENDER','FULLY_SENT_TO_TENDER','PO_CREATED')
                and(cast(:warehouseId as uuid) is null
                    or request.warehouse_id = cast(:warehouseId as uuid)
                    )
                  and (:hasWarehouseIds = false or request.warehouse_id in (:warehouseIds))
                union
                select reservation.material_id
                from material_reservations reservation
                where reservation.tenant_id = :tenantId
                  and (:companyScoped = false or reservation.company_id = :companyId)
                  and reservation.is_delete = false
                  and reservation.status in ('ACTIVE','PARTIALLY_RELEASED')
                  and greatest(reservation.reserved_quantity - reservation.released_quantity, 0) > 0
                  and (cast(:warehouseId as uuid) is null
                    or reservation.warehouse_id = cast(:warehouseId as uuid))
                  and (:hasWarehouseIds = false or reservation.warehouse_id in (:warehouseIds))
                union
                select line.material_id
                from purchase_order_items line
                join purchase_orders purchase_order on purchase_order.id = line.purchase_order_id
                where line.tenant_id = :tenantId
                  and (:companyScoped = false or line.company_id = :companyId)
                  and purchase_order.tenant_id = :tenantId
                  and (:companyScoped = false or purchase_order.company_id = :companyId)
                  and line.is_delete = false and purchase_order.is_delete = false
                  and line.line_type = 'MATERIAL' and line.material_id is not null
                  and purchase_order.status in ('APPROVED','SENT','PARTIALLY_RECEIVED','PARTIALLY_COMPLETED')
                  and greatest(line.quantity - line.received_quantity, 0) > 0
                  and (cast(:warehouseId as uuid) is null
                    or line.receipt_warehouse_id = cast(:warehouseId as uuid))
                  and (:hasWarehouseIds = false or line.receipt_warehouse_id in (:warehouseIds))
            ),
            stock as (
                select balance.material_id,
                       coalesce(sum(balance.quantity), 0) as on_hand,
                       coalesce(sum(balance.available_quantity), 0) as available,
                       coalesce(sum(balance.reserved_quantity), 0) as reserved,
                       coalesce(sum(balance.quarantine_quantity), 0) as quarantine,
                       coalesce(sum(balance.total_value), 0) as total_value,
                       count(distinct balance.warehouse_id) as warehouse_count,
                       count(distinct balance.storage_location_id) as location_count,
                       count(distinct balance.stock_batch_id) as batch_count,
                       max(balance.balance_at) as balance_at
                from stock_balances balance
                where balance.tenant_id = :tenantId
                  and (:companyScoped = false or balance.company_id = :companyId)
                  and balance.is_delete = false
                  and (cast(:warehouseId as uuid) is null
                    or balance.warehouse_id = cast(:warehouseId as uuid))
                  and (:hasWarehouseIds = false or balance.warehouse_id in (:warehouseIds))
                group by balance.material_id
            ),
            request_demand as (
                select line.material_id, coalesce(sum(line.quantity), 0) as quantity,
                       count(distinct request.id) as document_count,
                       min(request.required_date) as nearest_date
                from purchase_request_items line
                join purchase_requests request on request.id = line.purchase_request_id
                where line.tenant_id = :tenantId
                  and (:companyScoped = false or line.company_id = :companyId)
                  and request.tenant_id = :tenantId
                  and (:companyScoped = false or request.company_id = :companyId)
                  and line.is_delete = false and request.is_delete = false
                  and line.line_type = 'MATERIAL' and line.material_id is not null
                  and request.status in ('APPROVAL_PENDING','APPROVED','RFQ_CREATED',
                    'PARTIALLY_SENT_TO_TENDER','FULLY_SENT_TO_TENDER','PO_CREATED')
                and (cast(:warehouseId as uuid) is null
                        or request.warehouse_id = cast(:warehouseId as uuid)
                    )
                  and (:hasWarehouseIds = false or request.warehouse_id in (:warehouseIds))
                group by line.material_id
            ),
                unscoped_request_demand as (
                            select line.material_id, coalesce(sum(line.quantity), 0) as quantity
                            from purchase_request_items line
                            join purchase_requests request on request.id = line.purchase_request_id
                            where line.tenant_id = :tenantId
                              and (:companyScoped = false or line.company_id = :companyId)
                              and request.tenant_id = :tenantId
                              and (:companyScoped = false or request.company_id = :companyId)
                              and line.is_delete = false and request.is_delete = false
                              and line.line_type = 'MATERIAL' and line.material_id is not null
                              and request.status in ('APPROVAL_PENDING','APPROVED','RFQ_CREATED',
                                'PARTIALLY_SENT_TO_TENDER','FULLY_SENT_TO_TENDER','PO_CREATED')
                              and request.warehouse_id is null
                            group by line.material_id
                        ),
            order_supply as (
                select line.material_id,
                       coalesce(sum(greatest(line.quantity - line.received_quantity, 0)), 0) as quantity,
                       count(distinct purchase_order.id) as document_count,
                       min(purchase_order.expected_delivery_date) as nearest_date
                from purchase_order_items line
                join purchase_orders purchase_order on purchase_order.id = line.purchase_order_id
                where line.tenant_id = :tenantId
                  and (:companyScoped = false or line.company_id = :companyId)
                  and purchase_order.tenant_id = :tenantId
                  and (:companyScoped = false or purchase_order.company_id = :companyId)
                  and line.is_delete = false and purchase_order.is_delete = false
                  and line.line_type = 'MATERIAL' and line.material_id is not null
                  and purchase_order.status in ('APPROVED','SENT','PARTIALLY_RECEIVED','PARTIALLY_COMPLETED')
                  and greatest(line.quantity - line.received_quantity, 0) > 0
                  and (cast(:warehouseId as uuid) is null
                    or line.receipt_warehouse_id = cast(:warehouseId as uuid))
                  and (:hasWarehouseIds = false or line.receipt_warehouse_id in (:warehouseIds))
                group by line.material_id
            ),
                non_moving as (
                    select nm.material_id, min(nm.detected_at) as earliest_detected_at
                    from non_moving_stocks nm
                    where nm.tenant_id = :tenantId
                      and (:companyScoped = false or nm.company_id = :companyId)
                      and nm.is_delete = false
                      and nm.status not in ('CLOSED', 'CANCELLED')
                      and (cast(:warehouseId as uuid) is null
                        or nm.warehouse_id = cast(:warehouseId as uuid))
                      and (:hasWarehouseIds = false or nm.warehouse_id in (:warehouseIds))
                    group by nm.material_id
                )
            select material.id as material_id,
                   material.code as material_code,
                   material.name as material_name,
                   material.status as material_status,
                   material.material_group_id,
                   material_group.name as material_group_name,
                   unit.code as unit_code,
                   unit.measurement_name_short as unit_short_name,
                   unit.measurement_name_short_ru as unit_short_name_ru,
                   unit.measurement_name_short_uz as unit_short_name_uz,
                   unit.measurement_name_long as unit_name,
                   unit.measurement_name_long_ru as unit_name_ru,
                   unit.measurement_name_long_uz as unit_name_uz,
                   replacement.id as replacement_material_id,
                   replacement.code as replacement_material_code,
                   replacement.name as replacement_material_name,
                   coalesce(stock.on_hand, 0) as on_hand,
                   coalesce(stock.available, 0) as available,
                   coalesce(stock.reserved, 0) as reserved,
                   coalesce(stock.quarantine, 0) as quarantine,
                   coalesce(request_demand.quantity, 0) as open_demand,
                   coalesce(request_demand.document_count, 0) as demand_document_count,
                   coalesce(order_supply.quantity, 0) as open_supply,
                   coalesce(order_supply.document_count, 0) as supply_document_count,
                   request_demand.nearest_date as nearest_demand_date,
                                  order_supply.nearest_date as nearest_supply_date,
                                  case
                                    when request_demand.nearest_date is null then null
                                    when request_demand.nearest_date <= current_date then 'NOW'
                                    when request_demand.nearest_date <= current_date + 7 then 'WITHIN_7_DAYS'
                                    when request_demand.nearest_date <= current_date + 30 then 'WITHIN_30_DAYS'
                                    else 'LATER'
                                  end as demand_urgency_bucket,
                                  case
                                    when order_supply.nearest_date is null then null
                                    when order_supply.nearest_date <= current_date then 'NOW'
                                    when order_supply.nearest_date <= current_date + 7 then 'WITHIN_7_DAYS'
                                    when order_supply.nearest_date <= current_date + 30 then 'WITHIN_30_DAYS'
                                    else 'LATER'
                                end as supply_urgency_bucket,
                   coalesce(stock.available, 0) + coalesce(order_supply.quantity, 0)
                     - coalesce(request_demand.quantity, 0) as projected_available,
                   greatest(coalesce(request_demand.quantity, 0)
                     - coalesce(stock.available, 0) - coalesce(order_supply.quantity, 0), 0) as shortage,
                   coalesce(material.min_stock, 0) as min_stock,
                   coalesce(material.safety_stock, 0) as safety_stock,
                   coalesce(material.reorder_point, 0) as reorder_point,
                   material.max_stock,
                   coalesce(stock.total_value, 0) as total_value,
                   coalesce(stock.warehouse_count, 0) as warehouse_count,
                   coalesce(stock.location_count, 0) as location_count,
                   coalesce(stock.batch_count, 0) as batch_count,
                   stock.balance_at,
                   case
                     when coalesce(stock.available, 0) + coalesce(order_supply.quantity, 0)
                       - coalesce(request_demand.quantity, 0) < 0 then 'SHORTAGE'
                     when coalesce(stock.quarantine, 0) > 0 then 'QUARANTINE_BLOCKED'
                     when coalesce(stock.available, 0) + coalesce(order_supply.quantity, 0)
                       - coalesce(request_demand.quantity, 0)
                       < coalesce(material.safety_stock, 0) then 'BELOW_SAFETY'
                       when material.reorder_point is not null and material.reorder_point > 0
                         and coalesce(stock.available, 0) + coalesce(order_supply.quantity, 0)
                           - coalesce(request_demand.quantity, 0) <= material.reorder_point then 'REORDER_NEEDED'
                     when material.max_stock is not null and material.max_stock > 0
                       and coalesce(stock.on_hand, 0) > material.max_stock then 'EXCESS'
                     else 'HEALTHY'
                   end as signal,
                array[
                                       -- Keep these literals identical to the `signal` values above: the
                                       -- frontend must be able to treat signal and signalReasons as one
                                       -- shared vocabulary instead of maintaining two separate enums.
                                       case
                                           when coalesce(stock.available, 0) + coalesce(order_supply.quantity, 0)
                                               - coalesce(request_demand.quantity, 0) < 0
                                           then 'SHORTAGE'
                                       end,
                                       case
                                           when coalesce(stock.quarantine, 0) > 0
                                           then 'QUARANTINE_BLOCKED'
                                       end,
                                       case
                                           when coalesce(stock.available, 0) + coalesce(order_supply.quantity, 0)
                                               - coalesce(request_demand.quantity, 0)
                                               < coalesce(material.safety_stock, 0)
                                           then 'BELOW_SAFETY'
                                       end,
                                       case
                                           when material.max_stock is not null
                                               and material.max_stock > 0
                                               and coalesce(stock.on_hand, 0) > material.max_stock
                                           then 'EXCESS'
                                       end,
                                       -- New: reorder point reached, independent of the safety-stock check
                                       -- above (reorder_point and safety_stock are different thresholds and
                                       -- a material can cross one without crossing the other).
                                       case
                                           when material.reorder_point is not null and material.reorder_point > 0
                                               and coalesce(stock.available, 0) + coalesce(order_supply.quantity, 0)
                                                   - coalesce(request_demand.quantity, 0) <= material.reorder_point
                                           then 'REORDER_NEEDED'
                                       end,
                                       -- New: surfaces an existing, unresolved non-moving-stock case for this
                                       -- material (scoped to the same warehouse filter as everything else).
                                       case
                                           when non_moving.material_id is not null
                                           then 'NON_MOVING'
                                       end
                                   ] as signal_reasons,
                case
                  when cast(:warehouseId as uuid) is null and :hasWarehouseIds = false then 'COMPANY_LEVEL_DEMAND'
                  when coalesce(unscoped_request_demand.quantity, 0) > 0 then 'MIXED'
                  else 'WAREHOUSE_SPECIFIC'
                end as warehouse_scope_mode
            from scoped_materials scoped
            join materials material on material.id = scoped.material_id
              and material.tenant_id = :tenantId
              and (:companyScoped = false or material.company_id = :companyId)
              and material.is_delete = false
            left join material_groups material_group on material_group.id = material.material_group_id
              and material_group.tenant_id = :tenantId
              and (:companyScoped = false or material_group.company_id = :companyId)
              and material_group.is_delete = false
            left join unit_of_measures unit on unit.id = material.unit_of_measure_id and unit.is_delete = false
            left join materials replacement on replacement.id = material.replacement_material_id
              and replacement.tenant_id = :tenantId
              and (:companyScoped = false or replacement.company_id = :companyId)
              and replacement.is_delete = false
            left join stock on stock.material_id = material.id
            left join request_demand on request_demand.material_id = material.id
            left join unscoped_request_demand on unscoped_request_demand.material_id = material.id
            left join order_supply on order_supply.material_id = material.id
            left join non_moving on non_moving.material_id = material.id
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public Page<InventoryAvailabilityRow> search(InventoryAvailabilityFilter requested, Pageable pageable) {
        InventoryAvailabilityFilter filter = normalize(requested);
        MapSqlParameterSource parameters = parameters(filter);
        String where = where(filter, parameters);
        parameters.addValue("limit", pageable.getPageSize()).addValue("offset", pageable.getOffset());
        String source = "select * from (" + BASE_SQL + ") availability" + where;
        String pageSql = source + " order by shortage desc, material_code asc, material_id asc limit :limit offset :offset";
        String countSql = "select count(*) from (" + BASE_SQL + ") availability" + where;
        List<InventoryAvailabilityRow> rows = jdbcTemplate.query(pageSql, parameters, this::mapRow);
        Long total = jdbcTemplate.queryForObject(countSql, parameters, Long.class);
        return new PageImpl<>(rows, pageable, total == null ? 0 : total);
    }

    public long count(InventoryAvailabilityFilter requested) {
        InventoryAvailabilityFilter filter = normalize(requested);
        MapSqlParameterSource parameters = parameters(filter);
        Long total = jdbcTemplate.queryForObject(
                "select count(*) from (" + BASE_SQL + ") availability" + where(filter, parameters),
                parameters,
                Long.class);
        return total == null ? 0 : total;
    }

    public InventoryAvailabilityOverview overview(InventoryAvailabilityFilter requested) {
        InventoryAvailabilityFilter filter = normalize(requested);
        InventoryAvailabilityFilter allSignals = new InventoryAvailabilityFilter(
                null, filter.search(), filter.warehouseId(), filter.materialGroupId(),
                filter.replacementMissing(), filter.staleBalance(), filter.nonMoving(),
                filter.negativeProjected(), filter.urgency(),
                filter.materialCode(), filter.materialName(), filter.materialId(),
                filter.warehouseIds(), filter.asOfDate(), filter.dateFrom(), filter.dateTo());
        MapSqlParameterSource parameters = parameters(allSignals);
        String sql = "select"
                + " count(*)                                                                                     as material_count,"
                + " coalesce(sum(case when signal = 'SHORTAGE'           then 1 else 0 end), 0)                 as shortage_count,"
                + " coalesce(sum(case when signal = 'BELOW_SAFETY'       then 1 else 0 end), 0)                 as below_safety_count,"
                + " coalesce(sum(case when signal = 'QUARANTINE_BLOCKED' then 1 else 0 end), 0)                 as quarantine_count,"
                + " coalesce(sum(case when signal = 'REORDER_NEEDED'     then 1 else 0 end), 0)                 as reorder_needed_count,"
                + " coalesce(sum(case when signal = 'EXCESS'             then 1 else 0 end), 0)                 as excess_count,"
                + " coalesce(sum(case when signal = 'HEALTHY'            then 1 else 0 end), 0)                 as healthy_count,"
                + " coalesce(sum(case when balance_at is null or balance_at < :staleBefore then 1 else 0 end), 0) as stale_data_count,"
                + " coalesce(sum(case when signal = 'SHORTAGE'"
                + "     then shortage else 0 end), 0)                                                            as shortage_volume,"
                + " coalesce(sum(case when signal = 'QUARANTINE_BLOCKED'"
                + "     then quarantine else 0 end), 0)                                                          as quarantine_volume,"
                + " coalesce(sum(case when signal = 'BELOW_SAFETY'"
                + "     then greatest(safety_stock - projected_available, 0) else 0 end), 0)                     as below_safety_deficit,"
                + " coalesce(sum(case when signal = 'REORDER_NEEDED'"
                + "     then greatest(reorder_point - projected_available, 0) else 0 end), 0)                    as reorder_needed_deficit,"
                + " coalesce(sum(case when signal = 'EXCESS'"
                + "     then greatest(on_hand - max_stock, 0) else 0 end), 0)                                    as excess_surplus,"
                + " coalesce(sum(case when signal = 'HEALTHY'"
                + "     then total_value else 0 end), 0)                                                         as healthy_total_value,"
                + " coalesce(sum(on_hand),       0) as on_hand,"
                + " coalesce(sum(available),     0) as available,"
                + " coalesce(sum(reserved),      0) as reserved,"
                + " coalesce(sum(quarantine),    0) as quarantine,"
                + " coalesce(sum(shortage),      0) as shortage,"
                + " coalesce(sum(total_value),   0) as total_value"
                + " from (" + BASE_SQL + ") availability" + where(allSignals, parameters);

        return jdbcTemplate.queryForObject(sql, parameters, (rs, rowNum) -> new InventoryAvailabilityOverview(
                // counts
                rs.getLong("material_count"),
                rs.getLong("shortage_count"),
                rs.getLong("below_safety_count"),
                rs.getLong("quarantine_count"),
                rs.getLong("reorder_needed_count"),
                rs.getLong("excess_count"),
                rs.getLong("healthy_count"),
                rs.getLong("stale_data_count"),
                // per-signal values
                decimal(rs, "shortage_volume"),
                decimal(rs, "quarantine_volume"),
                decimal(rs, "below_safety_deficit"),
                decimal(rs, "reorder_needed_deficit"),
                decimal(rs, "excess_surplus"),
                decimal(rs, "healthy_total_value"),
                // global totals
                decimal(rs, "on_hand"),
                decimal(rs, "available"),
                decimal(rs, "reserved"),
                decimal(rs, "quarantine"),
                decimal(rs, "shortage"),
                decimal(rs, "total_value"),
                Instant.now()));
    }

    private InventoryAvailabilityFilter normalize(InventoryAvailabilityFilter filter) {
        return filter == null ? new InventoryAvailabilityFilter(null, null, null, null, null, null, null, null, null) : filter.normalized();
    }

    private MapSqlParameterSource parameters(InventoryAvailabilityFilter filter) {
        boolean hasWarehouseIds = filter.warehouseIds() != null && !filter.warehouseIds().isEmpty();
        return new MapSqlParameterSource()
                .addValue("tenantId", TenantContext.currentTenant())
                .addValue("companyId", CompanyContext.currentCompany())
                .addValue("companyScoped", AccessScope.companyScoped(true))
                .addValue("warehouseId", filter.warehouseId(), Types.OTHER, "uuid")
                .addValue("hasWarehouseIds", hasWarehouseIds)
                .addValue("warehouseIds", hasWarehouseIds ? filter.warehouseIds() : List.of(UUID.randomUUID()))
                .addValue("staleBefore", LocalDateTime.now().minusHours(STALE_BALANCE_HOURS));

    }

    private String where(InventoryAvailabilityFilter filter, MapSqlParameterSource parameters) {
        StringBuilder where = new StringBuilder(" where 1 = 1");
        if (filter.search() != null) {
            where.append(" and (lower(material_code) like :search or lower(material_name) like :search)");
            parameters.addValue("search", "%" + filter.search().toLowerCase(java.util.Locale.ROOT) + "%");
        }
        if (filter.materialCode() != null) {
            where.append(" and lower(material_code) like :materialCode");
            parameters.addValue("materialCode", "%" + filter.materialCode().toLowerCase(java.util.Locale.ROOT) + "%");
        }
        if (filter.materialName() != null) {
            where.append(" and lower(material_name) like :materialName");
            parameters.addValue("materialName", "%" + filter.materialName().toLowerCase(java.util.Locale.ROOT) + "%");
        }
        if (filter.materialId() != null) {
            where.append(" and material_id = cast(:materialId as uuid)");
            parameters.addValue("materialId", filter.materialId(), Types.OTHER, "uuid");
        }
        if (filter.asOfDate() != null) {
            where.append(" and (balance_at is null or balance_at <= :asOfDateTime)");
            parameters.addValue("asOfDateTime", filter.asOfDate().atTime(java.time.LocalTime.MAX));
        }
        if (filter.dateFrom() != null) {
            where.append(" and balance_at >= :dateFrom");
            parameters.addValue("dateFrom", filter.dateFrom().atStartOfDay());
        }
        if (filter.dateTo() != null) {
            where.append(" and balance_at <= :dateTo");
            parameters.addValue("dateTo", filter.dateTo().atTime(java.time.LocalTime.MAX));
        }
        if (filter.materialGroupId() != null) {
            where.append(" and material_group_id = :materialGroupId");
            parameters.addValue("materialGroupId", filter.materialGroupId());
        }
        if (filter.queue() != null) {
            where.append(" and signal = :queueSignal");
            parameters.addValue("queueSignal", filter.queue().name());
        }
        if (Boolean.TRUE.equals(filter.replacementMissing())) {
            where.append(" and replacement_material_id is null");
        }
        if (Boolean.TRUE.equals(filter.staleBalance())) {
            where.append(" and (balance_at is null or balance_at < :staleBefore)");
        }
        if (Boolean.TRUE.equals(filter.nonMoving())) {
            where.append(" and 'NON_MOVING' = any(signal_reasons)");
        }
        if (Boolean.TRUE.equals(filter.negativeProjected())) {
            where.append(" and projected_available < 0");
        }
        if (filter.urgency() != null) {
            where.append(" and demand_urgency_bucket = :urgency");
            parameters.addValue("urgency", filter.urgency().name());
        }
        return where.toString();
    }

    private InventoryAvailabilityRow mapRow(ResultSet rs, int rowNumber) throws SQLException {
        return new InventoryAvailabilityRow(
                uuid(rs, "material_id"), rs.getString("material_code"), rs.getString("material_name"),
                rs.getString("material_status"), uuid(rs, "material_group_id"), rs.getString("material_group_name"),
                rs.getString("unit_code"), rs.getString("unit_short_name"),
                rs.getString("unit_short_name_ru"), rs.getString("unit_short_name_uz"),
                rs.getString("unit_name"), rs.getString("unit_name_ru"), rs.getString("unit_name_uz"),
                uuid(rs, "replacement_material_id"),
                rs.getString("replacement_material_code"), rs.getString("replacement_material_name"),
                decimal(rs, "on_hand"), decimal(rs, "available"), decimal(rs, "reserved"),
                decimal(rs, "quarantine"), decimal(rs, "open_demand"), rs.getLong("demand_document_count"), decimal(rs, "open_supply"), rs.getLong("supply_document_count"),
                rs.getObject("nearest_demand_date", LocalDate.class), rs.getObject("nearest_supply_date", LocalDate.class), demandUrgencyBucket(rs), supplyUrgencyBucket(rs), decimal(rs, "projected_available"), decimal(rs, "shortage"), decimal(rs, "min_stock"),
                decimal(rs, "safety_stock"), decimal(rs, "reorder_point"), decimal(rs, "max_stock"),
                decimal(rs, "total_value"), rs.getLong("warehouse_count"), rs.getLong("location_count"),
                rs.getLong("batch_count"), rs.getObject("balance_at", LocalDateTime.class),
                InventoryAvailabilityQueueKey.valueOf(rs.getString("signal")), mapSignalReasons(rs),
                WarehouseScopeMode.valueOf(rs.getString("warehouse_scope_mode")));
    }

    private UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    private BigDecimal decimal(ResultSet rs, String column) throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        return value == null ? BigDecimal.ZERO : value;
    }

    private List<String> mapSignalReasons(ResultSet rs) throws SQLException {
        var sqlArray = rs.getArray("signal_reasons");

        if (sqlArray == null) {
            return List.of();
        }

        Object[] reasons = (Object[]) sqlArray.getArray();
        return reasons == null ? List.of() : java.util.Arrays.stream(reasons)
            .filter(java.util.Objects::nonNull)
            .map(Object::toString)
            .toList();
    }

    private DemandUrgencyBucket demandUrgencyBucket(ResultSet rs) throws SQLException {
        String value = rs.getString("demand_urgency_bucket");
        return value == null ? null : DemandUrgencyBucket.valueOf(value);
    }

    private DemandUrgencyBucket supplyUrgencyBucket(ResultSet rs) throws SQLException {
        String value = rs.getString("supply_urgency_bucket");
        return value == null ? null : DemandUrgencyBucket.valueOf(value);
    }

}
