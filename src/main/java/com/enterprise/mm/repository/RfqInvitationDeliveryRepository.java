package com.enterprise.mm.repository;

import com.enterprise.mm.entity.RfqInvitationDelivery;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RfqInvitationDeliveryRepository extends JpaRepository<RfqInvitationDelivery, UUID> {
    Optional<RfqInvitationDelivery> findFirstByProviderMessageIdAndIsDeletedFalseOrderByCreatedAtDesc(
        String providerMessageId
    );

    @Query("""
        select delivery
        from RfqInvitationDelivery delivery
        where delivery.isDeleted = false
          and (
            (delivery.status = 'PENDING' and (delivery.nextRetryAt is null or delivery.nextRetryAt <= :now))
            or (delivery.status = 'FAILED' and delivery.nextRetryAt is not null and delivery.nextRetryAt <= :now)
          )
        order by delivery.createdAt, delivery.id
        """)
    List<RfqInvitationDelivery> findDue(@Param("now") LocalDateTime now, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select delivery
        from RfqInvitationDelivery delivery
        where delivery.tenantId = :tenantId
          and delivery.companyId = :companyId
          and delivery.id = :id
          and delivery.isDeleted = false
        """)
    Optional<RfqInvitationDelivery> lockById(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("id") UUID id
    );

    Optional<RfqInvitationDelivery> findByTenantIdAndCompanyIdAndPublishedVersionIdAndSupplierIdAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        UUID publishedVersionId,
        UUID supplierId
    );

    List<RfqInvitationDelivery> findByTenantIdAndCompanyIdAndPublishedVersionIdAndIsDeletedFalseOrderByCreatedAtAsc(
        String tenantId,
        UUID companyId,
        UUID publishedVersionId
    );

    List<RfqInvitationDelivery> findByTenantIdAndCompanyIdAndInvitationIdAndIsDeletedFalseOrderByCreatedAtDesc(
        String tenantId,
        UUID companyId,
        UUID invitationId
    );

    @Query("""
        select delivery
        from RfqInvitationDelivery delivery
        where delivery.tenantId = :tenantId
          and delivery.companyId = :companyId
          and delivery.invitationId in :invitationIds
          and delivery.isDeleted = false
        order by delivery.invitationId, delivery.createdAt desc, delivery.id desc
        """)
    List<RfqInvitationDelivery> findForInvitations(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("invitationIds") List<UUID> invitationIds
    );
}
