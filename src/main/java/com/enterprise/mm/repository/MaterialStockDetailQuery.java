package com.enterprise.mm.repository;

import com.enterprise.mm.payload.MaterialStockDetailDtos.Filter;
import com.enterprise.mm.entity.StockMovementAllocation;
import com.enterprise.mm.entity.StockMovementAllocationCostLayer;
import com.enterprise.mm.service.MaterialCommandViewScopeGuard.MaterialScope;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Tenant/company predicates apply to every join, including ancestry outside the selected warehouse. */
@Repository
@RequiredArgsConstructor
public class MaterialStockDetailQuery {
    private final NamedParameterJdbcTemplate jdbc;

    private static final String BALANCE_HISTORY = """
        with evidence as (
            select a.balance_id,
                sum(a.on_hand_delta) all_quantity, sum(a.available_delta) all_available,
                sum(a.reserved_delta) all_reserved, sum(a.quarantine_delta) all_quarantine,
                sum(a.total_value_delta) all_value,
                sum(case when coalesce(m.movement_date, cast(m.posted_at as date)) <= :asOf
                    then a.on_hand_delta else 0 end) quantity,
                sum(case when coalesce(m.movement_date, cast(m.posted_at as date)) <= :asOf
                    then a.available_delta else 0 end) available,
                sum(case when coalesce(m.movement_date, cast(m.posted_at as date)) <= :asOf
                    then a.reserved_delta else 0 end) reserved,
                sum(case when coalesce(m.movement_date, cast(m.posted_at as date)) <= :asOf
                    then a.quarantine_delta else 0 end) quarantine,
                sum(case when coalesce(m.movement_date, cast(m.posted_at as date)) <= :asOf
                    then a.total_value_delta else 0 end) book_value
            from stock_movement_allocations a
            join stock_movements m on m.id=a.movement_id and m.tenant_id=a.tenant_id
                and m.company_id=a.company_id and m.material_id=a.material_id and m.posted_at is not null
            where a.tenant_id=:tenant and a.company_id=:company and a.material_id=:material
            group by a.balance_id
        )
        """;

    private static final String LAYER_HISTORY = """
        with evidence as (
            select c.cost_layer_id, sum(c.quantity_delta) all_quantity, sum(c.value_delta) all_value,
                sum(case when coalesce(m.movement_date, cast(m.posted_at as date)) <= :asOf
                    then c.quantity_delta else 0 end) quantity,
                sum(case when coalesce(m.movement_date, cast(m.posted_at as date)) <= :asOf
                    then c.value_delta else 0 end) book_value
            from stock_movement_allocation_cost_layers c
            join stock_movement_allocations a on a.id=c.allocation_id
                and a.tenant_id=c.tenant_id and a.company_id=c.company_id
            join stock_movements m on m.id=a.movement_id and m.tenant_id=a.tenant_id
                and m.company_id=a.company_id and m.material_id=a.material_id and m.posted_at is not null
            where c.tenant_id=:tenant and c.company_id=:company and a.material_id=:material
            group by c.cost_layer_id
        )
        """;

    /** Net-zero unrepresented operations can pass a totals comparison; reject those histories too. */
    public boolean hasUnallocatedHistory(MaterialScope scope, Filter filter) {
        String movementWarehouse = filter.warehouseId() == null ? ""
            : " and (m.warehouse_id=:warehouse or m.target_warehouse_id=:warehouse)";
        String movementBatch = filter.stockBatchId() == null ? ""
            : " and (m.stock_batch_id=:batch or m.target_stock_batch_id=:batch)";
        Long legacy = jdbc.queryForObject("""
            select count(*) from stock_movements m where m.tenant_id=:tenant and m.company_id=:company
                and m.material_id=:material and m.posted_at is not null
                and not exists (select 1 from stock_movement_allocations a where a.movement_id=m.id
                    and a.tenant_id=m.tenant_id and a.company_id=m.company_id and a.material_id=m.material_id)
            """ + movementWarehouse + movementBatch, params(scope, filter), Long.class);
        if (legacy != null && legacy > 0) return true;
        Long other = jdbc.queryForObject("""
            select count(*) from warehouse_posting_effects e
            join warehouse_posting_batches pb on pb.id=e.batch_id
            where pb.tenant_id=:tenant and pb.company_id=:company and pb.status='COMMITTED'
                and e.material_id=:material and e.effect_type='STOCK'
            """ + (filter.warehouseId() == null ? "" : " and e.warehouse_id=:warehouse")
            + (filter.stockBatchId() == null ? "" : " and e.stock_batch_id=:batch"), params(scope, filter), Long.class);
        return other != null && other > 0;
    }

