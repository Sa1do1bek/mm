package com.enterprise.mm.repository;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.hibernate.query.NativeQuery;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

/**
 * Stock risk alerts are fully computed inside the database (PostgreSQL in production):
 * aggregation (balances, consumption) -> classification (severity) -> filter -> sort -> LIMIT/OFFSET.
 * Only one page of rows ever reaches the JVM.
 *
 * Severity ranks (must match StockRiskAlertService#severityFromRank):
 * 4 = CRITICAL, 3 = HIGH, 2 = MEDIUM, 1 = LOW, 0 = OK
 */
@Repository
public class StockRiskAlertQueryRepository {

    // stock_movements columns, checked against the StockMovement entity:
    // movementType -> movement_type, quantity -> quantity, postedAt -> posted_at (default naming strategy,
    // the same one that produces movement_type, which StockMovementRepository's native queries confirm).
    private static final String MOVEMENT_TYPE_COLUMN = "movement_type";
    private static final String MOVEMENT_DATE_COLUMN = "posted_at";
    private static final String MOVEMENT_QTY_COLUMN = "quantity";

    /** API property name -> SQL expression. Whitelist: nothing from the client is ever concatenated into SQL. */
    private static final Map<String, String> SORT_EXPRESSIONS = Map.of(
            "severity", "severity_rank",
            "daysUntilMinStock", "days_until_min_stock",
            "daysUntilOutOfStock", "days_until_out_of_stock",
            "availableQuantity", "available",
            "averageDailyUsage", "avg_daily_usage",
            "materialCode", "LOWER(material_code)",
            "materialName", "LOWER(material_name)"
    );
    public static final Set<String> SORTABLE_PROPERTIES = SORT_EXPRESSIONS.keySet();

    private static final String DEFAULT_ORDER =
            "severity_rank DESC, days_until_min_stock ASC NULLS LAST, LOWER(material_code) ASC NULLS LAST";

    /**
     * Runs through the JPA persistence context on purpose: the query shares the surrounding transaction and
     * connection with every JPA write and is auto-flushed first, so it always sees what the same
     * transaction has just saved.
     */
    @PersistenceContext
    private EntityManager entityManager;

    public record Criteria(
            String tenantId,
            UUID companyId,
            UUID warehouseId,
            UUID materialId,
            String searchPattern,
            Set<Integer> severityRanks,
            boolean includeHealthy,
            int periodDays,
            LocalDateTime from,
            Collection<String> movementStatuses,
            Collection<String> movementTypes,
            int highRiskDays,
            int mediumRiskDays
    ) {
    }

    public record Row(
            UUID materialId,
            String materialCode,
            String materialName,
            UUID unitOfMeasureId,
            BigDecimal available,
            BigDecimal minStock,
            BigDecimal safetyStock,
            BigDecimal reorderPoint,
            BigDecimal consumption,
            BigDecimal averageDailyUsage,
            Integer daysUntilMinStock,
            Integer daysUntilOutOfStock,
            int severityRank
    ) {
    }

    public record PagedRows(List<Row> rows, long total) {
    }

