package com.enterprise.mm.warehouse.print;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

@Component
public class WarehousePrintFormRenderer {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    public String renderHtml(WarehousePrintFormData data, String language) {
        boolean uz = "uz".equalsIgnoreCase(language);
        StringBuilder html = new StringBuilder();

        html.append("<!DOCTYPE html>\n<html lang=\"").append(uz ? "uz" : "ru").append("\">\n<head>\n");
        html.append("<meta charset=\"UTF-8\">\n");
        html.append("<title>").append(escape(data.formTitle())).append(" - ").append(escape(data.documentNumber())).append("</title>\n");
        html.append("<style>\n");
        html.append("  @page { size: A4 landscape; margin: 12mm 10mm 12mm 10mm; }\n");
        html.append("  body { font-family: 'Times New Roman', serif; font-size: 10pt; color: #000; margin: 0; padding: 15px; }\n");
        html.append("  .header-box { width: 100%; margin-bottom: 12px; }\n");
        html.append("  .form-code { text-align: right; font-size: 9pt; font-weight: bold; }\n");
        html.append("  .org-title { font-size: 11pt; font-weight: bold; border-bottom: 1px solid #000; padding-bottom: 2px; display: inline-block; min-width: 320px; }\n");
        html.append("  .doc-title { text-align: center; margin: 14px 0 10px 0; font-size: 13pt; font-weight: bold; text-transform: uppercase; }\n");
        html.append("  .meta-table, .data-table { width: 100%; border-collapse: collapse; margin-bottom: 12px; }\n");
        html.append("  .meta-table td { padding: 4px 6px; font-size: 9.5pt; }\n");
        html.append("  .data-table th, .data-table td { border: 1px solid #000; padding: 5px 6px; font-size: 9pt; text-align: left; }\n");
        html.append("  .data-table th { background-color: #f2f2f2; text-align: center; font-weight: bold; }\n");
        html.append("  .num { text-align: right; white-space: nowrap; }\n");
        html.append("  .center { text-align: center; }\n");
        html.append("  .total-row td { font-weight: bold; background-color: #fafafa; }\n");
        html.append("  .in-words-box { margin: 12px 0; font-size: 9.5pt; font-style: italic; }\n");
        html.append("  .signature-grid { width: 100%; margin-top: 20px; border-collapse: collapse; }\n");
        html.append("  .signature-grid td { padding: 8px 12px; font-size: 9.5pt; vertical-align: bottom; }\n");
        html.append("  .line { border-bottom: 1px solid #000; display: inline-block; min-width: 140px; margin: 0 4px; }\n");
        html.append("  @media print {\n");
        html.append("    body { padding: 0; }\n");
        html.append("    .no-print { display: none !important; }\n");
        html.append("    button { display: none !important; }\n");
        html.append("  }\n");
        html.append("</style>\n</head>\n<body>\n");

        // Print action button (hidden in print)
        html.append("<div class=\"no-print\" style=\"text-align: right; margin-bottom: 15px;\">\n");
        html.append("  <button onclick=\"window.print()\" style=\"padding: 8px 18px; font-size: 12pt; background: #1976d2; color: #fff; border: none; border-radius: 4px; cursor: pointer;\">");
        html.append(uz ? "Chop etish (Print)" : "Печать (Print)");
        html.append("</button>\n</div>\n");

        // Top right code
        html.append("<div class=\"form-code\">").append(escape(data.formCode())).append("</div>\n");

        // Organization info
        html.append("<div class=\"header-box\">\n");
        html.append("  <div><strong>").append(uz ? "Tashkilot:" : "Организация:").append("</strong> <span class=\"org-title\">")
            .append(escape(data.organizationName())).append("</span></div>\n");
        if (data.organizationInn() != null) {
            html.append("  <div style=\"font-size: 9pt; margin-top: 3px;\"><strong>").append(uz ? "STIR (INN):" : "ИНН:").append("</strong> ")
                .append(escape(data.organizationInn())).append("</div>\n");
        }
        if (data.structuralDivision() != null) {
            html.append("  <div style=\"font-size: 9pt;\"><strong>").append(uz ? "Tarkibiy bo‘linma:" : "Структурное подразделение:").append("</strong> ")
                .append(escape(data.structuralDivision())).append("</div>\n");
        }
        html.append("</div>\n");

