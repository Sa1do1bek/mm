package com.enterprise.mm.warehouse.history;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.payload.TransferCreateRequest;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/** Later draft edits may select batches/locations but cannot change or exceed the reviewed source. */
@Service @RequiredArgsConstructor
public class IssueSourceTransferGuard {
    private final NamedParameterJdbcTemplate jdbc;

    public void validateEdit(UUID transferId, TransferCreateRequest request) {
        var p = new MapSqlParameterSource("tenant", TenantContext.currentTenant()).addValue("company", CompanyContext.currentCompany()).addValue("transfer", transferId);
        var source = jdbc.queryForList("""
            select d.warehouse_id,l.target_warehouse_id,l.material_id,l.quantity
            from warehouse_issue_source_links link
            join warehouse_history_records d on d.id=link.source_document_id and d.tenant_id=link.tenant_id and d.company_id=link.company_id
            join warehouse_history_records l on l.document_key=d.document_key and l.tenant_id=d.tenant_id and l.company_id=d.company_id
                and l.kind='LINE' and l.source_table='_document4470_vt4512'
            where link.tenant_id=:tenant and link.company_id=:company and link.transfer_document_id=:transfer
            """, p);
        if (source.isEmpty()) return;
        Map<UUID, BigDecimal> limits = new HashMap<>(), proposed = new HashMap<>();
        for (var row : source) {
            if (!Objects.equals(row.get("warehouse_id"), request.getSourceWarehouseId())
                || !Objects.equals(row.get("target_warehouse_id"), request.getDestinationWarehouseId()))
                throw new ConflictException("Warehouses must match the reviewed 1C request");
            limits.merge((UUID) row.get("material_id"), new BigDecimal((String) row.get("quantity")), BigDecimal::add);
        }
        for (var line : request.getLines()) proposed.merge(line.getMaterialId(), line.getPlannedQuantity(), BigDecimal::add);
        if (proposed.entrySet().stream().anyMatch(e -> !limits.containsKey(e.getKey()) || e.getValue().compareTo(limits.get(e.getKey())) > 0))
            throw new ConflictException("Materials and total quantities cannot exceed the reviewed 1C request");
    }
}
