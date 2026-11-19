package com.enterprise.mm.repository;

import com.enterprise.mm.entity.PurchaseOrder;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, UUID>, JpaSpecificationExecutor<PurchaseOrder> {
    Optional<PurchaseOrder> findAnyById(UUID id);
    Optional<PurchaseOrder> findByIdAndIsDeletedFalse(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select purchaseOrder from PurchaseOrder purchaseOrder where purchaseOrder.id = :id and purchaseOrder.isDeleted = false")
    Optional<PurchaseOrder> findByIdForUpdate(@Param("id") UUID id);

    long countByTenantIdAndStatusInAndIsDeletedFalse(String tenantId, List<String> statuses);

    long countByTenantIdAndCompanyIdAndStatusInAndIsDeletedFalse(String tenantId, UUID companyId, List<String> statuses);

    Optional<PurchaseOrder> findByTenantIdAndCodeAndIsDeletedFalse(String tenantId, String code);

    List<PurchaseOrder> findByTenantIdAndIsDeletedFalse(String tenantId);

    List<PurchaseOrder> findByTenantIdAndCompanyIdAndIsDeletedFalse(String tenantId, UUID companyId);

    @Query("""
        select purchaseOrder
        from PurchaseOrder purchaseOrder
        where purchaseOrder.isDeleted = false
          and purchaseOrder.tenantId = :tenantId
          and purchaseOrder.companyId = :companyId
          and (
            purchaseOrder.orderDate between :periodFrom and :periodTo
            or (purchaseOrder.orderDate is null and purchaseOrder.createdAt between :periodStart and :periodEnd)
            or exists (
                select 1 from PurchaseRequest request
                where request.isDeleted = false
                  and request.tenantId = :tenantId
                  and request.companyId = :companyId
                  and request.id = purchaseOrder.purchaseRequestId
                  and request.createdAt between :periodStart and :periodEnd
                  and (
                    (purchaseOrder.orderDate is not null and purchaseOrder.orderDate <= :periodTo)
                    or (purchaseOrder.orderDate is null and purchaseOrder.createdAt <= :periodEnd)
                  )
            )
            or exists (
                select 1 from StockMovement movement
                where movement.isDeleted = false
                  and movement.tenantId = :tenantId
                  and movement.companyId = :companyId
                  and movement.referenceType = 'PurchaseOrder'
                  and movement.referenceId = purchaseOrder.id
                  and movement.movementType = com.enterprise.mm.enums.MovementType.RECEIPT
                  and movement.status = 'POSTED'
                  and movement.postedAt between :periodStart and :periodEnd
            )
          )
        order by purchaseOrder.createdAt, purchaseOrder.id
        """)
    List<PurchaseOrder> findReportWindow(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("periodFrom") LocalDate periodFrom,
        @Param("periodTo") LocalDate periodTo,
        @Param("periodStart") LocalDateTime periodStart,
        @Param("periodEnd") LocalDateTime periodEnd,
        Pageable pageable
    );

    List<PurchaseOrder> findByTenantIdAndPurchaseRequestIdIsNotNullAndIsDeletedFalse(String tenantId);

    List<PurchaseOrder> findByTenantIdAndPurchaseRequestIdInAndIsDeletedFalse(String tenantId, List<UUID> purchaseRequestIds);

    List<PurchaseOrder> findByTenantIdAndCompanyIdAndPurchaseRequestIdInAndIsDeletedFalse(
        String tenantId, UUID companyId, List<UUID> purchaseRequestIds);

    List<PurchaseOrder> findByTenantIdAndCompanyIdAndSupplierIdAndIsDeletedFalseOrderByCreatedAtDesc(
        String tenantId, UUID companyId, UUID supplierId);

    Optional<PurchaseOrder> findFirstByTenantIdAndCompanyIdAndContractIdAndIsDeletedFalseOrderByCreatedAtDesc(
        String tenantId, UUID companyId, UUID contractId);

    List<PurchaseOrder> findByTenantIdAndCompanyIdAndPurchaseRequestIdIsNotNullAndIsDeletedFalse(String tenantId, UUID companyId);

    List<PurchaseOrder> findByTenantIdAndStatusInAndExpectedDeliveryDateBeforeAndIsDeletedFalse(String tenantId, List<String> statuses, LocalDate expectedDeliveryDate);

    List<PurchaseOrder> findByTenantIdAndStatusInAndExpectedDeliveryDateBeforeAndIsDeletedFalse(
        String tenantId, List<String> statuses, LocalDate expectedDeliveryDate, Pageable pageable);

    List<PurchaseOrder> findByTenantIdAndCompanyIdAndStatusInAndExpectedDeliveryDateBeforeAndIsDeletedFalse(String tenantId, UUID companyId, List<String> statuses, LocalDate expectedDeliveryDate);

    List<PurchaseOrder> findByTenantIdAndCompanyIdAndStatusInAndExpectedDeliveryDateBeforeAndIsDeletedFalse(
        String tenantId, UUID companyId, List<String> statuses, LocalDate expectedDeliveryDate, Pageable pageable);

    long countByTenantIdAndStatusInAndExpectedDeliveryDateBeforeAndIsDeletedFalse(
        String tenantId, List<String> statuses, LocalDate expectedDeliveryDate);

    long countByTenantIdAndCompanyIdAndStatusInAndExpectedDeliveryDateBeforeAndIsDeletedFalse(
        String tenantId, UUID companyId, List<String> statuses, LocalDate expectedDeliveryDate);

    @Query("""
        select coalesce(sum(purchaseOrder.totalAmount), 0) from PurchaseOrder purchaseOrder
        where purchaseOrder.isDeleted = false
          and purchaseOrder.tenantId = :tenantId
          and purchaseOrder.status in :statuses
          and purchaseOrder.expectedDeliveryDate < :expectedDeliveryDate
        """)
    BigDecimal sumTotalAmountByTenantAndStatusInAndExpectedDeliveryDateBefore(
        @Param("tenantId") String tenantId,
        @Param("statuses") List<String> statuses,
        @Param("expectedDeliveryDate") LocalDate expectedDeliveryDate
    );

    @Query("""
        select coalesce(sum(purchaseOrder.totalAmount), 0) from PurchaseOrder purchaseOrder
        where purchaseOrder.isDeleted = false
          and purchaseOrder.tenantId = :tenantId
          and purchaseOrder.companyId = :companyId
          and purchaseOrder.status in :statuses
          and purchaseOrder.expectedDeliveryDate < :expectedDeliveryDate
        """)
    BigDecimal sumTotalAmountByTenantAndCompanyAndStatusInAndExpectedDeliveryDateBefore(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("statuses") List<String> statuses,
        @Param("expectedDeliveryDate") LocalDate expectedDeliveryDate
    );

    @Query("""
        select coalesce(sum(purchaseOrder.totalAmount), 0) from PurchaseOrder purchaseOrder
        where purchaseOrder.isDeleted = false
          and purchaseOrder.tenantId = :tenantId
          and purchaseOrder.status <> :excludedStatus
        """)
    BigDecimal sumTotalAmountByTenantExcludingStatus(@Param("tenantId") String tenantId, @Param("excludedStatus") String excludedStatus);

    @Query("""
        select coalesce(sum(purchaseOrder.totalAmount), 0) from PurchaseOrder purchaseOrder
        where purchaseOrder.isDeleted = false
          and purchaseOrder.tenantId = :tenantId
          and purchaseOrder.companyId = :companyId
          and purchaseOrder.status <> :excludedStatus
        """)
    BigDecimal sumTotalAmountByTenantAndCompanyExcludingStatus(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("excludedStatus") String excludedStatus
    );

    @Query("""
        select coalesce(sum(purchaseOrder.totalAmount), 0) from PurchaseOrder purchaseOrder
        where purchaseOrder.isDeleted = false
          and purchaseOrder.tenantId = :tenantId
          and purchaseOrder.status in :statuses
        """)
    BigDecimal sumTotalAmountByTenantAndStatusIn(@Param("tenantId") String tenantId, @Param("statuses") List<String> statuses);

    @Query("""
        select coalesce(sum(purchaseOrder.totalAmount), 0) from PurchaseOrder purchaseOrder
        where purchaseOrder.isDeleted = false
          and purchaseOrder.tenantId = :tenantId
          and purchaseOrder.companyId = :companyId
          and purchaseOrder.status in :statuses
        """)
    BigDecimal sumTotalAmountByTenantAndCompanyAndStatusIn(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("statuses") List<String> statuses
    );
    Optional<PurchaseOrder> findByTenantIdAndIdAndIsDeletedFalse(String s, UUID purchaseOrderId);

    @Query("""
        SELECT e
        FROM PurchaseOrder e
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
            AND (:supplierId IS NULL OR e.supplierId = :supplierId)
            AND (:contractId IS NULL OR e.contractId = :contractId)
        """)
    Page<PurchaseOrder> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("includeDeleted") boolean includeDeleted,
        @Param("search") String search,
        @Param("dateFrom") LocalDateTime dateFrom,
        @Param("dateTo") LocalDateTime dateTo,
        @Param("status") List<String> status,
        @Param("statusEmpty") boolean statusEmpty,
        @Param("supplierId") UUID supplierId,
        @Param("contractId") UUID contractId,
        Pageable pageable
    );
}
