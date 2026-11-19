package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "inventory_document_materials")
public class InventoryDocumentMaterial extends BusinessEntity {
    @Column(name = "inventory_document_id", nullable = false)
    private UUID inventoryDocumentId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "inventory_document_id", insertable = false, updatable = false)
    private InventoryDocument inventoryDocument;

    @Column(name = "material_id", nullable = false)
    private UUID materialId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_id", insertable = false, updatable = false)
    private Material material;

    @PrePersist
    @PreUpdate
    private void syncRelations() {
        inventoryDocumentId = inventoryDocument == null ? inventoryDocumentId : inventoryDocument.getId();
        materialId = material == null ? materialId : material.getId();
    }
}
