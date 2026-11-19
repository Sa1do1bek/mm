package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "return_documents")
public class MaterialReturnDocument extends BusinessEntity {
    @Column(name = "issue_request_id")
    private UUID issueRequestId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "issue_request_id", insertable = false, updatable = false)
    private IssueRequest issueRequest;

    @Column(name = "origin_type", nullable = false, length = 40)
    private String originType = "NATIVE_ISSUE";

    @Column(name = "historical_document_number", length = 100)
    private String historicalDocumentNumber;

    @Column(name = "historical_document_date")
    private java.time.LocalDate historicalDocumentDate;

    @Column(name = "historical_source_system", length = 50)
    private String historicalSourceSystem;

    @Column(name = "owner_id", length = 64)
    private String ownerId;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id", insertable = false, updatable = false)
    private Warehouse warehouse;

    @Column(columnDefinition = "text")
    private String reason;

    @Column(name = "acceptance_status", length = 50)
    private String acceptanceStatus;

    @Column(name = "stock_movement_document_id")
    private UUID stockMovementDocumentId;
}
