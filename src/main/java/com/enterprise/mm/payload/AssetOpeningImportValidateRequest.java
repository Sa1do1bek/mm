package com.enterprise.mm.payload;

import java.util.List;

public record AssetOpeningImportValidateRequest(
    List<AssetOpeningImportLineRequest> lines
) {
}
