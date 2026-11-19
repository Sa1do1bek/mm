package com.enterprise.mm.warehouse.packaging;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter @Entity
@Table(name = "returnable_packaging_events")
public class ReturnablePackagingEvent extends BusinessEntity {
    @Column(name = "lot_id", nullable = false) private UUID lotId;
    @Column(name = "return_id") private UUID returnId;
    @Column(name = "event_type", nullable = false, length = 40) private String eventType;
    @Column(nullable = false, precision = 22, scale = 7) private BigDecimal quantity;
    @Column(name = "from_state", length = 30) private String fromState;
    @Column(name = "to_state", length = 30) private String toState;
    @Column(name = "stock_movement_id") private UUID stockMovementId;
    @Column(name = "posting_batch_id") private UUID postingBatchId;
    @Column(columnDefinition = "text") private String reason;
    @Column(name = "content_movement_id") private UUID contentMovementId;
}
