package com.enterprise.mm.repository;

import com.enterprise.mm.entity.RFQPurchaseRequestSource;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RFQPurchaseRequestSourceRepository
    extends JpaRepository<RFQPurchaseRequestSource, UUID>, JpaSpecificationExecutor<RFQPurchaseRequestSource> {

    boolean existsByTenantIdAndCompanyIdAndPurchaseRequestItemIdAndStatusAndIsDeletedFalse(
        String tenantId, UUID companyId, UUID purchaseRequestItemId, String status);

    List<RFQPurchaseRequestSource> findByTenantIdAndCompanyIdAndPurchaseRequestItemIdAndStatusAndIsDeletedFalse(
        String tenantId, UUID companyId, UUID purchaseRequestItemId, String status);

    List<RFQPurchaseRequestSource> findByTenantIdAndCompanyIdAndRfqIdAndIsDeletedFalseOrderByCreatedAtAsc(
        String tenantId, UUID companyId, UUID rfqId);

    List<RFQPurchaseRequestSource> findByTenantIdAndCompanyIdAndPurchaseRequestIdAndStatusAndIsDeletedFalse(
        String tenantId, UUID companyId, UUID purchaseRequestId, String status);

    List<RFQPurchaseRequestSource> findByTenantIdAndCompanyIdAndPurchaseRequestIdInAndStatusAndIsDeletedFalse(
        String tenantId, UUID companyId, java.util.Collection<UUID> purchaseRequestIds, String status);

    List<RFQPurchaseRequestSource> findByTenantIdAndPurchaseRequestIdAndStatusAndIsDeletedFalse(
        String tenantId, UUID purchaseRequestId, String status);

    List<RFQPurchaseRequestSource> findByTenantIdAndCompanyIdAndRfqIdAndStatusAndIsDeletedFalse(
        String tenantId, UUID companyId, UUID rfqId, String status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select source
        from RFQPurchaseRequestSource source
        where source.tenantId = :tenantId
          and source.companyId = :companyId
          and source.rfqId = :rfqId
          and source.status = :status
          and source.isDeleted = false
        order by source.purchaseRequestId, source.purchaseRequestItemId, source.id
        """)
    List<RFQPurchaseRequestSource> findActiveByRfqIdForUpdate(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("rfqId") UUID rfqId,
        @Param("status") String status);

    List<RFQPurchaseRequestSource> findByTenantIdAndCompanyIdAndRfqItemIdAndIsDeletedFalseOrderByCreatedAtAsc(
        String tenantId, UUID companyId, UUID rfqItemId);

    List<RFQPurchaseRequestSource> findByTenantIdAndCompanyIdAndRfqItemIdAndStatusAndIsDeletedFalseOrderByCreatedAtAsc(
        String tenantId, UUID companyId, UUID rfqItemId, String status);

    boolean existsByTenantIdAndCompanyIdAndPurchaseRequestIdAndStatusAndIsDeletedFalse(
        String tenantId, UUID companyId, UUID purchaseRequestId, String status);

    boolean existsByTenantIdAndCompanyIdAndRfqIdAndStatusAndIsDeletedFalse(
        String tenantId, UUID companyId, UUID rfqId, String status);

    boolean existsByTenantIdAndCompanyIdAndRfqItemIdAndIsDeletedFalse(
        String tenantId, UUID companyId, UUID rfqItemId);

    boolean existsByTenantIdAndCompanyIdAndRfqItemIdAndStatusAndIsDeletedFalse(
        String tenantId, UUID companyId, UUID rfqItemId, String status);
}
