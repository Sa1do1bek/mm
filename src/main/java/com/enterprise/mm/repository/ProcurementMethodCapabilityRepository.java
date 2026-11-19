package com.enterprise.mm.repository;

import com.enterprise.mm.entity.ProcurementMethodCapability;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcurementMethodCapabilityRepository extends JpaRepository<ProcurementMethodCapability, UUID> {
    Optional<ProcurementMethodCapability> findByTenantIdAndCompanyIdAndMethodCodeAndIsDeletedFalse(
        String tenantId, UUID companyId, String methodCode);

    Optional<ProcurementMethodCapability> findByIdAndTenantIdAndCompanyIdAndIsDeletedFalse(
        UUID id, String tenantId, UUID companyId);

    List<ProcurementMethodCapability> findByTenantIdAndCompanyIdAndIsDeletedFalseOrderByMethodCodeAsc(
        String tenantId, UUID companyId);

    boolean existsByTenantIdAndCompanyIdAndMethodCodeAndIsDeletedFalse(
        String tenantId, UUID companyId, String methodCode);
}