        // Document Title
        html.append("<div class=\"doc-title\">").append(escape(data.formTitle()))
            .append(" № ").append(escape(data.documentNumber()))
            .append(" ").append(uz ? "dan" : "от ").append(data.documentDate() != null ? data.documentDate().format(DATE_FMT) : "")
            .append("</div>\n");

        // Metadata grid
        html.append("<table class=\"meta-table\">\n<tr>\n");
        if (data.formType() == WarehousePrintFormType.M11) {
            html.append("  <td><strong>").append(uz ? "Chiqaruvchi ombor (Jo‘natuvchi):" : "Склад-отправитель:").append("</strong> ")
                .append(escape(data.sourceWarehouseName())).append("</td>\n");
            html.append("  <td><strong>").append(uz ? "Qabul qiluvchi (Bo‘linma/Ombor):" : "Получатель (Склад/Цех):").append("</strong> ")
                .append(escape(data.targetWarehouseName() != null ? data.targetWarehouseName() : data.targetDepartmentName())).append("</td>\n");
        } else if (data.formType() == WarehousePrintFormType.M4 || data.formType() == WarehousePrintFormType.M7) {
            html.append("  <td><strong>").append(uz ? "Yetkazib beruvchi:" : "Поставщик:").append("</strong> ")
                .append(escape(data.supplierName())).append("</td>\n");
            html.append("  <td><strong>").append(uz ? "Qabul qiluvchi ombor:" : "Склад назначения:").append("</strong> ")
                .append(escape(data.targetWarehouseName())).append("</td>\n");
        }
        html.append("  <td><strong>").append(uz ? "Valyuta:" : "Валюта:").append("</strong> ").append(escape(data.currencyCode())).append("</td>\n");
        html.append("</tr>\n");

        if (data.contractNumber() != null || data.invoiceNumber() != null) {
            html.append("<tr>\n");
            if (data.contractNumber() != null) {
                html.append("  <td><strong>").append(uz ? "Shartnoma:" : "Договор:").append("</strong> ").append(escape(data.contractNumber()))
                    .append(data.contractDate() != null ? " (" + data.contractDate().format(DATE_FMT) + ")" : "").append("</td>\n");
            }
            if (data.invoiceNumber() != null) {
                html.append("  <td><strong>").append(uz ? "Schyot-faktura/Hujjat:" : "Счет-фактура/ТТН:").append("</strong> ")
                    .append(escape(data.invoiceNumber()))
                    .append(data.invoiceDate() != null ? " (" + data.invoiceDate().format(DATE_FMT) + ")" : "").append("</td>\n");
            }
            html.append("  <td></td>\n</tr>\n");
        }
        html.append("</table>\n");

