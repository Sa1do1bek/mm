package com.enterprise.mm.repository;

import com.enterprise.mm.payload.MaterialCommandViewDtos.QualityCompatibilityFilter;
import com.enterprise.mm.payload.MaterialCommandViewDtos.QualityCompatibilityKind;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
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
public class MaterialQualityCompatibilityQueryRepository {
    private static final String UNION_SQL = """
        select issue.id, 'QUALITY_ISSUE' as kind, issue.status, issue.name as display_name,
               coalesce(issue.description, issue.issue_type) as detail,
               cast(null as date) as effective_from, cast(null as date) as effective_to,
               'material-quality-issues' as module_key, issue.id as record_id,
               coalesce(issue.updated_at, issue.created_at) as sort_at
        from material_quality_issues issue
        where issue.tenant_id = :tenantId and issue.company_id = :companyId
          and issue.is_delete = false and issue.material_id = :materialId
        union all
        select analog.id, 'ANALOGUE', analog.status, analog_material.name,
               analog.analog_type, analog.valid_from, analog.valid_to,
               'material-analogs', analog.id,
               coalesce(cast(analog.valid_from as timestamp), analog.updated_at, analog.created_at)
        from material_analogs analog
        join materials analog_material on analog_material.id = analog.analog_material_id
        where analog.tenant_id = :tenantId and analog.company_id = :companyId
          and analog_material.tenant_id = :tenantId and analog_material.company_id = :companyId
          and analog.is_delete = false and analog_material.is_delete = false and analog.material_id = :materialId
        union all
        select compatibility.id, 'EQUIPMENT_COMPATIBILITY', compatibility.status, equipment.name,
               compatibility.compatibility_type, compatibility.valid_from, compatibility.valid_to,
               'material-equipment-compatibilities', compatibility.id,
               coalesce(cast(compatibility.valid_from as timestamp), compatibility.updated_at, compatibility.created_at)
        from material_equipment_compatibilities compatibility
        join equipment on equipment.id = compatibility.equipment_id
        where compatibility.tenant_id = :tenantId and compatibility.company_id = :companyId
          and equipment.tenant_id = :tenantId and equipment.company_id = :companyId
          and compatibility.is_delete = false and equipment.is_delete = false
          and compatibility.material_id = :materialId
        union all
        select norm.id, 'CONSUMPTION_NORM', norm.status, norm.name, norm.norm_type,
               norm.effective_from, norm.effective_to, 'material-consumption-norms', norm.id
               , coalesce(cast(norm.effective_from as timestamp), norm.updated_at, norm.created_at)
        from material_consumption_norms norm
        where norm.tenant_id = :tenantId and norm.company_id = :companyId
          and norm.is_delete = false and norm.material_id = :materialId
        union all
        select material.id, 'REPLACEMENT', material.status, replacement.name, material.lifecycle_reason,
               cast(null as date), cast(null as date), 'materials', replacement.id,
               coalesce(material.updated_at, material.created_at)
        from materials material
        join materials replacement on replacement.id = material.replacement_material_id
        where material.tenant_id = :tenantId and material.company_id = :companyId
          and replacement.tenant_id = :tenantId and replacement.company_id = :companyId
          and material.is_delete = false and replacement.is_delete = false and material.id = :materialId
        """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public boolean openIssueExists(String tenantId, UUID companyId, UUID materialId) {
        Boolean exists = jdbcTemplate.queryForObject("""
            select exists (
                select 1 from material_quality_issues issue
                where issue.tenant_id = :tenantId
                  and issue.company_id = :companyId
                  and issue.material_id = :materialId
                  and issue.is_delete = false
                  and upper(coalesce(issue.status, '')) not in ('CLOSED', 'CANCELLED', 'RESOLVED')
            )
            """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("companyId", companyId)
                .addValue("materialId", materialId), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    public Page<QualityEvidenceRecord> page(
        String tenantId, UUID companyId, UUID materialId, QualityCompatibilityFilter filter, Pageable pageable
    ) {
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("tenantId", tenantId).addValue("companyId", companyId).addValue("materialId", materialId)
            .addValue("limit", pageable.getPageSize()).addValue("offset", pageable.getOffset());
        String filters = filters(filter, params);
        String from = " from (" + UNION_SQL + ") evidence" + filters;
        List<QualityEvidenceRecord> content = jdbcTemplate.query(
            "select *" + from + " order by sort_at desc nulls last, id desc limit :limit offset :offset",
            params, this::mapRow);
        Long total = jdbcTemplate.queryForObject("select count(*)" + from, params, Long.class);
        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }

    private String filters(QualityCompatibilityFilter filter, MapSqlParameterSource params) {
        if (filter == null) return "";
        StringBuilder sql = new StringBuilder(" where 1 = 1");
        if (filter.kind() != null) {
            sql.append(" and kind = :kind"); params.addValue("kind", filter.kind().name());
        }
        if (filter.status() != null && !filter.status().isBlank()) {
            sql.append(" and status = :status"); params.addValue("status", filter.status().trim());
        }
        return sql.toString();
    }

    private QualityEvidenceRecord mapRow(ResultSet rs, int rowNumber) throws SQLException {
        return new QualityEvidenceRecord(
            rs.getObject("id", UUID.class), QualityCompatibilityKind.valueOf(rs.getString("kind")),
            rs.getString("status"), rs.getString("display_name"), rs.getString("detail"),
            rs.getObject("effective_from", LocalDate.class), rs.getObject("effective_to", LocalDate.class),
            rs.getString("module_key"), rs.getObject("record_id", UUID.class));
    }

    public record QualityEvidenceRecord(
        UUID id, QualityCompatibilityKind kind, String status, String displayName, String detail,
        LocalDate effectiveFrom, LocalDate effectiveTo, String moduleKey, UUID recordId
    ) {
    }
}
