package com.enterprise.mm.payload;

import java.util.List;
import java.util.UUID;

public record AssetOpeningImportCreateRequest(
    UUID sourceFileId,
    List<AssetOpeningImportLineRequest> lines
) {
}
