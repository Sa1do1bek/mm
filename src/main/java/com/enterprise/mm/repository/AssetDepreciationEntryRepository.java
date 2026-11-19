package com.enterprise.mm.repository;

import com.enterprise.mm.entity.AssetDepreciationEntry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AssetDepreciationEntryRepository extends JpaRepository<AssetDepreciationEntry, UUID>, JpaSpecificationExecutor<AssetDepreciationEntry> {
    List<AssetDepreciationEntry> findByTenantIdAndCompanyIdAndFixedAssetIdAndIsDeletedFalseOrderByPeriodDateAsc(
        String tenantId, UUID companyId, UUID fixedAssetId
    );

    Page<AssetDepreciationEntry> findByTenantIdAndCompanyIdAndFixedAssetIdAndIsDeletedFalse(
        String tenantId, UUID companyId, UUID fixedAssetId, Pageable pageable
    );

    Optional<AssetDepreciationEntry> findByTenantIdAndCompanyIdAndFixedAssetIdAndPeriodYearAndPeriodMonthAndIsDeletedFalse(
        String tenantId, UUID companyId, UUID fixedAssetId, Integer periodYear, Integer periodMonth
    );

    List<AssetDepreciationEntry> findByTenantIdAndCompanyIdAndPeriodYearAndPeriodMonthAndIsDeletedFalse(
        String tenantId, UUID companyId, Integer periodYear, Integer periodMonth
    );

    boolean existsByTenantIdAndCompanyIdAndFixedAssetIdAndPeriodYearAndPeriodMonthAndIsDeletedFalse(
        String tenantId, UUID companyId, UUID fixedAssetId, Integer periodYear, Integer periodMonth
    );
}
