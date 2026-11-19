package com.enterprise.mm.repository;

import com.enterprise.mm.entity.AssetHandover;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AssetHandoverRepository extends JpaRepository<AssetHandover, UUID> {
    @EntityGraph(attributePaths = {"fromEmployee", "fromDepartment", "fromWarehouse", "toEmployee", "toDepartment", "toWarehouse"})
    Optional<AssetHandover> findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(String tenantId, UUID companyId, UUID id);
    
    @EntityGraph(attributePaths = {"fromEmployee", "fromDepartment", "fromWarehouse", "toEmployee", "toDepartment", "toWarehouse"})
    Page<AssetHandover> findByTenantIdAndCompanyIdAndIsDeletedFalse(String tenantId, UUID companyId, Pageable pageable);

    @EntityGraph(attributePaths = {"fromEmployee", "fromDepartment", "fromWarehouse", "toEmployee", "toDepartment", "toWarehouse"})
    @Query("""
        select distinct handover
        from AssetHandover handover
        left join AssetHandoverLine line
          on line.assetHandoverId = handover.id
          and line.isDeleted = false
        where handover.tenantId = :tenantId
          and handover.companyId = :companyId
          and handover.isDeleted = false
          and (:status is null or handover.status = :status)
          and (:handoverType is null or handover.handoverType = :handoverType)
          and (:fixedAssetId is null or line.fixedAssetId = :fixedAssetId)
          and (:fromWarehouseId is null or handover.fromWarehouseId = :fromWarehouseId)
          and (:departmentScoped = false or handover.fromDepartmentId = :departmentId or handover.toDepartmentId = :departmentId
            or (:noWarehouses = false and (handover.fromWarehouseId in :warehouseIds or handover.toWarehouseId in :warehouseIds)))
        """)
    Page<AssetHandover> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("status") com.enterprise.mm.enums.AssetHandoverStatus status,
        @Param("handoverType") com.enterprise.mm.enums.AssetHandoverType handoverType,
        @Param("fixedAssetId") UUID fixedAssetId,
        @Param("fromWarehouseId") UUID fromWarehouseId,
        @Param("departmentScoped") boolean departmentScoped,
        @Param("departmentId") UUID departmentId,
        @Param("warehouseIds") java.util.Set<UUID> warehouseIds,
        @Param("noWarehouses") boolean noWarehouses,
        Pageable pageable
    );
}
