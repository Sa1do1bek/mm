package com.enterprise.mm.payload;

import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AssetHandoverLineResponse extends BaseRecordDto {
    private UUID assetHandoverId;
    private UUID fixedAssetId;
    private String fixedAssetName;
    private UUID assetAllocationId;
    private String assetAllocationName;
    private String lineStatus;
}