    public PagedRows findPage(Criteria c, Pageable pageable) {
        boolean byWarehouse = c.warehouseId() != null;
        boolean hasHistory = historyTableExists();

        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Collection<?>> listParams = new LinkedHashMap<>();
        params.put("tenantId", c.tenantId());
        params.put("companyId", c.companyId());
        params.put("periodDays", c.periodDays());
        params.put("fromDate", c.from());
        params.put("highRiskDays", c.highRiskDays());
        params.put("mediumRiskDays", c.mediumRiskDays());
        params.put("limit", pageable.getPageSize());
        params.put("offset", pageable.getOffset());
        listParams.put("movementStatuses", c.movementStatuses());
        listParams.put("movementTypes", c.movementTypes());

        StringBuilder materialFilter = new StringBuilder();
        if (c.materialId() == null || byWarehouse) {
            materialFilter.append(" AND m.status = 'ACTIVE'");
        }
        if (c.materialId() != null) {
            materialFilter.append(" AND m.id = :materialId");
            params.put("materialId", c.materialId());
        }
        if (c.searchPattern() != null) {
            materialFilter.append(" AND (LOWER(m.code) LIKE :search OR LOWER(m.name) LIKE :search)");
            params.put("search", c.searchPattern());
        }
        if (byWarehouse) {
            params.put("warehouseId", c.warehouseId());
            materialFilter.append("""

                  AND (EXISTS (SELECT 1 FROM stock_balances wb
                               WHERE wb.tenant_id = :tenantId AND wb.company_id = :companyId
                                 AND wb.warehouse_id = :warehouseId AND wb.material_id = m.id
                                 AND wb.is_delete = false)
                    OR EXISTS (SELECT 1 FROM stock_movements wv
                               WHERE wv.tenant_id = :tenantId AND wv.company_id = :companyId
                                 AND wv.warehouse_id = :warehouseId AND wv.material_id = m.id
                                 AND wv.is_delete = false)
                    %s)""".formatted(hasHistory ? """
                    OR EXISTS (SELECT 1 FROM warehouse_imported_movements rh
                               WHERE rh.tenant_id = :tenantId AND rh.company_id = :companyId
                                 AND rh.warehouse_id = :warehouseId AND rh.material_id = m.id
                                 AND rh.quantity <> 0)
                    """ : ""));
        }
        if (!byWarehouse && c.materialId() == null) {
            // A catalog entry alone is not evidence of stock risk. Keep configured
            // shortages and depleted materials with stock history, even at zero balance.
            materialFilter.append("""

                  AND (COALESCE(m.min_stock, 0) > 0
                    OR COALESCE(m.safety_stock, 0) > 0
                    OR COALESCE(m.reorder_point, 0) > 0
                    OR EXISTS (SELECT 1 FROM stock_balances rb
                               WHERE rb.tenant_id = :tenantId AND rb.company_id = :companyId
                                 AND rb.material_id = m.id AND rb.is_delete = false)
                    OR EXISTS (SELECT 1 FROM stock_movements rv
                               WHERE rv.tenant_id = :tenantId AND rv.company_id = :companyId
                                 AND rv.material_id = m.id AND rv.is_delete = false)
                    %s)""".formatted(hasHistory ? """
                    OR EXISTS (SELECT 1 FROM warehouse_imported_movements rh
                               WHERE rh.tenant_id = :tenantId AND rh.company_id = :companyId
                                 AND rh.material_id = m.id AND rh.quantity <> 0)
                    """ : ""));
        }
        String balanceWarehouse = byWarehouse ? " AND b.warehouse_id = :warehouseId" : "";
        String movementWarehouse = byWarehouse ? " AND v.warehouse_id = :warehouseId" : "";

        List<String> outerFilters = new ArrayList<>();
        if (!c.includeHealthy()) {
            outerFilters.add("severity_rank > 0");
        }
        if (c.severityRanks() != null && !c.severityRanks().isEmpty()) {
            outerFilters.add("severity_rank IN (:severityRanks)");
            listParams.put("severityRanks", c.severityRanks());
        }
        String outerWhere = outerFilters.isEmpty() ? "" : "WHERE " + String.join(" AND ", outerFilters);

        // V9 imports are presentation-only: they are never posted into stock_movements.
        // Negative ISSUE quantities are historical consumption; openings, transfers,
        // returns and write-offs do not belong to the existing ISSUE-only risk policy.
        String historicalConsumption = hasHistory ? """
                UNION ALL
                SELECT h.material_id, -h.quantity AS consumed
                FROM warehouse_imported_movements h
                JOIN scoped_materials sm ON sm.material_id = h.material_id
                WHERE h.tenant_id = :tenantId AND h.company_id = :companyId
                  AND h.movement_type IN (:movementTypes) AND h.quantity < 0
                  AND h.movement_date >= CAST(:fromDate AS date)
                  AND CAST(h.movement_date AS timestamp) >= :fromDate
                """ + (byWarehouse ? " AND h.warehouse_id = :warehouseId" : "") : "";

        String sql = """
            WITH scoped_materials AS (
                SELECT m.id AS material_id, m.code AS material_code, m.name AS material_name,
                       m.unit_of_measure_id,
                       COALESCE(m.min_stock, 0)     AS min_stock,
                       COALESCE(m.safety_stock, 0)  AS safety_stock,
                       COALESCE(m.reorder_point, 0) AS reorder_point
                FROM materials m
                WHERE m.tenant_id = :tenantId
                  AND m.company_id = :companyId
                  AND m.is_delete = false
                  %s
            ),
            balances AS (
                SELECT b.material_id, SUM(b.available_quantity) AS available
                FROM stock_balances b
                JOIN scoped_materials sm ON sm.material_id = b.material_id
                WHERE b.tenant_id = :tenantId
                  AND b.company_id = :companyId
                  AND b.is_delete = false
                  %s
                GROUP BY b.material_id
            ),
            consumption_rows AS (
                SELECT v.material_id, SUM(v.%s) AS consumed
                FROM stock_movements v
                JOIN scoped_materials sm ON sm.material_id = v.material_id
                WHERE v.tenant_id = :tenantId
                  AND v.company_id = :companyId
                  AND v.is_delete = false
                  AND v.status IN (:movementStatuses)
                  AND v.%s IN (:movementTypes)
                  AND v.%s >= :fromDate
                  %s
                GROUP BY v.material_id
                %s
            ),
            consumption_totals AS (
                SELECT material_id, SUM(consumed) AS consumed
                FROM consumption_rows GROUP BY material_id
            ),
            usage_stats AS (
                SELECT sm.*,
                       COALESCE(bl.available, 0) AS available,
                       COALESCE(c.consumed, 0)   AS consumption,
                       CASE WHEN COALESCE(c.consumed, 0) <= 0 THEN 0
                            ELSE ROUND(c.consumed / CAST(:periodDays AS decimal(10, 2)), 4)
                       END AS avg_daily_usage
                FROM scoped_materials sm
                LEFT JOIN balances bl ON bl.material_id = sm.material_id
                LEFT JOIN consumption_totals c ON c.material_id = sm.material_id
            ),
            horizon AS (
                SELECT u.*,
                       CASE WHEN u.avg_daily_usage <= 0 THEN NULL
                            WHEN u.available - u.min_stock <= 0 THEN 0
                            ELSE CAST(CEIL((u.available - u.min_stock) / u.avg_daily_usage) AS integer)
                       END AS days_until_min_stock,
                       CASE WHEN u.avg_daily_usage <= 0 THEN NULL
                            WHEN u.available <= 0 THEN 0
                            ELSE CAST(CEIL(u.available / u.avg_daily_usage) AS integer)
                       END AS days_until_out_of_stock
                FROM usage_stats u
            ),
            classified AS (
                SELECT h.*,
                       CASE
                           WHEN h.available <= h.min_stock THEN 4
                           WHEN h.avg_daily_usage > 0 AND h.days_until_min_stock <= :highRiskDays THEN 3
                           WHEN h.avg_daily_usage > 0 AND h.days_until_min_stock <= :mediumRiskDays THEN 2
                           WHEN h.reorder_point > 0 AND h.available <= h.reorder_point THEN 1
                           ELSE 0
                       END AS severity_rank
                FROM horizon h
            )
            SELECT material_id, material_code, material_name, unit_of_measure_id,
                   available, min_stock, safety_stock, reorder_point,
                   consumption, avg_daily_usage, days_until_min_stock, days_until_out_of_stock,
                   severity_rank,
                   COUNT(*) OVER () AS total_count
            FROM classified
            %s
            ORDER BY %s
            LIMIT :limit OFFSET :offset
            """.formatted(
                materialFilter,
                balanceWarehouse,
                MOVEMENT_QTY_COLUMN,
                MOVEMENT_TYPE_COLUMN,
                MOVEMENT_DATE_COLUMN,
                movementWarehouse,
                historicalConsumption,
                outerWhere,
                buildOrderBy(pageable.getSort())
        );

        Query query = entityManager.createNativeQuery(sql);
        params.forEach((name, value) -> query.setParameter(name, value));
        NativeQuery<?> hibernateQuery = query.unwrap(NativeQuery.class);
        listParams.forEach((name, values) -> hibernateQuery.setParameterList(name, values));

        List<?> raw = query.getResultList();
        List<Row> rows = new ArrayList<>(raw.size());
        long total = 0L;
        for (Object item : raw) {
            Object[] r = (Object[]) item;
            total = ((Number) r[13]).longValue();
            rows.add(new Row(
                    toUuid(r[0]),
                    (String) r[1],
                    (String) r[2],
                    toUuid(r[3]),
                    toDecimal(r[4]),
                    toDecimal(r[5]),
                    toDecimal(r[6]),
                    toDecimal(r[7]),
                    toDecimal(r[8]),
                    toDecimal(r[9]),
                    toInteger(r[10]),
                    toInteger(r[11]),
                    ((Number) r[12]).intValue()
            ));
        }
        if (rows.isEmpty() && pageable.getOffset() > 0) {
            // A window count is absent when OFFSET skips every row. Preserve the real total.
            String countSql = sql.substring(0, sql.indexOf("SELECT material_id, material_code"))
                    + "SELECT COUNT(*) FROM classified " + outerWhere;
            Query countQuery = entityManager.createNativeQuery(countSql);
            params.forEach((name, value) -> {
                if (!name.equals("limit") && !name.equals("offset")) {
                    countQuery.setParameter(name, value);
                }
            });
            NativeQuery<?> nativeCount = countQuery.unwrap(NativeQuery.class);
            listParams.forEach((name, values) -> nativeCount.setParameterList(name, values));
            total = ((Number) countQuery.getSingleResult()).longValue();
        }
        return new PagedRows(rows, total);
    }

