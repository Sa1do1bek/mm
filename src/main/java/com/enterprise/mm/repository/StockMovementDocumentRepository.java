package com.enterprise.mm.repository;

import com.enterprise.mm.entity.StockMovementDocument;
import com.enterprise.mm.enums.MovementType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockMovementDocumentRepository extends JpaRepository<StockMovementDocument, UUID> {
    Optional<StockMovementDocument> findAnyById(UUID id);
    Optional<StockMovementDocument> findByIdAndIsDeletedFalse(UUID id);
    boolean existsByTenantIdAndCompanyIdAndReversalOfIdAndIsDeletedFalse(String tenantId, UUID companyId, UUID reversalOfId);

    @Query("""
        SELECT e
        FROM StockMovementDocument e
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
            AND (:referenceId IS NULL OR e.referenceId = :referenceId)
            AND (:movementType IS NULL OR e.movementType = :movementType)
        """)
    Page<StockMovementDocument> search(
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
        @Param("referenceId") UUID referenceId,
        @Param("movementType") MovementType movementType,
        Pageable pageable
    );
}

