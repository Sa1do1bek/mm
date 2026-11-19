package com.enterprise.mm.repository;

import com.enterprise.mm.payload.MaterialCommandViewDtos.DemandSupplyFilter;
import com.enterprise.mm.payload.MaterialCommandViewDtos.DemandSupplyKind;
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
public class MaterialDemandSupplyQueryRepository {
    private static final String UNION_SQL = """
        select pri.id,
               'PURCHASE_REQUEST_DEMAND' as kind,
               pr.name as name,
               pr.code as document_code,
               pr.status as document_status,
               pri.quantity as open_quantity,
               pri.unit as unit_code,
               pr.required_date as relevant_date,
               cast(null as uuid) as warehouse_id,
               cast(null as varchar) as warehouse_name,
               'purchase-requests' as module_key,
               pr.id as record_id
        from purchase_request_items pri
        join purchase_requests pr on pr.id = pri.purchase_request_id
        where :includePurchaseRequests = true
          and pri.tenant_id = :tenantId and pri.company_id = :companyId
          and pr.tenant_id = :tenantId and pr.company_id = :companyId
          and pri.is_delete = false and pr.is_delete = false
          and pri.line_type = 'MATERIAL' and pri.material_id = :materialId
          and pr.status in (
            'APPROVAL_PENDING', 'APPROVED', 'RFQ_CREATED',
            'PARTIALLY_SENT_TO_TENDER', 'FULLY_SENT_TO_TENDER', 'PO_CREATED')

        union all

        select reservation.id,
               'RESERVATION_DEMAND' as kind,
               reservation.name as name,
               reservation.code as document_code,
               reservation.status as document_status,
               greatest(reservation.reserved_quantity - reservation.released_quantity, 0) as open_quantity,
               cast(null as varchar) as unit_code,
               reservation.required_date as relevant_date,
               reservation.warehouse_id,
               warehouse.name as warehouse_name,
               'material-reservations' as module_key,
               reservation.id as record_id
        from material_reservations reservation
        join warehouses warehouse on warehouse.id = reservation.warehouse_id
        where :includeReservations = true
          and reservation.tenant_id = :tenantId and reservation.company_id = :companyId
          and warehouse.tenant_id = :tenantId and warehouse.company_id = :companyId
          and reservation.is_delete = false and warehouse.is_delete = false
          and reservation.material_id = :materialId
          and reservation.status in ('ACTIVE', 'PARTIALLY_RELEASED')
          and greatest(reservation.reserved_quantity - reservation.released_quantity, 0) > 0

        union all

        select poi.id,
               'PURCHASE_ORDER_SUPPLY' as kind,
               po.name as name,
               po.code as document_code,
               po.status as document_status,
               greatest(poi.quantity - poi.received_quantity, 0) as open_quantity,
               poi.unit as unit_code,
               po.expected_delivery_date as relevant_date,
               poi.receipt_warehouse_id as warehouse_id,
               warehouse.name as warehouse_name,
               'purchase-orders' as module_key,
               po.id as record_id
        from purchase_order_items poi
        join purchase_orders po on po.id = poi.purchase_order_id
        left join warehouses warehouse
          on warehouse.id = poi.receipt_warehouse_id
         and warehouse.tenant_id = :tenantId
         and warehouse.company_id = :companyId
         and warehouse.is_delete = false
        where :includePurchaseOrders = true
          and poi.tenant_id = :tenantId and poi.company_id = :companyId
          and po.tenant_id = :tenantId and po.company_id = :companyId
          and poi.is_delete = false and po.is_delete = false
          and poi.line_type = 'MATERIAL' and poi.material_id = :materialId
          and po.status in ('APPROVED', 'SENT', 'PARTIALLY_RECEIVED', 'PARTIALLY_COMPLETED')
          and greatest(poi.quantity - poi.received_quantity, 0) > 0
        """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    // Resolve the document's unit first. Stock UOM is a fallback only when the document
    // has no unit or explicitly uses that same unit, so quantities are never mislabeled.
    private static final String UNIT_SHORT_NAME_SQL = """
        coalesce(
            (select case when count(distinct nullif(trim(u.measurement_name_short), '')) = 1
                         then min(nullif(trim(u.measurement_name_short), '')) end
             from unit_of_measures u
             where u.tenant_id = :tenantId and u.company_id = :companyId
               and u.is_delete = false and u.code = timeline.unit_code),
            (select nullif(trim(u.measurement_name_short), '')
             from materials m
             join unit_of_measures u on u.id = m.unit_of_measure_id
               and u.tenant_id = :tenantId and u.company_id = :companyId and u.is_delete = false
             where m.id = :materialId and m.tenant_id = :tenantId and m.company_id = :companyId
               and m.is_delete = false
               and (nullif(trim(timeline.unit_code), '') is null
                    or timeline.unit_code in (u.code, u.measurement_name_short, u.measurement_name_long)))
        ) as unit_short_name
        """;

    public Page<DemandSupplyRecord> page(
        String tenantId,
        UUID companyId,
        UUID materialId,
        boolean includePurchaseRequests,
        boolean includeReservations,
        boolean includePurchaseOrders,
        DemandSupplyFilter filter,
        Pageable pageable
    ) {
        MapSqlParameterSource parameters = baseParameters(
            tenantId,
            companyId,
            materialId,
            includePurchaseRequests,
            includeReservations,
            includePurchaseOrders);
        String filterSql = filterSql(filter, parameters);
        parameters.addValue("limit", pageable.getPageSize());
        parameters.addValue("offset", pageable.getOffset());

        String pageSql = "select timeline.*, " + UNIT_SHORT_NAME_SQL + " from (" + UNION_SQL + ") timeline"
            + filterSql
            + " order by relevant_date desc nulls last, id desc limit :limit offset :offset";
        String countSql = "select count(*) from (" + UNION_SQL + ") timeline" + filterSql;

        List<DemandSupplyRecord> content = jdbcTemplate.query(pageSql, parameters, this::mapRow);
        Long total = jdbcTemplate.queryForObject(countSql, parameters, Long.class);
        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }

