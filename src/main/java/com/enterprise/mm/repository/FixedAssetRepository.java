package com.enterprise.mm.repository;

import com.enterprise.mm.entity.FixedAsset;
import com.enterprise.mm.enums.FixedAssetCustodyKind;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.lang.Nullable;

public interface FixedAssetRepository extends JpaRepository<FixedAsset, UUID>, JpaSpecificationExecutor<FixedAsset> {
    @EntityGraph(attributePaths = {"department", "custodianEmployee", "material", "warehouse", "equipment"})
    Page<FixedAsset> findAll(@Nullable Specification<FixedAsset> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"department", "custodianEmployee", "material", "warehouse", "equipment"})
    Optional<FixedAsset> findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(String tenantId, UUID companyId, UUID id);
    
    @EntityGraph(attributePaths = {"department", "custodianEmployee", "material", "warehouse", "equipment"})
    Page<FixedAsset> findByTenantIdAndCompanyIdAndIsDeletedFalse(String tenantId, UUID companyId, Pageable pageable);
    List<FixedAsset> findByTenantIdAndCompanyIdAndWarehouseIdAndIsDeletedFalse(String tenantId, UUID companyId, UUID warehouseId);
    @EntityGraph(attributePaths = {"material", "warehouse"})
    Page<FixedAsset> findByTenantIdAndCompanyIdAndWarehouseIdAndCustodyKindAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        UUID warehouseId,
        FixedAssetCustodyKind custodyKind,
        Pageable pageable
    );
    List<FixedAsset> findByTenantIdAndCompanyIdAndDepartmentIdAndIsDeletedFalse(String tenantId, UUID companyId, UUID departmentId);
    Optional<FixedAsset> findByTenantIdAndCompanyIdAndInventoryNumberAndIsDeletedFalse(
        String tenantId, UUID companyId, String inventoryNumber);
    boolean existsByTenantIdAndCompanyIdAndEquipmentIdAndIsDeletedFalse(String tenantId, UUID companyId, UUID equipmentId);
    boolean existsByTenantIdAndCompanyIdAndEquipmentIdAndIdNotAndIsDeletedFalse(
        String tenantId, UUID companyId, UUID equipmentId, UUID id);
    boolean existsByTenantIdAndCompanyIdAndSourceAcceptanceUnitIdAndIsDeletedFalse(
        String tenantId, UUID companyId, UUID sourceAcceptanceUnitId);

    @EntityGraph(attributePaths = {"department", "custodianEmployee", "material", "warehouse", "equipment"})
    Page<FixedAsset> findByTenantIdAndCompanyIdAndMaterialIdAndIsDeletedFalse(String tenantId, UUID companyId, UUID materialId, Pageable pageable);
}
