package com.enterprise.mm.repository;

import com.enterprise.mm.entity.AssetAllocation;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetAllocationRepository extends JpaRepository<AssetAllocation, UUID> {
    boolean existsByTenantIdAndCompanyIdAndFixedAssetIdAndStatusAndIsDeletedFalse(
        String tenantId, UUID companyId, UUID fixedAssetId, String status);

    List<AssetAllocation> findByTenantIdAndCompanyIdAndAssetHandoverIdAndIsDeletedFalse(
        String tenantId, UUID companyId, UUID assetHandoverId);
}
