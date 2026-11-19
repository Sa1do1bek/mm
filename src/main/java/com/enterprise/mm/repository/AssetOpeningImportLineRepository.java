package com.enterprise.mm.repository;

import com.enterprise.mm.entity.AssetOpeningImportLine;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetOpeningImportLineRepository extends JpaRepository<AssetOpeningImportLine, UUID> {
    List<AssetOpeningImportLine> findByTenantIdAndCompanyIdAndAssetOpeningImportIdAndIsDeletedFalse(
        String tenantId, UUID companyId, UUID assetOpeningImportId);
}
