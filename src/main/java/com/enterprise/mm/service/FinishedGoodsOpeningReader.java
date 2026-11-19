package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.BadRequestException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/** Reads immutable 1C balances; results still require live stock reconciliation. */
@Service
public class FinishedGoodsOpeningReader {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    public FinishedGoodsOpeningReader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    public List<FinishedGoodsOpeningCalculator.Position> preview(String tenant, UUID company, LocalDate snapshot, UUID warehouse) {
        if (tenant == null || company == null || snapshot == null
                || !tenant.equals(TenantContext.currentTenant()) || !company.equals(CompanyContext.currentCompany())
                || CompanyContext.DEFAULT_COMPANY_ID.equals(company))
            throw new BadRequestException("Explicit current company, tenant and source snapshot are required");
        var params = new MapSqlParameterSource("tenant", tenant).addValue("company", company)
            .addValue("snapshot", snapshot).addValue("warehouse", warehouse);
        Long organizations = jdbc.queryForObject("""
            select count(distinct organization_source_id) from warehouse_account_imports
            where tenant_id=:tenant and company_id=:company and snapshot_date=:snapshot and status='COMPLETED'
            """, params, Long.class);
        if (organizations != null && organizations > 1)
            throw new BadRequestException("Source imports have conflicting 1C organizations");
        var rows = jdbc.query("""
            select e.source_key, e.row_sha256, e.account_code, e.warehouse_source_id,
                   e.material_reference_type, e.material_source_id, e.uom,
                   e.material_id, lm.master_id as linked_material_id,
                   e.warehouse_id, lw.master_id as linked_warehouse_id,
                   e.quantity, e.amount, e.additional_dimensions::text as dimensions
            from warehouse_account_entries e
            join warehouse_account_imports r on r.id=e.import_id and r.tenant_id=e.tenant_id
                 and r.company_id=e.company_id and r.status='COMPLETED' and r.snapshot_date=:snapshot
            left join warehouse_source_master_links lm on lm.tenant_id=e.tenant_id and lm.company_id=e.company_id
                 and lm.reference_kind='MATERIAL' and lm.reference_type=e.material_reference_type
                 and lm.source_id=e.material_source_id
            left join warehouse_source_master_links lw on lw.tenant_id=e.tenant_id and lw.company_id=e.company_id
                 and lw.reference_kind='WAREHOUSE' and lw.reference_type='00000055' and lw.source_id=e.warehouse_source_id
            where e.tenant_id=:tenant and e.company_id=:company and e.kind='BALANCE'
              and e.source_date=:snapshot and e.stock_category='FINISHED_PRODUCTS'
              and e.account_code in ('2810','2811','2812','2813')
              and (cast(:warehouse as uuid) is null or e.warehouse_id=cast(:warehouse as uuid)
                   or lw.master_id=cast(:warehouse as uuid))
            order by e.source_key
            """, params, this::sourceRow);
        return FinishedGoodsOpeningCalculator.calculate(rows);
    }

    private FinishedGoodsOpeningCalculator.SourceRow sourceRow(ResultSet row, int index) throws SQLException {
        UUID material = consistent(row.getObject("material_id", UUID.class), row.getObject("linked_material_id", UUID.class));
        UUID warehouse = consistent(row.getObject("warehouse_id", UUID.class), row.getObject("linked_warehouse_id", UUID.class));
        return new FinishedGoodsOpeningCalculator.SourceRow(row.getString("source_key"), row.getString("row_sha256"),
            row.getString("account_code"), row.getString("warehouse_source_id"), row.getString("material_reference_type"),
            row.getString("material_source_id"), row.getString("uom"), warehouse, material,
            row.getBigDecimal("quantity"), row.getBigDecimal("amount"), invalidDimensions(row.getString("dimensions"),
                row.getString("material_reference_type"), row.getString("material_source_id"), row.getString("warehouse_source_id")));
    }

    private static UUID consistent(UUID direct, UUID linked) {
        // A UUID embedded in a historical row is not proof that it represents the
        // same 1C master. The independent source-master link is mandatory.
        if (linked == null || direct != null && !direct.equals(linked)) return null;
        return linked;
    }

    private boolean invalidDimensions(String text, String materialType, String materialId, String warehouseId) throws SQLException {
        try {
            JsonNode node = json.readTree(text);
            if (node == null || !node.isObject()) return true;
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (field.getKey().equals("currencySourceId")) {
                    if (!empty(field.getValue())) return true;
                } else if (!field.getKey().matches("dimension[1-4]_(type|rtref|rrref)")) return true;
            }
            int materials = 0, warehouses = 0;
            for (int i = 1; i <= 4; i++) {
                JsonNode type = node.get("dimension" + i + "_type");
                JsonNode referenceType = node.get("dimension" + i + "_rtref");
                JsonNode referenceId = node.get("dimension" + i + "_rrref");
                if (empty(type) && empty(referenceType) && empty(referenceId)) continue;
                if (type == null || referenceType == null || referenceId == null || !"08".equals(type.asText())) return true;
                if (Objects.equals(materialType, referenceType.asText()) && Objects.equals(materialId, referenceId.asText())) materials++;
                else if ("00000055".equals(referenceType.asText()) && Objects.equals(warehouseId, referenceId.asText())) warehouses++;
                else return true;
            }
            return materials != 1 || warehouses != 1;
        } catch (Exception error) {
            throw new SQLException("Invalid source balance dimensions", error);
        }
    }

    private static boolean empty(JsonNode value) {
        if (value == null || value.isNull()) return true;
        String text = value.asText();
        return text.isBlank() || text.matches("0+");
    }
}
