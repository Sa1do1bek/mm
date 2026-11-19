package com.enterprise.mm.repository;

import com.enterprise.mm.entity.ManagementOpportunityPolicy;
import com.enterprise.mm.enums.ManagementOpportunityType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ManagementOpportunityPolicyRepository extends JpaRepository<ManagementOpportunityPolicy, UUID> {
    Optional<ManagementOpportunityPolicy> findAnyById(UUID id);
    Optional<ManagementOpportunityPolicy> findByIdAndIsDeletedFalse(UUID id);

    @Query("""
        select e
        from ManagementOpportunityPolicy e
        where e.tenantId = :tenantId
          and e.companyId = :companyId
          and (:includeDeleted = true or e.isDeleted = false)
          and (:active is null or e.active = :active)
          and (:type is null or e.opportunityType = :type)
          and (:searchPattern is null
               or lower(coalesce(e.code, '')) like :searchPattern
               or lower(coalesce(e.name, '')) like :searchPattern)
          and (:statusEmpty = true or e.status in :status)
        """)
    Page<ManagementOpportunityPolicy> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("includeDeleted") boolean includeDeleted,
        @Param("active") Boolean active,
        @Param("type") ManagementOpportunityType type,
        @Param("searchPattern") String searchPattern,
        @Param("status") List<String> status,
        @Param("statusEmpty") boolean statusEmpty,
        Pageable pageable
    );

    @Query("""
        select e
        from ManagementOpportunityPolicy e
        where e.tenantId = :tenantId
          and e.companyId = :companyId
          and e.isDeleted = false
          and e.active = true
          and e.opportunityType = :type
          and e.effectiveFrom <= :asOfDate
          and (e.effectiveTo is null or e.effectiveTo >= :asOfDate)
        """)
    List<ManagementOpportunityPolicy> findActivePoliciesForResolution(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("type") ManagementOpportunityType type,
        @Param("asOfDate") LocalDate asOfDate
    );

    @Query("""
        select e
        from ManagementOpportunityPolicy e
        where e.tenantId = :tenantId
          and e.companyId = :companyId
          and e.isDeleted = false
          and e.active = true
          and e.opportunityType = :type
          and (:excludeId is null or e.id <> :excludeId)
        """)
    List<ManagementOpportunityPolicy> findActivePolicies(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("type") ManagementOpportunityType type,
        @Param("excludeId") UUID excludeId
    );
}
