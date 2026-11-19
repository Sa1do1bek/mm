package com.enterprise.mm.repository;

import java.util.Optional;
import com.enterprise.mm.entity.DeliverySchedule;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeliveryScheduleRepository extends JpaRepository<DeliverySchedule, UUID> {
    Optional<DeliverySchedule> findAnyById(UUID id);
    Optional<DeliverySchedule> findByIdAndIsDeletedFalse(UUID id);
    List<DeliverySchedule> findByTenantIdAndCompanyIdAndPurchaseOrder_IdAndIsDeletedFalseOrderByScheduleDateAsc(String tenantId, UUID companyId, UUID purchaseOrderId);

    List<DeliverySchedule> findByTenantIdAndCompanyIdAndContract_IdAndIsDeletedFalseOrderByScheduleDateAsc(String tenantId, UUID companyId, UUID contractId);

    List<DeliverySchedule> findByTenantIdAndCompanyIdAndScheduleDateBeforeAndStatusInAndIsDeletedFalse(String tenantId, UUID companyId, LocalDate scheduleDate, List<String> statuses);

    /** Used by the buyer-decision accept flow to make schedule projection idempotent:
     * a retry with the same commitment must not create a duplicate schedule row. */
    Optional<DeliverySchedule> findByTenantIdAndCompanyIdAndSupplierPoCommitmentIdAndIsDeletedFalse(
        String tenantId, UUID companyId, UUID supplierPoCommitmentId
    );

    @Query("""
        select s
        from DeliverySchedule s
        left join fetch s.purchaseOrder p
        where s.tenantId = :tenantId
          and s.companyId = :companyId
          and s.isDeleted = false
          and s.scheduleDate < :asOfDate
          and (
              s.overdueFlag = true
              or s.status = 'OVERDUE'
              or s.status = 'PARTIALLY_RECEIVED'
          )
        """)
    List<DeliverySchedule> findManagementOpportunityDelayCandidates(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("asOfDate") LocalDate asOfDate
    );

    @Query("""
        SELECT e
        FROM DeliverySchedule e
        WHERE
            e.tenantId = :tenantId
            AND (:companyId IS NULL OR e.companyId = :companyId)
            AND (:includeDeleted = true OR e.isDeleted = false)
            AND (:search IS NULL OR
                 LOWER(COALESCE(e.code, '')) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(COALESCE(e.name, '')) LIKE LOWER(CONCAT('%', :search, '%')))
            AND (CAST(:dateFrom AS timestamp) IS NULL OR e.createdAt >= :dateFrom)
            AND (CAST(:dateTo AS timestamp) IS NULL OR e.createdAt <= :dateTo)
            AND (:statusEmpty = true OR e.status IN :status)
            AND (:contractId IS NULL OR e.contractId = :contractId)
            AND (:purchaseOrderId IS NULL OR e.purchaseOrderId = :purchaseOrderId)
        """)
    Page<DeliverySchedule> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("includeDeleted") boolean includeDeleted,
        @Param("search") String search,
        @Param("dateFrom") LocalDateTime dateFrom,
        @Param("dateTo") LocalDateTime dateTo,
        @Param("status") List<String> status,
        @Param("statusEmpty") boolean statusEmpty,
        @Param("contractId") UUID contractId,
        @Param("purchaseOrderId") UUID purchaseOrderId,
        Pageable pageable
    );
}
