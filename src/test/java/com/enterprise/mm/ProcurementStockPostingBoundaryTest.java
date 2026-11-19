package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ProcurementStockPostingBoundaryTest {

    @Test
    void purchaseOrderReceiptDependsOnlyOnProcurementStockPostingPort() throws Exception {
        String source = Files.readString(Path.of(
            "src/main/java/com/enterprise/mm/service/PurchaseOrderService.java"));

        assertThat(source)
            .contains("ProcurementStockPostingPort")
            .doesNotContain("StockMovementService")
            .doesNotContain("new StockMovement")
            .doesNotContain("MovementType.RECEIPT");
    }
}
