package com.enterprise.mm.service.rfq;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.service.rfq.RfqEmailRenderer.RenderedEmail;
import com.enterprise.mm.service.rfq.RfqPackageSnapshot.Buyer;
import com.enterprise.mm.service.rfq.RfqPackageSnapshot.Position;
import com.enterprise.mm.service.rfq.RfqPackageSnapshot.SourceReference;
import com.enterprise.mm.service.rfq.RfqPackageSnapshot.SupplierRecipient;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class RfqPackageParityTest {
    private final RfqPdfRenderer pdfRenderer = new RfqPdfRenderer();
    private final RfqExcelRenderer excelRenderer = new RfqExcelRenderer();
    private final RfqEmailRenderer emailRenderer = new RfqEmailRenderer(750 * 1024);

    @Test
    void everyRendererUsesTheSameRfqAndPositionIdentity() throws Exception {
        UUID rfqItemId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        RfqPackageSnapshot snapshot = snapshot(List.of(new Position(
            rfqItemId,
            "MAT-001",
            "Ammonium nitrate",
            "Industrial grade",
            "kg",
            new BigDecimal("500"),
            LocalDate.of(2026, 8, 15)
        )));

        String pdfText;
        try (PdfReader reader = new PdfReader(pdfRenderer.render(snapshot))) {
            pdfText = new PdfTextExtractor(reader).getTextFromPage(1);
        }
        String excelText;
        try (XSSFWorkbook workbook = new XSSFWorkbook(
            new ByteArrayInputStream(excelRenderer.render(snapshot)))) {
            excelText = StreamSupport.stream(workbook.getSheetAt(0).spliterator(), false)
                .flatMap(row -> StreamSupport.stream(row.spliterator(), false))
                .map(Cell::toString)
                .collect(Collectors.joining(" "));
            assertThat(workbook.getSheet("_RFQ_META").getRow(0).getCell(1).getStringCellValue())
                .isEqualTo(snapshot.rfqId().toString());
        }
        RenderedEmail email = emailRenderer.render(
            snapshot,
            snapshot.suppliers().getFirst(),
            URI.create("https://portal.example.uz/supplier-portal/magic-link?token=test")
        );

        assertThat(pdfText).contains("RFQ-26-010", "MAT-001", "500");
        assertThat(excelText).contains("RFQ-26-010", "MAT-001", "500");
        assertThat(email.html()).contains("RFQ-26-010", "MAT-001", "500", "magic-link?token=test");
    }

    @Test
    void emailEscapesSupplierFacingText() {
        RfqPackageSnapshot snapshot = snapshot(List.of(new Position(
            UUID.randomUUID(),
            "MAT-<1>",
            "<script>alert('x')</script>",
            "A&B",
            "kg",
            BigDecimal.ONE,
            LocalDate.of(2026, 8, 15)
        )));

        RenderedEmail email = emailRenderer.render(
            snapshot,
            snapshot.suppliers().getFirst(),
            URI.create("https://portal.example.uz/supplier-portal/magic-link?token=safe")
        );

        assertThat(email.html())
            .contains("MAT-&lt;1&gt;", "&lt;script&gt;", "A&amp;B")
            .doesNotContain("<script>");
    }

    @Test
    void emailUsesQuotationTitleAndBorderedPositionsTable() {
        RfqPackageSnapshot snapshot = snapshot(List.of(new Position(
            UUID.randomUUID(),
            "MAT-001",
            "Laptop",
            "DELL",
            "pcs",
            new BigDecimal("2"),
            LocalDate.of(2026, 8, 5)
        )));

        RenderedEmail email = emailRenderer.render(
            snapshot,
            snapshot.suppliers().getFirst(),
            URI.create("https://portal.example.uz/supplier-portal/magic-link?token=table")
        );

        assertThat(email.subject())
            .startsWith("Request for quotation RFQ-26-010")
            .doesNotStartWith("RFQ ");
        assertThat(email.html())
            .contains("<h1>Request for quotation</h1>")
            .contains("border=\"1\"", "border:1px solid #d0d7de", "padding:8px 10px")
            .contains("01.08.2026", "05.08.2026")
            .doesNotContain("2026-08-05")
            .contains("MAT-001", "Laptop", "DELL");
        assertThat(email.text()).contains("01.08.2026");
    }

    @Test
    void renderersUseLabelsFromTheSelectedPackageLanguage() throws Exception {
        RfqPackageSnapshot base = snapshot(List.of());
        RfqPackageSnapshot uzbek = new RfqPackageSnapshot(
            base.rfqId(), base.rfqCode(), "ru: Насосы\nen: Pumps\nuz: Nasoslar", base.description(), "uz", base.dueDate(),
            base.buyer(), base.responseInstructions(), List.of(new Position(
                UUID.randomUUID(), "MAT-002", "ru: Насос\nen: Pump\nuz: Nasos", "IP68", "ta",
                BigDecimal.ONE, LocalDate.of(2026, 8, 15)
            )), base.sources(), base.suppliers(), base.documents()
        );

        RenderedEmail email = emailRenderer.render(
            uzbek,
            uzbek.suppliers().getFirst(),
            URI.create("https://portal.example.uz/magic-link?token=uz")
        );
        assertThat(email.html())
            .contains("Tijorat taklifi uchun so‘rov", "Javob muddati", "Xaridor", "Nasoslar", "Nasos")
            .doesNotContain("ru:", "en:", "uz:");

        try (XSSFWorkbook workbook = new XSSFWorkbook(
            new ByteArrayInputStream(excelRenderer.render(uzbek)))) {
            assertThat(workbook.getSheetAt(0).getSheetName()).isEqualTo("Tijorat taklifi");
            assertThat(workbook.getSheetAt(0).getRow(5).getCell(2).getStringCellValue())
                .isEqualTo("Material");
        }
    }

    @Test
    void pdfPreservesCyrillicSupplierContent() throws Exception {
        RfqPackageSnapshot base = snapshot(List.of());
        RfqPackageSnapshot snapshot = new RfqPackageSnapshot(
            base.rfqId(),
            "RFQ-RU-1",
            "Запрос коммерческого предложения",
            "Просим предоставить предложение",
            "ru",
            base.dueDate(),
            base.buyer(),
            "Ответьте через портал или электронную почту",
            List.of(new Position(
                UUID.randomUUID(),
                "MAT-RU-1",
                "Аммиачная селитра",
                "Промышленное качество",
                "кг",
                new BigDecimal("500"),
                LocalDate.of(2026, 8, 15)
            )),
            base.sources(),
            base.suppliers(),
            base.documents()
        );

        String pdfText;
        try (PdfReader reader = new PdfReader(pdfRenderer.render(snapshot))) {
            pdfText = new PdfTextExtractor(reader).getTextFromPage(1);
        }

        assertThat(pdfText).contains(
            "Запрос коммерческого предложения",
            "Аммиачная селитра",
            "Промышленное качество"
        );
    }

    static RfqPackageSnapshot snapshot(List<Position> positions) {
        return new RfqPackageSnapshot(
            UUID.fromString("00000000-0000-0000-0000-000000000010"),
            "RFQ-26-010",
            "Industrial chemicals",
            "Please quote the listed materials",
            "en",
            LocalDate.of(2026, 8, 1),
            new Buyer(
                "Example Buyer LLC",
                "Tashkent, Uzbekistan",
                "Procurement Manager",
                "procurement@example.uz",
                "+998 71 000 00 00"
            ),
            "Reply by portal or email.",
            positions,
            List.of(new SourceReference("PR-26-001", "August chemicals")),
            List.of(new SupplierRecipient(
                UUID.fromString("00000000-0000-0000-0000-000000000020"),
                "Supplier One",
                "Sales Manager",
                "sales@example.uz"
            )),
            List.of()
        );
    }
}
