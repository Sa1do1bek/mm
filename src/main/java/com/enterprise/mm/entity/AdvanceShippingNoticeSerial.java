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
@Table(name = "advance_shipping_notice_serials")
public class AdvanceShippingNoticeSerial extends BusinessEntity {
    @Column(name = "asn_item_id", nullable = false)
    private UUID asnItemId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asn_item_id", insertable = false, updatable = false)
    private AdvanceShippingNoticeItem asnItem;

    @Column(name = "asn_batch_id")
    private UUID asnBatchId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asn_batch_id", insertable = false, updatable = false)
    private AdvanceShippingNoticeBatch asnBatch;

    @Column(name = "batch_number", length = 160)
    private String batchNumber;

    @Column(name = "manufacturer_serial_number", length = 160)
    private String manufacturerSerialNumber;

    @Column(name = "normalized_serial_number", length = 160)
    private String normalizedSerialNumber;

    @PrePersist
    @PreUpdate
    private void syncRelations() {
        asnItemId = asnItem == null ? asnItemId : asnItem.getId();
        asnBatchId = asnBatch == null ? asnBatchId : asnBatch.getId();
    }
}
