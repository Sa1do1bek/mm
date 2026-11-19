package com.enterprise.mm.service.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.payload.ReportDtos.AbcReportDto;
import com.enterprise.mm.payload.ReportDtos.DashboardReportDto;
import com.enterprise.mm.payload.ReportDtos.NonMovingReportDto;
import com.enterprise.mm.payload.ReportDtos.OverdueDeliveryReportDto;
import com.enterprise.mm.payload.ReportDtos.PlanFactReportDto;
import com.enterprise.mm.payload.ReportDtos.ReportMetadataDto;
import com.enterprise.mm.payload.ReportDtos.ReportMetricHealth;
import com.enterprise.mm.payload.ReportDtos.ReportsSnapshotDto;
import com.enterprise.mm.payload.ReportDtos.SupplierRatingReportDto;
import com.enterprise.mm.payload.ReportDtos.XyzReportDto;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DashboardPdfRendererTest {

    @Test
    void rendersMultiPageInfographicPdfWithContractStrings() throws Exception {
        ReportsSnapshotDto snapshot = sampleSnapshot();
        byte[] pdf = new DashboardPdfRenderer().render(snapshot);

        assertThat(pdf).isNotEmpty();
        assertThat(pdf[0]).isEqualTo((byte) '%');
        assertThat(pdf[1]).isEqualTo((byte) 'P');
        assertThat(pdf[2]).isEqualTo((byte) 'D');
        assertThat(pdf[3]).isEqualTo((byte) 'F');

        try (PdfReader reader = new PdfReader(pdf)) {
            assertThat(reader.getNumberOfPages()).isGreaterThanOrEqualTo(2);
            StringBuilder all = new StringBuilder();
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                all.append(new PdfTextExtractor(reader).getTextFromPage(page)).append('\n');
            }
            String text = all.toString();
            String page1 = new PdfTextExtractor(reader).getTextFromPage(1);
            assertThat(page1).contains(
                "Selected procurement currency (no conversion): USD",
                "Stock valuation base currency: UZS",
                "Total stock value (UZS):",
                "Procurement savings (USD):"
            );
            assertThat(text).contains(
                "MM Dashboard Report",
                "Key performance indicators",
                "Supply health index",
                "Execution factors",
                "ABC analysis",
                "TOP non-moving stock",
                "TOP overdue deliveries",
                "TOP suppliers by rating",
                "Source health"
            );
        }
    }

    private ReportsSnapshotDto sampleSnapshot() {
        UUID materialId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        DashboardReportDto dashboard = new DashboardReportDto(
            120,
            new BigDecimal("1500000000"),
            18,
            BigDecimal.ZERO,
            7,
            new BigDecimal("700000"),
            12,
            new BigDecimal("1200000"),
            2,
            BigDecimal.ZERO,
            5,
            new BigDecimal("500000"),
            3,
            new BigDecimal("12000"),
            new BigDecimal("1500000000"),
            new BigDecimal("420000")
        );

        PlanFactReportDto planFact = new PlanFactReportDto(
            new BigDecimal("1000000"),
            new BigDecimal("820000"),
            new BigDecimal("610000"),
            new BigDecimal("-390000"),
            "USD",
            ReportMetricHealth.READY,
            "plan/fact"
        );

        ReportMetadataDto metadata = new ReportMetadataDto(
            Instant.parse("2026-07-16T10:00:00Z"),
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 7, 16),
            UUID.randomUUID(),
            "CURRENT_COMPANY",
            "USD",
            "UZS",
            ReportMetricHealth.READY,
            List.of("All report sources are forced to the current company."),
            Map.of(),
            Map.of()
        );

        return new ReportsSnapshotDto(
            dashboard,
            planFact,
            List.of(new SupplierRatingReportDto(supplierId, "SUP-1", "Acme Supply", "ACTIVE", "GOODS", new BigDecimal("72.5"))),
            List.of(
                new AbcReportDto(materialId, "MAT-1", "Steel plate", new BigDecimal("500000"), new BigDecimal("0.4"), "A"),
                new AbcReportDto(UUID.randomUUID(), "MAT-2", "Bolt M10", new BigDecimal("120000"), new BigDecimal("0.7"), "B")
            ),
            List.of(
                new XyzReportDto(materialId, "MAT-1", "Steel plate", new BigDecimal("10"), new BigDecimal("0.08"), "X"),
                new XyzReportDto(UUID.randomUUID(), "MAT-3", "Paint", new BigDecimal("2"), new BigDecimal("0.4"), "Z")
            ),
            List.of(new NonMovingReportDto(
                UUID.randomUUID(), materialId, "MAT-1", "Steel plate", warehouseId, "WH-1", "Main", 14, "OPEN", "REVIEW")),
            List.of(new OverdueDeliveryReportDto(
                orderId, "PO-100", supplierId, "SUP-1", "Acme Supply", LocalDate.now().minusDays(9), "SENT", new BigDecimal("12000"))),
            metadata,
            List.of(),
            List.of(),
            List.of()
        );
    }
}
