package com.enterprise.mm.repository;

import com.enterprise.mm.entity.BudgetTransaction;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BudgetTransactionRepository extends JpaRepository<BudgetTransaction, UUID> {
    List<BudgetTransaction> findByTenantIdAndCompanyIdAndIsDeletedFalse(String tenantId, UUID companyId);
    Optional<BudgetTransaction> findByTenantIdAndCompanyIdAndSourceKeyAndActionKeyAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        String sourceKey,
        String actionKey
    );

    Optional<BudgetTransaction> findFirstByTenantIdAndCompanyIdAndSourceKeyAndTransactionTypeAndIsDeletedFalseOrderByCreatedAtDesc(
        String tenantId,
        UUID companyId,
        String sourceKey,
        String transactionType
    );

    @Query("""
        select coalesce(sum(abs(t.usedDelta)), 0)
        from BudgetTransaction t
        where t.tenantId = :tenantId and t.companyId = :companyId
          and t.reversalOfTransactionId = :originalTransactionId
          and t.transactionType = 'CONSUMPTION_REVERSAL'
          and t.isDeleted = false
        """)
    BigDecimal sumConsumptionReversals(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("originalTransactionId") UUID originalTransactionId
    );

    @Query("""
        select coalesce(sum(abs(t.committedDelta)), 0)
        from BudgetTransaction t
        where t.tenantId = :tenantId and t.companyId = :companyId
          and t.reversalOfTransactionId = :originalTransactionId
          and t.transactionType = 'COMMITMENT_REVERSAL'
          and t.isDeleted = false
        """)
    BigDecimal sumCommitmentReversals(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("originalTransactionId") UUID originalTransactionId
    );

    @Query("""
        select
            coalesce(sum(t.reservedDelta), 0) as reservedDelta,
            coalesce(sum(t.committedDelta), 0) as committedDelta,
            coalesce(sum(t.usedDelta), 0) as usedDelta
        from BudgetTransaction t
        where t.tenantId = :tenantId
            and t.companyId = :companyId
            and t.budgetItemId = :budgetItemId
            and t.isDeleted = false
        """)
    BudgetLedgerTotals sumActiveDeltas(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("budgetItemId") UUID budgetItemId
    );

    long countByTenantIdAndCompanyIdAndSourceIdAndIsDeletedFalse(String tenantId, UUID companyId, UUID sourceId);

    @Query("""
        select coalesce(sum(t.reservedDelta + t.committedDelta), 0)
        from BudgetTransaction t
        where t.tenantId = :tenantId
          and t.companyId = :companyId
          and t.sourceId = :sourceId
          and t.isDeleted = false
        """)
    BigDecimal sumOpenEncumbranceBySourceId(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("sourceId") UUID sourceId
    );

    @Query("""
        select coalesce(sum(t.reservedDelta + t.committedDelta), 0)
        from BudgetTransaction t
        where t.tenantId = :tenantId
          and t.companyId = :companyId
          and t.sourceKey = :sourceKey
          and t.isDeleted = false
        """)
    BigDecimal sumOpenEncumbranceBySourceKey(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("sourceKey") String sourceKey
    );

    interface BudgetLedgerTotals {
        BigDecimal getReservedDelta();
        BigDecimal getCommittedDelta();
        BigDecimal getUsedDelta();
    }
}