    public BalanceTotals balances(MaterialScope scope, Filter filter, boolean historical) {
        var params = params(scope, filter);
        String quantity = historical ? "coalesce(e.quantity,0)" : "b.quantity";
        String available = historical ? "coalesce(e.available,0)" : "b.available_quantity";
        String reserved = historical ? "coalesce(e.reserved,0)" : "b.reserved_quantity";
        String quarantine = historical ? "coalesce(e.quarantine,0)" : "b.quarantine_quantity";
        String value = historical ? "coalesce(e.book_value,0)" : "b.total_value";
        String invalid = historical ? """
            sum(case when b.is_delete=true or e.balance_id is null
                or b.quantity<>e.all_quantity or b.available_quantity<>e.all_available
                or b.reserved_quantity<>e.all_reserved or b.quarantine_quantity<>e.all_quarantine
                or b.total_value<>e.all_value then 1 else 0 end)
            """ : "0";
        String sql = (historical ? BALANCE_HISTORY : "") + "select coalesce(sum(" + quantity + "),0) quantity, "
            + "coalesce(sum(" + available + "),0) available, coalesce(sum(" + reserved + "),0) reserved, "
            + "coalesce(sum(" + quarantine + "),0) quarantine, coalesce(sum(" + value + "),0) book_value, "
            + "coalesce(" + invalid + ",0) invalid from stock_balances b "
            + (historical ? "left join evidence e on e.balance_id=b.id " : "")
            + where("b", filter, historical);
        return jdbc.queryForObject(sql, params, (rs, n) -> new BalanceTotals(rs.getBigDecimal("quantity"),
            rs.getBigDecimal("available"), rs.getBigDecimal("reserved"), rs.getBigDecimal("quarantine"),
            rs.getBigDecimal("book_value"), rs.getLong("invalid")));
    }

    public LayerTotals layerTotals(MaterialScope scope, Filter filter, boolean historical) {
        String quantity = historical ? "coalesce(e.quantity,0)" : "l.remaining_quantity";
        String value = historical ? "coalesce(e.book_value,0)" : "l.total_cost";
        String invalid = historical ? """
            sum(case when l.is_delete=true or e.cost_layer_id is null
                or l.remaining_quantity<>e.all_quantity or l.total_cost<>e.all_value then 1 else 0 end)
            """ : "0";
        String sql = (historical ? LAYER_HISTORY : "") + "select coalesce(sum(" + quantity + "),0) quantity, "
            + "coalesce(sum(" + value + "),0) book_value, coalesce(" + invalid + ",0) invalid, "
            + "min(l.currency_code) min_currency, max(l.currency_code) max_currency "
            + "from inventory_cost_layers l " + (historical ? "left join evidence e on e.cost_layer_id=l.id " : "")
            + where("l", filter, historical);
        return jdbc.queryForObject(sql, params(scope, filter), (rs, n) -> new LayerTotals(
            rs.getBigDecimal("quantity"), rs.getBigDecimal("book_value"), rs.getLong("invalid"),
            rs.getString("min_currency"), rs.getString("max_currency")));
    }

