package com.enterprise.mm.repository;

import com.enterprise.mm.entity.Criteria;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CriteriaRepository extends JpaRepository<Criteria, UUID> {
    Optional<Criteria> findAnyById(UUID id);
    Optional<Criteria> findByIdAndIsDeletedFalse(UUID id);
    Optional<Criteria> findByTenantIdAndIdAndIsDeletedFalse(String tenantId, UUID id);
    Optional<Criteria> findByTenantIdAndCompanyIdAndCodeAndIsDeletedFalse(String tenantId, UUID companyId, String code);
    List<Criteria> findByTenantIdAndCompanyIdAndIsActiveTrueAndIsDeletedFalseOrderByCodeAsc(String tenantId, UUID companyId);

    @Query("""
        SELECT e
        FROM Criteria e
        WHERE
            e.tenantId = :tenantId
            AND (:companyId IS NULL OR e.companyId = :companyId)
            AND (:includeDeleted = true OR e.isDeleted = false)
            AND (:includeInactive = true OR e.isActive = true)
            AND (:searchEmpty = true OR
                 LOWER(COALESCE(e.code, '')) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(COALESCE(e.name, '')) LIKE LOWER(CONCAT('%', :search, '%')))
            AND (CAST(:dateFrom AS timestamp) IS NULL OR e.createdAt >= :dateFrom)
            AND (CAST(:dateTo AS timestamp) IS NULL OR e.createdAt <= :dateTo)
        """)
    Page<Criteria> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("includeDeleted") boolean includeDeleted,
        @Param("includeInactive") boolean includeInactive,
        @Param("search") String search,
        @Param("searchEmpty") boolean searchEmpty,
        @Param("dateFrom") LocalDateTime dateFrom,
        @Param("dateTo") LocalDateTime dateTo,
        Pageable pageable
    );
}
