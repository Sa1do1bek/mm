package com.enterprise.mm.payload;

import com.enterprise.mm.enums.PickingMethod;
import com.enterprise.mm.enums.StockAllocationMode;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public final class WarehouseStockFactPickingDtos {
    private WarehouseStockFactPickingDtos() {}
    public record Options(UUID materialId, PickingMethod pickingMethod, int quantityScale,
        BigDecimal eligibleAvailableQuantity, int page, int size, long totalElements,
        List<TransferPickingDtos.Position> positions) {}
    public record Line(UUID materialId, PickingMethod pickingMethod, StockAllocationMode allocationMode,
        BigDecimal quantity, List<TransferPickingDtos.SelectedAllocation> allocations) {}
    public record Preview(UUID warehouseId, List<Line> lines) {}
}