    public Page<LayerRow> layers(MaterialScope scope, Filter filter, boolean historical, Pageable page) {
        String quantity = historical ? "coalesce(e.quantity,0)" : "l.remaining_quantity";
        String value = historical ? "coalesce(e.book_value,0)" : "l.total_cost";
        var params = params(scope, filter).addValue("limit", page.getPageSize()).addValue("offset", page.getOffset());
        String prefix = historical ? LAYER_HISTORY : "";
        String from = " from inventory_cost_layers l "
            + (historical ? "left join evidence e on e.cost_layer_id=l.id " : "")
            + "left join warehouses w on w.id=l.warehouse_id and w.tenant_id=l.tenant_id "
            + "and w.company_id=l.company_id and w.is_delete=false "
            + "left join stock_batches b on b.id=l.stock_batch_id and b.tenant_id=l.tenant_id "
            + "and b.company_id=l.company_id and b.material_id=l.material_id and b.is_delete=false "
            + where("l", filter, historical)
            + (historical ? " and exists (select 1 from stock_movements sm where sm.id=l.source_movement_id "
                + "and sm.tenant_id=l.tenant_id and sm.company_id=l.company_id and sm.material_id=l.material_id "
                + "and coalesce(sm.movement_date,cast(sm.posted_at as date))<=:asOf)" : "")
            + (filter.positiveOnly() ? " and " + quantity + ">0" : "");
        Long count = jdbc.queryForObject(prefix + "select count(*)" + from, params, Long.class);
        List<LayerRow> rows = jdbc.query(prefix + "select l.id, l.warehouse_id, w.name warehouse_name, "
            + "l.stock_batch_id, b.batch_number, l.layer_type, l.received_quantity, " + quantity
            + " remaining_quantity, " + value + " remaining_value, l.currency_code " + from
            + " order by l.received_at desc, l.id desc limit :limit offset :offset", params,
            (rs, n) -> new LayerRow(uuid(rs, "id"), uuid(rs, "warehouse_id"), rs.getString("warehouse_name"),
                uuid(rs, "stock_batch_id"), rs.getString("batch_number"), rs.getString("layer_type"),
                rs.getBigDecimal("received_quantity"), rs.getBigDecimal("remaining_quantity"),
                rs.getBigDecimal("remaining_value"), rs.getString("currency_code")));
        return new PageImpl<>(rows, page, count == null ? 0 : count);
    }

    /** Batches an entire ancestry level; never joins mutable PO item prices to receipt history. */
    public List<OriginEvidence> origins(MaterialScope scope, Collection<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        var params = params(scope, new Filter(null, null, null, true)).addValue("ids", ids);
        return jdbc.query("""
            select l.id, l.parent_cost_layer_id, l.layer_type, l.source_warehouse_id,
                sw.code source_warehouse_code, sw.name source_warehouse_name,
                m.id movement_id, coalesce(m.movement_date, cast(m.posted_at as date)) received_date,
                m.unit_cost receipt_unit_cost, l.currency_code,
                m.source_unit_price_snapshot, m.source_currency_code_snapshot, m.source_exchange_rate_snapshot,
                case when gr.receipt_id is not null then 'GoodsReceipt' else m.reference_type end reference_type,
                coalesce(gr.receipt_id,m.reference_id) reference_id, coalesce(gr.receipt_code,po.code) document_code,
                s.id supplier_id, s.code supplier_code, s.name supplier_name,
                m.posted_at, gr.receipt_id verified_receipt_id
            from inventory_cost_layers l
            left join stock_movements m on m.id=l.source_movement_id and m.tenant_id=l.tenant_id
                and m.company_id=l.company_id and m.material_id=l.material_id
            left join (
                select movement_id, cast(min(cast(supplier_id as varchar)) as uuid) supplier_id,
                    cast(min(cast(receipt_id as varchar)) as uuid) receipt_id, min(receipt_code) receipt_code
                from (
                    select gl.stock_movement_id movement_id, gl.supplier_id, rh.id receipt_id, rh.code receipt_code
                    from goods_receipt_lines gl
                    join goods_receipts rh on rh.id=gl.goods_receipt_id and rh.tenant_id=gl.tenant_id
                        and rh.company_id=gl.company_id and rh.is_delete=false
                    where gl.tenant_id=:tenant and gl.company_id=:company and gl.material_id=:material
                        and gl.is_delete=false and gl.stock_movement_id in (
                            select source_movement_id from inventory_cost_layers where tenant_id=:tenant
                                and company_id=:company and material_id=:material and id in (:ids))
                    union
                    select gb.stock_movement_id, gl.supplier_id, rh.id, rh.code
                    from goods_receipt_batches gb
                    join goods_receipt_lines gl on gl.id=gb.goods_receipt_line_id and gl.tenant_id=gb.tenant_id
                        and gl.company_id=gb.company_id and gl.is_delete=false and gl.material_id=:material
                    join goods_receipts rh on rh.id=gl.goods_receipt_id and rh.tenant_id=gl.tenant_id
                        and rh.company_id=gl.company_id and rh.is_delete=false
                    where gb.tenant_id=:tenant and gb.company_id=:company and gb.is_delete=false
                        and gb.stock_movement_id in (
                            select source_movement_id from inventory_cost_layers where tenant_id=:tenant
                                and company_id=:company and material_id=:material and id in (:ids))
                ) receipt_links group by movement_id having count(*)=1
            ) gr on gr.movement_id=m.id
            left join purchase_orders po on po.id=m.reference_id and m.reference_type='PurchaseOrder'
                and po.tenant_id=l.tenant_id and po.company_id=l.company_id and po.is_delete=false
            left join suppliers s on s.id=coalesce(gr.supplier_id,po.supplier_id) and s.tenant_id=l.tenant_id
                and s.company_id=l.company_id and s.is_delete=false
            left join warehouses sw on sw.id=l.source_warehouse_id and sw.tenant_id=l.tenant_id
                and sw.company_id=l.company_id and sw.is_delete=false
            where l.tenant_id=:tenant and l.company_id=:company and l.material_id=:material and l.id in (:ids)
            """, params, (rs, n) -> new OriginEvidence(uuid(rs, "id"), uuid(rs, "parent_cost_layer_id"),
                rs.getString("layer_type"), uuid(rs, "source_warehouse_id"), rs.getString("source_warehouse_code"),
                rs.getString("source_warehouse_name"), uuid(rs, "movement_id"),
                rs.getObject("received_date", LocalDate.class), rs.getBigDecimal("receipt_unit_cost"),
                rs.getString("currency_code"), rs.getString("reference_type"), uuid(rs, "reference_id"),
                rs.getString("document_code"), uuid(rs, "supplier_id"), rs.getString("supplier_code"),
                rs.getString("supplier_name"), rs.getTimestamp("posted_at") != null,
                uuid(rs, "verified_receipt_id") != null, rs.getBigDecimal("source_unit_price_snapshot"),
                rs.getString("source_currency_code_snapshot"), rs.getBigDecimal("source_exchange_rate_snapshot")));
    }

