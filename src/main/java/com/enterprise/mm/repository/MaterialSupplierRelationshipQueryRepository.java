package com.enterprise.mm.repository;

import com.enterprise.mm.payload.MaterialCommandViewDtos.SupplierFilter;
import java.math.BigDecimal;
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
public class MaterialSupplierRelationshipQueryRepository {
    private static final String UNION_SQL = """
        select poi.id, supplier.id as supplier_id, supplier.code as supplier_code, supplier.name as supplier_name,
               'PURCHASE_ORDER' as source_type, po.id as source_id, poi.unit_price as last_price,
               po.currency_code, po.order_date as evidence_date, 'purchase-orders' as module_key
        from purchase_order_items poi
        join purchase_orders po on po.id = poi.purchase_order_id
        join suppliers supplier on supplier.id = po.supplier_id
        where poi.tenant_id = :tenantId and poi.company_id = :companyId
          and po.tenant_id = :tenantId and po.company_id = :companyId
          and supplier.tenant_id = :tenantId and supplier.company_id = :companyId
          and poi.is_delete = false and po.is_delete = false and supplier.is_delete = false
          and poi.line_type = 'MATERIAL' and poi.material_id = :materialId

        union all

        select sqi.id, supplier.id, supplier.code, supplier.name,
               'SUPPLIER_QUOTATION' as source_type, quotation.id as source_id, sqi.unit_price as last_price,
               quotation.currency_code, cast(quotation.submitted_at as date) as evidence_date,
               'suppliers' as module_key
        from supplier_quotation_items sqi
        join supplier_quotations quotation on quotation.id = sqi.quotation_id
        join suppliers supplier on supplier.id = quotation.supplier_id
        where sqi.tenant_id = :tenantId and sqi.company_id = :companyId
          and quotation.tenant_id = :tenantId and quotation.company_id = :companyId
          and supplier.tenant_id = :tenantId and supplier.company_id = :companyId
          and sqi.is_delete = false and quotation.is_delete = false and supplier.is_delete = false
          and sqi.line_type = 'MATERIAL' and sqi.material_id = :materialId
        """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public Page<SupplierEvidenceRecord> page(
        String tenantId,
        UUID companyId,
        UUID materialId,
        SupplierFilter filter,
        Pageable pageable
    ) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("companyId", companyId)
            .addValue("materialId", materialId)
            .addValue("limit", pageable.getPageSize())
            .addValue("offset", pageable.getOffset());
        String filters = filters(filter, parameters);
        String from = " from (" + UNION_SQL + ") evidence" + filters;
        List<SupplierEvidenceRecord> content = jdbcTemplate.query(
            "select *" + from + " order by evidence_date desc nulls last, id desc limit :limit offset :offset",
            parameters,
            this::mapRow);
        Long total = jdbcTemplate.queryForObject("select count(*)" + from, parameters, Long.class);
        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }

    private String filters(SupplierFilter filter, MapSqlParameterSource parameters) {
        if (filter == null) {
            return "";
        }
        StringBuilder sql = new StringBuilder(" where 1 = 1");
        if (filter.sourceType() != null && !filter.sourceType().isBlank()) {
            sql.append(" and source_type = :sourceType");
            parameters.addValue("sourceType", filter.sourceType().trim());
        }
        if (filter.dateFrom() != null) {
            sql.append(" and evidence_date >= :dateFrom");
            parameters.addValue("dateFrom", filter.dateFrom());
        }
        if (filter.dateTo() != null) {
            sql.append(" and evidence_date <= :dateTo");
            parameters.addValue("dateTo", filter.dateTo());
        }
        return sql.toString();
    }

    private SupplierEvidenceRecord mapRow(ResultSet rs, int rowNumber) throws SQLException {
        return new SupplierEvidenceRecord(
            rs.getObject("id", UUID.class), rs.getObject("supplier_id", UUID.class),
            rs.getString("supplier_code"), rs.getString("supplier_name"), rs.getString("source_type"),
            rs.getObject("source_id", UUID.class), rs.getBigDecimal("last_price"), rs.getString("currency_code"),
            rs.getObject("evidence_date", LocalDate.class), rs.getString("module_key"));
    }

    public record SupplierEvidenceRecord(
        UUID id, UUID supplierId, String supplierCode, String supplierName, String sourceType, UUID sourceId,
        BigDecimal lastPrice, String currencyCode, LocalDate evidenceDate, String moduleKey
    ) {
    }
}
