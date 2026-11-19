package com.enterprise.mm.repository;

import com.enterprise.mm.entity.RfqSupplierInvitation;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RfqSupplierInvitationRepository extends JpaRepository<RfqSupplierInvitation, UUID> {
    @Query("""
        select count(invitation) > 0
        from RfqSupplierInvitation invitation
        where invitation.tenantId = :tenantId
          and invitation.companyId = :companyId
          and invitation.rfqId = :rfqId
          and invitation.supplierId = :supplierId
          and invitation.status = 'INVITED'
          and invitation.isDeleted = false
        """)
    boolean existsActiveInvitation(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("rfqId") UUID rfqId,
        @Param("supplierId") UUID supplierId
    );

    @Query("""
        select invitation.rfqId
        from RfqSupplierInvitation invitation
        where invitation.tenantId = :tenantId
          and invitation.companyId = :companyId
          and invitation.supplierId = :supplierId
          and invitation.status = 'INVITED'
          and invitation.isDeleted = false
        order by invitation.createdAt, invitation.id
        """)
    List<UUID> findActiveRfqIdsForSupplier(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("supplierId") UUID supplierId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select invitation
        from RfqSupplierInvitation invitation
        where invitation.tenantId = :tenantId
          and invitation.companyId = :companyId
          and invitation.rfqId = :rfqId
          and invitation.status = 'INVITED'
          and invitation.isDeleted = false
        order by invitation.createdAt, invitation.id
        """)
    List<RfqSupplierInvitation> findActiveForUpdate(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("rfqId") UUID rfqId
    );

    List<RfqSupplierInvitation> findByTenantIdAndCompanyIdAndRfqIdAndIsDeletedFalseOrderByCreatedAtAsc(
        String tenantId,
        UUID companyId,
        UUID rfqId
    );

    @Query("""
        select invitation
        from RfqSupplierInvitation invitation
        where invitation.tenantId = :tenantId
          and invitation.companyId = :companyId
          and invitation.supplierId = :supplierId
          and invitation.status = 'INVITED'
          and invitation.isDeleted = false
        order by invitation.createdAt desc, invitation.id desc
        """)
    List<RfqSupplierInvitation> findActiveForSupplierNewest(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("supplierId") UUID supplierId
    );

    @Query("""
        select invitation
        from RfqSupplierInvitation invitation
        where invitation.tenantId = :tenantId
          and invitation.companyId = :companyId
          and invitation.rfqId = :rfqId
          and invitation.supplierId = :supplierId
          and invitation.status = 'INVITED'
          and invitation.isDeleted = false
        """)
    Optional<RfqSupplierInvitation> findActiveInvitation(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("rfqId") UUID rfqId,
        @Param("supplierId") UUID supplierId
    );
}