    public MovementRow movement(MaterialScope scope, UUID movementId) {
        var rows = jdbc.query("""
            select id, material_id, status, quantity from stock_movements
            where id=:id and tenant_id=:tenant and company_id=:company and material_id=:material and is_delete=false
            """, params(scope, new Filter(null, null, null, true)).addValue("id", movementId),
            (rs, n) -> new MovementRow(uuid(rs, "id"), uuid(rs, "material_id"), rs.getString("status"),
                rs.getBigDecimal("quantity")));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Page<StockMovementAllocation> allocations(MaterialScope scope, UUID movementId, Pageable page) {
        var params = params(scope, new Filter(null, null, null, true)).addValue("movement", movementId)
            .addValue("limit", page.getPageSize()).addValue("offset", page.getOffset());
        String from = " from stock_movement_allocations a where a.tenant_id=:tenant and a.company_id=:company "
            + "and a.material_id=:material and a.movement_id=:movement"
            + (scope.warehouseId() == null ? "" : " and (case when a.allocation_role='SOURCE' then "
                + "a.source_warehouse_id_snapshot else a.target_warehouse_id_snapshot end)=:warehouse");
        params.addValue("warehouse", scope.warehouseId());
        Long total = jdbc.queryForObject("select count(*)" + from, params, Long.class);
        var rows = jdbc.query("select a.*" + from
            + " order by a.allocation_sequence, a.id limit :limit offset :offset", params, (rs, n) -> {
                var a = new StockMovementAllocation();
                a.setId(uuid(rs, "id")); a.setAllocationSequence(rs.getInt("allocation_sequence"));
                a.setAllocationRole(rs.getString("allocation_role"));
                a.setSourceWarehouseIdSnapshot(uuid(rs, "source_warehouse_id_snapshot"));
                a.setTargetWarehouseIdSnapshot(uuid(rs, "target_warehouse_id_snapshot"));
                a.setSourceStockBatchIdSnapshot(uuid(rs, "source_stock_batch_id_snapshot"));
                a.setTargetStockBatchIdSnapshot(uuid(rs, "target_stock_batch_id_snapshot"));
                a.setSourceBatchNumberSnapshot(rs.getString("source_batch_number_snapshot"));
                a.setTargetBatchNumberSnapshot(rs.getString("target_batch_number_snapshot"));
                a.setSourceStorageLocationIdSnapshot(uuid(rs, "source_storage_location_id_snapshot"));
                a.setTargetStorageLocationIdSnapshot(uuid(rs, "target_storage_location_id_snapshot"));
                a.setSourceStorageBinIdSnapshot(uuid(rs, "source_storage_bin_id_snapshot"));
                a.setTargetStorageBinIdSnapshot(uuid(rs, "target_storage_bin_id_snapshot"));
                a.setOnHandDelta(rs.getBigDecimal("on_hand_delta")); a.setAvailableDelta(rs.getBigDecimal("available_delta"));
                a.setReservedDelta(rs.getBigDecimal("reserved_delta")); a.setQuarantineDelta(rs.getBigDecimal("quarantine_delta"));
                a.setTotalValueDelta(rs.getBigDecimal("total_value_delta")); a.setUnitCostSnapshot(rs.getBigDecimal("unit_cost_snapshot"));
                a.setCurrencyCodeSnapshot(rs.getString("currency_code_snapshot"));
                return a;
            });
        return new PageImpl<>(rows, page, total == null ? 0 : total);
    }

    public List<StockMovementAllocationCostLayer> costAllocations(MaterialScope scope, Collection<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.query("""
            select c.* from stock_movement_allocation_cost_layers c
            join stock_movement_allocations a on a.id=c.allocation_id and a.tenant_id=c.tenant_id and a.company_id=c.company_id
            where c.tenant_id=:tenant and c.company_id=:company and a.material_id=:material and c.allocation_id in (:ids)
            order by c.lineage_sequence, c.id
            """, params(scope, new Filter(null, null, null, true)).addValue("ids", ids), (rs, n) -> {
                var c = new StockMovementAllocationCostLayer();
                c.setAllocationId(uuid(rs, "allocation_id")); c.setCostLayerId(uuid(rs, "cost_layer_id"));
                c.setParentCostLayerIdSnapshot(uuid(rs, "parent_cost_layer_id_snapshot"));
                c.setLineageRole(rs.getString("lineage_role")); c.setQuantityDelta(rs.getBigDecimal("quantity_delta"));
                c.setValueDelta(rs.getBigDecimal("value_delta")); c.setUnitCostSnapshot(rs.getBigDecimal("unit_cost_snapshot"));
                c.setCurrencyCodeSnapshot(rs.getString("currency_code_snapshot"));
                return c;
            });
    }

    private String where(String alias, Filter filter, boolean includeDeleted) {
        return " where " + alias + ".tenant_id=:tenant and " + alias + ".company_id=:company and "
            + alias + ".material_id=:material" + (includeDeleted ? "" : " and " + alias + ".is_delete=false")
            + (filter.warehouseId() == null ? "" : " and " + alias + ".warehouse_id=:warehouse")
            + (filter.stockBatchId() == null ? "" : " and " + alias + ".stock_batch_id=:batch");
    }

    private MapSqlParameterSource params(MaterialScope scope, Filter filter) {
        return new MapSqlParameterSource("tenant", scope.tenantId()).addValue("company", scope.companyId())
            .addValue("material", scope.materialId()).addValue("warehouse", filter.warehouseId())
            .addValue("batch", filter.stockBatchId()).addValue("asOf", filter.asOf());
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException { return rs.getObject(column, UUID.class); }

    public record BalanceTotals(BigDecimal quantity, BigDecimal available, BigDecimal reserved,
        BigDecimal quarantine, BigDecimal value, long invalidHistoryRows) { }
    public record LayerTotals(BigDecimal quantity, BigDecimal value, long invalidHistoryRows,
        String minCurrency, String maxCurrency) { }
    public record LayerRow(UUID id, UUID warehouseId, String warehouseName, UUID batchId, String batchNumber,
        String layerType, BigDecimal received, BigDecimal remaining, BigDecimal value, String currency) { }
    public record OriginEvidence(UUID id, UUID parentId, String layerType, UUID sourceWarehouseId,
        String sourceWarehouseCode, String sourceWarehouseName, UUID movementId, LocalDate receivedDate,
        BigDecimal receiptUnitCost, String currency, String referenceType, UUID referenceId,
        String documentCode, UUID supplierId, String supplierCode, String supplierName, boolean posted,
        boolean verifiedReceipt, BigDecimal purchaseUnitPrice, String purchaseCurrency, BigDecimal purchaseExchangeRate) { }
    public record MovementRow(UUID id, UUID materialId, String status, BigDecimal quantity) { }
}
