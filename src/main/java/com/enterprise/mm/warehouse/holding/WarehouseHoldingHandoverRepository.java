package com.enterprise.mm.warehouse.holding;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WarehouseHoldingHandoverRepository extends JpaRepository<WarehouseHoldingHandover, UUID> {

    Optional<WarehouseHoldingHandover> findByTenantIdAndCompanyIdAndIdempotencyKeyAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        String idempotencyKey
    );

    Optional<WarehouseHoldingHandover> findByTenantIdAndCompanyIdAndHandoverNumberAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        String handoverNumber
    );

    List<WarehouseHoldingHandover> findByHoldingIdAndStatusAndIsDeletedFalse(
        UUID holdingId,
        String status
    );

    default List<WarehouseHoldingHandover> findByHoldingIdAndHandoverStatusAndIsDeletedFalse(
        UUID holdingId,
        HandoverStatus status
    ) {
        return findByHoldingIdAndStatusAndIsDeletedFalse(holdingId, status == null ? null : status.name());
    }

    List<WarehouseHoldingHandover> findByTenantIdAndCompanyIdAndDepartmentIdAndStatusAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        UUID departmentId,
        String status
    );

    default List<WarehouseHoldingHandover> findByTenantIdAndCompanyIdAndDepartmentIdAndHandoverStatusAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        UUID departmentId,
        HandoverStatus status
    ) {
        return findByTenantIdAndCompanyIdAndDepartmentIdAndStatusAndIsDeletedFalse(
            tenantId, companyId, departmentId, status == null ? null : status.name()
        );
    }
}