        // Table lines
        html.append("<table class=\"data-table\">\n<thead>\n<tr>\n");
        html.append("  <th style=\"width: 25px;\">№</th>\n");
        html.append("  <th style=\"width: 80px;\">").append(uz ? "Schyot" : "Счет учета").append("</th>\n");
        html.append("  <th style=\"width: 75px;\">").append(uz ? "Korr. schyot" : "Корр. счет").append("</th>\n");
        html.append("  <th style=\"width: 100px;\">").append(uz ? "Kod (MXIK)" : "Номенклатурный номер").append("</th>\n");
        html.append("  <th>").append(uz ? "Material nomi va tavsifi" : "Наименование материала, сорт, размер").append("</th>\n");
        html.append("  <th style=\"width: 50px;\">").append(uz ? "O‘lch.b." : "Ед. изм.").append("</th>\n");
        if (data.formType() == WarehousePrintFormType.M11) {
            html.append("  <th style=\"width: 75px;\">").append(uz ? "Talab etildi" : "Затребовано").append("</th>\n");
            html.append("  <th style=\"width: 75px;\">").append(uz ? "Chiqarildi" : "Отпущено").append("</th>\n");
        } else {
            html.append("  <th style=\"width: 75px;\">").append(uz ? "Hujjat bo‘yicha" : "По документу").append("</th>\n");
            html.append("  <th style=\"width: 75px;\">").append(uz ? "Qabul qilindi" : "Принято").append("</th>\n");
        }
        html.append("  <th style=\"width: 85px;\">").append(uz ? "Narx" : "Цена").append("</th>\n");
        html.append("  <th style=\"width: 95px;\">").append(uz ? "Summa" : "Сумма").append("</th>\n");
        html.append("</tr>\n</thead>\n<tbody>\n");

        int idx = 1;
        for (WarehousePrintFormLine line : data.lines()) {
            html.append("<tr>\n");
            html.append("  <td class=\"center\">").append(idx++).append("</td>\n");
            html.append("  <td class=\"center\">").append(escape(line.accountCode())).append("</td>\n");
            html.append("  <td class=\"center\">").append(escape(line.correspAccountCode())).append("</td>\n");
            html.append("  <td class=\"center\">").append(escape(line.materialCode())).append("</td>\n");
            html.append("  <td>").append(escape(line.materialName()));
            String codes = identification(line);
            if (!codes.isEmpty()) {
                html.append("<br/><span style=\"font-size:10px;\">").append(escape(codes)).append("</span>");
            }
            html.append("</td>\n");
            html.append("  <td class=\"center\">").append(escape(line.unitOfMeasure())).append("</td>\n");
            html.append("  <td class=\"num\">").append(formatQty(line.requestedQuantity())).append("</td>\n");
            html.append("  <td class=\"num\">").append(formatQty(line.releasedOrAcceptedQuantity())).append("</td>\n");
            html.append("  <td class=\"num\">").append(formatMoney(line.unitPrice())).append("</td>\n");
            html.append("  <td class=\"num\">").append(formatMoney(line.totalAmount())).append("</td>\n");
            html.append("</tr>\n");
        }

        // Totals
        html.append("<tr class=\"total-row\">\n");
        html.append("  <td colspan=\"6\" style=\"text-align: right;\"><strong>").append(uz ? "Jami:" : "Итого:").append("</strong></td>\n");
        html.append("  <td class=\"num\">-</td>\n");
        html.append("  <td class=\"num\">").append(formatQty(data.totalQuantity())).append("</td>\n");
        html.append("  <td class=\"num\">-</td>\n");
        html.append("  <td class=\"num\">").append(formatMoney(data.totalAmount())).append("</td>\n");
        html.append("</tr>\n");
        html.append("</tbody>\n</table>\n");

        // Amount in words
        html.append("<div class=\"in-words-box\">\n");
        html.append("  <div><strong>").append(uz ? "Jami summa so‘z bilan:" : "Всего отпущено/принято на сумму прописью:").append("</strong> ")
            .append(escape(data.totalAmountInWords())).append("</div>\n");
        html.append("</div>\n");

