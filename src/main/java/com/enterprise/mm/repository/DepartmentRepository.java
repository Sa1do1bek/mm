package com.enterprise.mm.repository;

import com.enterprise.mm.entity.Department;
import com.enterprise.mm.security.AccessScope;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DepartmentRepository extends JpaRepository<Department, UUID> {
    List<Department> findByTenantIdAndCompanyIdAndIsDeletedFalse(String tenantId, UUID companyId);
    Optional<Department> findAnyById(UUID id);
    Optional<Department> findByIdAndIsDeletedFalse(UUID id);
    Optional<Department> findByTenantIdAndCompanyIdAndNameIgnoreCaseAndIsDeletedFalse(String tenantId, UUID companyId, String name);

    List<Department> findAllByTenantIdAndCodeAndIsDeletedFalse(String tenantId, String code);

    default Optional<Department> findByTenantIdAndCodeAndIsDeletedFalse(String tenantId, String code) {
        return findAllByTenantIdAndCodeAndIsDeletedFalse(tenantId, code).stream()
            .filter(item -> AccessScope.canAccessCompany(item.getCompanyId()))
            .findFirst();
    }

    List<Department> findAllByTenantIdAndExternalIdAndIsDeletedFalse(String tenantId, String externalId);

    default Optional<Department> findByTenantIdAndExternalIdAndIsDeletedFalse(String tenantId, String externalId) {
        return findAllByTenantIdAndExternalIdAndIsDeletedFalse(tenantId, externalId).stream()
            .filter(item -> AccessScope.canAccessCompany(item.getCompanyId()))
            .findFirst();
    }

    List<Department> findByTenantIdAndIsDeletedFalseOrderBySortOrderAscNameAsc(String tenantId);

    List<Department> findByTenantIdAndParentDepartment_IdAndIsDeletedFalseOrderBySortOrderAscNameAsc(String tenantId, UUID parentDepartmentId);

    List<Department> findByTenantIdAndParentDepartmentIsNullAndIsDeletedFalseOrderBySortOrderAscNameAsc(String tenantId);

    List<Department> findByTenantIdAndCompanyIdAndManagerEmployee_IdAndIsDeletedFalse(String tenantId, UUID companyId, UUID managerEmployeeId);

    @Query("""
        SELECT e
        FROM Department e
        WHERE
            e.tenantId = :tenantId
            AND (:companyId IS NULL OR e.companyId = :companyId)
            AND (:includeDeleted = true OR e.isDeleted = false)
            AND (CAST(:search AS string) IS NULL OR
                 LOWER(COALESCE(e.code, '')) LIKE CONCAT('%', LOWER(CAST(:search AS string)), '%')
                 OR LOWER(COALESCE(e.name, '')) LIKE CONCAT('%', LOWER(CAST(:search AS string)), '%'))
            AND (CAST(:dateFrom AS timestamp) IS NULL OR e.createdAt >= :dateFrom)
            AND (CAST(:dateTo AS timestamp) IS NULL OR e.createdAt <= :dateTo)
            AND (:statusEmpty = true OR e.status IN :status)
        """)
    Page<Department> search(
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

    Optional<Department> findByIdAndTenantIdAndCompanyIdAndIsDeletedFalse(
            UUID id,
            String tenantId,
            UUID companyId
    );
}
