package com.enterprise.mm.repository;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.RfqPublishedVersion;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RfqPublishedVersionRepository extends JpaRepository<RfqPublishedVersion, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select publishedVersion
        from RfqPublishedVersion publishedVersion
        where publishedVersion.tenantId = :tenantId
          and publishedVersion.companyId = :companyId
          and publishedVersion.rfqId = :rfqId
          and publishedVersion.isDeleted = false
        order by publishedVersion.versionNumber desc
        """)
    List<RfqPublishedVersion> findLatestForUpdate(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("rfqId") UUID rfqId,
        Pageable pageable
    );

    default Optional<RfqPublishedVersion> lockLatest(String tenantId, UUID companyId, UUID rfqId) {
        return findLatestForUpdate(tenantId, companyId, rfqId, PageRequest.of(0, 1))
            .stream()
            .findFirst();
    }

    default Optional<RfqPublishedVersion> lockLatest(UUID rfqId) {
        return lockLatest(TenantContext.currentTenant(), CompanyContext.currentCompany(), rfqId);
    }

    List<RfqPublishedVersion> findByTenantIdAndCompanyIdAndRfqIdAndIsDeletedFalseOrderByVersionNumberAsc(
        String tenantId,
        UUID companyId,
        UUID rfqId
    );

    Optional<RfqPublishedVersion> findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        UUID id
    );

    long countByTenantIdAndCompanyIdAndRfqIdAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        UUID rfqId
    );
}
