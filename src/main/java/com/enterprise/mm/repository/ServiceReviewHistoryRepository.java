package com.enterprise.mm.repository;

import com.enterprise.mm.entity.ServiceReviewHistory;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceReviewHistoryRepository extends JpaRepository<ServiceReviewHistory, UUID> {
    Optional<ServiceReviewHistory> findAnyById(UUID id);
    Optional<ServiceReviewHistory> findByIdAndIsDeletedFalse(UUID id);
    List<ServiceReviewHistory> findByTenantIdAndServiceRequestIdAndIsDeletedFalseOrderByActedAtAsc(String tenantId, UUID serviceRequestId);
    List<ServiceReviewHistory> findByTenantIdAndCompanyIdAndServiceRequestIdAndIsDeletedFalseOrderByActedAtAsc(String tenantId, UUID companyId, UUID serviceRequestId);
    List<ServiceReviewHistory> findByTenantIdAndServiceActIdAndIsDeletedFalseOrderByActedAtAsc(String tenantId, UUID serviceActId);
    List<ServiceReviewHistory> findByTenantIdAndCompanyIdAndClaimOriginTypeAndClaimOriginIdAndIsDeletedFalseOrderByActedAtAsc(
        String tenantId, UUID companyId, String claimOriginType, UUID claimOriginId);
}
