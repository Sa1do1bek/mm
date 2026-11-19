package com.enterprise.mm.warehouse.packaging;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface ReturnablePackagingReturnRepository extends JpaRepository<ReturnablePackagingReturn, UUID> {
    Optional<ReturnablePackagingReturn> findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(String tenantId, UUID companyId, UUID id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ReturnablePackagingReturn> findWithLockByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(String tenantId, UUID companyId, UUID id);
    List<ReturnablePackagingReturn> findByTenantIdAndCompanyIdAndLotIdAndIsDeletedFalseOrderByCreatedAtDesc(
        String tenantId, UUID companyId, UUID lotId);
    @Query("""
        select doc from ReturnablePackagingReturn doc
        join ReturnablePackagingLot lot on lot.id = doc.lotId
        join Supplier supplier on supplier.id = doc.supplierId
        join Material material on material.id = lot.materialId
        where doc.tenantId = :tenantId and doc.companyId = :companyId and doc.isDeleted = false
          and lot.isDeleted = false and supplier.isDeleted = false and material.isDeleted = false
          and (:warehouseId is null or lot.warehouseId = :warehouseId)
          and (:supplierId is null or doc.supplierId = :supplierId)
          and (:materialId is null or lot.materialId = :materialId)
          and (:status is null or doc.status = :status)
          and (:ownership is null or lot.ownership = :ownership)
          and (:search is null or lower(doc.code) like :search or lower(doc.documentNumber) like :search
            or lower(lot.code) like :search or lower(supplier.name) like :search or lower(material.name) like :search)
        """)
    Page<ReturnablePackagingReturn> search(@Param("tenantId") String tenantId, @Param("companyId") UUID companyId,
        @Param("warehouseId") UUID warehouseId, @Param("supplierId") UUID supplierId,
        @Param("materialId") UUID materialId, @Param("status") String status,
        @Param("ownership") String ownership, @Param("search") String search, Pageable pageable);
}
