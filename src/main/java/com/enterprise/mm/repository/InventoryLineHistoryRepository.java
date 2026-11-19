package com.enterprise.mm.repository;

import com.enterprise.mm.entity.InventoryLineHistory;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryLineHistoryRepository extends JpaRepository<InventoryLineHistory, UUID> {
    List<InventoryLineHistory> findByTenantIdAndInventoryItemIdAndIsDeletedFalseOrderByChangedAtAsc(String tenantId, UUID inventoryItemId);
}
