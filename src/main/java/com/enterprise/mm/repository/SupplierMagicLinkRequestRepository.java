package com.enterprise.mm.repository;

import com.enterprise.mm.entity.SupplierMagicLinkRequest;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupplierMagicLinkRequestRepository extends JpaRepository<SupplierMagicLinkRequest, UUID> {
    @Query("""
        select count(request)
        from SupplierMagicLinkRequest request
        where request.tenantId = :tenantId
          and request.normalizedEmailHash = :emailHash
          and request.requestedAt >= :since
          and request.isDeleted = false
          and ((:companyId is null and request.companyId is null) or request.companyId = :companyId)
        """)
    long countRecentForEmail(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("emailHash") String emailHash,
        @Param("since") LocalDateTime since
    );

    @Query("""
        select count(request)
        from SupplierMagicLinkRequest request
        where request.tenantId = :tenantId
          and request.requestIpHash = :ipHash
          and request.requestedAt >= :since
          and request.isDeleted = false
        """)
    long countRecentForIp(
        @Param("tenantId") String tenantId,
        @Param("ipHash") String ipHash,
        @Param("since") LocalDateTime since
    );
}
