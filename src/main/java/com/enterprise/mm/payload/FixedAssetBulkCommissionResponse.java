package com.enterprise.mm.payload;

import java.util.List;

public record FixedAssetBulkCommissionResponse(
    List<FixedAssetResponse> assets
) {
}
