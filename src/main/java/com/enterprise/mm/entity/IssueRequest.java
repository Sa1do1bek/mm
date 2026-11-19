package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.IssueRequestCostObjectType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "issue_requests")
public class IssueRequest extends BusinessEntity {
    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id", insertable = false, updatable = false)
    private Warehouse warehouse;

    @Column(name = "requester_user_id", nullable = false)
    private UUID requesterUserId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requester_user_id", insertable = false, updatable = false)
    private User requesterUser;

    @Column(name = "receiver_user_id", nullable = false)
    private UUID receiverUserId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receiver_user_id", insertable = false, updatable = false)
    private User receiverUser;

    @Enumerated(EnumType.STRING)
    @Column(name = "cost_object_type", nullable = false, length = 30)
    private IssueRequestCostObjectType costObjectType;

    @Column(name = "cost_object_id", nullable = false)
    private UUID costObjectId;

    @Column(name = "required_date")
    private LocalDate requiredDate;

    @Column(name = "issued_date")
    private LocalDate issuedDate;

    @Column(name = "purpose", columnDefinition = "text")
    private String purpose;

    @Column(name = "approval_comment", columnDefinition = "text")
    private String approvalComment;

    @Column(name = "stock_movement_document_id")
    private UUID stockMovementDocumentId;
}
