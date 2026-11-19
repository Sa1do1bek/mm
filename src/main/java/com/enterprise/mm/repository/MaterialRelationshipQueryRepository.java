package com.enterprise.mm.repository;

import com.enterprise.mm.payload.MaterialCommandViewDtos.RelationshipFilter;
import com.enterprise.mm.payload.MaterialCommandViewDtos.RelationshipType;
import java.sql.ResultSet;
import java.sql.SQLException;
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
public class MaterialRelationshipQueryRepository {
    private static final String UNION_SQL = """
        select pri.id, 'PURCHASE_REQUEST' as relationship_type, pr.code as business_code,
               pr.name as display_name, pr.status, coalesce(pr.updated_at, pr.created_at) as occurred_at,
               'PURCHASE_REQUEST' as source_type, pr.id as source_id, 'purchase-requests' as module_key
        from purchase_request_items pri join purchase_requests pr on pr.id = pri.purchase_request_id
        where :includeDocuments = true and pri.tenant_id = :tenantId and pri.company_id = :companyId
          and pr.tenant_id = :tenantId and pr.company_id = :companyId
          and pri.is_delete = false and pr.is_delete = false and pri.line_type = 'MATERIAL'
          and pri.material_id = :materialId
        union all
        select poi.id, 'PURCHASE_ORDER', po.code, po.name, po.status,
               coalesce(po.updated_at, po.created_at), 'PURCHASE_ORDER', po.id, 'purchase-orders'
        from purchase_order_items poi join purchase_orders po on po.id = poi.purchase_order_id
        where :includeDocuments = true and poi.tenant_id = :tenantId and poi.company_id = :companyId
          and po.tenant_id = :tenantId and po.company_id = :companyId
          and poi.is_delete = false and po.is_delete = false and poi.line_type = 'MATERIAL'
          and poi.material_id = :materialId
        union all
        select reservation.id, 'RESERVATION', reservation.code, reservation.name, reservation.status,
               coalesce(reservation.updated_at, reservation.created_at), 'MATERIAL_RESERVATION', reservation.id,
               'material-reservations'
        from material_reservations reservation
        where :includeDocuments = true and reservation.tenant_id = :tenantId
          and reservation.company_id = :companyId and reservation.is_delete = false
          and reservation.material_id = :materialId
        union all
        select movement.id, 'STOCK_MOVEMENT', movement.code, movement.name, movement.status,
               coalesce(movement.posted_at, movement.created_at), 'STOCK_MOVEMENT', movement.id, 'stock-movements'
        from stock_movements movement
        where :includeDocuments = true and movement.tenant_id = :tenantId
          and movement.company_id = :companyId and movement.is_delete = false
          and movement.material_id = :materialId
        union all
        select audit.id, 'AUDIT_EVENT', audit.code, audit.name, audit.status,
               coalesce(audit.occurred_at, audit.created_at), audit.action, audit.id, 'audit'
        from audit_logs audit
        where :includeAudit = true and audit.tenant_id = :tenantId and audit.company_id = :companyId
          and audit.is_delete = false and audit.entity_type = 'Material' and audit.entity_id = :materialId
        union all
        select event.id, 'INTEGRATION_EVENT', event.code, event.name, event.status, event.created_at,
               event.event_type, event.id, 'integration-events'
        from integration_events event
        where :includeIntegrations = true and event.tenant_id = :tenantId and event.company_id = :companyId
          and event.is_delete = false and event.aggregate_type = 'Material' and event.aggregate_id = :materialId
        """;
    private static final String DIRECT_EVENT_UNION_SQL = """
        select audit.id, 'AUDIT_EVENT' as relationship_type, audit.code as business_code,
               audit.name as display_name, audit.status, coalesce(audit.occurred_at, audit.created_at) as occurred_at,
               audit.action as source_type, audit.id as source_id, 'audit' as module_key
        from audit_logs audit
        where :includeAudit = true and audit.tenant_id = :tenantId and audit.company_id = :companyId
          and audit.is_delete = false and audit.entity_type = 'Material' and audit.entity_id = :materialId
        union all
        select event.id, 'INTEGRATION_EVENT', event.code, event.name, event.status, event.created_at,
               event.event_type, event.id, 'integration-events'
        from integration_events event
        where :includeIntegrations = true and event.tenant_id = :tenantId and event.company_id = :companyId
          and event.is_delete = false and event.aggregate_type = 'Material' and event.aggregate_id = :materialId
        """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public Page<RelationshipRecord> page(
        String tenantId, UUID companyId, UUID materialId, boolean includeDocuments, boolean includeAudit,
        boolean includeIntegrations, RelationshipFilter filter, Pageable pageable
    ) {
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("tenantId", tenantId).addValue("companyId", companyId).addValue("materialId", materialId)
            .addValue("includeDocuments", includeDocuments).addValue("includeAudit", includeAudit)
            .addValue("includeIntegrations", includeIntegrations)
            .addValue("limit", pageable.getPageSize()).addValue("offset", pageable.getOffset());
        String filters = filters(filter, params);
        String selectedUnion = includeDocuments ? UNION_SQL : DIRECT_EVENT_UNION_SQL;
        String from = " from (" + selectedUnion + ") relationships" + filters;
        List<RelationshipRecord> content = jdbcTemplate.query(
            "select *" + from + " order by occurred_at desc nulls last, id desc limit :limit offset :offset",
            params, this::mapRow);
        Long total = jdbcTemplate.queryForObject("select count(*)" + from, params, Long.class);
        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }

