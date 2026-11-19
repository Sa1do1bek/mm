package com.enterprise.mm.warehouse.reconciliation;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WarehouseReconciliationQuery {
    private final NamedParameterJdbcTemplate jdbc;

    public WarehouseReconciliationQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public WarehouseReconciliationResult reconcile(ReconciliationFilter filter) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();

        if (tenantId == null || companyId == null) {
            return new WarehouseReconciliationResult(ReconciliationSummary.empty(), List.of(), 0, filter.size(), 0);
        }

        boolean hasTables = checkTableExists("warehouse_account_entries") && checkTableExists("warehouse_posting_effects");
        if (!hasTables) {
            return new WarehouseReconciliationResult(ReconciliationSummary.empty(), List.of(), 0, filter.size(), 0);
        }

        var params = new MapSqlParameterSource()
            .addValue("tenant", tenantId)
            .addValue("company", companyId);

        String baseCte = """
            WITH source_pos AS (
                SELECT
                    COALESCE(e.material_id, lm.master_id) AS material_id,
                    COALESCE(e.warehouse_id, lw.master_id) AS warehouse_id,
                    e.account_code,
                    MAX(e.account_name) AS account_name,
                    COALESCE(SUM(e.closing_quantity), 0) AS source_quantity,
                    COALESCE(SUM(e.closing_amount), 0) AS source_amount
                FROM warehouse_account_entries e
                JOIN warehouse_account_imports i ON i.id = e.import_id AND i.status = 'COMPLETED'
                LEFT JOIN warehouse_source_master_links lm ON lm.tenant_id = e.tenant_id AND lm.company_id = e.company_id
                    AND lm.reference_kind = 'MATERIAL' AND lm.reference_type = e.material_reference_type
                    AND lm.source_id = e.material_source_id
                LEFT JOIN warehouse_source_master_links lw ON lw.tenant_id = e.tenant_id AND lw.company_id = e.company_id
                    AND lw.reference_kind = 'WAREHOUSE' AND lw.reference_type = '00000055'
                    AND lw.source_id = e.warehouse_source_id
                WHERE e.tenant_id = :tenant AND e.company_id = :company
                GROUP BY COALESCE(e.material_id, lm.master_id), COALESCE(e.warehouse_id, lw.master_id), e.account_code
            ),
            native_stock AS (
                SELECT
                    sb.material_id,
                    sb.warehouse_id,
                    COALESCE(SUM(sb.quantity), 0) AS physical_quantity,
                    COALESCE(SUM(sb.total_value), 0) AS physical_amount
                FROM stock_balances sb
                WHERE sb.tenant_id = :tenant AND sb.company_id = :company
                  AND sb.is_delete = false
                GROUP BY sb.material_id, sb.warehouse_id
            ),
            proven_stock AS (
                SELECT
                    pe.material_id,
                    pe.warehouse_id,
                    pe.account_code,
                    COALESCE(SUM(pe.quantity), 0) AS proven_quantity,
                    COALESCE(SUM(pe.amount), 0) AS proven_amount
                FROM warehouse_posting_effects pe
                JOIN warehouse_posting_batches pb ON pb.id = pe.batch_id
                WHERE pb.tenant_id = :tenant AND pb.company_id = :company
                  AND pb.status = 'COMMITTED'
                  AND pe.effect_type = 'ACCOUNT'
                GROUP BY pe.material_id, pe.warehouse_id, pe.account_code
            ),
            combined AS (
                SELECT
                    COALESCE(s.material_id, p.material_id) AS material_id,
                    COALESCE(s.warehouse_id, p.warehouse_id) AS warehouse_id,
                    COALESCE(s.account_code, p.account_code) AS account_code,
                    COALESCE(s.account_name, p.account_code) AS account_name,
                    COALESCE(s.source_quantity, 0) AS source_quantity,
                    COALESCE(s.source_amount, 0) AS source_amount,
                    COALESCE(n.physical_quantity, 0) AS physical_quantity,
                    COALESCE(n.physical_amount, 0) AS physical_amount,
                    COALESCE(p.proven_quantity, 0) AS proven_quantity,
                    COALESCE(p.proven_amount, 0) AS proven_amount,
                    (COALESCE(p.proven_quantity, 0) - COALESCE(s.source_quantity, 0)) AS quantity_delta,
                    (COALESCE(p.proven_amount, 0) - COALESCE(s.source_amount, 0)) AS amount_delta,
                    CASE
                        WHEN COALESCE(s.material_id, p.material_id) IS NULL OR COALESCE(s.warehouse_id, p.warehouse_id) IS NULL THEN 'UNMAPPED'
                        WHEN (s.source_quantity IS NULL OR s.source_quantity = 0) AND COALESCE(p.proven_quantity, 0) <> 0 THEN 'NATIVE_ONLY'
                        WHEN (p.proven_quantity IS NULL OR p.proven_quantity = 0) AND COALESCE(s.source_quantity, 0) <> 0 THEN 'SOURCE_ONLY'
                        WHEN (COALESCE(p.proven_quantity, 0) - COALESCE(s.source_quantity, 0)) = 0
                         AND (COALESCE(p.proven_amount, 0) - COALESCE(s.source_amount, 0)) = 0 THEN 'BALANCED'
                        ELSE 'DISCREPANCY'
                    END AS status
                FROM source_pos s
                FULL OUTER JOIN proven_stock p ON p.material_id = s.material_id
                    AND p.warehouse_id = s.warehouse_id
                    AND p.account_code = s.account_code
                LEFT JOIN native_stock n ON n.material_id = COALESCE(s.material_id, p.material_id)
                    AND n.warehouse_id = COALESCE(s.warehouse_id, p.warehouse_id)
            )
            """;

        // Query summary across all rows matching tenant/company
        String summarySql = baseCte + """
            SELECT
                count(*) as total_pos,
                count(*) filter (where status = 'BALANCED') as balanced_pos,
                count(*) filter (where status = 'DISCREPANCY') as discrepancy_pos,
                count(*) filter (where status = 'SOURCE_ONLY') as source_only_pos,
                count(*) filter (where status = 'NATIVE_ONLY') as native_only_pos,
                count(*) filter (where status = 'UNMAPPED') as unmapped_pos,
                COALESCE(sum(source_quantity), 0) as total_source_qty,
                COALESCE(sum(source_amount), 0) as total_source_amt,
                COALESCE(sum(proven_quantity), 0) as total_proven_qty,
                COALESCE(sum(proven_amount), 0) as total_proven_amt,
                COALESCE(sum(quantity_delta), 0) as total_diff_qty,
                COALESCE(sum(amount_delta), 0) as total_diff_amt
            FROM combined
            """;

        ReconciliationSummary summary = jdbc.query(summarySql, params, rs -> {
            if (!rs.next()) return ReconciliationSummary.empty();
            return new ReconciliationSummary(
                rs.getLong("total_pos"),
                rs.getLong("balanced_pos"),
                rs.getLong("discrepancy_pos"),
                rs.getLong("source_only_pos"),
                rs.getLong("native_only_pos"),
                rs.getLong("unmapped_pos"),
                rs.getBigDecimal("total_source_qty").setScale(7, RoundingMode.HALF_UP),
                rs.getBigDecimal("total_source_amt").setScale(4, RoundingMode.HALF_UP),
                rs.getBigDecimal("total_proven_qty").setScale(7, RoundingMode.HALF_UP),
                rs.getBigDecimal("total_proven_amt").setScale(4, RoundingMode.HALF_UP),
                rs.getBigDecimal("total_diff_qty").setScale(7, RoundingMode.HALF_UP),
                rs.getBigDecimal("total_diff_amt").setScale(4, RoundingMode.HALF_UP)
            );
        });

        // Query items with filter
        StringBuilder itemsSql = new StringBuilder(baseCte);
        itemsSql.append("""
            SELECT
                c.*,
                m.code as material_code,
                m.name as material_name,
                COALESCE(u.symbol, u.code, '') as uom,
                w.code as warehouse_code,
                w.name as warehouse_name
            FROM combined c
            LEFT JOIN materials m ON m.id = c.material_id AND m.tenant_id = :tenant AND m.company_id = :company
            LEFT JOIN unit_of_measures u ON u.id = m.unit_of_measure_id AND u.tenant_id = :tenant
            LEFT JOIN warehouses w ON w.id = c.warehouse_id AND w.tenant_id = :tenant AND w.company_id = :company
            WHERE 1=1
            """);

        if (filter.accountCode() != null && !filter.accountCode().isBlank()) {
            itemsSql.append(" AND c.account_code = :accountCode");
            params.addValue("accountCode", filter.accountCode());
        }
        if (filter.warehouseId() != null) {
            itemsSql.append(" AND c.warehouse_id = :warehouseId");
            params.addValue("warehouseId", filter.warehouseId());
        }
        if (filter.materialId() != null) {
            itemsSql.append(" AND c.material_id = :materialId");
            params.addValue("materialId", filter.materialId());
        }
        if (filter.status() != null) {
            itemsSql.append(" AND c.status = :status");
            params.addValue("status", filter.status().name());
        }

        itemsSql.append(" ORDER BY c.account_code, m.code, w.code LIMIT :limit OFFSET :offset");
        params.addValue("limit", filter.size());
        params.addValue("offset", (long) filter.page() * filter.size());

        List<ReconciliationPosition> items = jdbc.query(itemsSql.toString(), params, (rs, rowNum) -> mapPosition(rs));

        return new WarehouseReconciliationResult(
            summary != null ? summary : ReconciliationSummary.empty(),
            items,
            filter.page(),
            filter.size(),
            summary != null ? summary.totalPositions() : 0
        );
    }

    private ReconciliationPosition mapPosition(ResultSet rs) throws SQLException {
        String statusStr = rs.getString("status");
        ReconciliationStatus status = statusStr != null ? ReconciliationStatus.valueOf(statusStr) : ReconciliationStatus.DISCREPANCY;

        return new ReconciliationPosition(
            rs.getObject("material_id", UUID.class),
            rs.getString("material_code"),
            rs.getString("material_name"),
            rs.getString("uom"),
            rs.getObject("warehouse_id", UUID.class),
            rs.getString("warehouse_code"),
            rs.getString("warehouse_name"),
            rs.getString("account_code"),
            rs.getString("account_name"),
            rs.getBigDecimal("source_quantity"),
            rs.getBigDecimal("source_amount"),
            rs.getBigDecimal("physical_quantity"),
            rs.getBigDecimal("physical_amount"),
            rs.getBigDecimal("proven_quantity"),
            rs.getBigDecimal("proven_amount"),
            rs.getBigDecimal("quantity_delta"),
            rs.getBigDecimal("amount_delta"),
            status
        );
    }

    private boolean checkTableExists(String tableName) {
        try {
            return Boolean.TRUE.equals(jdbc.getJdbcTemplate().execute(
                (org.springframework.jdbc.core.ConnectionCallback<Boolean>) c -> {
                    try (var tables = c.getMetaData().getTables(c.getCatalog(), c.getSchema(), tableName, new String[]{"TABLE"})) {
                        return tables.next();
                    }
                }
            ));
        } catch (Exception e) {
            return false;
        }
    }
}
