package com.enterprise.mm.repository;

import com.enterprise.mm.entity.Employee;
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

public interface EmployeeRepository extends JpaRepository<Employee, UUID> {
    Optional<Employee> findAnyById(UUID id);
    Optional<Employee> findByIdAndIsDeletedFalse(UUID id);
    List<Employee> findAllByTenantIdAndExternalIdAndIsDeletedFalse(String tenantId, String externalId);

    default Optional<Employee> findByTenantIdAndExternalIdAndIsDeletedFalse(String tenantId, String externalId) {
        return findAllByTenantIdAndExternalIdAndIsDeletedFalse(tenantId, externalId).stream()
            .filter(item -> AccessScope.canAccessCompany(item.getCompanyId()))
            .findFirst();
    }

    List<Employee> findAllByTenantIdAndPersonnelNumberAndIsDeletedFalse(String tenantId, String personnelNumber);

    default Optional<Employee> findByTenantIdAndPersonnelNumberAndIsDeletedFalse(String tenantId, String personnelNumber) {
        return findAllByTenantIdAndPersonnelNumberAndIsDeletedFalse(tenantId, personnelNumber).stream()
            .filter(item -> AccessScope.canAccessCompany(item.getCompanyId()))
            .findFirst();
    }

    List<Employee> findAllByTenantIdAndCodeAndIsDeletedFalse(String tenantId, String code);

    default Optional<Employee> findByTenantIdAndCodeAndIsDeletedFalse(String tenantId, String code) {
        return findAllByTenantIdAndCodeAndIsDeletedFalse(tenantId, code).stream()
            .filter(item -> AccessScope.canAccessCompany(item.getCompanyId()))
            .findFirst();
    }

    List<Employee> findByTenantIdAndIsDeletedFalse(String tenantId);

    List<Employee> findByTenantIdAndCompanyIdAndDepartment_IdInAndIsDeletedFalse(String tenantId, UUID companyId, List<UUID> departmentIds);

    Optional<Employee> findByTenantIdAndCompanyIdAndEmailIgnoreCaseAndIsDeletedFalse(String tenantId, UUID companyId, String email);

    Optional<Employee> findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(String tenantId, UUID companyId, UUID id);

    List<Employee> findByTenantIdAndCompanyIdAndWarehouse_IdAndIsDeletedFalse(String tenantId, UUID companyId, UUID warehouseId);

    List<Employee> findByTenantIdAndCompanyIdAndIdInAndIsDeletedFalse(String tenantId, UUID companyId, List<UUID> ids);

    long countByTenantIdAndDepartment_IdAndIsDeletedFalse(String tenantId, UUID departmentId);

    @Query("""
        SELECT COUNT(e)
        FROM Employee e
        WHERE e.tenantId = :tenantId
          AND e.companyId = :companyId
          AND e.isDeleted = false
          AND e.position.id = :positionId
          AND (e.employmentStatus IS NULL OR e.employmentStatus <> com.enterprise.mm.enums.EmployeeStatus.DISMISSED)
        """)
    long countOccupiedByPosition(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("positionId") UUID positionId
    );

    @Query("""
        SELECT COUNT(e)
        FROM Employee e
        WHERE e.tenantId = :tenantId
          AND e.companyId = :companyId
          AND e.isDeleted = false
          AND e.position.id = :positionId
          AND (:excludeEmployeeId IS NULL OR e.id <> :excludeEmployeeId)
          AND (e.employmentStatus IS NULL OR e.employmentStatus <> com.enterprise.mm.enums.EmployeeStatus.DISMISSED)
        """)
    long countOccupiedByPositionExcludingEmployee(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("positionId") UUID positionId,
        @Param("excludeEmployeeId") UUID excludeEmployeeId
    );

    @Query("""
        SELECT e
        FROM Employee e
        WHERE
            e.tenantId = :tenantId
            AND (:companyId IS NULL OR e.companyId = :companyId)
            AND (:includeDeleted = true OR e.isDeleted = false)
            AND (:search IS NULL OR
                 LOWER(COALESCE(e.code, '')) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(COALESCE(e.name, '')) LIKE LOWER(CONCAT('%', :search, '%')))
            AND (:statusEmpty = true OR e.status IN :status)
            AND (CAST(:dateFrom AS timestamp) IS NULL OR e.createdAt >= :dateFrom)
            AND (CAST(:dateTo AS timestamp) IS NULL OR e.createdAt <= :dateTo)
            AND (:employmentStatus IS NULL OR UPPER(CAST(e.employmentStatus AS string)) = :employmentStatus)
        """)
    Page<Employee> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("includeDeleted") boolean includeDeleted,
        @Param("search") String search,
        @Param("status") List<String> status,
        @Param("statusEmpty") boolean statusEmpty,
        @Param("dateFrom") LocalDateTime dateFrom,
        @Param("dateTo") LocalDateTime dateTo,
        @Param("employmentStatus") String employmentStatus,
        Pageable pageable
    );

}
