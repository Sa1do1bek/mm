package com.enterprise.mm.procurement.command.repository;

import com.enterprise.mm.procurement.command.entity.ProcurementWorkAssignment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface ProcurementWorkAssignmentRepository extends JpaRepository<ProcurementWorkAssignment, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select a from ProcurementWorkAssignment a
        where a.tenantId = :tenantId and a.companyId = :companyId
          and a.purchaseRequestId = :purchaseRequestId and a.isDeleted = false
        """)
    Optional<ProcurementWorkAssignment> findActiveForUpdate(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("purchaseRequestId") UUID purchaseRequestId);

    Optional<ProcurementWorkAssignment> findByTenantIdAndCompanyIdAndPurchaseRequestIdAndIsDeletedFalse(
        String tenantId, UUID companyId, UUID purchaseRequestId);

    List<ProcurementWorkAssignment> findByTenantIdAndCompanyIdAndPurchaseRequestIdInAndIsDeletedFalse(
        String tenantId, UUID companyId, List<UUID> purchaseRequestIds);
}
