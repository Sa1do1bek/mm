package com.enterprise.mm.repository;

import com.enterprise.mm.entity.InventoryCommissionMemberSetting;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryCommissionMemberSettingRepository extends JpaRepository<InventoryCommissionMemberSetting, UUID> {
    List<InventoryCommissionMemberSetting> findByTenantIdAndCommissionIdAndIsDeletedFalseOrderByCreatedAtAsc(
        String tenantId,
        UUID commissionId
    );

    Optional<InventoryCommissionMemberSetting> findFirstByTenantIdAndCommissionIdAndCommissionMemberIdAndIsDeletedFalse(
        String tenantId,
        UUID commissionId,
        UUID commissionMemberId
    );
}
