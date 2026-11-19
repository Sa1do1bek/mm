package com.enterprise.mm.repository;

import com.enterprise.mm.entity.AssetVerification;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetVerificationRepository extends JpaRepository<AssetVerification, UUID> {
    @EntityGraph(attributePaths = {"warehouse", "department"})
    Optional<AssetVerification> findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(String tenantId, UUID companyId, UUID id);
    @EntityGraph(attributePaths = {"warehouse", "department"})
    Page<AssetVerification> findByTenantIdAndCompanyIdAndIsDeletedFalse(String tenantId, UUID companyId, Pageable pageable);
}
