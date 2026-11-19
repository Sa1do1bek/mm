package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.payload.ProcurementMethodStabilizationDtos.ClassificationReport;
import com.enterprise.mm.payload.ProcurementMethodStabilizationDtos.ClassificationRow;
import com.enterprise.mm.payload.ProcurementMethodStabilizationDtos.CapabilityUsageRow;
import com.enterprise.mm.payload.ProcurementMethodStabilizationDtos.MethodUsageReport;
import com.enterprise.mm.payload.ProcurementMethodStabilizationDtos.MethodUsageRow;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProcurementMethodStabilizationService {
    private final EntityManager entityManager;

    @Transactional(readOnly = true)
    public ClassificationReport classifyCurrentCompany() {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        return new ClassificationReport(List.of(
            row("PROCUREMENT_PROCEDURE", "AUCTION_NON_COMPLETED", "REQUIRES_MANUAL_REVIEW",
                count("""
                    select count(*)
                    from procurement_procedures
                    where tenant_id = :tenantId
                      and company_id = :companyId
                      and is_delete = false
                      and method = 'E_AUCTION'
                      and status <> 'COMPLETED'
                    """, tenantId, companyId)),
            row("PROCUREMENT_PROCEDURE", "AUCTION_COMPLETED", "GRANDFATHERED",
                count("""
                    select count(*)
                    from procurement_procedures
                    where tenant_id = :tenantId
                      and company_id = :companyId
                      and is_delete = false
                      and method = 'E_AUCTION'
                      and status = 'COMPLETED'
                    """, tenantId, companyId)),
            row("PROCUREMENT_METHOD_DECISION", "HISTORICAL_METHOD_ALIAS", "AUTO_MIGRATABLE",
                count("""
                    select count(*)
                    from procurement_method_decisions
                    where tenant_id = :tenantId
                      and company_id = :companyId
                      and is_delete = false
                      and selected_method in ('SINGLE_SUPPLIER', 'COMPETITIVE_TENDER', 'SPOT_BUY')
                    """, tenantId, companyId)),
            row("PROCUREMENT_PROCEDURE", "HISTORICAL_METHOD_ALIAS", "AUTO_MIGRATABLE",
                count("""
                    select count(*)
                    from procurement_procedures
                    where tenant_id = :tenantId
                      and company_id = :companyId
                      and is_delete = false
                      and method in ('SINGLE_SUPPLIER', 'COMPETITIVE_TENDER', 'SPOT_BUY')
                    """, tenantId, companyId)),
            row("PROCUREMENT_PROCEDURE", "MISSING_CAPABILITY_SNAPSHOT", "REQUIRES_CONFIGURATION",
                count("""
                    select count(*)
                    from procurement_procedures
                    where tenant_id = :tenantId
                      and company_id = :companyId
                      and is_delete = false
                      and status <> 'COMPLETED'
                      and (capability_state_snapshot is null or capability_version_snapshot is null)
                    """, tenantId, companyId)),
            row("PROCUREMENT_AWARD_OUTCOME", "FRAMEWORK_CALLOFF_WITHOUT_LEDGER", "INVALID",
                count("""
                    select count(*)
                    from procurement_award_outcomes outcome
                    where outcome.tenant_id = :tenantId
                      and outcome.company_id = :companyId
                      and outcome.is_delete = false
                      and outcome.source_type = 'FRAMEWORK_CALLOFF'
                      and outcome.purchase_order_id is not null
                      and not exists (
                          select 1
                          from procurement_framework_ledger ledger
                          where ledger.tenant_id = outcome.tenant_id
                            and ledger.company_id = outcome.company_id
                            and ledger.purchase_order_id = outcome.purchase_order_id
                            and ledger.transaction_type in ('COMMIT', 'CONSUME')
                            and ledger.is_delete = false
                      )
                    """, tenantId, companyId)),
            row("PROCUREMENT_AWARD_OUTCOME", "DEPRECATED_PATH_AWARD", "REQUIRES_MANUAL_REVIEW",
                count("""
                    select count(*)
                    from procurement_award_outcomes outcome
                    where outcome.tenant_id = :tenantId
                      and outcome.company_id = :companyId
                      and outcome.is_delete = false
                      and outcome.source_type = 'MANAGED_AUCTION'
                    """, tenantId, companyId))
        ));
    }

    @Transactional(readOnly = true)
    public MethodUsageReport usageCurrentCompany() {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        return new MethodUsageReport(
            capabilityRows(tenantId, companyId),
            usageRows(tenantId, companyId)
        );
    }

    private ClassificationRow row(String recordType, String issueCode, String classification, long count) {
        return new ClassificationRow(recordType, issueCode, classification, count);
    }

    private long count(String sql, String tenantId, UUID companyId) {
        Number value = (Number) entityManager.createNativeQuery(sql)
            .setParameter("tenantId", tenantId)
            .setParameter("companyId", companyId)
            .getSingleResult();
        return value.longValue();
    }

    private List<CapabilityUsageRow> capabilityRows(String tenantId, UUID companyId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                select method_code,
                       capability_state,
                       effective_from,
                       effective_to,
                       min_amount,
                       max_amount,
                       currency_code,
                       change_reason,
                       configuration_version
                from procurement_method_capabilities
                where tenant_id = :tenantId
                  and company_id = :companyId
                  and is_delete = false
                order by method_code
                """)
            .setParameter("tenantId", tenantId)
            .setParameter("companyId", companyId)
            .getResultList();
        return rows.stream()
            .map(row -> new CapabilityUsageRow(
                text(row[0]),
                text(row[1]),
                localDate(row[2]),
                localDate(row[3]),
                decimal(row[4]),
                decimal(row[5]),
                text(row[6]),
                text(row[7]),
                row[8] == null ? null : ((Number) row[8]).intValue()
            ))
            .toList();
    }

    private List<MethodUsageRow> usageRows(String tenantId, UUID companyId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                with methods as (
                    select method_code from procurement_method_capabilities
                    where tenant_id = :tenantId and company_id = :companyId and is_delete = false
                    union
                    select selected_method from procurement_method_decisions
                    where tenant_id = :tenantId and company_id = :companyId and is_delete = false
                    union
                    select method from procurement_procedures
                    where tenant_id = :tenantId and company_id = :companyId and is_delete = false
                    union
                    select 'DIRECT_PURCHASE'
                    union
                    select 'SINGLE_SOURCE'
                    union
                    select 'FRAME_AGREEMENT'
                )
                select method.method_code,
                       coalesce(decision_counts.total, 0) as method_decision_count,
                       coalesce(procedure_counts.total, 0) as procedure_count,
                       coalesce(outcome_counts.total, 0) as award_outcome_count,
                       coalesce(direct_counts.total, 0) as direct_award_count,
                       coalesce(framework_counts.total, 0) as framework_count,
                       coalesce(framework_ledger_counts.total, 0) as framework_ledger_entry_count
                from methods method
                left join (
                    select selected_method as method_code, count(*) as total
                    from procurement_method_decisions
                    where tenant_id = :tenantId and company_id = :companyId and is_delete = false
                    group by selected_method
                ) decision_counts on decision_counts.method_code = method.method_code
                left join (
                    select method as method_code, count(*) as total
                    from procurement_procedures
                    where tenant_id = :tenantId and company_id = :companyId and is_delete = false
                    group by method
                ) procedure_counts on procedure_counts.method_code = method.method_code
                left join (
                    select case
                             when source_type = 'DIRECT_AWARD' then 'DIRECT_PURCHASE'
                             when source_type in ('FRAMEWORK_AWARD', 'FRAMEWORK_CALLOFF') then 'FRAME_AGREEMENT'
                             when source_type = 'MANAGED_AUCTION' then 'E_AUCTION'
                             else 'REQUEST_FOR_QUOTATION'
                           end as method_code,
                           count(*) as total
                    from procurement_award_outcomes
                    where tenant_id = :tenantId and company_id = :companyId and is_delete = false
                    group by 1
                ) outcome_counts on outcome_counts.method_code = method.method_code
                left join (
                    select method as method_code, count(*) as total
                    from procurement_direct_awards
                    where tenant_id = :tenantId and company_id = :companyId and is_delete = false
                    group by method
                ) direct_counts on direct_counts.method_code = method.method_code
                left join (
                    select 'FRAME_AGREEMENT' as method_code, count(*) as total
                    from procurement_frameworks
                    where tenant_id = :tenantId and company_id = :companyId and is_delete = false
                ) framework_counts on framework_counts.method_code = method.method_code
                left join (
                    select 'FRAME_AGREEMENT' as method_code, count(*) as total
                    from procurement_framework_ledger
                    where tenant_id = :tenantId and company_id = :companyId and is_delete = false
                ) framework_ledger_counts on framework_ledger_counts.method_code = method.method_code
                order by method.method_code
                """)
            .setParameter("tenantId", tenantId)
            .setParameter("companyId", companyId)
            .getResultList();
        return rows.stream()
            .map(row -> new MethodUsageRow(
                text(row[0]),
                number(row[1]),
                number(row[2]),
                number(row[3]),
                number(row[4]),
                number(row[5]),
                number(row[6])
            ))
            .toList();
    }

    private String text(Object value) {
        return value == null ? null : value.toString();
    }

    private long number(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }

    private BigDecimal decimal(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal decimal) return decimal;
        return new BigDecimal(value.toString());
    }

    private LocalDate localDate(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDate localDate) return localDate;
        if (value instanceof Date date) return date.toLocalDate();
        return LocalDate.parse(value.toString());
    }
}
