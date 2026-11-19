package com.enterprise.mm.warehouse.reports;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.BadRequestException;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferMovementQuery {
    private final JdbcTemplate jdbc;

    public TransferMovementQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> between(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new BadRequestException("Transfer movement period is required");
        }
        if (to.isBefore(from)) {
            throw new BadRequestException("Transfer movement period ends before it starts");
        }
        List<Map<String, Object>> rows = jdbc.query("""
            select d.id, d.code, d.planned_date, d.dispatched_date, d.source_warehouse_id, d.destination_warehouse_id,
                   l.material_id, l.planned_quantity, l.dispatched_quantity, l.in_transit_quantity, l.received_quantity
            from transfer_documents d
            join transfer_document_lines l on l.transfer_document_id = d.id
            where d.tenant_id = ? and d.company_id = ? and d.is_delete = false and l.is_delete = false
              and coalesce(d.dispatched_date, d.planned_date) between ? and ?
            order by coalesce(d.dispatched_date, d.planned_date), d.code, l.material_id
            """, (rs, index) -> row(rs), TenantContext.currentTenant(), CompanyContext.currentCompany(),
            Date.valueOf(from), Date.valueOf(to));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", from.toString());
        result.put("to", to.toString());
        result.put("rows", rows);
        return result;
    }

    private Map<String, Object> row(ResultSet rs) throws SQLException {
        BigDecimal planned = decimal(rs, "planned_quantity");
        BigDecimal dispatched = decimal(rs, "dispatched_quantity");
        BigDecimal received = decimal(rs, "received_quantity");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("documentId", rs.getObject("id"));
        row.put("code", rs.getString("code"));
        row.put("plannedDate", rs.getDate("planned_date"));
        row.put("dispatchedDate", rs.getDate("dispatched_date"));
        row.put("sourceWarehouseId", rs.getObject("source_warehouse_id"));
        row.put("destinationWarehouseId", rs.getObject("destination_warehouse_id"));
        row.put("materialId", rs.getObject("material_id"));
        row.put("plannedQuantity", planned);
        row.put("dispatchedQuantity", dispatched);
        row.put("inTransitQuantity", decimal(rs, "in_transit_quantity"));
        row.put("receivedQuantity", received);
        row.put("partial", dispatched.compareTo(planned) < 0 || received.compareTo(dispatched) < 0);
        return row;
    }

    private static BigDecimal decimal(ResultSet rs, String column) throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        return value == null ? BigDecimal.ZERO : value;
    }
}
