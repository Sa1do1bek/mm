package com.enterprise.mm.repository;

import com.enterprise.mm.entity.Position;
import com.enterprise.mm.security.AccessScope;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PositionRepository extends JpaRepository<Position, UUID> {
    Optional<Position> findAnyById(UUID id);
    Optional<Position> findByIdAndIsDeletedFalse(UUID id);
    List<Position> findAllByTenantIdAndExternalIdAndIsDeletedFalse(String tenantId, String externalId);

    default Optional<Position> findByTenantIdAndExternalIdAndIsDeletedFalse(String tenantId, String externalId) {
        return findAllByTenantIdAndExternalIdAndIsDeletedFalse(tenantId, externalId).stream()
            .filter(item -> AccessScope.canAccessCompany(item.getCompanyId()))
            .findFirst();
    }

    List<Position> findAllByTenantIdAndCodeAndIsDeletedFalse(String tenantId, String code);

    default Optional<Position> findByTenantIdAndCodeAndIsDeletedFalse(String tenantId, String code) {
        return findAllByTenantIdAndCodeAndIsDeletedFalse(tenantId, code).stream()
            .filter(item -> AccessScope.canAccessCompany(item.getCompanyId()))
            .findFirst();
    }

    List<Position> findByTenantIdAndIsDeletedFalse(String tenantId);

    long countByTenantIdAndDepartment_IdAndIsDeletedFalse(String tenantId, UUID departmentId);

    @Query("""
        SELECT e
        FROM Position e
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
        """)
    Page<Position> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("includeDeleted") boolean includeDeleted,
        @Param("search") String search,
        @Param("dateFrom") LocalDateTime dateFrom,
        @Param("dateTo") LocalDateTime dateTo,
        @Param("status") List<String> status,
        @Param("statusEmpty") boolean statusEmpty,
        Pageable pageable
    );
}
