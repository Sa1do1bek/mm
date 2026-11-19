package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.entity.StockBatch;
import com.enterprise.mm.entity.InventoryCostLayer;
import com.enterprise.mm.enums.PickingMethod;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StockPickingOrderTest {
    @Test void fifoAndLifoKeepUnknownBatchDatesLastAndBreakTiesDeterministically() {
        var old = balance(1, LocalDate.of(2025,1,1));
        var newer = balance(2, LocalDate.of(2026,1,1));
        var tied = balance(3, LocalDate.of(2026,1,1));
        var unknown = balance(4, null);
        var input = List.of(unknown,tied,newer,old);
        assertThat(input.stream().sorted(StockPickingOrder.balances(PickingMethod.FIFO)).toList())
            .containsExactly(old,newer,tied,unknown);
        assertThat(input.stream().sorted(StockPickingOrder.balances(PickingMethod.LIFO)).toList())
            .containsExactly(newer,tied,old,unknown);
    }
    @Test void costLayerSelectionUsesReceiptTimeWithoutPuttingUnknownDatesFirst() {
        var older = layer(1,LocalDateTime.of(2025,1,1,0,0));
        var newer = layer(2,LocalDateTime.of(2026,1,1,0,0));
        var unknown = layer(3,null);
        assertThat(List.of(unknown,older,newer).stream().sorted(StockPickingOrder.costLayers(PickingMethod.LIFO)).toList())
            .containsExactly(newer,older,unknown);
    }
    private StockBalance balance(int id,LocalDate date) {
        var batch=new StockBatch();batch.setId(new UUID(0,id));batch.setReceivedDate(date);
        var row=new StockBalance();row.setId(new UUID(1,id));row.setStockBatchId(batch.getId());row.setStockBatch(batch);return row;
    }
    private InventoryCostLayer layer(int id,LocalDateTime date) {
        var row=new InventoryCostLayer();row.setId(new UUID(0,id));row.setReceivedAt(date);return row;
    }
}
