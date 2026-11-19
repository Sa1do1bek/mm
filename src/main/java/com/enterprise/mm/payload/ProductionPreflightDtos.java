package com.enterprise.mm.payload;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class ProductionPreflightDtos {
    private ProductionPreflightDtos() {
    }

    public record ProductionPreflightReport(
        String status,
        boolean productionReady,
        LocalDateTime checkedAt,
        Map<String, Long> summary,
        List<ProductionPreflightCheck> checks
    ) {
    }

    public record ProductionPreflightCheck(
        String area,
        String code,
        String status,
        boolean blocksProduction,
        String message,
        List<String> actions
    ) {
    }
}