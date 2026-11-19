package com.enterprise.mm.procurement.command.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.procurement.command.payload.ProcurementAnalyticsDtos.CycleStage;
import com.enterprise.mm.procurement.command.payload.ProcurementAnalyticsDtos.CycleTimeResponse;
import com.enterprise.mm.procurement.command.payload.ProcurementAnalyticsDtos.DailyFlowPoint;
import com.enterprise.mm.procurement.command.payload.ProcurementAnalyticsDtos.DailyFlowResponse;
import com.enterprise.mm.procurement.command.payload.ProcurementAnalyticsDtos.OrderStatusResponse;
import com.enterprise.mm.procurement.command.payload.ProcurementAnalyticsDtos.OrderStatusSlice;
import com.enterprise.mm.procurement.command.payload.ProcurementAnalyticsDtos.TopSupplierRow;
import com.enterprise.mm.procurement.command.payload.ProcurementAnalyticsDtos.TopSuppliersResponse;
import com.enterprise.mm.security.AccessScope;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProcurementAnalyticsService {

    private static final List<String> CLOSED_REQUEST_STATUSES = List.of(
        "PO_CREATED",
        "CONVERTED_TO_PO",
        "CANCELLED",
        "REJECTED"
    );

    private static final List<String> EXCLUDED_ORDER_STATUSES = List.of(
        "DRAFT",
        "CANCELLED",
        "REJECTED"
    );

    private final EntityManager entityManager;

    public ProcurementAnalyticsService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public DailyFlowResponse dailyFlow(int days) {
        int window = clamp(days, 7, 120);
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(window - 1L);
        LocalDateTime fromTs = from.atStartOfDay();

        Map<LocalDate, long[]> byDay = new LinkedHashMap<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            byDay.put(day, new long[2]);
        }

        TypedQuery<LocalDateTime> createdQuery = entityManager.createQuery(
            "select p.createdAt from PurchaseRequest p"
                + " where p.tenantId = :tenantId and p.isDeleted = false"
                + scopeClause("p")
                + " and p.createdAt >= :fromTs",
            LocalDateTime.class
        );
        applyScope(createdQuery);
        createdQuery.setParameter("fromTs", fromTs);
        for (LocalDateTime createdAt : createdQuery.getResultList()) {
            long[] counters = byDay.get(createdAt.toLocalDate());
            if (counters != null) {
                counters[0]++;
            }
        }

        TypedQuery<LocalDateTime> closedQuery = entityManager.createQuery(
            "select p.updatedAt from PurchaseRequest p"
                + " where p.tenantId = :tenantId and p.isDeleted = false"
                + scopeClause("p")
                + " and p.status in :closedStatuses"
                + " and p.updatedAt >= :fromTs",
            LocalDateTime.class
        );
        applyScope(closedQuery);
        closedQuery.setParameter("closedStatuses", CLOSED_REQUEST_STATUSES);
        closedQuery.setParameter("fromTs", fromTs);
        for (LocalDateTime closedAt : closedQuery.getResultList()) {
            long[] counters = byDay.get(closedAt.toLocalDate());
            if (counters != null) {
                counters[1]++;
            }
        }

        List<DailyFlowPoint> points = new ArrayList<>(byDay.size());
        byDay.forEach((day, counters) -> points.add(new DailyFlowPoint(day, counters[0], counters[1])));
        return new DailyFlowResponse(from, to, points);
    }

    @Transactional(readOnly = true)
    public CycleTimeResponse cycleTimes(int days) {
        int window = clamp(days, 30, 365);
        LocalDateTime fromTs = LocalDate.now().minusDays(window - 1L).atStartOfDay();

        List<CycleStage> stages = new ArrayList<>(3);
        stages.add(cycleStage(
            "PR_TO_RFQ",
            "select p.createdAt, r.createdAt from RFQ r, PurchaseRequest p"
                + " where r.purchaseRequestId = p.id"
                + " and r.tenantId = :tenantId and r.isDeleted = false"
                + scopeClause("r")
                + " and p.isDeleted = false"
                + " and r.createdAt >= :fromTs",
            fromTs
        ));
        stages.add(cycleStage(
            "RFQ_TO_PO",
            "select r.createdAt, po.createdAt from PurchaseOrder po, RFQ r"
                + " where po.purchaseRequestId = r.purchaseRequestId"
                + " and po.tenantId = :tenantId and po.isDeleted = false"
                + scopeClause("po")
                + " and r.isDeleted = false"
                + " and po.createdAt >= :fromTs",
            fromTs
        ));
        stages.add(cycleStage(
            "PR_TO_PO",
            "select p.createdAt, po.createdAt from PurchaseOrder po, PurchaseRequest p"
                + " where po.purchaseRequestId = p.id"
                + " and po.tenantId = :tenantId and po.isDeleted = false"
                + scopeClause("po")
                + " and p.isDeleted = false"
                + " and po.createdAt >= :fromTs",
            fromTs
        ));
        return new CycleTimeResponse(window, stages);
    }

    @Transactional(readOnly = true)
    public TopSuppliersResponse topSuppliers(int limit) {
        int size = clamp(limit, 1, 20);
        LocalDate to = LocalDate.now();
        LocalDate from = to.withDayOfMonth(1);
        LocalDateTime fromTs = from.atStartOfDay();
        LocalDateTime toTs = LocalDateTime.of(to, LocalTime.MAX);

        TypedQuery<Object[]> query = entityManager.createQuery(
            "select po.supplierId, s.name,"
                + " sum(po.totalAmount * coalesce(po.exchangeRate, 1)), count(po)"
                + " from PurchaseOrder po join po.supplier s"
                + " where po.tenantId = :tenantId and po.isDeleted = false"
                + scopeClause("po")
                + " and po.status not in :excludedStatuses"
                + " and po.createdAt between :fromTs and :toTs"
                + " group by po.supplierId, s.name"
                + " order by sum(po.totalAmount * coalesce(po.exchangeRate, 1)) desc",
            Object[].class
        );
        applyScope(query);
        query.setParameter("excludedStatuses", EXCLUDED_ORDER_STATUSES);
        query.setParameter("fromTs", fromTs);
        query.setParameter("toTs", toTs);
        query.setMaxResults(size);

        List<TopSupplierRow> suppliers = new ArrayList<>();
        for (Object[] row : query.getResultList()) {
            suppliers.add(new TopSupplierRow(
                (UUID) row[0],
                (String) row[1],
                row[2] == null ? BigDecimal.ZERO : (BigDecimal) row[2],
                ((Number) row[3]).longValue()
            ));
        }
        return new TopSuppliersResponse(from, to, "UZS", suppliers);
    }

    @Transactional(readOnly = true)
    public OrderStatusResponse orderStatuses(int days) {
        int window = clamp(days, 7, 365);
        LocalDateTime fromTs = LocalDate.now().minusDays(window - 1L).atStartOfDay();

        TypedQuery<Object[]> query = entityManager.createQuery(
            "select po.status, count(po) from PurchaseOrder po"
                + " where po.tenantId = :tenantId and po.isDeleted = false"
                + scopeClause("po")
                + " and po.status is not null"
                + " and po.createdAt >= :fromTs"
                + " group by po.status"
                + " order by count(po) desc",
            Object[].class
        );
        applyScope(query);
        query.setParameter("fromTs", fromTs);

        long total = 0;
        List<OrderStatusSlice> slices = new ArrayList<>();
        for (Object[] row : query.getResultList()) {
            long count = ((Number) row[1]).longValue();
            total += count;
            slices.add(new OrderStatusSlice((String) row[0], count));
        }
        return new OrderStatusResponse(total, slices);
    }

    private CycleStage cycleStage(String stage, String jpql, LocalDateTime fromTs) {
        TypedQuery<Object[]> query = entityManager.createQuery(jpql, Object[].class);
        applyScope(query);
        query.setParameter("fromTs", fromTs);
        long samples = 0;
        double totalDays = 0;
        for (Object[] row : query.getResultList()) {
            LocalDateTime start = (LocalDateTime) row[0];
            LocalDateTime end = (LocalDateTime) row[1];
            if (start == null || end == null || end.isBefore(start)) {
                continue;
            }
            totalDays += Duration.between(start, end).toMinutes() / (60.0 * 24.0);
            samples++;
        }
        Double avgDays = samples == 0 ? null : Math.round(totalDays / samples * 10.0) / 10.0;
        return new CycleStage(stage, avgDays, samples);
    }

    private String scopeClause(String alias) {
        return AccessScope.companyScoped(true)
            ? " and " + alias + ".companyId = :companyId"
            : "";
    }

    private void applyScope(TypedQuery<?> query) {
        query.setParameter("tenantId", TenantContext.currentTenant());
        if (AccessScope.companyScoped(true)) {
            query.setParameter("companyId", CompanyContext.currentCompany());
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
