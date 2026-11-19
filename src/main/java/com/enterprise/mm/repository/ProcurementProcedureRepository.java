package com.enterprise.mm.repository;

import com.enterprise.mm.entity.ProcurementProcedure;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProcurementProcedureRepository extends JpaRepository<ProcurementProcedure, UUID>, JpaSpecificationExecutor<ProcurementProcedure> {
    java.util.List<ProcurementProcedure> findByTenantIdAndCompanyIdAndProcurementPlanIdAndIsDeletedFalse(String tenantId, UUID companyId, UUID planId);
    Optional<ProcurementProcedure> findAnyById(UUID id);
    Optional<ProcurementProcedure> findByIdAndIsDeletedFalse(UUID id);
    Optional<ProcurementProcedure> findByMethodDecisionIdAndIsDeletedFalse(UUID methodDecisionId);
    Optional<ProcurementProcedure> findByRfqIdAndIsDeletedFalse(UUID rfqId);
    Optional<ProcurementProcedure> findByPriceDiscoveryRfqIdAndIsDeletedFalse(UUID priceDiscoveryRfqId);
    Optional<ProcurementProcedure> findByTenantIdAndCompanyIdAndProcedureNumberAndIsDeletedFalse(String tenantId, UUID companyId, String procedureNumber);

    Optional<ProcurementProcedure> findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        UUID purchaseOrderId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select procedure
        from ProcurementProcedure procedure
        where procedure.tenantId = :tenantId
          and procedure.companyId = :companyId
          and procedure.id = :id
          and procedure.isDeleted = false
        """)
    Optional<ProcurementProcedure> lockScopedById(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("id") UUID id
    );
}
