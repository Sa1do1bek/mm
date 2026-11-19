package com.enterprise.mm.payload;

import java.util.UUID;

public record AssetHandoverSearchRequest(
    String status,
    String handoverType,
    UUID fixedAssetId,
    UUID fromWarehouseId
) {
}
