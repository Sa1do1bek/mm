package com.enterprise.mm.warehouse.reports;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

@Repository
public class WarehouseReportApprovalStore {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final WarehouseReportQuery reportQuery;

    public WarehouseReportApprovalStore(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper, WarehouseReportQuery reportQuery) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.reportQuery = reportQuery;
    }

    public record Saved(UUID id, UUID warehouseId, LocalDate from, LocalDate to, int revision,
        OffsetDateTime approvedAt, String approvedBy, String hash, WarehouseReport report) {
        public WarehouseOverview.Approval summary() {
            return new WarehouseOverview.Approval(id, revision, approvedAt, approvedBy);
        }
    }

    public Map<UUID,WarehouseOverview.Approval> latest(LocalDate from, LocalDate to, Collection<UUID> warehouseIds) {
        if (warehouseIds.isEmpty()) return Map.of();
        var params=reportQuery.scope().addValue("from",from).addValue("to",to).addValue("warehouses",warehouseIds);
        Map<UUID,WarehouseOverview.Approval> result=new HashMap<>();
        jdbc.query("""
            select distinct on (warehouse_id) id,warehouse_id,revision,approved_at,approved_by
            from warehouse_report_approvals
            where tenant_id=:tenant and company_id=:company and period_from=:from and period_to=:to
              and warehouse_id in (:warehouses)
            order by warehouse_id,revision desc
            """,params,(RowCallbackHandler) rs -> result.put(rs.getObject("warehouse_id",UUID.class),
                new WarehouseOverview.Approval(rs.getObject("id",UUID.class),rs.getInt("revision"),
                    rs.getObject("approved_at",OffsetDateTime.class),rs.getString("approved_by"))));
        return result;
    }

    public Saved latest(UUID warehouseId, LocalDate from, LocalDate to) {
        var params=reportQuery.scope().addValue("warehouse",warehouseId).addValue("from",from).addValue("to",to);
        return jdbc.query("""
            select * from warehouse_report_approvals
            where tenant_id=:tenant and company_id=:company and warehouse_id=:warehouse
              and period_from=:from and period_to=:to
            order by revision desc limit 1
            """,params,(rs,n)->read(rs.getObject("id",UUID.class),rs.getObject("warehouse_id",UUID.class),
                rs.getDate("period_from").toLocalDate(),rs.getDate("period_to").toLocalDate(),
                rs.getInt("revision"),rs.getObject("approved_at",OffsetDateTime.class),
                rs.getString("approved_by"),rs.getString("snapshot_hash"),rs.getString("snapshot")))
            .stream().findFirst().orElse(null);
    }

    public List<WarehouseOverview.Approval> history(UUID warehouseId, LocalDate from, LocalDate to) {
        return jdbc.query("""
            select id,revision,approved_at,approved_by from warehouse_report_approvals
            where tenant_id=:tenant and company_id=:company and warehouse_id=:warehouse
              and period_from=:from and period_to=:to order by revision desc
            """,reportQuery.scope().addValue("warehouse",warehouseId).addValue("from",from).addValue("to",to),
            (rs,n)->new WarehouseOverview.Approval(rs.getObject("id",UUID.class),rs.getInt("revision"),
                rs.getObject("approved_at",OffsetDateTime.class),rs.getString("approved_by")));
    }

    public Saved byId(UUID id) {
        return jdbc.query("select * from warehouse_report_approvals where id=:id and tenant_id=:tenant and company_id=:company",
            reportQuery.scope().addValue("id",id),(rs,n)->read(rs.getObject("id",UUID.class),rs.getObject("warehouse_id",UUID.class),
                rs.getDate("period_from").toLocalDate(),rs.getDate("period_to").toLocalDate(),
                rs.getInt("revision"),rs.getObject("approved_at",OffsetDateTime.class),
                rs.getString("approved_by"),rs.getString("snapshot_hash"),rs.getString("snapshot")))
            .stream().findFirst().orElse(null);
    }

    public Saved save(UUID warehouseId, LocalDate from, LocalDate to, int revision,
        String approvedBy, String hash, WarehouseReport report) {
        UUID id=UUID.randomUUID();
        String json;
        try { json=mapper.writeValueAsString(report); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Could not serialize warehouse report",e); }
        jdbc.update("""
            insert into warehouse_report_approvals
              (id,tenant_id,company_id,warehouse_id,period_from,period_to,revision,approved_by,snapshot_hash,snapshot)
            values (:id,:tenant,:company,:warehouse,:from,:to,:revision,:approvedBy,:hash,cast(:snapshot as jsonb))
            """,reportQuery.scope().addValue("id",id).addValue("warehouse",warehouseId)
            .addValue("from",from).addValue("to",to).addValue("revision",revision)
            .addValue("approvedBy",approvedBy).addValue("hash",hash).addValue("snapshot",json));
        return latest(warehouseId,from,to);
    }

    private Saved read(UUID id, UUID warehouseId, LocalDate from, LocalDate to, int revision,
        OffsetDateTime approvedAt, String approvedBy, String hash, String json) {
        try { return new Saved(id,warehouseId,from,to,revision,approvedAt,approvedBy,hash,
            mapper.readValue(json,WarehouseReport.class)); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Could not read approved warehouse report",e); }
    }
}
