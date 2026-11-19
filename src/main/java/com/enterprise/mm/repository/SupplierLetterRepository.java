package com.enterprise.mm.repository;

import com.enterprise.mm.entity.SupplierLetter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupplierLetterRepository extends JpaRepository<SupplierLetter, UUID> {
    Optional<SupplierLetter> findAnyById(UUID id);
    Optional<SupplierLetter> findByIdAndIsDeletedFalse(UUID id);
    List<SupplierLetter> findByTenantIdAndSupplier_IdAndIsDeletedFalseOrderByCreatedAtDesc(String tenantId, UUID supplierId);
    Optional<SupplierLetter> findByTenantIdAndCodeAndIsDeletedFalse(String tenantId, String code);

    @Query("""
        SELECT e
        FROM SupplierLetter e
        WHERE
            e.tenantId = :tenantId
            AND (:companyId IS NULL OR e.companyId = :companyId)
            AND (:includeDeleted = true OR e.isDeleted = false)
            AND (:searchPattern IS NULL OR
                 LOWER(COALESCE(e.code, '')) LIKE :searchPattern
                 OR LOWER(COALESCE(e.name, '')) LIKE :searchPattern)
            AND (CAST(:dateFrom AS timestamp) IS NULL OR e.createdAt >= :dateFrom)
            AND (CAST(:dateTo AS timestamp) IS NULL OR e.createdAt <= :dateTo)
            AND (:statusEmpty = true OR e.status IN :status)
            AND (:supplierId IS NULL OR e.supplierId = :supplierId)
        """)
    Page<SupplierLetter> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("includeDeleted") boolean includeDeleted,
        @Param("searchPattern") String searchPattern,
        @Param("dateFrom") LocalDateTime dateFrom,
        @Param("dateTo") LocalDateTime dateTo,
        @Param("status") List<String> status,
        @Param("statusEmpty") boolean statusEmpty,
        @Param("supplierId") UUID supplierId,
        Pageable pageable
    );
}
