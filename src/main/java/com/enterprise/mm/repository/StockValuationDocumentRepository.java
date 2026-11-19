package com.enterprise.mm.repository;

import java.util.Optional;
import com.enterprise.mm.entity.StockValuationDocument;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockValuationDocumentRepository extends JpaRepository<StockValuationDocument, UUID> {
    Optional<StockValuationDocument> findAnyById(UUID id);
    Optional<StockValuationDocument> findByIdAndIsDeletedFalse(UUID id);
    List<StockValuationDocument> findByTenantIdAndCompanyIdAndMaterialIdAndWarehouseIdAndIsDeletedFalseOrderByDocumentDateDesc(String tenantId, UUID companyId, UUID materialId, UUID warehouseId);

    @Query("""
        SELECT e
        FROM StockValuationDocument e
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
            AND (:warehouseId IS NULL OR e.warehouseId = :warehouseId)
            AND (:warehouseIdsEmpty = true OR e.warehouseId IN :warehouseIds)
            AND (:materialIdsEmpty = true OR e.materialId IN :materialIds)
        """)
    Page<StockValuationDocument> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("includeDeleted") boolean includeDeleted,
        @Param("search") String search,
        @Param("dateFrom") LocalDateTime dateFrom,
        @Param("dateTo") LocalDateTime dateTo,
        @Param("status") List<String> status,
        @Param("statusEmpty") boolean statusEmpty,
        @Param("warehouseId") UUID warehouseId,
        @Param("warehouseIds") java.util.List<UUID> warehouseIds,
        @Param("warehouseIdsEmpty") boolean warehouseIdsEmpty,
        @Param("materialIds") List<UUID> materialIds,
        @Param("materialIdsEmpty") boolean materialIdsEmpty,
        Pageable pageable
    );

    @Query("""
        select count(e) from StockValuationDocument e
        where e.isDeleted = false
          and e.tenantId = :tenantId
          and e.companyId = :companyId
          and e.status not in ('POSTED', 'CANCELLED')
          and e.documentDate between :periodStart and :periodEnd
          and (:warehouseIdEmpty = true or e.warehouseId = :warehouseId)
        """)
    long countOpenInPeriod(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("warehouseIdEmpty") boolean warehouseIdEmpty,
        @Param("warehouseId") UUID warehouseId,
        @Param("periodStart") java.time.LocalDate periodStart,
        @Param("periodEnd") java.time.LocalDate periodEnd
    );
}
