package com.enterprise.mm.service;

import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.warehouse.production.ProductionOperationDtos.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Follows persisted movement allocations, including transfer layer parents, across material transformations. */
@Service
@RequiredArgsConstructor
public class ProductionHistoryService {
    private final com.enterprise.mm.service.UnitOfMeasureDisplayService unitDisplay;
    private static final int MAX_NODES = 200;
    private final JdbcTemplate jdbc;
    private final MaterialCatalogGuard catalog;
    private final RoleDataScopeService scope;

    @Transactional(readOnly = true)
    public History material(UUID materialId, UUID warehouseId, UUID stockBatchId) {
        var company = ProductionOperationService.company();
        var tenant = ProductionOperationService.tenant();
        var material = catalog.requireMaterial(materialId, "materialId");
        if (!company.equals(material.getCompanyId())) throw new NotFoundException("Material not found");
        scope.assertWarehouseAccess(warehouseId);
        Integer warehouse = jdbc.queryForObject("select count(*) from warehouses where id=? and tenant_id=? and company_id=? and is_delete=false",
            Integer.class, warehouseId, tenant, company);
        if (warehouse == null || warehouse == 0) throw new NotFoundException("Warehouse not found");
        List<UUID> roots = jdbc.query("select id from stock_movements where tenant_id=? and company_id=? and material_id=? and warehouse_id=? and is_delete=false"
            + (stockBatchId == null ? "" : " and stock_batch_id=?") + " order by movement_date desc,id limit 201",
            (rs, n) -> rs.getObject(1, UUID.class), stockBatchId == null
                ? new Object[]{tenant, company, materialId, warehouseId} : new Object[]{tenant, company, materialId, warehouseId, stockBatchId});
        Deque<UUID> pending = new ArrayDeque<>(roots);
        Set<UUID> attempted = new HashSet<>();
        List<HistoryNode> nodes = new ArrayList<>();
        boolean truncated = false;
        while (!pending.isEmpty() && nodes.size() < MAX_NODES) {
            UUID id = pending.removeFirst();
            if (!attempted.add(id)) continue;
            var rows = jdbc.query("""
                select m.*, mat.code material_code,mat.name material_name,w.name warehouse_name,
                    coalesce(m.uom_code_snapshot,u.code) unit_code, coalesce(b.batch_number,m.batch_number_snapshot) actual_batch_number,
                    coalesce(p.operation_number,t.code,m.code) document_number
                from stock_movements m
                join materials mat on mat.id=m.material_id and mat.tenant_id=m.tenant_id and mat.company_id=m.company_id
                join warehouses w on w.id=m.warehouse_id and w.tenant_id=m.tenant_id and w.company_id=m.company_id
                left join unit_of_measures u on u.id=mat.unit_of_measure_id
                left join stock_batches b on b.id=m.stock_batch_id and b.tenant_id=m.tenant_id and b.company_id=m.company_id
                left join warehouse_production_operations p on p.id=m.reference_id and p.tenant_id=m.tenant_id and p.company_id=m.company_id
                    and m.reference_type='PRODUCTION_OPERATION'
                left join transfer_documents t on t.id=m.reference_id and t.tenant_id=m.tenant_id and t.company_id=m.company_id
                    and m.reference_type in ('TRANSFER_RECEIPT','TRANSFER_RECEIVE','TRANSFER_DISPATCH','TRANSFER_RETURN',
                        'TRANSFER_GOOD_RECEIPT','TRANSFER_QUARANTINE_RECEIPT','TRANSFER_TRANSIT_RETURN','TRANSFER_QUARANTINE_RETURN_DISPATCH')
                where m.id=? and m.tenant_id=? and m.company_id=? and m.is_delete=false
                """, (rs, n) -> node(rs), id, tenant, company);
            if (rows.isEmpty()) { truncated = true; continue; }
            HistoryNode row = rows.getFirst();
            if (!scope.canAccessWarehouse(row.warehouseId())) { truncated = true; continue; }
            List<UUID> parents = parents(row);
            nodes.add(new HistoryNode(row.movementId(), row.materialId(), row.materialCode(), row.materialName(),
                row.warehouseId(), row.warehouseName(), row.stockBatchId(), row.batchNumber(), row.unitCode(), row.movementType(),
                row.quantity(), row.date(), row.referenceType(), row.referenceId(), row.documentNumber(), row.status(), parents).withUnitShortName(row.unitShortName()));
            pending.addAll(parents);
        }
        Set<UUID> visible = new HashSet<>();
        nodes.forEach(node -> visible.add(node.movementId()));
        var unitLabels = unitDisplay.shortNames(tenant, company, nodes.stream().map(HistoryNode::unitCode).toList());
        var safe = nodes.stream().map(row -> new HistoryNode(row.movementId(), row.materialId(), row.materialCode(), row.materialName(),
            row.warehouseId(), row.warehouseName(), row.stockBatchId(), row.batchNumber(), row.unitCode(), row.movementType(),
            row.quantity(), row.date(), row.referenceType(), row.referenceId(), row.documentNumber(), row.status(),
            row.parents().stream().filter(visible::contains).toList()).withUnitShortName(unitLabels.get(row.unitCode()))).toList();
        return new History(safe, truncated || pending.stream().anyMatch(id -> !attempted.contains(id)));
    }

