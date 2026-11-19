package com.enterprise.mm.repository;

import com.enterprise.mm.entity.ProcurementDirectAward;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProcurementDirectAwardRepository extends JpaRepository<ProcurementDirectAward, UUID>, JpaSpecificationExecutor<ProcurementDirectAward> {
    Optional<ProcurementDirectAward> findByIdAndIsDeletedFalse(UUID id);
    Optional<ProcurementDirectAward> findByTenantIdAndCompanyIdAndProcurementProcedureIdAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        UUID procurementProcedureId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select award
        from ProcurementDirectAward award
        where award.tenantId = :tenantId
          and award.companyId = :companyId
          and award.id = :id
          and award.isDeleted = false
        """)
    Optional<ProcurementDirectAward> lockScopedById(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("id") UUID id
    );
}
