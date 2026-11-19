package com.enterprise.mm.payload;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AssetAcceptanceUnitResponse extends BaseRecordDto {
    private UUID assetAcceptanceLineId;
    private UUID purchaseOrderItemId;
    private UUID materialId;
    private String materialTypeSnapshot;
    private Integer lineSequence;
    private String receivingUnitNumber;
    private String manufacturerSerialNumber;
    private String serialNumber;
    private String temporaryTag;
    private String condition;
    private String qualityDecision;
    private LocalDateTime qualityDecisionAt;
    private String qualityDecisionBy;
    private UUID postedFixedAssetId;
    private String postedFixedAssetName;
}
