package com.enterprise.mm.repository;

import com.enterprise.mm.entity.MaterialUnit;
import com.enterprise.mm.enums.MaterialUnitOperationType;
import com.enterprise.mm.enums.MaterialUnitStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MaterialUnitRepository extends JpaRepository<MaterialUnit, UUID> {
    @Query("""
        select u from MaterialUnit u where u.tenantId = :tenantId and u.companyId = :companyId
          and u.warehouseId = :warehouseId and u.materialId in :materialIds
          and u.isDeleted = false and u.unitStatus = com.enterprise.mm.enums.MaterialUnitStatus.IN_STOCK
        order by u.receivedAt, u.id
        """)
    List<MaterialUnit> findTransferPickingUnits(@Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId, @Param("warehouseId") UUID warehouseId,
        @Param("materialIds") List<UUID> materialIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.QueryHints(@jakarta.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
    @Query("""
        select u from MaterialUnit u where u.tenantId = :tenantId and u.companyId = :companyId
          and u.id in :ids and u.isDeleted = false order by u.id
        """)
    List<MaterialUnit> findForTransferUpdate(@Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId, @Param("ids") Collection<UUID> ids);

    boolean existsByTenantIdAndCompanyIdAndMaterialIdAndNormalizedSerialNumberAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        UUID materialId,
        String normalizedSerialNumber
    );

    Optional<MaterialUnit> findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(String tenantId, UUID companyId, UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select unit
        from MaterialUnit unit
        where unit.tenantId = :tenantId
          and unit.companyId = :companyId
          and unit.id in :ids
          and unit.isDeleted = false
        """)
    List<MaterialUnit> findAllForUpdate(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("ids") Collection<UUID> ids
    );

    @Query("""
        select unit
        from MaterialUnit unit
        left join StockBatch batch on batch.id = unit.stockBatchId
        where unit.tenantId = :tenantId
          and unit.companyId = :companyId
          and unit.isDeleted = false
          and (:materialId is null or unit.materialId = :materialId)
          and (:warehouseId is null or unit.warehouseId = :warehouseId)
          and (:storageLocationId is null or unit.storageLocationId = :storageLocationId)
          and (:storageBinId is null or unit.storageBinId = :storageBinId)
          and (:stockBatchId is null or unit.stockBatchId = :stockBatchId)
          and (:statusEmpty = true or unit.unitStatus in :statuses)
          and (
              cast(:search as string) is null
              or lower(coalesce(unit.manufacturerSerialNumber, '')) like lower(concat('%', cast(:search as string), '%'))
              or lower(coalesce(unit.internalUnitNumber, '')) like lower(concat('%', cast(:search as string), '%'))
              or lower(coalesce(batch.batchNumber, '')) like lower(concat('%', cast(:search as string), '%'))
              or lower(coalesce(batch.manufacturerBatchNumber, '')) like lower(concat('%', cast(:search as string), '%'))
          )
          and (
              :sourceReferenceType is null
              or exists (
                  select operation.id
                  from MaterialUnitOperation operation
                  where operation.tenantId = unit.tenantId
                    and operation.companyId = unit.companyId
                    and operation.materialUnitId = unit.id
                    and operation.isDeleted = false
                    and operation.referenceType = :sourceReferenceType
                    and (:sourceReferenceId is null or operation.referenceId = :sourceReferenceId)
                    and (:sourceOperationType is null or operation.operationType = :sourceOperationType)
              )
          )
        """)
    Page<MaterialUnit> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("search") String search,
        @Param("materialId") UUID materialId,
        @Param("warehouseId") UUID warehouseId,
        @Param("storageLocationId") UUID storageLocationId,
        @Param("storageBinId") UUID storageBinId,
        @Param("stockBatchId") UUID stockBatchId,
        @Param("statuses") List<MaterialUnitStatus> statuses,
        @Param("statusEmpty") boolean statusEmpty,
        @Param("sourceReferenceType") String sourceReferenceType,
        @Param("sourceReferenceId") UUID sourceReferenceId,
        @Param("sourceOperationType") MaterialUnitOperationType sourceOperationType,
        Pageable pageable
    );
}
