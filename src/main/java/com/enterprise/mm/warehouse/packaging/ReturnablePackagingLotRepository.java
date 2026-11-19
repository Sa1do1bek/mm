package com.enterprise.mm.warehouse.packaging;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.LocalDate;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ReturnablePackagingLotRepository extends JpaRepository<ReturnablePackagingLot, UUID> {
    Optional<ReturnablePackagingLot> findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(String tenantId, UUID companyId, UUID id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ReturnablePackagingLot> findWithLockByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(String tenantId, UUID companyId, UUID id);
    List<ReturnablePackagingLot> findByTenantIdAndCompanyIdAndIsDeletedFalseOrderByCreatedAtDesc(String tenantId, UUID companyId);
    @Query("""
        select lot from ReturnablePackagingLot lot
        left join Supplier supplier on supplier.id = lot.supplierId
        join Material material on material.id = lot.materialId
        left join Material content on content.id = lot.contentMaterialId
        where lot.tenantId = :tenantId and lot.companyId = :companyId and lot.isDeleted = false
          and (lot.supplierId is null or supplier.isDeleted = false) and material.isDeleted = false
          and (lot.contentMaterialId is null or content.isDeleted = false)
          and (:supplierId is null or lot.supplierId = :supplierId)
          and (:materialId is null or lot.materialId = :materialId)
          and (:contentMaterialId is null or lot.contentMaterialId = :contentMaterialId)
          and (:warehouseId is null or lot.warehouseId = :warehouseId)
          and (:ownership is null or lot.ownership = :ownership)
          and (:sourceType is null or lot.sourceType = :sourceType)
          and (:state is null or (:state = 'FILLED' and lot.filledQuantity > 0)
            or (:state = 'PARTIAL' and lot.partialQuantity > 0)
            or (:state = 'EMPTY' and lot.emptyQuantity > 0)
            or (:state = 'DAMAGED' and lot.damagedQuantity > 0)
            or (:state = 'UNKNOWN' and lot.unknownQuantity > 0))
          and (:dueFrom is null or lot.dueDate >= :dueFrom)
          and (:dueTo is null or lot.dueDate <= :dueTo)
          and (:onlyOnHand = false or lot.receivedQuantity > lot.dispatchedQuantity)
          and (:search is null or lower(lot.code) like :search or lower(supplier.name) like :search
            or lower(material.name) like :search or lower(content.name) like :search)
        """)
    Page<ReturnablePackagingLot> search(@Param("tenantId") String tenantId, @Param("companyId") UUID companyId,
        @Param("supplierId") UUID supplierId, @Param("materialId") UUID materialId,
        @Param("contentMaterialId") UUID contentMaterialId, @Param("warehouseId") UUID warehouseId,
        @Param("ownership") String ownership, @Param("sourceType") String sourceType,
        @Param("state") String state, @Param("dueFrom") LocalDate dueFrom, @Param("dueTo") LocalDate dueTo,
        @Param("onlyOnHand") boolean onlyOnHand, @Param("search") String search, Pageable pageable);
    Optional<ReturnablePackagingLot> findByTenantIdAndCompanyIdAndGoodsReceiptLineIdAndMaterialIdAndIsDeletedFalse(
        String tenantId, UUID companyId, UUID receiptLineId, UUID materialId);
    boolean existsByTenantIdAndCompanyIdAndMaterialIdAndIsDeletedFalse(String tenantId, UUID companyId, UUID materialId);
}
