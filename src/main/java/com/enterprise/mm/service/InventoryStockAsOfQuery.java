package com.enterprise.mm.service;

import com.enterprise.mm.error.BusinessException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reconstructs the accounting cut-off only when movement evidence reconciles to current stock. */
@Repository
@RequiredArgsConstructor
public class InventoryStockAsOfQuery {
    private final NamedParameterJdbcTemplate jdbc;

    public record HistoricalBalance(BigDecimal quantity, BigDecimal value) {}

    public boolean hasLateBackdatedMovement(String tenant, UUID company, Collection<UUID> warehouseIds,
        LocalDate asOf, LocalDateTime capturedAt) {
        if (asOf == null || capturedAt == null) return false;
        boolean limited = warehouseIds != null && !warehouseIds.isEmpty();
        var params = new MapSqlParameterSource("tenant", tenant).addValue("company", company)
            .addValue("asOf", asOf).addValue("capturedAt", capturedAt);
        if (limited) params.addValue("warehouses", warehouseIds);
        Long count = jdbc.queryForObject("""
            select count(*) from stock_movements m
            where m.tenant_id=:tenant and m.company_id=:company
              and m.posted_at > :capturedAt
              and coalesce(m.movement_date, cast(m.posted_at as date)) <= :asOf
            """ + (limited ? " and (m.warehouse_id in (:warehouses) or m.target_warehouse_id in (:warehouses))" : ""),
            params, Long.class);
        return count != null && count > 0;
    }

    public Map<UUID, HistoricalBalance> historical(String tenant, UUID company, Collection<UUID> warehouseIds, LocalDate asOf) {
        if (asOf == null || asOf.isAfter(LocalDate.now()))
            throw new BusinessException("Historical stock date is invalid", "INVENTORY_AS_OF_DATE_INVALID");
        boolean limited = warehouseIds != null && !warehouseIds.isEmpty();
        String balanceScope = limited ? " and b.warehouse_id in (:warehouses)" : "";
        String movementScope = limited ? " and (m.warehouse_id in (:warehouses) or m.target_warehouse_id in (:warehouses))" : "";
        String effectsScope = limited ? " and e.warehouse_id in (:warehouses)" : "";
        var params = new MapSqlParameterSource("tenant", tenant).addValue("company", company).addValue("asOf", asOf);
        if (limited) params.addValue("warehouses", warehouseIds);

        Long legacy = jdbc.queryForObject("""
            select count(*) from stock_movements m
            where m.tenant_id=:tenant and m.company_id=:company and m.posted_at is not null
              and not exists (select 1 from stock_movement_allocations a
                  where a.movement_id=m.id and a.tenant_id=m.tenant_id and a.company_id=m.company_id
                    and a.material_id=m.material_id)
            """ + movementScope, params, Long.class);
        Long other = jdbc.queryForObject("""
            select count(*) from warehouse_posting_effects e
            join warehouse_posting_batches pb on pb.id=e.batch_id
            where pb.tenant_id=:tenant and pb.company_id=:company and pb.status='COMMITTED'
              and e.effect_type='STOCK'
            """ + effectsScope, params, Long.class);
        Long missingBalance = jdbc.queryForObject("""
            select count(*) from stock_movement_allocations a
            join stock_movements m on m.id=a.movement_id and m.tenant_id=a.tenant_id
                and m.company_id=a.company_id and m.material_id=a.material_id and m.posted_at is not null
            where a.tenant_id=:tenant and a.company_id=:company and a.balance_id is null
              and a.on_hand_delta<>0
            """ + movementScope, params, Long.class);
        if (legacy != null && legacy > 0 || other != null && other > 0 || missingBalance != null && missingBalance > 0) incomplete();

        var result = new LinkedHashMap<UUID, HistoricalBalance>();
        jdbc.query("""
            select b.id, b.is_delete, b.quantity current_quantity, b.total_value current_value,
              coalesce(sum(case when m.id is not null then a.on_hand_delta else 0 end),0) all_quantity,
              coalesce(sum(case when m.id is not null then a.total_value_delta else 0 end),0) all_value,
              coalesce(sum(case when m.id is not null and coalesce(m.movement_date, cast(m.posted_at as date)) <= :asOf
                  then a.on_hand_delta else 0 end),0) as_of_quantity,
              coalesce(sum(case when m.id is not null and coalesce(m.movement_date, cast(m.posted_at as date)) <= :asOf
                  then a.total_value_delta else 0 end),0) as_of_value,
              coalesce(sum(case when a.id is not null and m.id is null then 1 else 0 end),0) invalid_movements,
              count(a.id) allocation_count
            from stock_balances b
            left join stock_movement_allocations a on a.balance_id=b.id and a.tenant_id=b.tenant_id
                and a.company_id=b.company_id and a.material_id=b.material_id
            left join stock_movements m on m.id=a.movement_id and m.tenant_id=a.tenant_id
                and m.company_id=a.company_id and m.material_id=a.material_id and m.posted_at is not null
            where b.tenant_id=:tenant and b.company_id=:company
            """ + balanceScope + """
            group by b.id, b.is_delete, b.quantity, b.total_value
            """, params, (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> {
            while (rs.next()) {
                BigDecimal quantity = rs.getBigDecimal("as_of_quantity");
                BigDecimal value = rs.getBigDecimal("as_of_value");
                boolean invalid = rs.getLong("invalid_movements") != 0
                    || rs.getBigDecimal("current_quantity").compareTo(rs.getBigDecimal("all_quantity")) != 0
                    || rs.getBigDecimal("current_value").compareTo(rs.getBigDecimal("all_value")) != 0
                    || rs.getBoolean("is_delete") && (rs.getLong("allocation_count") != 0 || quantity.signum() != 0);
                if (invalid || quantity.signum() < 0) incomplete();
                if (!rs.getBoolean("is_delete")) result.put(rs.getObject("id", UUID.class), new HistoricalBalance(quantity, value));
            }
            return null;
        });
        return result;
    }

    private void incomplete() {
        throw new BusinessException("Historical stock evidence is incomplete for this count scope", "HISTORICAL_EVIDENCE_INCOMPLETE");
    }
}
