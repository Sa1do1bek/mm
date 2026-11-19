package com.enterprise.mm.repository;

import com.enterprise.mm.entity.ServiceRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ServiceRequestRepository extends JpaRepository<ServiceRequest, UUID>, JpaSpecificationExecutor<ServiceRequest> {
    Optional<ServiceRequest> findAnyById(UUID id);
    Optional<ServiceRequest> findByIdAndIsDeletedFalse(UUID id);
    Optional<ServiceRequest> findByTenantIdAndIdAndIsDeletedFalse(String tenantId, UUID id);
    List<ServiceRequest> findByTenantIdAndCompanyIdAndStatusInAndIsDeletedFalse(String tenantId, UUID companyId, List<String> statuses);
    List<ServiceRequest> findByTenantIdAndCompanyIdAndIsDeletedFalseOrderByCreatedAtDesc(String tenantId, UUID companyId);
}
