package com.enterprise.mm.payload;

import java.util.List;
import java.util.UUID;

public record AssetHandoverUpdateRequest(
    Long expectedVersion,
    String handoverType,
    String fromCustodyType,
    UUID fromEmployeeId,
    UUID fromDepartmentId,
    UUID fromWarehouseId,
    String toCustodyType,
    UUID toEmployeeId,
    UUID toDepartmentId,
    UUID toWarehouseId,
    List<AssetHandoverLineRequest> lines,
    UUID contractId
) {
}
