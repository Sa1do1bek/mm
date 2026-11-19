package com.enterprise.mm.repository;

import com.enterprise.mm.entity.SupplierAccreditationApplication;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SupplierAccreditationApplicationRepository
    extends JpaRepository<SupplierAccreditationApplication, UUID>, JpaSpecificationExecutor<SupplierAccreditationApplication> {
    Optional<SupplierAccreditationApplication> findAnyById(UUID id);
    Optional<SupplierAccreditationApplication> findByIdAndIsDeletedFalse(UUID id);
    Optional<SupplierAccreditationApplication> findByTenantIdAndCodeAndIsDeletedFalse(String tenantId, String code);
    List<SupplierAccreditationApplication> findByTenantIdAndCompanyIdAndSupplierIdAndIsDeletedFalseOrderByCreatedAtDesc(String tenantId, UUID companyId, UUID supplierId);
    List<SupplierAccreditationApplication> findByTenantIdAndCompanyIdAndSupplierIdAndStatusInAndIsDeletedFalseOrderByCreatedAtDesc(String tenantId, UUID companyId, UUID supplierId, Collection<String> statuses);
}
