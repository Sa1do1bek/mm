package com.enterprise.mm.warehouse.holding;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WarehouseHoldingRepository extends JpaRepository<WarehouseHolding, UUID> {

    Optional<WarehouseHolding> findFirstByTenantIdAndCompanyIdAndMaterialIdAndDepartmentIdAndEmployeeIdAndAccountCodeAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        UUID materialId,
        UUID departmentId,
        UUID employeeId,
        String accountCode
    );

    List<WarehouseHolding> findByTenantIdAndCompanyIdAndDepartmentIdAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        UUID departmentId
    );

    List<WarehouseHolding> findByTenantIdAndCompanyIdAndEmployeeIdAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        UUID employeeId
    );

    List<WarehouseHolding> findByTenantIdAndCompanyIdAndOriginReferenceAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        String originReference
    );
}
