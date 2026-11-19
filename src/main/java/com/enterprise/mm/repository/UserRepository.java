package com.enterprise.mm.repository;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.enums.RoleName;
import com.enterprise.mm.security.UserSecurityState;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findAnyById(UUID id);
    @EntityGraph(type = EntityGraph.EntityGraphType.LOAD, attributePaths = {"department", "employee", "employee.department", "employee.warehouse"})
    Optional<User> findByIdAndIsDeletedFalse(UUID id);
    @EntityGraph(type = EntityGraph.EntityGraphType.LOAD, attributePaths = {"department", "employee", "employee.department", "employee.warehouse"})
    Optional<User> findByTenantIdAndCompanyIdAndUsernameAndIsDeletedFalse(String tenantId, UUID companyId, String username);
    @EntityGraph(type = EntityGraph.EntityGraphType.LOAD, attributePaths = {"department", "employee", "employee.department", "employee.warehouse"})
    Optional<User> findByTenantIdAndCompanyIdAndUsernameIgnoreCaseAndIsDeletedFalse(String tenantId, UUID companyId, String username);
    Optional<User> findByTenantIdAndCompanyIdAndFullNameIgnoreCaseAndIsDeletedFalse(String tenantId, UUID companyId, String fullName);
    @EntityGraph(type = EntityGraph.EntityGraphType.LOAD, attributePaths = {"department", "employee", "employee.department", "employee.warehouse"})
    Optional<User> findByTenantIdAndCompanyIdIsNullAndUsernameAndIsDeletedFalse(String tenantId, String username);
    Optional<User> findByTenantIdAndEmployee_IdAndIsDeletedFalse(String tenantId, UUID employeeId);
    @EntityGraph(type = EntityGraph.EntityGraphType.LOAD, attributePaths = {"department", "employee", "employee.department", "employee.warehouse"})
    Optional<User> findByUsernameAndIsDeletedFalse(String username);
    @EntityGraph(type = EntityGraph.EntityGraphType.LOAD, attributePaths = {"department", "employee", "employee.department", "employee.warehouse"})
    Optional<User> findByUsernameIgnoreCaseAndIsDeletedFalse(String username);
    @EntityGraph(type = EntityGraph.EntityGraphType.LOAD, attributePaths = {"department", "employee", "employee.department", "employee.warehouse"})
    Optional<User> findFirstByUsernameIgnoreCaseAndIsDeletedFalseOrderByCreatedAtDesc(String username);
    Optional<User> findFirstByFullNameIgnoreCaseAndIsDeletedFalseOrderByCreatedAtDesc(String username);
    List<User> findByTenantIdAndCompanyIdAndEmailIgnoreCaseAndIsDeletedFalse(String tenantId, UUID companyId, String email);
    @EntityGraph(type = EntityGraph.EntityGraphType.LOAD, attributePaths = {"department", "employee", "employee.department", "employee.warehouse"})
    List<User> findAllByTenantIdAndUsernameAndIsDeletedFalse(String tenantId, String username);
    @EntityGraph(type = EntityGraph.EntityGraphType.LOAD, attributePaths = {"department", "employee", "employee.department", "employee.warehouse"})
    List<User> findAllByTenantIdAndUsernameIgnoreCaseAndIsDeletedFalse(String tenantId, String username);
    List<User> findAllByTenantIdAndFullNameIgnoreCaseAndIsDeletedFalse(String tenantId, String fullName);
    List<User> findAllByTenantIdAndCompanyIdAndIsDeletedFalse(String tenantId, UUID companyId);
    List<User> findAllByCompanyIdAndUsernameAndIsDeletedFalse(UUID companyId, String username);
    List<User> findAllByUsernameAndIsDeletedFalse(String username);

    @Query("""
        SELECT
            u.id AS id,
            u.username AS username,
            u.tenantId AS tenantId,
            u.companyId AS companyId,
            u.enabled AS enabled,
            u.isDeleted AS deleted,
            u.tokenVersion AS tokenVersion
        FROM User u
        WHERE u.id = :userId
        """)
    Optional<UserSecurityState> findSecurityStateById(@Param("userId") UUID userId);

    @Query("""
        SELECT
            u.id AS id,
            u.username AS username,
            u.tenantId AS tenantId,
            u.companyId AS companyId,
            u.enabled AS enabled,
            u.isDeleted AS deleted,
            u.tokenVersion AS tokenVersion
        FROM User u
        WHERE u.tenantId = :tenantId
          AND u.username = :username
          AND (
              u.companyId = :companyId
              OR (u.companyId IS NULL AND :companyId IS NULL)
          )
        """)
    Optional<UserSecurityState> findLegacySecurityState(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("username") String username
    );

    @Query("""
        SELECT CASE WHEN COUNT(u) > 0 THEN true ELSE false END
        FROM User u
        JOIN u.roles role
        WHERE u.id = :userId
          AND u.companyId IS NULL
          AND role = com.enterprise.mm.enums.RoleName.ADMIN
        """)
    boolean isGlobalAdminSecurityState(@Param("userId") UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT u
        FROM User u
        WHERE u.id = :userId
          AND u.tenantId = :tenantId
          AND u.username = :username
          AND u.isDeleted = false
        """)
    Optional<User> findCurrentUserForPasswordChange(
        @Param("userId") UUID userId,
        @Param("tenantId") String tenantId,
        @Param("username") String username
    );

    default Optional<User> findByTenantIdAndUsernameAndIsDeletedFalse(String tenantId, String username) {
        return findByTenantIdAndCompanyIdAndUsernameAndIsDeletedFalse(tenantId, CompanyContext.currentCompany(), username)
            .or(() -> findAllByTenantIdAndUsernameAndIsDeletedFalse(tenantId, username).stream().findFirst());
    }

    default Optional<User> findByTenantIdAndUsernameIgnoreCaseAndIsDeletedFalse(String tenantId, String username) {
        return findAllByTenantIdAndUsernameIgnoreCaseAndIsDeletedFalse(tenantId, username).stream().findFirst();
    }
    default Optional<User> findByTenantIdAndFullNameIgnoreCaseAndIsDeletedFalse(String tenantId, String fullName) {
        return findAllByTenantIdAndFullNameIgnoreCaseAndIsDeletedFalse(tenantId, fullName).stream().findFirst();
    }

    default Optional<User> resolveActiveUserByUsername(String tenantId, UUID companyId, String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        String normalized = username.trim();
        return findByTenantIdAndCompanyIdAndUsernameIgnoreCaseAndIsDeletedFalse(tenantId, companyId, normalized)
            .or(() -> findByTenantIdAndUsernameIgnoreCaseAndIsDeletedFalse(tenantId, normalized))
            .or(() -> findFirstByUsernameIgnoreCaseAndIsDeletedFalseOrderByCreatedAtDesc(normalized));
    }
    default Optional<User> resolveActiveUserByFullName(String tenantId, UUID companyId, String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return Optional.empty();
        }
        String normalized = fullName.trim();
        return findByTenantIdAndCompanyIdAndFullNameIgnoreCaseAndIsDeletedFalse(tenantId, companyId, normalized)
                .or(() -> findByTenantIdAndFullNameIgnoreCaseAndIsDeletedFalse(tenantId, normalized))
                .or(() -> findFirstByFullNameIgnoreCaseAndIsDeletedFalseOrderByCreatedAtDesc(normalized));
    }
    @Query(value = """
        SELECT DISTINCT e
        FROM User e
        LEFT JOIN e.roles r
        WHERE
            e.tenantId = :tenantId
            AND (:includeDeleted = true OR e.isDeleted = false)
            AND (CAST(:search AS string) IS NULL OR
                 LOWER(COALESCE(e.code, '')) LIKE CONCAT('%', LOWER(CAST(:search AS string)), '%')
                 OR LOWER(COALESCE(e.name, '')) LIKE CONCAT('%', LOWER(CAST(:search AS string)), '%'))
            AND (:statusEmpty = true OR e.status IN :status)
            AND (CAST(:dateFrom AS timestamp) IS NULL OR e.createdAt >= :dateFrom)
            AND (CAST(:dateTo AS timestamp) IS NULL OR e.createdAt <= :dateTo)
            AND (:companyId IS NULL OR e.companyId = :companyId OR (e.companyId IS NULL AND r = com.enterprise.mm.enums.RoleName.ADMIN))
        """, countQuery = """
        SELECT COUNT(DISTINCT e.id)
        FROM User e
        LEFT JOIN e.roles r
        WHERE
            e.tenantId = :tenantId
            AND (:includeDeleted = true OR e.isDeleted = false)
            AND (CAST(:search AS string) IS NULL OR
                 LOWER(COALESCE(e.code, '')) LIKE CONCAT('%', LOWER(CAST(:search AS string)), '%')
                 OR LOWER(COALESCE(e.name, '')) LIKE CONCAT('%', LOWER(CAST(:search AS string)), '%'))
            AND (:statusEmpty = true OR e.status IN :status)
            AND (CAST(:dateFrom AS timestamp) IS NULL OR e.createdAt >= :dateFrom)
            AND (CAST(:dateTo AS timestamp) IS NULL OR e.createdAt <= :dateTo)
            AND (:companyId IS NULL OR e.companyId = :companyId OR (e.companyId IS NULL AND r = com.enterprise.mm.enums.RoleName.ADMIN))
        """)
    Page<User> search(
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

    /**
     * Active company-scoped user lookup for workspace owner selectors.
     * CAST(:search AS string) is required for PostgreSQL: Hibernate otherwise binds the
     * LIKE pattern parameter as bytea and lower(bytea) fails at runtime.
     */
    @Query("""
        SELECT e
        FROM User e
        WHERE e.tenantId = :tenantId
          AND e.companyId = :companyId
          AND e.isDeleted = false
          AND e.enabled = true
          AND (CAST(:search AS string) IS NULL OR
               LOWER(COALESCE(e.code, '')) LIKE CONCAT('%', LOWER(CAST(:search AS string)), '%')
               OR LOWER(COALESCE(e.name, '')) LIKE CONCAT('%', LOWER(CAST(:search AS string)), '%')
               OR LOWER(COALESCE(e.fullName, '')) LIKE CONCAT('%', LOWER(CAST(:search AS string)), '%')
               OR LOWER(COALESCE(e.username, '')) LIKE CONCAT('%', LOWER(CAST(:search AS string)), '%'))
        """)
    @EntityGraph(type = EntityGraph.EntityGraphType.LOAD, attributePaths = {"department", "employee", "employee.department", "employee.warehouse"})
    Page<User> searchActiveCompanyUsers(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("search") String search,
        Pageable pageable
    );


    @Query("""
        SELECT DISTINCT e
        FROM User e
        JOIN e.roles r
        WHERE e.tenantId = :tenantId
          AND e.companyId = :companyId
          AND e.isDeleted = false
          AND e.enabled = true
          AND r = :role
        """)
    @EntityGraph(type = EntityGraph.EntityGraphType.LOAD, attributePaths = {"department", "employee", "employee.department", "employee.warehouse"})
    List<User> findActiveCompanyUsersByRole(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("role") RoleName role
    );
}
