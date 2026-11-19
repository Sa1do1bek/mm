package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

@Getter @Setter @Entity @Immutable @Table(name = "transfer_transit_entries")
public class TransferTransitEntry extends BusinessEntity {
    @Column(name = "allocation_id", nullable = false, updatable = false) private UUID allocationId;
    @Column(name = "operation_id", nullable = false, updatable = false) private UUID operationId;
    @Column(name = "kind", nullable = false, length = 40, updatable = false) private String kind;
    @Column(name = "posting_date", nullable = false, updatable = false) private LocalDate postingDate;
    @Column(name = "quantity_delta", nullable = false, precision = 22, scale = 7, updatable = false) private BigDecimal quantityDelta;
    @Column(name = "value_delta", nullable = false, precision = 19, scale = 4, updatable = false) private BigDecimal valueDelta;
}
