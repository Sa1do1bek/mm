package com.enterprise.mm.repository;

import com.enterprise.mm.entity.SmsGatewaySetting;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SmsGatewaySettingRepository extends JpaRepository<SmsGatewaySetting, UUID> {
    Optional<SmsGatewaySetting> findAnyById(UUID id);
    Optional<SmsGatewaySetting> findByIdAndIsDeletedFalse(UUID id);
    Optional<SmsGatewaySetting> findFirstByTenantIdAndIsDeletedFalseOrderByCreatedAtAsc(String tenantId);
}