    public boolean overdueSupplyExists(
        String tenantId,
        UUID companyId,
        UUID materialId,
        UUID warehouseId,
        LocalDate asOfDate
    ) {
        String warehousePredicate = warehouseId == null ? "" : "  and poi.receipt_warehouse_id = :warehouseId\n";
        String sql = """
            select 1
            from purchase_order_items poi
            join purchase_orders po on po.id = poi.purchase_order_id
            where poi.tenant_id = :tenantId and poi.company_id = :companyId
              and po.tenant_id = :tenantId and po.company_id = :companyId
              and poi.is_delete = false and po.is_delete = false
              and poi.line_type = 'MATERIAL' and poi.material_id = :materialId
              and po.status in ('APPROVED', 'SENT', 'PARTIALLY_RECEIVED', 'PARTIALLY_COMPLETED')
              and greatest(poi.quantity - poi.received_quantity, 0) > 0
              and po.expected_delivery_date < :asOfDate
            """
            + warehousePredicate
            + """
            limit 1
            """;
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("companyId", companyId)
            .addValue("materialId", materialId)
            .addValue("warehouseId", warehouseId)
            .addValue("asOfDate", asOfDate);
        Boolean exists = jdbcTemplate.query(sql, parameters,
            (org.springframework.jdbc.core.ResultSetExtractor<Boolean>) resultSet -> resultSet.next());
        return Boolean.TRUE.equals(exists);
    }

    public DemandSupplyTotals totals(
        String tenantId, UUID companyId, UUID materialId, boolean includePurchaseRequests,
        boolean includeReservations, boolean includePurchaseOrders, UUID warehouseId
    ) {
        String warehouseFilter = warehouseId == null ? "" : " where warehouse_id = :warehouseId";
        String sql = "select "
            + "coalesce(sum(case when kind in ('PURCHASE_REQUEST_DEMAND','RESERVATION_DEMAND') then open_quantity else 0 end),0) as open_demand, "
            + "coalesce(sum(case when kind = 'PURCHASE_ORDER_SUPPLY' then open_quantity else 0 end),0) as open_supply "
            + "from (" + UNION_SQL + ") timeline" + warehouseFilter;
        MapSqlParameterSource parameters = baseParameters(tenantId, companyId, materialId,
            includePurchaseRequests, includeReservations, includePurchaseOrders).addValue("warehouseId", warehouseId);
        return jdbcTemplate.queryForObject(sql, parameters, (rs, row) -> new DemandSupplyTotals(
            rs.getBigDecimal("open_demand"), rs.getBigDecimal("open_supply")));
    }

    private MapSqlParameterSource baseParameters(
        String tenantId,
        UUID companyId,
        UUID materialId,
        boolean includePurchaseRequests,
        boolean includeReservations,
        boolean includePurchaseOrders
    ) {
        return new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("companyId", companyId)
            .addValue("materialId", materialId)
            .addValue("includePurchaseRequests", includePurchaseRequests)
            .addValue("includeReservations", includeReservations)
            .addValue("includePurchaseOrders", includePurchaseOrders);
    }

    private String filterSql(DemandSupplyFilter filter, MapSqlParameterSource parameters) {
        if (filter == null) {
            return "";
        }
        StringBuilder sql = new StringBuilder(" where 1 = 1");
        if (filter.dateFrom() != null) {
            sql.append(" and relevant_date >= :dateFrom");
            parameters.addValue("dateFrom", filter.dateFrom());
        }
        if (filter.dateTo() != null) {
            sql.append(" and relevant_date <= :dateTo");
            parameters.addValue("dateTo", filter.dateTo());
        }
        if (filter.warehouseId() != null) {
            sql.append(" and warehouse_id = :warehouseId");
            parameters.addValue("warehouseId", filter.warehouseId());
        }
        if (filter.status() != null && !filter.status().isBlank()) {
            sql.append(" and document_status = :status");
            parameters.addValue("status", filter.status().trim());
        }
        return sql.toString();
    }

    private DemandSupplyRecord mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
        return new DemandSupplyRecord(
            resultSet.getObject("id", UUID.class),
            DemandSupplyKind.valueOf(resultSet.getString("kind")),
                resultSet.getString("name"),
            resultSet.getString("document_code"),
            resultSet.getString("document_status"),
            resultSet.getBigDecimal("open_quantity"),
            resultSet.getString("unit_code"),
            resultSet.getString("unit_short_name"),
            resultSet.getObject("relevant_date", LocalDate.class),
            resultSet.getObject("warehouse_id", UUID.class),
            resultSet.getString("warehouse_name"),
            resultSet.getString("module_key"),
            resultSet.getObject("record_id", UUID.class));
    }

    public record DemandSupplyRecord(
        UUID id,
        DemandSupplyKind kind,
        String name,
        String documentCode,
        String documentStatus,
        BigDecimal openQuantity,
        String unitOfMeasureCode,
        String unitOfMeasureShortName,
        LocalDate relevantDate,
        UUID warehouseId,
        String warehouseName,
        String moduleKey,
        UUID recordId
    ) {
    }

    public record DemandSupplyTotals(BigDecimal openDemand, BigDecimal openSupply) {
    }
}
