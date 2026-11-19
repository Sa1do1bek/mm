package com.enterprise.mm.repository;

import com.enterprise.mm.entity.InventoryAiDraft;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

public interface InventoryAiDraftRepository extends JpaRepository<InventoryAiDraft, UUID> {
    Optional<InventoryAiDraft> findByIdAndTenantIdAndCompanyIdAndOwnerUsername(UUID id, String tenantId, UUID companyId, String ownerUsername);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<InventoryAiDraft> findForUpdateByIdAndTenantIdAndCompanyIdAndOwnerUsername(UUID id, String tenantId, UUID companyId, String ownerUsername);
    List<InventoryAiDraft> findByStatusAndExpiresAtBefore(String status, LocalDateTime cutoff);
}
