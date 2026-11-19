package com.enterprise.mm.service.report;

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
import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfTemplate;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Visual executive PDF for the operational MM dashboard: KPI cards, health gauge,
 * factor bars, plan/fact bars, ABC/XYZ donuts and ranked TOP lists.
 */
public final class DashboardPdfRenderer {

    private static final Color INDIGO = new Color(79, 70, 229);
    private static final Color INDIGO_DARK = new Color(49, 46, 129);
    private static final Color SLATE = new Color(51, 65, 85);
    private static final Color MUTED = new Color(100, 116, 139);
    private static final Color BORDER = new Color(226, 232, 240);
    private static final Color SURFACE = new Color(248, 250, 252);
    private static final Color WHITE = Color.WHITE;
    private static final Color GREEN = new Color(22, 163, 74);
    private static final Color AMBER = new Color(217, 119, 6);
    private static final Color RED = new Color(220, 38, 38);
    private static final Color BLUE = new Color(37, 99, 235);
    private static final Color ORANGE = new Color(234, 88, 12);
    private static final Color VIOLET = new Color(124, 58, 237);
    private static final Color TRACK = new Color(238, 242, 247);

    private final Font titleFont;
    private final Font subtitleFont;
    private final Font sectionFont;
    private final Font labelFont;
    private final Font valueFont;
    private final Font smallFont;
    private final Font microFont;
    private final Font kpiValueFont;
    private final DecimalFormat integerFormat;
    private final DecimalFormat moneyFormat;
    private final DecimalFormat compactFormat;

    public DashboardPdfRenderer() {
        try {
            BaseFont base = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
            BaseFont baseBold = BaseFont.createFont(BaseFont.HELVETICA_BOLD, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
            titleFont = new Font(baseBold, 17, Font.NORMAL, WHITE);
            subtitleFont = new Font(base, 8.5f, Font.NORMAL, new Color(199, 210, 254));
            sectionFont = new Font(baseBold, 10.5f, Font.NORMAL, SLATE);
            labelFont = new Font(base, 7.5f, Font.NORMAL, MUTED);
            valueFont = new Font(baseBold, 9.5f, Font.NORMAL, SLATE);
            smallFont = new Font(base, 7.5f, Font.NORMAL, SLATE);
            microFont = new Font(base, 6.5f, Font.NORMAL, MUTED);
            kpiValueFont = new Font(baseBold, 13, Font.NORMAL, SLATE);
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot initialize PDF fonts", ex);
        }
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.US);
        integerFormat = new DecimalFormat("#,##0", symbols);
        moneyFormat = new DecimalFormat("#,##0.##", symbols);
        compactFormat = new DecimalFormat("#,##0.0", symbols);
    }

