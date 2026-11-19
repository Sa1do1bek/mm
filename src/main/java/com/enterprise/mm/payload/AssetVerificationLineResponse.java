package com.enterprise.mm.payload;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AssetVerificationLineResponse extends BaseRecordDto {
    private UUID assetVerificationId;
    private String assetVerificationName;
    private UUID fixedAssetId;
    private String fixedAssetName;
    private String expectedCustodyKind;
    private UUID expectedEmployeeId;
    private UUID expectedDepartmentId;
    private UUID expectedWarehouseId;
    private String observedCustodyKind;
    private UUID observedEmployeeId;
    private UUID observedDepartmentId;
    private UUID observedWarehouseId;
    private LocalDateTime observedAt;
    private String discrepancyType;
    private String resolutionStatus;
}
