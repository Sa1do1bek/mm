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
@Table(name = "transfer_shipments")
public class TransferShipment extends BusinessEntity {
    @Column(name = "transfer_document_id", updatable = false, nullable = false)
    private UUID transferDocumentId;

    @Column(name = "dispatched_date", updatable = false, nullable = false)
    private LocalDate dispatchedDate;

    @Column(name = "operation_key", updatable = false, nullable = false, length = 160)
    private String operationKey;

    @Column(name = "movement_document_id", updatable = false)
    private UUID movementDocumentId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "picking_decisions", updatable = false, nullable = false, columnDefinition = "jsonb")
    private List<com.enterprise.mm.payload.TransferPickingDtos.Decision> pickingDecisions = List.of();
}