        // Signatures grid
        html.append("<table class=\"signature-grid\">\n<tr>\n");
        html.append("  <td style=\"width: 33%;\">\n");
        html.append("    <div><strong>").append(uz ? "Topshirdi (Chiqardi):" : "Отпустил:").append("</strong></div>\n");
        html.append("    <div style=\"margin-top: 15px;\">").append(escape(data.releasedByRole())).append(" <span class=\"line\"></span> ")
            .append(escape(data.releasedByName())).append("</div>\n");
        html.append("  </td>\n");
        html.append("  <td style=\"width: 33%;\">\n");
        html.append("    <div><strong>").append(uz ? "Qabul qildi:" : "Получил:").append("</strong></div>\n");
        html.append("    <div style=\"margin-top: 15px;\">").append(escape(data.receivedByRole())).append(" <span class=\"line\"></span> ")
            .append(escape(data.receivedByName())).append("</div>\n");
        html.append("  </td>\n");
        html.append("  <td style=\"width: 34%;\">\n");
        html.append("    <div><strong>").append(uz ? "Bosh buxgalter / Tekshirdi:" : "Главный бухгалтер:").append("</strong></div>\n");
        html.append("    <div style=\"margin-top: 15px;\"><span class=\"line\"></span> ").append(escape(data.accountantName())).append("</div>\n");
        html.append("  </td>\n");
        html.append("</tr>\n</table>\n");

        html.append("</body>\n</html>");
        return html.toString();
    }

