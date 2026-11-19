package com.enterprise.mm.repository;

import com.enterprise.mm.entity.AssetVerificationLine;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetVerificationLineRepository extends JpaRepository<AssetVerificationLine, UUID> {
    @EntityGraph(attributePaths = {"assetVerification", "fixedAsset"})
    List<AssetVerificationLine> findByTenantIdAndCompanyIdAndAssetVerificationIdAndIsDeletedFalse(
        String tenantId, UUID companyId, UUID assetVerificationId);
}
