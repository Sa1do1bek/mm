package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.AssetAcceptanceApprovalStatus;
import com.enterprise.mm.enums.AssetAcceptancePostingStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "asset_acceptances")
public class AssetAcceptance extends BusinessEntity {
    @Column(name = "contract_id")
    private UUID contractId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contract_id", insertable = false, updatable = false)
    private Contract contract;

    @Column(name = "purchase_order_id")
    private UUID purchaseOrderId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_id", insertable = false, updatable = false)
    private PurchaseOrder purchaseOrder;

    @Column(name = "supplier_id")
    private UUID supplierId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", insertable = false, updatable = false)
    private Supplier supplier;

    @Column(name = "asn_id")
    private UUID asnId;

    @Column(name = "delivery_reference", length = 120)
    private String deliveryReference;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id", insertable = false, updatable = false)
    private Warehouse warehouse;

    @Column(name = "storage_location_id")
    private UUID storageLocationId;

    @Column(name = "received_by_employee_id")
    private UUID receivedByEmployeeId;

    @Column(name = "receipt_date", nullable = false)
    private LocalDate receiptDate;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    @Column(name = "currency_code", length = 3)
    private String currencyCode;

    @Column(name = "exchange_rate", precision = 19, scale = 6)
    private BigDecimal exchangeRate;

    @Column(name = "exchange_rate_source", length = 120)
    private String exchangeRateSource;

    @Column(name = "source_evidence", length = 240)
    private String sourceEvidence;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", nullable = false, length = 30)
    private AssetAcceptanceApprovalStatus approvalStatus = AssetAcceptanceApprovalStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(name = "posting_status", nullable = false, length = 30)
    private AssetAcceptancePostingStatus postingStatus = AssetAcceptancePostingStatus.NOT_POSTED;

    @Column(name = "approval_instance_id")
    private UUID approvalInstanceId;

    @Column(name = "posted_at")
    private LocalDateTime postedAt;

    @Column(name = "posted_by", length = 120)
    private String postedBy;

    @OneToMany(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_acceptance_id", insertable = false, updatable = false)
    private List<AssetAcceptanceLine> lines = new ArrayList<>();
}