    public byte[] renderExcel(WarehousePrintFormData data, String language) {
        boolean uz = "uz".equalsIgnoreCase(language);
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet(data.formType().name());

            // Fonts & Styles
            Font titleFont = workbook.createFont(); titleFont.setFontName("Arial"); titleFont.setFontHeightInPoints((short) 12); titleFont.setBold(true);
            Font boldFont = workbook.createFont(); boldFont.setFontName("Arial"); boldFont.setFontHeightInPoints((short) 9); boldFont.setBold(true);
            Font regularFont = workbook.createFont(); regularFont.setFontName("Arial"); regularFont.setFontHeightInPoints((short) 9);

            CellStyle titleStyle = workbook.createCellStyle(); titleStyle.setFont(titleFont); titleStyle.setAlignment(HorizontalAlignment.CENTER);
            CellStyle rightCodeStyle = workbook.createCellStyle(); rightCodeStyle.setFont(boldFont); rightCodeStyle.setAlignment(HorizontalAlignment.RIGHT);

            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFont(boldFont);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            headerStyle.setBorderTop(BorderStyle.THIN); headerStyle.setBorderBottom(BorderStyle.THIN);
            headerStyle.setBorderLeft(BorderStyle.THIN); headerStyle.setBorderRight(BorderStyle.THIN);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            CellStyle bodyStyle = workbook.createCellStyle();
            bodyStyle.setFont(regularFont);
            bodyStyle.setBorderTop(BorderStyle.THIN); bodyStyle.setBorderBottom(BorderStyle.THIN);
            bodyStyle.setBorderLeft(BorderStyle.THIN); bodyStyle.setBorderRight(BorderStyle.THIN);

            CellStyle bodyCenter = workbook.createCellStyle();
            bodyCenter.cloneStyleFrom(bodyStyle); bodyCenter.setAlignment(HorizontalAlignment.CENTER);

            CellStyle bodyRight = workbook.createCellStyle();
            bodyRight.cloneStyleFrom(bodyStyle); bodyRight.setAlignment(HorizontalAlignment.RIGHT);

            CellStyle numStyle = workbook.createCellStyle();
            numStyle.cloneStyleFrom(bodyRight);
            numStyle.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00"));

            CellStyle qtyStyle = workbook.createCellStyle();
            qtyStyle.cloneStyleFrom(bodyRight);
            qtyStyle.setDataFormat(workbook.createDataFormat().getFormat("#,##0.000"));

            CellStyle totalStyle = workbook.createCellStyle();
            totalStyle.cloneStyleFrom(numStyle); totalStyle.setFont(boldFont);
            totalStyle.setFillForegroundColor(IndexedColors.LEMON_CHIFFON.getIndex());
            totalStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            int r = 0;
            // Row 0: Form code
            Row row0 = sheet.createRow(r++);
            Cell c0 = row0.createCell(8);
            c0.setCellValue(data.formCode());
            c0.setCellStyle(rightCodeStyle);

            // Row 1: Org
            Row row1 = sheet.createRow(r++);
            Cell cOrg = row1.createCell(0);
            cOrg.setCellValue((uz ? "Tashkilot: " : "Организация: ") + data.organizationName() + (data.organizationInn() != null ? " (INN: " + data.organizationInn() + ")" : ""));
            CellStyle orgStyle = workbook.createCellStyle(); orgStyle.setFont(boldFont);
            cOrg.setCellStyle(orgStyle);

            // Row 3: Title
            r++;
            Row rowTitle = sheet.createRow(r++);
            Cell cTitle = rowTitle.createCell(0);
            cTitle.setCellValue(data.formTitle() + " № " + data.documentNumber() + " " + (uz ? "dan" : "от") + " " + (data.documentDate() != null ? data.documentDate().format(DATE_FMT) : ""));
            cTitle.setCellStyle(titleStyle);
            sheet.addMergedRegion(new CellRangeAddress(rowTitle.getRowNum(), rowTitle.getRowNum(), 0, 9));

            // Metadata rows
            r++;
            Row rowMeta1 = sheet.createRow(r++);
            rowMeta1.createCell(0).setCellValue((uz ? "Jo‘natuvchi: " : "Отправитель: ") + data.sourceWarehouseName());
            rowMeta1.createCell(5).setCellValue((uz ? "Qabul qiluvchi: " : "Получатель: ") + (data.targetWarehouseName() != null ? data.targetWarehouseName() : data.targetDepartmentName()));

            Row rowMeta2 = sheet.createRow(r++);
            rowMeta2.createCell(0).setCellValue((uz ? "Valyuta: " : "Валюта: ") + data.currencyCode());
            if (data.contractNumber() != null) {
                rowMeta2.createCell(5).setCellValue((uz ? "Shartnoma: " : "Договор: ") + data.contractNumber());
            }

            r++;
            // Headers
            Row hRow = sheet.createRow(r++);
            String[] headers = {
                "№",
                uz ? "Schyot" : "Счет",
                uz ? "Korr. schyot" : "Корр. счет",
                uz ? "Kod" : "Номенкл. номер",
                uz ? "Nomi" : "Наименование",
                uz ? "O‘lch.b." : "Ед. изм.",
                uz ? "Talab etildi" : "Затребовано",
                uz ? "Berildi" : "Отпущено",
                uz ? "Narx" : "Цена",
                uz ? "Summa" : "Сумма"
            };
            for (int i = 0; i < headers.length; i++) {
                Cell c = hRow.createCell(i);
                c.setCellValue(headers[i]);
                c.setCellStyle(headerStyle);
            }

            int idx = 1;
            for (WarehousePrintFormLine l : data.lines()) {
                Row row = sheet.createRow(r++);
                Cell cIdx = row.createCell(0); cIdx.setCellValue(idx++); cIdx.setCellStyle(bodyCenter);
                Cell cAcc = row.createCell(1); cAcc.setCellValue(l.accountCode() != null ? l.accountCode() : ""); cAcc.setCellStyle(bodyCenter);
                Cell cCor = row.createCell(2); cCor.setCellValue(l.correspAccountCode() != null ? l.correspAccountCode() : ""); cCor.setCellStyle(bodyCenter);
                Cell cCod = row.createCell(3); cCod.setCellValue(l.materialCode() != null ? l.materialCode() : ""); cCod.setCellStyle(bodyCenter);
                Cell cNam = row.createCell(4);
                String codes = identification(l);
                String name = l.materialName() != null ? l.materialName() : "";
                cNam.setCellValue(codes.isEmpty() ? name : name + "\n" + codes);
                cNam.setCellStyle(bodyStyle);
                Cell cUom = row.createCell(5); cUom.setCellValue(l.unitOfMeasure() != null ? l.unitOfMeasure() : ""); cUom.setCellStyle(bodyCenter);

                Cell cReq = row.createCell(6);
                if (l.requestedQuantity() != null) cReq.setCellValue(l.requestedQuantity().doubleValue());
                cReq.setCellStyle(qtyStyle);

                Cell cRel = row.createCell(7);
                if (l.releasedOrAcceptedQuantity() != null) cRel.setCellValue(l.releasedOrAcceptedQuantity().doubleValue());
                cRel.setCellStyle(qtyStyle);

                Cell cPrc = row.createCell(8);
                if (l.unitPrice() != null) cPrc.setCellValue(l.unitPrice().doubleValue());
                cPrc.setCellStyle(numStyle);

                Cell cAmt = row.createCell(9);
                if (l.totalAmount() != null) cAmt.setCellValue(l.totalAmount().doubleValue());
                cAmt.setCellStyle(numStyle);
            }

            // Total row
            Row tRow = sheet.createRow(r++);
            Cell tLabel = tRow.createCell(0);
            tLabel.setCellValue(uz ? "JAMI:" : "ИТОГО:");
            tLabel.setCellStyle(headerStyle);
            sheet.addMergedRegion(new CellRangeAddress(tRow.getRowNum(), tRow.getRowNum(), 0, 5));
            for (int i = 1; i <= 5; i++) tRow.createCell(i).setCellStyle(headerStyle);

            Cell tReq = tRow.createCell(6); tReq.setCellValue("-"); tReq.setCellStyle(headerStyle);
            Cell tQty = tRow.createCell(7);
            if (data.totalQuantity() != null) tQty.setCellValue(data.totalQuantity().doubleValue());
            tQty.setCellStyle(qtyStyle);

            Cell tPrc = tRow.createCell(8); tPrc.setCellValue("-"); tPrc.setCellStyle(headerStyle);
            Cell tAmt = tRow.createCell(9);
            if (data.totalAmount() != null) tAmt.setCellValue(data.totalAmount().doubleValue());
            tAmt.setCellStyle(totalStyle);

            r += 2;
            // In words
            Row wRow = sheet.createRow(r++);
            Cell cWords = wRow.createCell(0);
            cWords.setCellValue((uz ? "Summa so‘z bilan: " : "Сумма прописью: ") + data.totalAmountInWords());
            CellStyle italicStyle = workbook.createCellStyle(); italicStyle.setFont(boldFont);
            cWords.setCellStyle(italicStyle);

            r++;
            // Signatures
            Row sigRow = sheet.createRow(r++);
            sigRow.createCell(0).setCellValue((uz ? "Topshirdi: " : "Отпустил: ") + data.releasedByRole() + " " + data.releasedByName());
            sigRow.createCell(5).setCellValue((uz ? "Qabul qildi: " : "Получил: ") + data.receivedByRole() + " " + data.receivedByName());

            // Column sizing & Print setup
            sheet.setColumnWidth(0, 5 * 256);
            sheet.setColumnWidth(1, 10 * 256);
            sheet.setColumnWidth(2, 10 * 256);
            sheet.setColumnWidth(3, 14 * 256);
            sheet.setColumnWidth(4, 28 * 256);
            sheet.setColumnWidth(5, 8 * 256);
            sheet.setColumnWidth(6, 12 * 256);
            sheet.setColumnWidth(7, 12 * 256);
            sheet.setColumnWidth(8, 12 * 256);
            sheet.setColumnWidth(9, 15 * 256);

            sheet.getPrintSetup().setPaperSize(PrintSetup.A4_PAPERSIZE);
            sheet.getPrintSetup().setLandscape(true);
            sheet.setFitToPage(true);
            sheet.getPrintSetup().setFitWidth((short) 1);
            sheet.getPrintSetup().setFitHeight((short) 0);

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to render print form excel: " + e.getMessage(), e);
        }
    }

    public static String formatAmountInWords(BigDecimal amount, String currency, String language) {
        if (amount == null) return "-";
        boolean uz = "uz".equalsIgnoreCase(language);
        long whole = amount.setScale(2, RoundingMode.HALF_UP).longValue();
        long cents = amount.remainder(BigDecimal.ONE).movePointRight(2).abs().longValue();

        String wholeWords = numberToWords(whole, uz);
        String currencyUnit = "UZS".equalsIgnoreCase(currency) ? (uz ? "so‘m" : "сум") : (uz ? "AQSH dollari" : "долл. США");
        String centsUnit = uz ? "tiyin" : "тыйин";

        return wholeWords + " " + currencyUnit + " " + String.format("%02d", cents) + " " + centsUnit;
    }

    private static String numberToWords(long number, boolean uz) {
        if (number == 0) return uz ? "nol" : "ноль";
        if (number < 0) return (uz ? "minus " : "минус ") + numberToWords(-number, uz);

        String[] unitsRu = {"", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять", "десять",
            "одиннадцать", "двенадцать", "тринадцать", "четырнадцать", "пятнадцать", "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать"};
        String[] tensRu = {"", "", "двадцать", "тридцать", "сорок", "пятьдесят", "шестьдесят", "семьдесят", "восемьдесят", "девяносто"};
        String[] hundredsRu = {"", "сто", "двести", "триста", "четыреста", "пятьсот", "шестьсот", "семьсот", "восемьдесят", "девятьсот"};

        String[] unitsUz = {"", "bir", "ikki", "uch", "to‘rt", "besh", "olti", "yetti", "sakkiz", "to‘qqiz", "o‘n",
            "o‘n bir", "o‘n ikki", "o‘n uch", "o‘n to‘rt", "o‘n besh", "o‘n olti", "o‘n yetti", "o‘n sakkiz", "o‘n to‘qqiz"};
        String[] tensUz = {"", "o‘n", "yigirma", "o‘ttiz", "qirq", "ellik", "oltmish", "yetmish", "sakson", "to‘qson"};

        StringBuilder sb = new StringBuilder();

        if (number >= 1_000_000_000) {
            long b = number / 1_000_000_000;
            sb.append(numberToWords(b, uz)).append(uz ? " milliard " : " миллиард ");
            number %= 1_000_000_000;
        }
        if (number >= 1_000_000) {
            long m = number / 1_000_000;
            sb.append(numberToWords(m, uz)).append(uz ? " million " : " миллион ");
            number %= 1_000_000;
        }
        if (number >= 1_000) {
            long k = number / 1_000;
            sb.append(numberToWords(k, uz)).append(uz ? " ming " : " тысяч ");
            number %= 1_000;
        }
        if (number >= 100) {
            long h = number / 100;
            if (uz) {
                sb.append(unitsUz[(int) h]).append(" yuz ");
            } else {
                sb.append(hundredsRu[(int) h]).append(" ");
            }
            number %= 100;
        }
        if (number >= 20) {
            long t = number / 10;
            sb.append(uz ? tensUz[(int) t] : tensRu[(int) t]).append(" ");
            number %= 10;
        }
        if (number > 0) {
            sb.append(uz ? unitsUz[(int) number] : unitsRu[(int) number]).append(" ");
        }

        String res = sb.toString().trim();
        return res.isEmpty() ? "" : Character.toUpperCase(res.charAt(0)) + res.substring(1);
    }

    private static String formatQty(BigDecimal val) {
        if (val == null) return "0.000";
        return val.setScale(3, RoundingMode.HALF_UP).toPlainString();
    }

    private static String formatMoney(BigDecimal val) {
        if (val == null) return "0.00";
        return val.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String identification(WarehousePrintFormLine line) {
        StringBuilder codes = new StringBuilder();
        appendCode(codes, "Штрихкод", line.barcode());
        appendCode(codes, "QR", line.qrCode());
        appendCode(codes, "RFID", line.rfidTag());
        return codes.toString();
    }

    private static void appendCode(StringBuilder codes, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!codes.isEmpty()) {
            codes.append("; ");
        }
        codes.append(label).append(": ").append(value.trim());
    }
}
