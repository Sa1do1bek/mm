package com.enterprise.mm.repository;

import com.enterprise.mm.entity.TenderCommissionMemberSetting;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenderCommissionMemberSettingRepository extends JpaRepository<TenderCommissionMemberSetting, UUID> {
    List<TenderCommissionMemberSetting> findByTenantIdAndTenderCommissionIdAndIsDeletedFalseOrderByCreatedAtAsc(String tenantId, UUID tenderCommissionId);
}
