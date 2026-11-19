package com.enterprise.mm.repository;

import com.enterprise.mm.enums.RfqAggregationMode;
import com.enterprise.mm.payload.RfqAggregationDtos.AggregationPreviewFilter;
import com.enterprise.mm.payload.RfqAggregationDtos.RfqAggregationCandidateRow;
import com.enterprise.mm.payload.RfqAggregationDtos.RfqAggregationDocumentScope;
import com.enterprise.mm.payload.RfqAggregationDtos.RfqAggregationGroupKeyRow;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RfqAggregationQueryRepository {
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public Page<RfqAggregationGroupKeyRow> findGroupPage(
        AggregationPreviewFilter filter,
        RfqAggregationDocumentScope scope,
        Pageable pageable
    ) {
        AggregationPreviewFilter normalized = normalize(filter);
        MapSqlParameterSource params = params(normalized, scope);
        String eligible = eligibleCte(normalized, scope, params);
        String grouped = groupedSelect(normalized.groupBy());
        long total = jdbcTemplate.queryForObject(
            eligible + " select count(distinct group_key) from eligible",
            params,
            Long.class);
        params.addValue("limit", pageable.getPageSize());
        params.addValue("offset", pageable.getOffset());
        List<RfqAggregationGroupKeyRow> content = jdbcTemplate.query(
            eligible + grouped + " order by earliest_required_date, group_key limit :limit offset :offset",
            params,
            groupRowMapper(normalized.groupBy()));
        return new PageImpl<>(content, pageable, total);
    }

    public List<RfqAggregationCandidateRow> findCandidates(
        AggregationPreviewFilter filter,
        RfqAggregationDocumentScope scope,
        List<RfqAggregationGroupKeyRow> groups
    ) {
        if (groups == null || groups.isEmpty()) {
            return List.of();
        }
        AggregationPreviewFilter normalized = normalize(filter);
        MapSqlParameterSource params = params(normalized, scope);
        List<String> groupKeys = groups.stream().map(RfqAggregationGroupKeyRow::groupKey).toList();
        params.addValue("groupKeys", groupKeys);
        String sql = eligibleCte(normalized, scope, params)
            + " select * from eligible where group_key in (:groupKeys)"
            + " order by purchase_request_code, purchase_request_item_code, cast(purchase_request_item_id as varchar)";
        return jdbcTemplate.query(sql, params, candidateRowMapper());
    }

    private AggregationPreviewFilter normalize(AggregationPreviewFilter filter) {
        RfqAggregationMode groupBy = filter == null || filter.groupBy() == null
            ? RfqAggregationMode.MATERIAL
            : filter.groupBy();
        return new AggregationPreviewFilter(
            groupBy,
            filter == null ? null : filter.materialId(),
            filter == null ? null : filter.materialGroupId(),
            filter == null ? null : filter.departmentId(),
            filter == null ? null : filter.requiredDateFrom(),
            filter == null ? null : filter.requiredDateTo(),
            blankToNull(filter == null ? null : filter.search()));
    }

    private MapSqlParameterSource params(AggregationPreviewFilter filter, RfqAggregationDocumentScope scope) {
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("tenantId", scope.tenantId())
            .addValue("companyId", scope.companyId())
            .addValue("materialId", filter.materialId())
            .addValue("materialGroupId", filter.materialGroupId())
            .addValue("departmentId", filter.departmentId())
            .addValue("requiredDateFrom", filter.requiredDateFrom())
            .addValue("requiredDateTo", filter.requiredDateTo())
            .addValue("search", filter.search() == null ? null : "%" + filter.search().toLowerCase(java.util.Locale.ROOT) + "%")
            .addValue("ownPrincipals", new ArrayList<>(scope.ownPrincipals()))
            .addValue("teamPrincipals", new ArrayList<>(scope.teamPrincipals()))
            .addValue("allowedDepartmentIds", new ArrayList<>(scope.allowedDepartmentIds()));
        return params;
    }

    private String eligibleCte(
        AggregationPreviewFilter filter,
        RfqAggregationDocumentScope scope,
        MapSqlParameterSource params
    ) {
        String unitExpression = "coalesce(nullif(trim(pri.unit), ''), nullif(trim(uom.code), ''), nullif(trim(uom.measurement_name_short), ''))";
        String groupKey = filter.groupBy() == RfqAggregationMode.MATERIAL_GROUP
            ? "'MATERIAL_GROUP|' || cast(pr.company_id as varchar) || '|' || upper(coalesce(pr.currency_code, '')) || '|' || cast(m.material_group_id as varchar)"
            : "'MATERIAL|' || cast(pr.company_id as varchar) || '|' || upper(coalesce(pr.currency_code, '')) || '|' || cast(pri.material_id as varchar) || '|' || upper(" + unitExpression + ")";
        StringBuilder where = new StringBuilder("""
            where pri.tenant_id = :tenantId
              and pr.tenant_id = :tenantId
              and m.tenant_id = :tenantId
              and pri.company_id = :companyId
              and pr.company_id = :companyId
              and m.company_id = :companyId
              and pri.is_delete = false
              and pr.is_delete = false
              and m.is_delete = false
              and pr.status in ('APPROVED', 'PARTIALLY_SENT_TO_TENDER')
              and pr.request_type = 'GOODS'
              and pri.line_type = 'MATERIAL'
              and pri.quantity > 0
              and m.status = 'ACTIVE'
              and %s is not null
              and not exists (
                  select 1
                  from rfq_purchase_request_sources src
                  where src.tenant_id = pri.tenant_id
                    and src.company_id = pri.company_id
                    and src.purchase_request_item_id = pri.id
                    and src.status = 'ACTIVE'
                    and src.is_delete = false
              )
            """.formatted(unitExpression));
        if (filter.groupBy() == RfqAggregationMode.MATERIAL_GROUP) {
            where.append(" and m.material_group_id is not null\n");
        }
        if (filter.materialId() != null) {
            where.append(" and pri.material_id = :materialId\n");
        }
        if (filter.materialGroupId() != null) {
            where.append(" and m.material_group_id = :materialGroupId\n");
        }
        if (filter.departmentId() != null) {
            where.append(" and pr.department_id = :departmentId\n");
        }
        if (filter.requiredDateFrom() != null) {
            where.append(" and coalesce(pri.required_date, pr.required_date) >= :requiredDateFrom\n");
        }
        if (filter.requiredDateTo() != null) {
            where.append(" and coalesce(pri.required_date, pr.required_date) <= :requiredDateTo\n");
        }
        if (filter.search() != null) {
            where.append("""
                 and (
                    lower(coalesce(pr.code, '')) like :search
                    or lower(coalesce(pr.name, '')) like :search
                    or lower(coalesce(pri.code, '')) like :search
                    or lower(coalesce(pri.name, '')) like :search
                    or lower(coalesce(m.code, '')) like :search
                    or lower(coalesce(m.name, '')) like :search
                 )
                """);
        }
        if (scope.documentFilteringRequired()) {
            List<String> clauses = new ArrayList<>();
            if (!scope.ownPrincipals().isEmpty()) {
                clauses.add("lower(coalesce(pr.created_by, '')) in (:ownPrincipals)");
                clauses.add("lower(coalesce(pr.requested_by, '')) in (:ownPrincipals)");
            }
            if (!scope.teamPrincipals().isEmpty()) {
                clauses.add("lower(coalesce(pr.created_by, '')) in (:teamPrincipals)");
                clauses.add("lower(coalesce(pr.requested_by, '')) in (:teamPrincipals)");
            }
            if (!scope.allowedDepartmentIds().isEmpty()) {
                clauses.add("pr.department_id in (:allowedDepartmentIds)");
            }
            where.append(clauses.isEmpty() ? " and 1 = 0\n" : " and (" + String.join(" or ", clauses) + ")\n");
        }
        return """
            with eligible as (
                select
                    %s as group_key,
                    pr.id as purchase_request_id,
                    pri.id as purchase_request_item_id,
                    pr.code as purchase_request_code,
                    pr.name as purchase_request_name,
                    pri.code as purchase_request_item_code,
                    pr.company_id as company_id,
                    upper(coalesce(pr.currency_code, '')) as currency_code,
                    pr.department_id as department_id,
                    dep.code as department_code,
                    dep.name as department_name,
                    pri.budget_item_id as budget_item_id,
                    bi.code as budget_item_code,
                    bi.name as budget_item_name,
                    coalesce(pri.required_date, pr.required_date) as required_date,
                    pri.material_id as material_id,
                    m.code as material_code,
                    m.name as material_name,
                    m.material_group_id as material_group_id,
                    mg.code as material_group_code,
                    mg.name as material_group_name,
                    %s as unit,
                    upper(%s) as normalized_unit_key,
                    pri.quantity as quantity,
                    coalesce(pri.estimated_price, 0) as estimated_price,
                    pri.quantity * coalesce(pri.estimated_price, 0) as estimated_amount
                from purchase_request_items pri
                join purchase_requests pr on pr.id = pri.purchase_request_id
                join materials m on m.id = pri.material_id
                left join unit_of_measures uom on uom.id = m.unit_of_measure_id
                left join material_groups mg on mg.id = m.material_group_id
                left join departments dep on dep.id = pr.department_id
                left join budget_items bi on bi.id = pri.budget_item_id
                %s
            )
            """.formatted(groupKey, unitExpression, unitExpression, where);
    }

    private String groupedSelect(RfqAggregationMode groupBy) {
        if (groupBy == RfqAggregationMode.MATERIAL_GROUP) {
            return """
                select
                    group_key,
                    company_id,
                    currency_code,
                    cast(null as uuid) as material_id,
                    cast(null as varchar) as material_code,
                    cast(null as varchar) as material_name,
                    cast(null as varchar) as unit,
                    cast(null as varchar) as normalized_unit_key,
                    material_group_id,
                    material_group_code,
                    material_group_name,
                    sum(quantity) as total_quantity,
                    sum(estimated_amount) as estimated_amount,
                    count(distinct purchase_request_id) as purchase_request_count,
                    count(*) as item_count,
                    min(required_date) as earliest_required_date,
                    max(required_date) as latest_required_date
                from eligible
                group by group_key, company_id, currency_code, material_group_id, material_group_code, material_group_name
                """;
        }
        return """
            select
                group_key,
                company_id,
                currency_code,
                material_id,
                material_code,
                material_name,
                unit,
                normalized_unit_key,
                material_group_id,
                material_group_code,
                material_group_name,
                sum(quantity) as total_quantity,
                sum(estimated_amount) as estimated_amount,
                count(distinct purchase_request_id) as purchase_request_count,
                count(*) as item_count,
                min(required_date) as earliest_required_date,
                max(required_date) as latest_required_date
            from eligible
            group by group_key, company_id, currency_code, material_id, material_code, material_name, unit, normalized_unit_key, material_group_id, material_group_code, material_group_name
            """;
    }

    private RowMapper<RfqAggregationGroupKeyRow> groupRowMapper(RfqAggregationMode groupBy) {
        return (rs, rowNum) -> new RfqAggregationGroupKeyRow(
            rs.getString("group_key"),
            groupBy,
            uuid(rs, "company_id"),
            rs.getString("currency_code"),
            uuid(rs, "material_id"),
            rs.getString("material_code"),
            rs.getString("material_name"),
            rs.getString("unit"),
            rs.getString("normalized_unit_key"),
            uuid(rs, "material_group_id"),
            rs.getString("material_group_code"),
            rs.getString("material_group_name"),
            rs.getBigDecimal("total_quantity"),
            rs.getBigDecimal("estimated_amount"),
            rs.getLong("purchase_request_count"),
            rs.getLong("item_count"),
            localDate(rs, "earliest_required_date"),
            localDate(rs, "latest_required_date"));
    }

    private RowMapper<RfqAggregationCandidateRow> candidateRowMapper() {
        return (rs, rowNum) -> new RfqAggregationCandidateRow(
            rs.getString("group_key"),
            uuid(rs, "purchase_request_id"),
            uuid(rs, "purchase_request_item_id"),
            rs.getString("purchase_request_code"),
            rs.getString("purchase_request_name"),
            rs.getString("purchase_request_item_code"),
            uuid(rs, "company_id"),
            rs.getString("currency_code"),
            uuid(rs, "department_id"),
            rs.getString("department_code"),
            rs.getString("department_name"),
            uuid(rs, "budget_item_id"),
            rs.getString("budget_item_code"),
            rs.getString("budget_item_name"),
            localDate(rs, "required_date"),
            uuid(rs, "material_id"),
            rs.getString("material_code"),
            rs.getString("material_name"),
            uuid(rs, "material_group_id"),
            rs.getString("material_group_code"),
            rs.getString("material_group_name"),
            rs.getString("unit"),
            rs.getString("normalized_unit_key"),
            valueOrZero(rs.getBigDecimal("quantity")),
            valueOrZero(rs.getBigDecimal("estimated_price")));
    }

    private UUID uuid(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) {
            return null;
        }
        return value instanceof UUID uuid ? uuid : UUID.fromString(value.toString());
    }

    private LocalDate localDate(ResultSet rs, String column) throws SQLException {
        java.sql.Date value = rs.getDate(column);
        return value == null ? null : value.toLocalDate();
    }

    private BigDecimal valueOrZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