    public java.util.Optional<RelationshipRecord> latestIntegrationRisk(
        String tenantId, UUID companyId, UUID materialId
    ) {
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("companyId", companyId)
            .addValue("materialId", materialId);
        return jdbcTemplate.query("""
            select event.id, 'INTEGRATION_EVENT' as relationship_type, event.code as business_code,
                   event.name as display_name, event.status, event.created_at as occurred_at,
                   event.event_type as source_type, event.id as source_id, 'integration-events' as module_key
            from integration_events event
            where event.tenant_id = :tenantId and event.company_id = :companyId
              and event.is_delete = false
              and event.aggregate_type = 'Material' and event.aggregate_id = :materialId
              and event.status in ('FAILED', 'RETRY', 'RETRYING')
            order by event.created_at desc, event.id desc
            limit 1
            """, params, this::mapRow).stream().findFirst();
    }

    private String filters(RelationshipFilter filter, MapSqlParameterSource params) {
        if (filter == null) return "";
        StringBuilder sql = new StringBuilder(" where 1 = 1");
        if (filter.type() != null) {
            sql.append(" and relationship_type = :relationshipType");
            params.addValue("relationshipType", filter.type().name());
        }
        if (filter.status() != null && !filter.status().isBlank()) {
            sql.append(" and status = :status"); params.addValue("status", filter.status().trim());
        }
        if (filter.dateFrom() != null) {
            sql.append(" and occurred_at >= :dateFrom"); params.addValue("dateFrom", filter.dateFrom().atStartOfDay());
        }
        if (filter.dateTo() != null) {
            sql.append(" and occurred_at < :dateTo"); params.addValue("dateTo", filter.dateTo().plusDays(1).atStartOfDay());
        }
        return sql.toString();
    }

    private RelationshipRecord mapRow(ResultSet rs, int rowNumber) throws SQLException {
        return new RelationshipRecord(
            rs.getObject("id", UUID.class), RelationshipType.valueOf(rs.getString("relationship_type")),
            rs.getString("business_code"), rs.getString("display_name"), rs.getString("status"),
            rs.getObject("occurred_at", LocalDateTime.class), rs.getString("source_type"),
            rs.getObject("source_id", UUID.class), rs.getString("module_key"));
    }

    public record RelationshipRecord(
        UUID id, RelationshipType type, String businessCode, String displayName, String status,
        LocalDateTime occurredAt, String sourceType, UUID sourceId, String moduleKey
    ) {
    }
}
