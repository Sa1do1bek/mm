package com.enterprise.mm.repository;

import com.enterprise.mm.entity.TenderCommission;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenderCommissionRepository extends JpaRepository<TenderCommission, UUID> {
    Optional<TenderCommission> findAnyById(UUID id);

    Optional<TenderCommission> findByIdAndIsDeletedFalse(UUID id);

    List<TenderCommission> findByTenantIdAndCompanyIdAndRfqIdAndActiveTrueAndIsDeletedFalse(String tenantId, UUID companyId, UUID rfqId);

    List<TenderCommission> findByTenantIdAndCommissionIdAndActiveTrueAndIsDeletedFalse(String tenantId, UUID commissionId);
}
