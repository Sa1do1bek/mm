package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.AssetVerificationDiscrepancyType;
import com.enterprise.mm.enums.AssetVerificationResolutionStatus;
import com.enterprise.mm.enums.FixedAssetCustodyKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "asset_verification_lines")
public class AssetVerificationLine extends BusinessEntity {
    @Column(name = "asset_verification_id", nullable = false)
    private UUID assetVerificationId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_verification_id", insertable = false, updatable = false)
    private AssetVerification assetVerification;

    @Column(name = "fixed_asset_id", nullable = false)
    private UUID fixedAssetId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fixed_asset_id", insertable = false, updatable = false)
    private FixedAsset fixedAsset;

    @Enumerated(EnumType.STRING)
    @Column(name = "expected_custody_type", nullable = false, length = 30)
    private FixedAssetCustodyKind expectedCustodyKind;

    @Column(name = "expected_employee_id")
    private UUID expectedEmployeeId;

    @Column(name = "expected_department_id")
    private UUID expectedDepartmentId;

    @Column(name = "expected_warehouse_id")
    private UUID expectedWarehouseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "observed_custody_type", length = 30)
    private FixedAssetCustodyKind observedCustodyKind;

    @Column(name = "observed_employee_id")
    private UUID observedEmployeeId;

    @Column(name = "observed_department_id")
    private UUID observedDepartmentId;

    @Column(name = "observed_warehouse_id")
    private UUID observedWarehouseId;

    @Column(name = "observed_at")
    private LocalDateTime observedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "discrepancy_type", length = 30)
    private AssetVerificationDiscrepancyType discrepancyType;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_status", nullable = false, length = 30)
    private AssetVerificationResolutionStatus resolutionStatus = AssetVerificationResolutionStatus.OPEN;
}