    private List<UUID> parents(HistoryNode node) {
        String tenant = ProductionOperationService.tenant();
        UUID company = ProductionOperationService.company();
        LinkedHashSet<UUID> parents = new LinkedHashSet<>();
        if ("PRODUCTION_OPERATION".equals(node.referenceType()) && "RECEIPT".equals(node.movementType())) {
            parents.addAll(jdbc.query("select l.movement_id from warehouse_production_operation_lines l join warehouse_production_operations p on p.id=l.operation_id where p.id=? and p.tenant_id=? and p.company_id=? and l.line_type='INPUT' order by l.sequence_number",
                (rs, n) -> rs.getObject(1, UUID.class), node.referenceId(), tenant, company));
        }
        parents.addAll(jdbc.query("""
            select distinct l.source_movement_id from stock_movement_allocations a
            join stock_movement_allocation_cost_layers c on c.allocation_id=a.id and c.tenant_id=a.tenant_id and c.company_id=a.company_id
            join inventory_cost_layers l on l.id=c.cost_layer_id and l.tenant_id=a.tenant_id and l.company_id=a.company_id
            where a.movement_id=? and a.tenant_id=? and a.company_id=? and c.quantity_delta<0 and l.source_movement_id is not null
            """, (rs, n) -> rs.getObject(1, UUID.class), node.movementId(), tenant, company));
        parents.addAll(jdbc.query("""
            select distinct parent.source_movement_id from inventory_cost_layers l
            join inventory_cost_layers parent on parent.id=l.parent_cost_layer_id and parent.tenant_id=l.tenant_id and parent.company_id=l.company_id
            where l.source_movement_id=? and l.tenant_id=? and l.company_id=? and parent.source_movement_id is not null
            """, (rs, n) -> rs.getObject(1, UUID.class), node.movementId(), tenant, company));
        if ("REVERSAL".equals(node.referenceType()) && node.referenceId() != null) parents.add(node.referenceId());
        parents.remove(node.movementId());
        return List.copyOf(parents);
    }

    private HistoryNode node(ResultSet rs) throws SQLException {
        var date = rs.getDate("movement_date");
        return new HistoryNode(rs.getObject("id", UUID.class), rs.getObject("material_id", UUID.class),
            rs.getString("material_code"), rs.getString("material_name"), rs.getObject("warehouse_id", UUID.class),
            rs.getString("warehouse_name"), rs.getObject("stock_batch_id", UUID.class), rs.getString("actual_batch_number"),
            rs.getString("unit_code"), rs.getString("movement_type"), rs.getBigDecimal("quantity"), date == null ? null : date.toLocalDate(),
            rs.getString("reference_type"), rs.getObject("reference_id", UUID.class), rs.getString("document_number"), rs.getString("status"), List.of());
    }
}