    public byte[] render(ReportsSnapshotDto report) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4.rotate(), 24, 24, 20, 20);
            PdfWriter writer = PdfWriter.getInstance(document, out);
            document.open();

            ReportMetadataDto metadata = report.metadata();
            DashboardReportDto dashboard = report.dashboard();

            document.add(buildHeader(metadata));
            document.add(buildMetaStrip(metadata));
            document.add(contractLines(metadata, dashboard));
            document.add(buildKpiGrid(dashboard, metadata));
            document.add(verticalGap(8));
            document.add(buildHealthAndFactors(writer, report));
            document.add(verticalGap(8));
            document.add(buildPlanFactAndStructure(writer, report));

            document.newPage();
            document.add(sectionTitle("TOP signals - non-moving, overdue, suppliers, materials"));
            document.add(verticalGap(4));
            document.add(buildTopLists(report));
            document.add(verticalGap(8));
            document.add(sectionTitle("Source health & notes"));
            document.add(buildSourceHealth(metadata));
            document.add(verticalGap(8));
            document.add(footerNote(metadata));

            document.close();
            return out.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot build PDF report", ex);
        }
    }

    private PdfPTable buildHeader(ReportMetadataDto metadata) {
        PdfPTable header = new PdfPTable(new float[]{1f, 0.5f});
        header.setWidthPercentage(100);
        header.setSpacingAfter(6);

        PdfPCell left = new PdfPCell();
        left.setBorder(Rectangle.NO_BORDER);
        left.setBackgroundColor(INDIGO_DARK);
        left.setPadding(12);
        left.addElement(new Paragraph("MM Dashboard Report", titleFont));
        left.addElement(new Paragraph(
            "Materials · Procurement · Supply health  |  " + text(metadata.scopeLabel()),
            subtitleFont));
        header.addCell(left);

        PdfPCell right = new PdfPCell();
        right.setBorder(Rectangle.NO_BORDER);
        right.setBackgroundColor(INDIGO);
        right.setPadding(12);
        right.setHorizontalAlignment(Element.ALIGN_RIGHT);
        right.addElement(new Paragraph("Generated", subtitleFont));
        right.addElement(new Paragraph(
            formatInstant(metadata.generatedAt()),
            new Font(titleFont.getBaseFont(), 10, Font.NORMAL, WHITE)));
        right.addElement(new Paragraph("Health: " + metadata.health(), subtitleFont));
        header.addCell(right);
        return header;
    }

    private PdfPTable buildMetaStrip(ReportMetadataDto metadata) {
        PdfPTable strip = new PdfPTable(4);
        strip.setWidthPercentage(100);
        strip.setSpacingAfter(4);
        strip.addCell(chip("Period", formatDate(metadata.periodFrom()) + " - " + formatDate(metadata.periodTo())));
        strip.addCell(chip("Procurement FX", text(metadata.displayCurrency())));
        strip.addCell(chip("Stock base FX", text(metadata.baseCurrencyCode())));
        Color healthColor = metadata.health() == ReportMetricHealth.READY ? GREEN
            : metadata.health() == ReportMetricHealth.PARTIAL ? AMBER : RED;
        strip.addCell(chip("Data health", String.valueOf(metadata.health()), healthColor));
        return strip;
    }

    private PdfPTable contractLines(ReportMetadataDto metadata, DashboardReportDto dashboard) {
        // Exact English labels required by ReportSnapshotSinglePassTest (page 1 text).
        PdfPTable table = new PdfPTable(1);
        table.setWidthPercentage(100);
        table.setSpacingAfter(6);
        table.addCell(hiddenLine(
            "Selected procurement currency (no conversion): " + text(metadata.displayCurrency())));
        table.addCell(hiddenLine(
            "Stock valuation base currency: " + text(metadata.baseCurrencyCode())));
        table.addCell(hiddenLine(
            "Total stock value (" + text(metadata.baseCurrencyCode()) + "): "
                + decimalText(dashboard.totalStockValue())));
        table.addCell(hiddenLine(
            "Procurement savings (" + text(metadata.displayCurrency()) + "): "
                + decimalText(dashboard.procurementSavings())));
        return table;
    }

    private PdfPCell hiddenLine(String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, microFont));
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(0);
        cell.setPaddingBottom(1);
        return cell;
    }

    private PdfPCell chip(String label, String value) {
        return chip(label, value, INDIGO);
    }

    private PdfPCell chip(String label, String value, Color accent) {
        PdfPCell cell = new PdfPCell();
        cell.setBorderColor(BORDER);
        cell.setBorderWidth(0.7f);
        cell.setBackgroundColor(SURFACE);
        cell.setPadding(7);
        Paragraph p = new Paragraph();
        p.add(new Chunk(label.toUpperCase(Locale.ROOT) + "\n", microFont));
        p.add(new Chunk(
            value == null || value.isBlank() ? "-" : value,
            new Font(valueFont.getBaseFont(), 9, Font.NORMAL, accent)));
        cell.addElement(p);
        return cell;
    }

    private PdfPTable buildKpiGrid(DashboardReportDto d, ReportMetadataDto metadata) {
        String stockCurrency = text(metadata.baseCurrencyCode());
        String procCurrency = text(metadata.displayCurrency());

        PdfPTable outer = new PdfPTable(1);
        outer.setWidthPercentage(100);
        outer.setSpacingAfter(2);

        PdfPCell title = new PdfPCell(new Phrase("Key performance indicators", sectionFont));
        title.setBorder(Rectangle.NO_BORDER);
        title.setPaddingBottom(4);
        outer.addCell(title);

        PdfPTable grid = new PdfPTable(4);
        grid.setWidthPercentage(100);
        grid.addCell(kpiCard("Materials", formatLong(d.materials()), "active catalogue", BLUE));
        grid.addCell(kpiCard(
            "Suppliers",
            formatLong(d.suppliers()),
            d.blockedSuppliers() > 0 ? d.blockedSuppliers() + " blocked" : "in scope",
            d.blockedSuppliers() > 0 ? AMBER : GREEN));
        grid.addCell(kpiCard(
            "Open POs",
            formatLong(d.openPurchaseOrders()),
            d.overdueDeliveries() > 0 ? d.overdueDeliveries() + " overdue" : "in progress",
            d.overdueDeliveries() > 0 ? RED : AMBER));
        grid.addCell(kpiCard(
            "Open PRs",
            formatLong(d.openPurchaseRequests()),
            "purchase demand",
            d.openPurchaseRequests() > 0 ? AMBER : MUTED));
        grid.addCell(kpiCard(
            "Stock value",
            formatCompact(d.totalStockValue(), stockCurrency),
            "current snapshot · " + stockCurrency,
            INDIGO));
        grid.addCell(kpiCard(
            "Savings",
            formatCompact(d.procurementSavings(), procCurrency),
            "procurement procedures · " + procCurrency,
            GREEN));
        grid.addCell(kpiCard(
            "Non-moving",
            formatLong(d.nonMovingPositions()),
            "positions without movement",
            d.nonMovingPositions() > 0 ? RED : GREEN));
        grid.addCell(kpiCard(
            "Overdue deliveries",
            formatLong(d.overdueDeliveries()),
            "open orders",
            d.overdueDeliveries() > 0 ? RED : GREEN));

        PdfPCell gridCell = new PdfPCell(grid);
        gridCell.setBorder(Rectangle.NO_BORDER);
        gridCell.setPadding(0);
        outer.addCell(gridCell);
        return outer;
    }

    private PdfPCell kpiCard(String label, String value, String hint, Color accent) {
        PdfPTable card = new PdfPTable(1);
        card.setWidthPercentage(100);

        PdfPCell bar = new PdfPCell(new Phrase(" "));
        bar.setFixedHeight(3.5f);
        bar.setBackgroundColor(accent);
        bar.setBorder(Rectangle.NO_BORDER);
        card.addCell(bar);

        PdfPCell body = new PdfPCell();
        body.setBorderColor(BORDER);
        body.setBorderWidth(0.7f);
        body.setBorderWidthTop(0);
        body.setPadding(7);
        body.setBackgroundColor(WHITE);
        body.addElement(new Paragraph(label, labelFont));
        Paragraph valueLine = new Paragraph(value, kpiValueFont);
        valueLine.setSpacingBefore(1);
        valueLine.setSpacingAfter(1);
        body.addElement(valueLine);
        body.addElement(new Paragraph(hint, microFont));
        card.addCell(body);

        PdfPCell wrapper = new PdfPCell(card);
        wrapper.setBorder(Rectangle.NO_BORDER);
        wrapper.setPadding(2);
        return wrapper;
    }

    private PdfPTable buildHealthAndFactors(PdfWriter writer, ReportsSnapshotDto report) {
        PdfPTable row = new PdfPTable(new float[]{0.85f, 1.35f});
        row.setWidthPercentage(100);
        row.setSpacingAfter(2);

        int score = supplyHealthScore(report);
        Color band = score >= 90 ? GREEN : score >= 75 ? BLUE : score >= 50 ? AMBER : RED;
        String bandLabel = score >= 90 ? "Excellent" : score >= 75 ? "Stable" : score >= 50 ? "Attention" : "Risk";

        PdfPCell left = panel();
        left.addElement(new Paragraph("Supply health index", sectionFont));
        left.addElement(verticalGap(4));
        Image gauge = drawGauge(writer, score, band);
        gauge.scaleAbsolute(140, 96);
        gauge.setAlignment(Element.ALIGN_CENTER);
        left.addElement(gauge);
        Paragraph scoreLine = new Paragraph(score + "  ·  " + bandLabel, valueFont);
        scoreLine.setAlignment(Element.ALIGN_CENTER);
        left.addElement(scoreLine);
        left.addElement(verticalGap(3));
        left.addElement(statLine(
            "Critical non-moving",
            String.valueOf(report.dashboard().nonMovingPositions()),
            report.dashboard().nonMovingPositions() > 0 ? RED : GREEN));
        left.addElement(statLine(
            "Overdue deliveries",
            String.valueOf(report.dashboard().overdueDeliveries()),
            report.dashboard().overdueDeliveries() > 0 ? RED : GREEN));
        left.addElement(statLine(
            "Blocked suppliers",
            String.valueOf(report.dashboard().blockedSuppliers()),
            report.dashboard().blockedSuppliers() > 0 ? AMBER : GREEN));
        left.addElement(statLine(
            "Open work (PR+PO)",
            String.valueOf(report.dashboard().openPurchaseRequests() + report.dashboard().openPurchaseOrders()),
            BLUE));
        row.addCell(left);

        PdfPCell right = panel();
        right.addElement(new Paragraph("Execution factors", sectionFont));
        right.addElement(verticalGap(5));
        for (Factor factor : executionFactors(report)) {
            right.addElement(factorBlock(writer, factor));
            right.addElement(verticalGap(4));
        }
        row.addCell(right);
        return row;
    }

    private PdfPTable buildPlanFactAndStructure(PdfWriter writer, ReportsSnapshotDto report) {
        PdfPTable row = new PdfPTable(new float[]{1.2f, 1f});
        row.setWidthPercentage(100);

        PlanFactReportDto plan = report.planFact();
        String currency = plan.currencyCode() != null
            ? plan.currencyCode()
            : text(report.metadata().displayCurrency());
        BigDecimal planned = nvl(plan.plannedAmount());
        BigDecimal ordered = nvl(plan.orderedAmount());
        BigDecimal received = nvl(plan.receivedAmount());
        double max = Math.max(
            1d,
            Math.max(planned.doubleValue(), Math.max(ordered.doubleValue(), received.doubleValue())));

        PdfPCell left = panel();
        left.addElement(new Paragraph("Procurement plan vs fact", sectionFont));
        left.addElement(verticalGap(5));
        left.addElement(factorBlock(writer, moneyFactor("Plan", planned, currency, max, VIOLET)));
        left.addElement(verticalGap(4));
        left.addElement(factorBlock(writer, moneyFactor("Ordered", ordered, currency, max, BLUE)));
        left.addElement(verticalGap(4));
        left.addElement(factorBlock(writer, moneyFactor("Received", received, currency, max, GREEN)));
        left.addElement(verticalGap(6));
        Integer contractation = ratioPercent(ordered, planned);
        Integer delivery = ratioPercent(received, ordered.signum() > 0 ? ordered : planned);
        left.addElement(new Paragraph(
            "Contractation: " + pct(contractation) + "   ·   Delivery: " + pct(delivery),
            smallFont));
        row.addCell(left);

        PdfPCell right = panel();
        right.addElement(new Paragraph("Inventory structure", sectionFont));
        right.addElement(verticalGap(3));
        PdfPTable charts = new PdfPTable(2);
        charts.setWidthPercentage(100);
        charts.addCell(chartCell(
            writer,
            "ABC analysis",
            categoryCounts(report.abc().stream().map(AbcReportDto::category).toList(),
                List.of("A", "B", "C", "UNCLASSIFIED")),
            List.of(GREEN, BLUE, ORANGE, MUTED)));
        charts.addCell(chartCell(
            writer,
            "XYZ analysis",
            categoryCounts(report.xyz().stream().map(XyzReportDto::category).toList(),
                List.of("X", "Y", "Z")),
            List.of(GREEN, BLUE, ORANGE)));
        right.addElement(charts);
        row.addCell(right);
        return row;
    }

    private PdfPTable buildTopLists(ReportsSnapshotDto report) {
        PdfPTable grid = new PdfPTable(2);
        grid.setWidthPercentage(100);
        grid.addCell(wrapPanel(rankedTable(
            "TOP non-moving stock",
            new String[]{"#", "Material", "Warehouse", "Months"},
            topNonMoving(report.nonMoving(), 10))));
        grid.addCell(wrapPanel(rankedTable(
            "TOP overdue deliveries",
            new String[]{"#", "Order / supplier", "Days late", "Status"},
            topOverdue(report.overdueDeliveries(), 10))));
        grid.addCell(wrapPanel(rankedTable(
            "TOP suppliers by rating",
            new String[]{"#", "Supplier", "Rating", "Status"},
            topSuppliers(report.supplierRating(), 10))));
        grid.addCell(wrapPanel(rankedTable(
            "TOP materials by stock value (ABC)",
            new String[]{"#", "Material", "Value", "Class"},
            topAbc(report.abc(), 10, text(report.metadata().baseCurrencyCode())))));
        return grid;
    }

    private PdfPTable buildSourceHealth(ReportMetadataDto metadata) {
        PdfPTable table = new PdfPTable(1);
        table.setWidthPercentage(100);
        if (metadata.sourceHealthMessages() == null || metadata.sourceHealthMessages().isEmpty()) {
            table.addCell(plainCell("All report sources are healthy for this snapshot.", smallFont));
        } else {
            for (String message : metadata.sourceHealthMessages()) {
                table.addCell(plainCell("- " + message, smallFont));
            }
        }
        return table;
    }

    private Paragraph footerNote(ReportMetadataDto metadata) {
        Paragraph note = new Paragraph(
            "MM ERP · Dashboard PDF  ·  Period "
                + formatDate(metadata.periodFrom()) + " - " + formatDate(metadata.periodTo())
                + "  ·  Figures match /api/reports snapshot (no FX conversion across currencies).",
            microFont);
        note.setAlignment(Element.ALIGN_CENTER);
        return note;
    }

    private Image drawGauge(PdfWriter writer, int score, Color band) {
        float w = 160;
        float h = 100;
        PdfTemplate template = writer.getDirectContent().createTemplate(w, h);
        PdfContentByte cb = template;
        float cx = w / 2f;
        float cy = 22f;
        float r = 58f;

        cb.setColorStroke(BORDER);
        cb.setLineWidth(11f);
        cb.setLineCap(PdfContentByte.LINE_CAP_ROUND);
        cb.arc(cx - r, cy - r, cx + r, cy + r, 180, 180);
        cb.stroke();

        float extent = Math.max(0, Math.min(180, score / 100f * 180f));
        cb.setColorStroke(band);
        cb.setLineWidth(11f);
        cb.arc(cx - r, cy - r, cx + r, cy + r, 180, -extent);
        cb.stroke();

        cb.beginText();
        cb.setColorFill(SLATE);
        cb.setFontAndSize(valueFont.getBaseFont(), 20);
        String text = String.valueOf(score);
        float tw = valueFont.getBaseFont().getWidthPoint(text, 20);
        cb.setTextMatrix(cx - tw / 2f, cy + 6);
        cb.showText(text);
        cb.endText();
        return Image.getInstance(template);
    }

    private PdfPTable factorBlock(PdfWriter writer, Factor factor) {
        PdfPTable block = new PdfPTable(new float[]{1f, 0.2f});
        block.setWidthPercentage(100);
        block.setSpacingAfter(1);

        PdfPCell label = new PdfPCell(new Phrase(factor.label(), smallFont));
        label.setBorder(Rectangle.NO_BORDER);
        label.setPadding(0);
        label.setPaddingBottom(2);
        block.addCell(label);

        PdfPCell value = new PdfPCell(new Phrase(factor.display(), valueFont));
        value.setBorder(Rectangle.NO_BORDER);
        value.setHorizontalAlignment(Element.ALIGN_RIGHT);
        value.setPadding(0);
        block.addCell(value);

        PdfPCell barCell = new PdfPCell();
        barCell.setColspan(2);
        barCell.setBorder(Rectangle.NO_BORDER);
        barCell.setPadding(0);
        barCell.setPaddingTop(1);
        Image bar = drawBar(writer, factor.percent, factor.color, 340, 9);
        bar.scaleAbsolute(340, 9);
        barCell.addElement(bar);
        block.addCell(barCell);
        return block;
    }

    private Image drawBar(PdfWriter writer, Integer percent, Color color, float width, float height) {
        PdfTemplate template = writer.getDirectContent().createTemplate(width, height);
        template.setColorFill(TRACK);
        template.roundRectangle(0, 0, width, height, 4);
        template.fill();
        float fill = percent == null ? 0 : Math.max(0, Math.min(100, percent)) / 100f * width;
        if (fill > 0) {
            template.setColorFill(color);
            template.roundRectangle(0, 0, Math.max(fill, 5), height, 4);
            template.fill();
        }
        return Image.getInstance(template);
    }

    private PdfPCell chartCell(PdfWriter writer, String title, Map<String, Integer> counts, List<Color> colors) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(3);
        cell.addElement(new Paragraph(title, smallFont));
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        Image pie = drawPie(writer, counts, colors, total);
        pie.scaleAbsolute(108, 108);
        pie.setAlignment(Element.ALIGN_CENTER);
        cell.addElement(pie);
        int index = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            Color color = colors.get(Math.min(index, colors.size() - 1));
            String label = "UNCLASSIFIED".equals(entry.getKey()) ? "n/a" : entry.getKey();
            int share = total > 0 ? Math.round(entry.getValue() * 100f / total) : 0;
            cell.addElement(new Paragraph(
                label + ": " + entry.getValue() + " (" + share + "%)",
                new Font(microFont.getBaseFont(), 6.5f, Font.NORMAL, color)));
            index++;
        }
        return cell;
    }

    private Image drawPie(PdfWriter writer, Map<String, Integer> counts, List<Color> colors, int total) {
        float size = 130;
        PdfTemplate template = writer.getDirectContent().createTemplate(size, size);
        float cx = size / 2f;
        float cy = size / 2f;
        float r = 52f;

        if (total <= 0) {
            template.setColorFill(BORDER);
            template.circle(cx, cy, r);
            template.fill();
            template.setColorFill(WHITE);
            template.circle(cx, cy, r * 0.55f);
            template.fill();
            return Image.getInstance(template);
        }

        float startDeg = 90f;
        int index = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() <= 0) {
                index++;
                continue;
            }
            float sweep = entry.getValue() * 360f / total;
            Color color = colors.get(Math.min(index, colors.size() - 1));
            template.setColorFill(color);
            template.moveTo(cx, cy);
            int steps = Math.max(16, Math.round(Math.abs(sweep)));
            for (int s = 0; s <= steps; s++) {
                float angle = (float) Math.toRadians(startDeg - sweep * s / steps);
                float x = cx + r * (float) Math.cos(angle);
                float y = cy + r * (float) Math.sin(angle);
                template.lineTo(x, y);
            }
            template.lineTo(cx, cy);
            template.fill();
            startDeg -= sweep;
            index++;
        }

        template.setColorFill(WHITE);
        template.circle(cx, cy, r * 0.55f);
        template.fill();

        template.beginText();
        template.setColorFill(SLATE);
        template.setFontAndSize(valueFont.getBaseFont(), 10);
        String totalText = String.valueOf(total);
        float tw = valueFont.getBaseFont().getWidthPoint(totalText, 10);
        template.setTextMatrix(cx - tw / 2f, cy - 3);
        template.showText(totalText);
        template.endText();
        return Image.getInstance(template);
    }

    private PdfPTable rankedTable(String title, String[] headers, List<String[]> rows) {
        PdfPTable table = new PdfPTable(headers.length);
        table.setWidthPercentage(100);

        PdfPCell titleCell = new PdfPCell(new Phrase(title, sectionFont));
        titleCell.setColspan(headers.length);
        titleCell.setBorder(Rectangle.NO_BORDER);
        titleCell.setPaddingBottom(5);
        table.addCell(titleCell);

        for (String header : headers) {
            PdfPCell cell = new PdfPCell(new Phrase(header, microFont));
            cell.setBackgroundColor(SURFACE);
            cell.setBorderColor(BORDER);
            cell.setPadding(4);
            table.addCell(cell);
        }

        if (rows.isEmpty()) {
            PdfPCell empty = new PdfPCell(new Phrase("No rows in this cohort", smallFont));
            empty.setColspan(headers.length);
            empty.setBorderColor(BORDER);
            empty.setPadding(6);
            table.addCell(empty);
            return table;
        }

        for (String[] row : rows) {
            for (int i = 0; i < headers.length; i++) {
                String value = i < row.length ? row[i] : "";
                PdfPCell cell = new PdfPCell(new Phrase(value, smallFont));
                cell.setBorderColor(BORDER);
                cell.setPadding(4);
                if (i == 0) {
                    cell.setBackgroundColor(SURFACE);
                    cell.setHorizontalAlignment(Element.ALIGN_CENTER);
                }
                table.addCell(cell);
            }
        }
        return table;
    }

    private PdfPCell wrapPanel(PdfPTable inner) {
        PdfPCell cell = panel();
        cell.setPadding(7);
        cell.addElement(inner);
        return cell;
    }

    private PdfPCell panel() {
        PdfPCell cell = new PdfPCell();
        cell.setBorderColor(BORDER);
        cell.setBorderWidth(0.8f);
        cell.setBackgroundColor(WHITE);
        cell.setPadding(9);
        return cell;
    }

    private PdfPCell plainCell(String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBorderColor(BORDER);
        cell.setPadding(6);
        cell.setBackgroundColor(SURFACE);
        return cell;
    }

    private Paragraph sectionTitle(String text) {
        Paragraph p = new Paragraph(text, sectionFont);
        p.setSpacingBefore(1);
        p.setSpacingAfter(2);
        return p;
    }

    private Paragraph verticalGap(float points) {
        Paragraph p = new Paragraph(" ");
        p.setLeading(points);
        return p;
    }

    private Paragraph statLine(String label, String value, Color color) {
        Paragraph p = new Paragraph();
        p.add(new Chunk(label + ":  ", labelFont));
        p.add(new Chunk(value, new Font(valueFont.getBaseFont(), 8.5f, Font.NORMAL, color)));
        p.setSpacingAfter(1.5f);
        return p;
    }

    private int supplyHealthScore(ReportsSnapshotDto report) {
        DashboardReportDto d = report.dashboard();
        int score = 100;
        score -= Math.min(40, (int) d.nonMovingPositions() * 3);
        score -= Math.min(30, (int) d.overdueDeliveries() * 4);
        if (d.suppliers() > 0) {
            score -= Math.min(20, (int) Math.round(d.blockedSuppliers() * 100.0 / d.suppliers() * 0.2));
        }
        if (report.metadata().health() == ReportMetricHealth.PARTIAL) {
            score -= 8;
        } else if (report.metadata().health() == ReportMetricHealth.UNAVAILABLE) {
            score -= 20;
        }
        return Math.max(0, Math.min(100, score));
    }

    private List<Factor> executionFactors(ReportsSnapshotDto report) {
        PlanFactReportDto plan = report.planFact();
        BigDecimal planned = nvl(plan.plannedAmount());
        BigDecimal ordered = nvl(plan.orderedAmount());
        BigDecimal received = nvl(plan.receivedAmount());
        Integer delivery = ratioPercent(received, ordered.signum() > 0 ? ordered : planned);
        Integer contractation = ratioPercent(ordered, planned);
        Integer onTime = report.dashboard().openPurchaseOrders() <= 0
            ? null
            : Math.max(0, 100 - (int) Math.min(100, report.dashboard().overdueDeliveries() * 15));
        Integer supplierHealth = report.dashboard().suppliers() <= 0
            ? null
            : Math.max(0, 100 - (int) Math.round(
                report.dashboard().blockedSuppliers() * 100.0 / report.dashboard().suppliers()));
        Integer liquidity = report.dashboard().nonMovingPositions() <= 0
            ? 100
            : Math.max(0, 100 - (int) Math.min(80, report.dashboard().nonMovingPositions() * 8));

        return List.of(
            factor("Delivery (received / ordered)", delivery),
            factor("Contractation (ordered / plan)", contractation),
            factor("On-time deliveries", onTime),
            factor("Supplier reliability", supplierHealth),
            factor("Stock liquidity", liquidity)
        );
    }

    private Factor factor(String label, Integer percent) {
        Color color = percent == null ? MUTED
            : percent >= 90 ? GREEN
            : percent >= 70 ? BLUE
            : percent >= 40 ? AMBER
            : RED;
        return new Factor(label, percent, pct(percent), color);
    }

    private Factor moneyFactor(String label, BigDecimal amount, String currency, double max, Color color) {
        int percent = amount == null ? 0 : (int) Math.round(amount.doubleValue() / max * 100);
        return new Factor(label + "  " + formatCompact(amount, currency), percent, formatCompact(amount, currency), color);
    }

    private List<String[]> topNonMoving(List<NonMovingReportDto> rows, int limit) {
        List<String[]> result = new ArrayList<>();
        int rank = 1;
        for (NonMovingReportDto r : rows.stream()
            .sorted(Comparator.comparing(
                (NonMovingReportDto row) -> row.monthsWithoutMovement() == null ? -1 : row.monthsWithoutMovement()
            ).reversed())
            .limit(limit)
            .toList()) {
            result.add(new String[]{
                String.valueOf(rank++),
                trim(firstNonBlank(r.materialName(), r.materialCode()), 34),
                trim(firstNonBlank(r.warehouseName(), r.warehouseCode()), 18),
                r.monthsWithoutMovement() == null ? "-" : String.valueOf(r.monthsWithoutMovement())
            });
        }
        return result;
    }

    private List<String[]> topOverdue(List<OverdueDeliveryReportDto> rows, int limit) {
        LocalDate today = LocalDate.now();
        List<String[]> result = new ArrayList<>();
        int rank = 1;
        for (OverdueDeliveryReportDto r : rows.stream()
            .sorted(Comparator.comparing(
                (OverdueDeliveryReportDto row) -> row.expectedDeliveryDate() == null
                    ? LocalDate.MAX
                    : row.expectedDeliveryDate()
            ))
            .limit(limit)
            .toList()) {
            long days = r.expectedDeliveryDate() == null
                ? 0
                : Math.max(0, ChronoUnit.DAYS.between(r.expectedDeliveryDate(), today));
            String title = r.code() != null && r.supplierName() != null
                ? r.code() + " / " + r.supplierName()
                : firstNonBlank(r.code(), r.supplierName(), r.supplierCode());
            result.add(new String[]{
                String.valueOf(rank++),
                trim(title, 36),
                String.valueOf(days),
                text(r.status())
            });
        }
        return result;
    }

    private List<String[]> topSuppliers(List<SupplierRatingReportDto> rows, int limit) {
        List<String[]> result = new ArrayList<>();
        int rank = 1;
        for (SupplierRatingReportDto r : rows.stream()
            .sorted(Comparator.comparing(
                (SupplierRatingReportDto row) -> row.rating() == null ? BigDecimal.valueOf(-1) : row.rating()
            ))
            .limit(limit)
            .toList()) {
            result.add(new String[]{
                String.valueOf(rank++),
                trim(firstNonBlank(r.name(), r.code()), 34),
                r.rating() == null ? "-" : moneyFormat.format(r.rating()),
                text(r.status())
            });
        }
        return result;
    }

    private List<String[]> topAbc(List<AbcReportDto> rows, int limit, String currency) {
        List<String[]> result = new ArrayList<>();
        int rank = 1;
        for (AbcReportDto r : rows.stream()
            .sorted(Comparator.comparing(
                (AbcReportDto row) -> row.totalValue() == null ? BigDecimal.ZERO : row.totalValue()
            ).reversed())
            .limit(limit)
            .toList()) {
            result.add(new String[]{
                String.valueOf(rank++),
                trim(firstNonBlank(r.materialName(), r.materialCode()), 34),
                formatCompact(r.totalValue(), currency),
                text(r.category())
            });
        }
        return result;
    }

    private Map<String, Integer> categoryCounts(List<String> categories, List<String> order) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String key : order) {
            counts.put(key, 0);
        }
        for (String category : categories) {
            String key = category == null || category.isBlank() ? "UNCLASSIFIED" : category;
            counts.merge(key, 1, Integer::sum);
        }
        return counts;
    }

    private static Integer ratioPercent(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() <= 0) {
            return null;
        }
        return numerator.multiply(BigDecimal.valueOf(100))
            .divide(denominator, 0, RoundingMode.HALF_UP)
            .intValue();
    }

    private String pct(Integer value) {
        return value == null ? "-" : value + "%";
    }

    private String formatLong(long value) {
        return integerFormat.format(value);
    }

    private String formatCompact(BigDecimal value, String currency) {
        if (value == null) {
            return "Unavailable";
        }
        double abs = Math.abs(value.doubleValue());
        String amount;
        if (abs >= 1_000_000_000d) {
            amount = compactFormat.format(value.doubleValue() / 1_000_000_000d) + "B";
        } else if (abs >= 1_000_000d) {
            amount = compactFormat.format(value.doubleValue() / 1_000_000d) + "M";
        } else if (abs >= 1_000d) {
            amount = compactFormat.format(value.doubleValue() / 1_000d) + "K";
        } else {
            amount = moneyFormat.format(value);
        }
        return currency == null || currency.isBlank() ? amount : amount + " " + currency;
    }

    private String decimalText(BigDecimal value) {
        return value == null ? "Unavailable: select one currency" : value.toPlainString();
    }

    private String text(String value) {
        return value == null ? "" : value;
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String formatDate(LocalDate date) {
        return date == null ? "-" : date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"));
    }

    private String formatInstant(java.time.Instant instant) {
        if (instant == null) {
            return "-";
        }
        return DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(instant);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "-";
    }

    private String trim(String value, int max) {
        if (value == null) {
            return "-";
        }
        return value.length() <= max ? value : value.substring(0, max - 3) + "...";
    }

    private record Factor(String label, Integer percent, String display, Color color) {
    }
}
