package com.enterprise.mm.repository;

import com.enterprise.mm.entity.AssetAcceptanceUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetAcceptanceUnitRepository extends JpaRepository<AssetAcceptanceUnit, UUID> {
    @EntityGraph(attributePaths = {"postedFixedAsset"})
    Optional<AssetAcceptanceUnit> findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
        String tenantId, UUID companyId, UUID id);

    @EntityGraph(attributePaths = {"postedFixedAsset"})
    List<AssetAcceptanceUnit> findByTenantIdAndCompanyIdAndAssetAcceptanceLineIdAndIsDeletedFalse(
        String tenantId, UUID companyId, UUID assetAcceptanceLineId);

    boolean existsByTenantIdAndCompanyIdAndPostedFixedAssetIdAndIsDeletedFalse(
        String tenantId, UUID companyId, UUID postedFixedAssetId);
}
