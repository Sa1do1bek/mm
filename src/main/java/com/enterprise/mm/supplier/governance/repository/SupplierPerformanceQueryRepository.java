package com.enterprise.mm.supplier.governance.repository;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.supplier.governance.payload.SupplierGovernanceDtos.MetricDefinition;
import com.enterprise.mm.supplier.governance.payload.SupplierGovernanceDtos.MoneyTotal;
import com.enterprise.mm.supplier.governance.payload.SupplierGovernanceDtos.OperationalPerformance;
import com.enterprise.mm.supplier.governance.payload.SupplierGovernanceDtos.PerformancePeriod;
import com.enterprise.mm.supplier.governance.payload.SupplierGovernanceDtos.ProcurementPerformance;
import com.enterprise.mm.supplier.governance.payload.SupplierGovernanceDtos.SupplierPerformanceSummary;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class SupplierPerformanceQueryRepository {
    @PersistenceContext
    private EntityManager entityManager;

    public SupplierPerformanceSummary summary(PerformanceQuery query) {
        PerformanceQuery safe = query.normalized();
        LocalDateTime from = safe.from().atStartOfDay();
        LocalDateTime toExclusive = safe.to().plusDays(1).atStartOfDay();
        long invitations = count("""
            select count(distinct invitation.rfq_id)
            from rfq_supplier_invitations invitation
            where invitation.tenant_id = :tenantId
              and invitation.company_id = :companyId
              and invitation.supplier_id = :supplierId
              and invitation.is_delete = false
              and invitation.created_at >= :from
              and invitation.created_at < :toExclusive
            """, safe, from, toExclusive);
        long participated = count("""
            select count(distinct quotation.rfq_id)
            from supplier_quotations quotation
            where quotation.tenant_id = :tenantId
              and quotation.company_id = :companyId
              and quotation.supplier_id = :supplierId
              and quotation.is_delete = false
              and quotation.current_revision = true
              and quotation.submitted_at is not null
              and quotation.submitted_at >= :from
              and quotation.submitted_at < :toExclusive
            """, safe, from, toExclusive);
        long quotes = participated;
        long awards = count("""
            select count(distinct quotation.rfq_id)
            from supplier_quotations quotation
            where quotation.tenant_id = :tenantId
              and quotation.company_id = :companyId
              and quotation.supplier_id = :supplierId
              and quotation.is_delete = false
              and quotation.current_revision = true
              and quotation.selected_flag = true
              and coalesce(quotation.submitted_at, quotation.created_at) >= :from
              and coalesce(quotation.submitted_at, quotation.created_at) < :toExclusive
            """, safe, from, toExclusive);
        List<MoneyTotal> money = moneyTotals(safe, from, toExclusive);
        BigDecimal winRate = participated == 0
            ? null
            : BigDecimal.valueOf(awards).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(participated), 2, RoundingMode.HALF_UP);
        ProcurementPerformance procurement = new ProcurementPerformance(
            invitations, participated, quotes, awards, winRate, money, invitations > 0 || participated > 0);

        long evaluated = count("""
            select count(distinct evaluation.id)
            from supplier_evaluations evaluation
            where evaluation.tenant_id = :tenantId
              and evaluation.company_id = :companyId
              and evaluation.supplier_id = :supplierId
              and evaluation.is_delete = false
              and evaluation.evaluated_at >= :from
              and evaluation.evaluated_at < :toExclusive
            """, safe, from, toExclusive);
        long onTime = count("""
            select count(distinct evaluation.id)
            from supplier_evaluations evaluation
            where evaluation.tenant_id = :tenantId
              and evaluation.company_id = :companyId
              and evaluation.supplier_id = :supplierId
              and evaluation.is_delete = false
              and evaluation.late_days <= 0
              and evaluation.evaluated_at >= :from
              and evaluation.evaluated_at < :toExclusive
            """, safe, from, toExclusive);
        BigDecimal onTimePercent = evaluated == 0
            ? null
            : BigDecimal.valueOf(onTime).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(evaluated), 2, RoundingMode.HALF_UP);
        BigDecimal qualityScore = decimal("""
            select avg(evaluation.quality_score)
            from supplier_evaluations evaluation
            where evaluation.tenant_id = :tenantId
              and evaluation.company_id = :companyId
              and evaluation.supplier_id = :supplierId
              and evaluation.is_delete = false
              and evaluation.evaluated_at >= :from
              and evaluation.evaluated_at < :toExclusive
            """, safe, from, toExclusive);
        Long claims = count("""
            select count(distinct claim.id)
            from quality_claims claim
            where claim.tenant_id = :tenantId
              and claim.company_id = :companyId
              and claim.supplier_id = :supplierId
              and claim.is_delete = false
              and claim.created_at >= :from
              and claim.created_at < :toExclusive
            """, safe, from, toExclusive);
        Long returns = count("""
            select count(distinct nc.id)
            from quality_nonconformances nc
            where nc.tenant_id = :tenantId
              and nc.company_id = :companyId
              and nc.supplier_id = :supplierId
              and nc.is_delete = false
              and nc.disposition = 'RETURN_TO_SUPPLIER'
              and nc.created_at >= :from
              and nc.created_at < :toExclusive
            """, safe, from, toExclusive);
        OperationalPerformance operations = new OperationalPerformance(
            onTimePercent, qualityScore, claims, returns, evaluated, evaluated > 0);
        return new SupplierPerformanceSummary(
            new PerformancePeriod(safe.from(), safe.to(), safe.preset()),
            procurement,
            operations,
            definitions(),
            LocalDateTime.now()
        );
    }

    @SuppressWarnings("unchecked")
    private List<MoneyTotal> moneyTotals(PerformanceQuery query, LocalDateTime from, LocalDateTime toExclusive) {
        return entityManager.createNativeQuery("""
            select coalesce(quotation.currency_code, 'UZS') as currency_code,
                   coalesce(sum(quotation.total_amount), 0) as amount
            from supplier_quotations quotation
            where quotation.tenant_id = :tenantId
              and quotation.company_id = :companyId
              and quotation.supplier_id = :supplierId
              and quotation.is_delete = false
              and quotation.current_revision = true
              and quotation.selected_flag = true
              and coalesce(quotation.submitted_at, quotation.created_at) >= :from
              and coalesce(quotation.submitted_at, quotation.created_at) < :toExclusive
            group by coalesce(quotation.currency_code, 'UZS')
            order by coalesce(quotation.currency_code, 'UZS')
            """)
            .setParameter("tenantId", query.tenantId())
            .setParameter("companyId", query.companyId())
            .setParameter("supplierId", query.supplierId())
            .setParameter("from", Timestamp.valueOf(from))
            .setParameter("toExclusive", Timestamp.valueOf(toExclusive))
            .getResultList()
            .stream()
            .map(row -> {
                Object[] values = (Object[]) row;
                return new MoneyTotal((String) values[0], toBigDecimal(values[1]));
            })
            .toList();
    }

    private long count(String sql, PerformanceQuery query, LocalDateTime from, LocalDateTime toExclusive) {
        Number value = (Number) bind(sql, query, from, toExclusive).getSingleResult();
        return value == null ? 0L : value.longValue();
    }

    private BigDecimal decimal(String sql, PerformanceQuery query, LocalDateTime from, LocalDateTime toExclusive) {
        return toBigDecimal(bind(sql, query, from, toExclusive).getSingleResult());
    }

    private jakarta.persistence.Query bind(String sql, PerformanceQuery query, LocalDateTime from, LocalDateTime toExclusive) {
        return entityManager.createNativeQuery(sql)
            .setParameter("tenantId", query.tenantId())
            .setParameter("companyId", query.companyId())
            .setParameter("supplierId", query.supplierId())
            .setParameter("from", Timestamp.valueOf(from))
            .setParameter("toExclusive", Timestamp.valueOf(toExclusive));
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return new BigDecimal(value.toString());
    }

    private List<MetricDefinition> definitions() {
        return List.of(
            new MetricDefinition("WIN_RATE", "PERCENT", "supplier.performance.winRate"),
            new MetricDefinition("ON_TIME_DELIVERY", "PERCENT", "supplier.performance.onTimeDelivery"),
            new MetricDefinition("QUALITY_SCORE", "SCORE", "supplier.performance.qualityScore")
        );
    }

    public record PerformanceQuery(
        String tenantId,
        UUID companyId,
        UUID supplierId,
        LocalDate from,
        LocalDate to,
        String preset,
        List<RequestType> requestTypes,
        UUID materialGroupId
    ) {
        public PerformanceQuery normalized() {
            LocalDate today = LocalDate.now();
            return new PerformanceQuery(
                tenantId == null ? TenantContext.currentTenant() : tenantId,
                companyId == null ? CompanyContext.currentCompany() : companyId,
                supplierId,
                from == null ? today.minusMonths(12) : from,
                to == null ? today : to,
                preset,
                requestTypes == null ? List.of() : List.copyOf(requestTypes),
                materialGroupId
            );
        }
    }
}
