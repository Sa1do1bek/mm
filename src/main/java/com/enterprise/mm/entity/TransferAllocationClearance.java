package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@Entity
@Immutable
@Table(name = "transfer_allocation_clearances")
public class TransferAllocationClearance extends BusinessEntity {
    public enum Kind { GOOD_RECEIPT, QUARANTINE_RECEIPT, TRANSIT_RETURN, TRANSIT_LOSS }

    @Column(name = "allocation_id", updatable = false, nullable = false)
    private UUID allocationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", updatable = false, nullable = false, length = 40)
    private Kind kind;

    @Column(name = "quantity", updatable = false, nullable = false, precision = 22, scale = 7)
    private BigDecimal quantity;

    @Column(name = "book_value", updatable = false, nullable = false, precision = 19, scale = 4)
    private BigDecimal bookValue;

    @Column(name = "posting_date", updatable = false, nullable = false)
    private LocalDate postingDate;

    @Column(name = "operation_key", updatable = false, nullable = false, length = 160)
    private String operationKey;

    @Column(name = "destination_stock_batch_id", updatable = false)
    private UUID destinationStockBatchId;

    @Column(name = "movement_id", updatable = false)
    private UUID movementId;

    @Column(name = "exception_id", updatable = false)
    private UUID exceptionId;

    @Column(name = "compensates_clearance_id", updatable = false)
    private UUID compensatesClearanceId;

}
