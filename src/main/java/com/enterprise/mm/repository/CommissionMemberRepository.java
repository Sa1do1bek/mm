package com.enterprise.mm.repository;

import com.enterprise.mm.entity.CommissionMember;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommissionMemberRepository extends JpaRepository<CommissionMember, UUID> {
    List<CommissionMember> findByTenantIdAndCommissionIdAndIsDeletedFalseOrderByCreatedAtAsc(String tenantId, UUID commissionId);

    long countByTenantIdAndCommissionIdAndActiveTrueAndIsDeletedFalse(String tenantId, UUID commissionId);

    Optional<CommissionMember> findFirstByTenantIdAndCommissionIdAndUserIdAndActiveTrueAndIsDeletedFalse(String tenantId, UUID commissionId, UUID userId);
}
