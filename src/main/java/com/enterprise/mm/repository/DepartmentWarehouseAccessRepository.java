package com.enterprise.mm.repository;

import com.enterprise.mm.entity.DepartmentWarehouseAccess;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DepartmentWarehouseAccessRepository extends JpaRepository<DepartmentWarehouseAccess, UUID> {
    List<DepartmentWarehouseAccess> findByTenantIdAndCompanyIdAndDepartment_Id(
        String tenantId, UUID companyId, UUID departmentId);

    @Query("""
        select access.warehouse.id
        from DepartmentWarehouseAccess access
        where access.tenantId = :tenantId
          and access.companyId = :companyId
          and access.department.id = :departmentId
          and access.department.isDeleted = false
          and access.department.status = 'ACTIVE'
          and access.department.tenantId = :tenantId
          and access.department.companyId = :companyId
          and access.warehouse.tenantId = :tenantId
          and access.warehouse.companyId = :companyId
          and access.warehouse.isDeleted = false
          and access.warehouse.status = 'ACTIVE'
        """)
    Set<UUID> findActiveWarehouseIds(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("departmentId") UUID departmentId
    );
}
