package com.enterprise.mm.repository;

import com.enterprise.mm.entity.InventoryDocumentMaterial;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryDocumentMaterialRepository extends JpaRepository<InventoryDocumentMaterial, UUID> {
    List<InventoryDocumentMaterial> findByTenantIdAndInventoryDocumentIdAndIsDeletedFalseOrderByCreatedAtAsc(String tenantId, UUID inventoryDocumentId);
}
