package com.enterprise.mm.repository;

import com.enterprise.mm.entity.CustomsDeclaration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomsDeclarationRepository extends JpaRepository<CustomsDeclaration, UUID> {
    Optional<CustomsDeclaration> findAnyById(UUID id);
    Optional<CustomsDeclaration> findByIdAndIsDeletedFalse(UUID id);
    Optional<CustomsDeclaration> findByTenantIdAndCompanyIdAndDeclarationNumberAndIsDeletedFalse(String tenantId, UUID companyId, String declarationNumber);

    List<CustomsDeclaration> findByTenantIdAndCompanyIdAndImportDelivery_IdAndIsDeletedFalse(String tenantId, UUID companyId, UUID importDeliveryId);

    @Query("""
        SELECT e
        FROM CustomsDeclaration e
        WHERE
            e.tenantId = :tenantId
            AND (:companyId IS NULL OR e.companyId = :companyId)
            AND (:includeDeleted = true OR e.isDeleted = false)
            AND (:search IS NULL OR
                 LOWER(COALESCE(e.code, '')) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(COALESCE(e.name, '')) LIKE LOWER(CONCAT('%', :search, '%')))
            AND (CAST(:dateFrom AS timestamp) IS NULL OR e.createdAt >= :dateFrom)
            AND (CAST(:dateTo AS timestamp) IS NULL OR e.createdAt <= :dateTo)
            AND (:statusEmpty = true OR e.status IN :status)
            AND (:supplierId IS NULL OR e.supplierId = :supplierId)
            AND (:purchaseOrderId IS NULL OR e.purchaseOrderId = :purchaseOrderId)
        """)
    Page<CustomsDeclaration> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("includeDeleted") boolean includeDeleted,
        @Param("search") String search,
        @Param("dateFrom") LocalDateTime dateFrom,
        @Param("dateTo") LocalDateTime dateTo,
        @Param("status") List<String> status,
        @Param("statusEmpty") boolean statusEmpty,
        @Param("supplierId") UUID supplierId,
        @Param("purchaseOrderId") UUID purchaseOrderId,
        Pageable pageable
    );
}
