package com.enterprise.mm.repository;

import com.enterprise.mm.entity.ServiceClaim;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ServiceClaimRepository extends JpaRepository<ServiceClaim, UUID>, JpaSpecificationExecutor<ServiceClaim> {
    Optional<ServiceClaim> findAnyById(UUID id);
    Optional<ServiceClaim> findByIdAndIsDeletedFalse(UUID id);
    List<ServiceClaim> findByTenantIdAndServiceRequestIdAndIsDeletedFalse(String tenantId, UUID serviceRequestId);
    List<ServiceClaim> findByTenantIdAndServiceRequestIdAndStatusNotAndIsDeletedFalse(String tenantId, UUID serviceRequestId, String status);
    List<ServiceClaim> findByTenantIdAndServiceActIdAndStatusNotAndIsDeletedFalse(String tenantId, UUID serviceActId, String status);
    List<ServiceClaim> findByTenantIdAndServiceOrderIdAndStatusNotAndIsDeletedFalse(String tenantId, UUID serviceOrderId, String status);
    List<ServiceClaim> findByTenantIdAndPurchaseOrderItemIdAndStatusNotAndIsDeletedFalse(String tenantId, UUID purchaseOrderItemId, String status);
    List<ServiceClaim> findByTenantIdAndWorkCompletionActIdAndStatusNotAndIsDeletedFalse(String tenantId, UUID workCompletionActId, String status);
    List<ServiceClaim> findByTenantIdAndCompanyIdAndIsDeletedFalseOrderByCreatedAtDesc(String tenantId, UUID companyId);
    List<ServiceClaim> findByTenantIdAndCompanyIdAndServiceRequestIdAndIsDeletedFalseOrderByCreatedAtDesc(String tenantId, UUID companyId, UUID serviceRequestId);
    List<ServiceClaim> findByTenantIdAndCompanyIdAndServiceOrderIdAndIsDeletedFalseOrderByCreatedAtDesc(String tenantId, UUID companyId, UUID serviceOrderId);
    List<ServiceClaim> findByTenantIdAndCompanyIdAndPurchaseOrderItemIdAndIsDeletedFalseOrderByCreatedAtDesc(String tenantId, UUID companyId, UUID purchaseOrderItemId);
}
