package com.enterprise.mm.payload;

import java.util.UUID;

public record AssetHandoverLineRequest(
    UUID fixedAssetId
) {
}
