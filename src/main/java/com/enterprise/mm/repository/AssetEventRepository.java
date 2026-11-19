package com.enterprise.mm.repository;

import com.enterprise.mm.entity.AssetEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetEventRepository extends JpaRepository<AssetEvent, UUID> {
    @EntityGraph(attributePaths = {"fixedAsset"})
    List<AssetEvent> findByTenantIdAndCompanyIdAndFixedAssetIdAndIsDeletedFalseOrderByRecordedAtDesc(
        String tenantId, UUID companyId, UUID fixedAssetId);
}
