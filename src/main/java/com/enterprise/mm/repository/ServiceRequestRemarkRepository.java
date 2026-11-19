package com.enterprise.mm.repository;

import com.enterprise.mm.entity.ServiceRequestRemark;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceRequestRemarkRepository extends JpaRepository<ServiceRequestRemark, UUID> {
    List<ServiceRequestRemark> findByTenantIdAndServiceRequestIdAndIsDeletedFalseOrderByCreatedAtAsc(
        String tenantId,
        UUID serviceRequestId
    );
    List<ServiceRequestRemark> findByTenantIdAndCompanyIdAndServiceRequestIdAndIsDeletedFalseOrderByCreatedAtAsc(
        String tenantId,
        UUID companyId,
        UUID serviceRequestId
    );
}
