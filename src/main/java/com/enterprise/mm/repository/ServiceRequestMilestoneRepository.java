package com.enterprise.mm.repository;

import com.enterprise.mm.entity.ServiceRequestMilestone;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ServiceRequestMilestoneRepository extends JpaRepository<ServiceRequestMilestone, UUID> {
    List<ServiceRequestMilestone> findByTenantIdAndCompanyIdAndServiceRequestIdAndIsDeletedFalseOrderBySequenceNumberAsc(
        String tenantId,
        UUID companyId,
        UUID serviceRequestId
    );

    boolean existsByTenantIdAndCompanyIdAndServiceRequestIdAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        UUID serviceRequestId
    );

    @Query("""
        select milestone
        from ServiceRequestMilestone milestone
        where milestone.tenantId = :tenantId
          and milestone.companyId = :companyId
          and milestone.serviceRequestId = :requestId
          and milestone.id = :milestoneId
          and milestone.isDeleted = false
        """)
    Optional<ServiceRequestMilestone> findScoped(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("requestId") UUID requestId,
        @Param("milestoneId") UUID milestoneId
    );
}