    private boolean historyTableExists() {
        // Follow warehouse reports: installations/tests without V9 retain operational-only behavior.
        // Use JDBC metadata so a missing table never aborts the surrounding PostgreSQL transaction.
        return entityManager.unwrap(org.hibernate.Session.class).doReturningWork(connection -> {
            try (var tables = connection.getMetaData().getTables(connection.getCatalog(),
                    connection.getSchema(), "warehouse_imported_movements", new String[] {"TABLE"})) {
                return tables.next();
            }
        });
    }

    private static UUID toUuid(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value instanceof byte[] bytes && bytes.length == 16) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            return new UUID(buffer.getLong(), buffer.getLong());
        }
        return UUID.fromString(value.toString());
    }

    private static BigDecimal toDecimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        return new BigDecimal(value.toString());
    }

    private static Integer toInteger(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    private String buildOrderBy(Sort sort) {
        List<String> parts = new ArrayList<>();
        for (Sort.Order order : sort) {
            String expression = SORT_EXPRESSIONS.get(order.getProperty());
            if (expression == null) {
                throw new IllegalArgumentException("Unsupported sort property: " + order.getProperty());
            }
            parts.add(expression + (order.isAscending() ? " ASC" : " DESC") + " NULLS LAST");
        }
        String base = parts.isEmpty() ? DEFAULT_ORDER : String.join(", ", parts);
        // material_id as the final tie-breaker keeps pagination stable between requests.
        return base + ", material_id";
    }
}
