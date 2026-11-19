package com.enterprise.mm.repository;

import com.enterprise.mm.entity.SupplierUnblockRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierUnblockRequestRepository extends JpaRepository<SupplierUnblockRequest, UUID> {
    List<SupplierUnblockRequest> findByTenantIdAndCompanyIdAndSupplierIdAndIsDeletedFalseOrderByCreatedAtDesc(
        String tenantId, UUID companyId, UUID supplierId);

    java.util.Optional<SupplierUnblockRequest> findByTenantIdAndCompanyIdAndSupplierIdAndIdAndIsDeletedFalse(
        String tenantId, UUID companyId, UUID supplierId, UUID id);

    boolean existsByTenantIdAndCompanyIdAndSupplierIdAndStatusInAndIsDeletedFalse(
        String tenantId, UUID companyId, UUID supplierId, List<String> statuses);
}
