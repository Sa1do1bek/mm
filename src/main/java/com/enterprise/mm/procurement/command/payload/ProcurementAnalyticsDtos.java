package com.enterprise.mm.procurement.command.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class ProcurementAnalyticsDtos {

    private ProcurementAnalyticsDtos() {
    }

    public record DailyFlowPoint(
        LocalDate date,
        long created,
        long closed
    ) {
    }

    public record DailyFlowResponse(
        LocalDate from,
        LocalDate to,
        List<DailyFlowPoint> points
    ) {
    }

    public record CycleStage(
        String stage,
        Double avgDays,
        long sampleSize
    ) {
    }

    public record CycleTimeResponse(
        int windowDays,
        List<CycleStage> stages
    ) {
    }

    public record TopSupplierRow(
        UUID supplierId,
        String supplierName,
        BigDecimal totalAmount,
        long orderCount
    ) {
    }

    public record TopSuppliersResponse(
        LocalDate from,
        LocalDate to,
        String currencyCode,
        List<TopSupplierRow> suppliers
    ) {
    }

    public record OrderStatusSlice(
        String status,
        long count
    ) {
    }

    public record OrderStatusResponse(
        long total,
        List<OrderStatusSlice> slices
    ) {
    }
}
