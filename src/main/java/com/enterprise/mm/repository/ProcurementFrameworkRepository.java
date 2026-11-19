package com.enterprise.mm.repository;

import com.enterprise.mm.entity.ProcurementFramework;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProcurementFrameworkRepository extends JpaRepository<ProcurementFramework, UUID> {
    Optional<ProcurementFramework> findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(String tenantId, UUID companyId, UUID id);
    Optional<ProcurementFramework> findByTenantIdAndCompanyIdAndProcedureIdAndIsDeletedFalse(String tenantId, UUID companyId, UUID procedureId);
    Optional<ProcurementFramework> findByTenantIdAndCompanyIdAndContractIdAndIsDeletedFalse(String tenantId, UUID companyId, UUID contractId);

    @Query("select f from ProcurementFramework f where f.tenantId = :tenantId and f.companyId = :companyId and f.id = :id and f.isDeleted = false")
    Optional<ProcurementFramework> findScopedById(
        @Param("tenantId") String tenantId, @Param("companyId") UUID companyId, @Param("id") UUID id);

    @Query("select f from ProcurementFramework f where f.tenantId = :tenantId and f.companyId = :companyId and f.procedureId = :procedureId and f.isDeleted = false")
    Optional<ProcurementFramework> findScopedByProcedureId(
        @Param("tenantId") String tenantId, @Param("companyId") UUID companyId, @Param("procedureId") UUID procedureId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from ProcurementFramework f where f.tenantId = :tenantId and f.companyId = :companyId and f.id = :id and f.isDeleted = false")
    Optional<ProcurementFramework> lockScoped(@Param("tenantId") String tenantId, @Param("companyId") UUID companyId, @Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from ProcurementFramework f where f.tenantId = :tenantId and f.companyId = :companyId and f.procedureId = :procedureId and f.isDeleted = false")
    Optional<ProcurementFramework> lockScopedByProcedureId(@Param("tenantId") String tenantId, @Param("companyId") UUID companyId, @Param("procedureId") UUID procedureId);
}
