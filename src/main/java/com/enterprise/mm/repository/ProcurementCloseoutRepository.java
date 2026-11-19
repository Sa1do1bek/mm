package com.enterprise.mm.repository;

import com.enterprise.mm.entity.ProcurementCloseout;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProcurementCloseoutRepository extends JpaRepository<ProcurementCloseout, UUID> {
    Optional<ProcurementCloseout> findByTenantIdAndCompanyIdAndProcurementProcedureIdAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        UUID procurementProcedureId
    );

    List<ProcurementCloseout> findByTenantIdAndCompanyIdAndIsDeletedFalse(String tenantId, UUID companyId);

    long countByTenantIdAndCompanyIdAndIsDeletedFalse(String tenantId, UUID companyId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select closeout from ProcurementCloseout closeout where closeout.id = :id and closeout.isDeleted = false")
    Optional<ProcurementCloseout> findByIdForUpdate(@Param("id") UUID id);
}
