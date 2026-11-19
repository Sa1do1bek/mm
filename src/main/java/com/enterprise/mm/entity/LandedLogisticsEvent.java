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
@Table(name = "landed_logistics_events")
public class LandedLogisticsEvent extends BusinessEntity {
    @Column(name = "import_delivery_id", nullable = false)
    private UUID importDeliveryId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "import_delivery_id", insertable = false, updatable = false)
    private ImportDelivery importDelivery;

    @Column(name = "event_type", nullable = false, length = 80)
    private String eventType;

    @Column(name = "event_date")
    private LocalDateTime eventDate;

    @Column(length = 240)
    private String location;

    @Column(name = "carrier_status", length = 240)
    private String carrierStatus;

    @Column(name = "external_system", length = 80)
    private String externalSystem;

    @Column(name = "external_event_id", length = 160)
    private String externalEventId;

    @Column(name = "exception_flag", nullable = false)
    private boolean exceptionFlag;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @PrePersist
    @PreUpdate
    private void syncRelations() {
        importDeliveryId = importDelivery == null ? importDeliveryId : importDelivery.getId();
    }
}
