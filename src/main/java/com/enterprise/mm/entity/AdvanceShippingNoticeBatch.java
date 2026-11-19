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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "advance_shipping_notice_batches")
public class AdvanceShippingNoticeBatch extends BusinessEntity {
    @Column(name = "asn_item_id", nullable = false)
    private UUID asnItemId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asn_item_id", insertable = false, updatable = false)
    private AdvanceShippingNoticeItem asnItem;

    @Column(name = "batch_number", length = 160)
    private String batchNumber;

    @Column(precision = 22, scale = 7, nullable = false)
    private BigDecimal quantity = BigDecimal.ZERO;

    @Column(name = "expiration_date")
    private LocalDate expirationDate;

    @PrePersist
    @PreUpdate
    private void syncRelations() {
        asnItemId = asnItem == null ? asnItemId : asnItem.getId();
    }
}
