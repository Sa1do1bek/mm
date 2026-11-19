package com.enterprise.mm.repository;

import com.enterprise.mm.entity.SupplierMagicLink;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupplierMagicLinkRepository extends JpaRepository<SupplierMagicLink, UUID> {
    @Query("""
        select magicLink
        from SupplierMagicLink magicLink
        where magicLink.tokenHash = :tokenHash
          and magicLink.status = 'ISSUED'
          and magicLink.consumedAt is null
          and magicLink.revokedAt is null
          and magicLink.expiresAt > :now
          and magicLink.isDeleted = false
        """)
    Optional<SupplierMagicLink> findUsable(
        @Param("tokenHash") String tokenHash,
        @Param("now") LocalDateTime now
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select magicLink
        from SupplierMagicLink magicLink
        where magicLink.tokenHash = :tokenHash
          and magicLink.status = 'ISSUED'
          and magicLink.consumedAt is null
          and magicLink.revokedAt is null
          and magicLink.expiresAt > :now
          and magicLink.isDeleted = false
        """)
    Optional<SupplierMagicLink> lockUsable(
        @Param("tokenHash") String tokenHash,
        @Param("now") LocalDateTime now
    );

    List<SupplierMagicLink> findByTenantIdAndCompanyIdAndInvitationIdAndStatusAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        UUID invitationId,
        String status
    );

    List<SupplierMagicLink> findByTenantIdAndCompanyIdAndPurchaseOrderIdAndStatusAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        UUID purchaseOrderId,
        String status
    );

    @Query("""
        select magicLink
        from SupplierMagicLink magicLink
        where magicLink.tenantId = :tenantId
          and magicLink.companyId = :companyId
          and magicLink.portalAccountId = :portalAccountId
          and magicLink.invitationId is null
          and magicLink.purchaseOrderId is null
          and magicLink.status = :status
          and magicLink.isDeleted = false
        """)
    List<SupplierMagicLink> findAccountActivationLinks(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("portalAccountId") UUID portalAccountId,
        @Param("status") String status
    );
}
