package com.enterprise.mm.repository;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.entity.Role;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoleRepository extends JpaRepository<Role, UUID> {
    Optional<Role> findAnyById(UUID id);
    Optional<Role> findByIdAndIsDeletedFalse(UUID id);
    Optional<Role> findByTenantIdAndIdAndIsDeletedFalse(String tenantId, UUID id);
    Optional<Role> findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(String tenantId, UUID companyId, UUID id);
    Optional<Role> findByTenantIdAndCompanyIdIsNullAndIdAndIsDeletedFalse(String tenantId, UUID id);

    Optional<Role> findByTenantIdAndCompanyIdAndRoleNameAndIsDeletedFalse(String tenantId, UUID companyId, String roleName);
    Optional<Role> findByTenantIdAndCompanyIdIsNullAndRoleNameAndIsDeletedFalse(String tenantId, String roleName);
    List<Role> findAllByTenantIdAndRoleNameAndIsDeletedFalse(String tenantId, String roleName);

    default Optional<Role> findByTenantIdAndRoleNameAndIsDeletedFalse(String tenantId, String roleName) {
        return findByTenantIdAndCompanyIdAndRoleNameAndIsDeletedFalse(tenantId, CompanyContext.currentCompany(), roleName)
            .or(() -> findByTenantIdAndCompanyIdIsNullAndRoleNameAndIsDeletedFalse(tenantId, roleName));
    }
    @Query("""
        SELECT e
        FROM Role e
        WHERE
            e.tenantId = :tenantId
            AND (:includeDeleted = true OR e.isDeleted = false)
            AND (:search IS NULL OR
                 LOWER(COALESCE(e.code, '')) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(COALESCE(e.name, '')) LIKE LOWER(CONCAT('%', :search, '%')))
            AND (:statusEmpty = true OR e.status IN :status)
            AND (CAST(:dateFrom AS timestamp) IS NULL OR e.createdAt >= :dateFrom)
            AND (CAST(:dateTo AS timestamp) IS NULL OR e.createdAt <= :dateTo)
            AND (:companyId IS NULL OR e.companyId = :companyId OR e.companyId IS NULL)
        """)
    Page<Role> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("includeDeleted") boolean includeDeleted,
        @Param("search") String search,
        @Param("status") List<String> status,
        @Param("statusEmpty") boolean statusEmpty,
        @Param("dateFrom") LocalDateTime dateFrom,
        @Param("dateTo") LocalDateTime dateTo,
        Pageable pageable
    );

}
