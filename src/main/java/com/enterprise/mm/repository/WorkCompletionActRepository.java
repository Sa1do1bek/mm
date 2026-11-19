package com.enterprise.mm.repository;

import com.enterprise.mm.entity.WorkCompletionAct;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkCompletionActRepository extends JpaRepository<WorkCompletionAct, UUID>, JpaSpecificationExecutor<WorkCompletionAct> {
    Optional<WorkCompletionAct> findAnyById(UUID id);
    Optional<WorkCompletionAct> findByIdAndIsDeletedFalse(UUID id);

    List<WorkCompletionAct> findByTenantIdAndCompanyIdAndPurchaseOrderItemIdInAndStatusInAndIsDeletedFalseOrderByCreatedAtAsc(
        String tenantId, UUID companyId, List<UUID> purchaseOrderItemIds, List<String> statuses);

    List<WorkCompletionAct> findByTenantIdAndPurchaseOrderIdAndStatusInAndIsDeletedFalseOrderByCreatedAtAsc(
        String tenantId, UUID purchaseOrderId, List<String> statuses);
    List<WorkCompletionAct> findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByCreatedAtDesc(
        String tenantId, UUID companyId, UUID purchaseOrderId);
    List<WorkCompletionAct> findByTenantIdAndCompanyIdAndPurchaseOrderItemIdAndIsDeletedFalseOrderByCreatedAtDesc(
        String tenantId, UUID companyId, UUID purchaseOrderItemId);

    List<WorkCompletionAct> findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByCreatedAtAsc(
        String tenantId, UUID companyId, UUID purchaseOrderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from WorkCompletionAct e where e.id = :id and e.isDeleted = false")
    Optional<WorkCompletionAct> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
        select coalesce(sum(act.completedQuantity), 0)
        from WorkCompletionAct act
        where act.tenantId = :tenantId
          and act.purchaseOrderItemId = :purchaseOrderItemId
          and act.isDeleted = false
          and act.status in :statuses
          and (:excludeId is null or act.id <> :excludeId)
        """)
    BigDecimal cumulativeQuantity(
        @Param("tenantId") String tenantId,
        @Param("purchaseOrderItemId") UUID purchaseOrderItemId,
        @Param("statuses") List<String> statuses,
        @Param("excludeId") UUID excludeId
    );

    @Query("""
        select coalesce(sum(act.amount), 0)
        from WorkCompletionAct act
        where act.tenantId = :tenantId
          and act.purchaseOrderItemId = :purchaseOrderItemId
          and act.isDeleted = false
          and act.status in :statuses
          and (:excludeId is null or act.id <> :excludeId)
        """)
    BigDecimal cumulativeAmount(
        @Param("tenantId") String tenantId,
        @Param("purchaseOrderItemId") UUID purchaseOrderItemId,
        @Param("statuses") List<String> statuses,
        @Param("excludeId") UUID excludeId
    );
}
