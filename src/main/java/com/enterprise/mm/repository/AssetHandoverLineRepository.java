package com.enterprise.mm.repository;

import com.enterprise.mm.entity.AssetHandoverLine;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetHandoverLineRepository extends JpaRepository<AssetHandoverLine, UUID> {
    @EntityGraph(attributePaths = {"fixedAsset", "assetAllocation"})
    List<AssetHandoverLine> findByTenantIdAndCompanyIdAndAssetHandoverIdAndIsDeletedFalse(
        String tenantId, UUID companyId, UUID assetHandoverId);
}
