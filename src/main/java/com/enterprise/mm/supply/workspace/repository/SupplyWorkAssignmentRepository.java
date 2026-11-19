package com.enterprise.mm.supply.workspace.repository;

import com.enterprise.mm.supply.workspace.entity.SupplyWorkAssignment;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupplyWorkAssignmentRepository extends JpaRepository<SupplyWorkAssignment, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select assignment from SupplyWorkAssignment assignment
        where assignment.tenantId = :tenantId
          and assignment.companyId = :companyId
          and assignment.commitmentKey = :commitmentKey
          and assignment.isDeleted = false
        """)
    Optional<SupplyWorkAssignment> findActiveForUpdate(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("commitmentKey") String commitmentKey);

    Optional<SupplyWorkAssignment> findByTenantIdAndCompanyIdAndCommitmentKeyAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        String commitmentKey);
}
