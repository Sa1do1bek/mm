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
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "purchase_order_revisions")
public class PurchaseOrderRevision extends BusinessEntity {
    @Column(name = "purchase_order_id", nullable = false)
    private UUID purchaseOrderId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_id", insertable = false, updatable = false)
    private PurchaseOrder purchaseOrder;
    private Integer revisionNumber;
    @Column(length = 500)
    private String reason;
    @Column(columnDefinition = "text")
    private String diffJson;
    @Column(columnDefinition = "text")
    private String snapshotJson;
    private LocalDateTime revisedAt;

    @PrePersist
    @PreUpdate
    private void syncRelations() {
        purchaseOrderId = purchaseOrder == null ? purchaseOrderId : purchaseOrder.getId();
    }
}
