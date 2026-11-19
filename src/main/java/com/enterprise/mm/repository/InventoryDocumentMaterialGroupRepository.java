package com.enterprise.mm.repository;

import com.enterprise.mm.entity.InventoryDocumentMaterialGroup;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryDocumentMaterialGroupRepository extends JpaRepository<InventoryDocumentMaterialGroup, UUID> {
    List<InventoryDocumentMaterialGroup> findByTenantIdAndInventoryDocumentIdAndIsDeletedFalseOrderByCreatedAtAsc(String tenantId, UUID inventoryDocumentId);
}
