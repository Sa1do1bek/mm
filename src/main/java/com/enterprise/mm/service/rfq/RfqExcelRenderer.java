package com.enterprise.mm.service.rfq;

import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.service.rfq.RfqPackageLabels.Labels;
import com.enterprise.mm.service.rfq.RfqPackageSnapshot.Position;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

@Component
public class RfqExcelRenderer {
    public byte[] render(RfqPackageSnapshot snapshot) {
        Labels labels = RfqPackageLabels.forLanguage(snapshot.language());
        String[] headers = headers(labels);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet(labels.quotationSheet());
            CellStyle labelStyle = labelStyle(workbook);
            CellStyle headerStyle = headerStyle(workbook);
            CellStyle responseStyle = responseStyle(workbook);

            writePair(sheet.createRow(0), "RFQ", snapshot.rfqCode(), labelStyle);
            writePair(sheet.createRow(1), labels.title(), snapshot.title(), labelStyle);
            writePair(sheet.createRow(2), labels.deadline(), text(snapshot.dueDate()), labelStyle);
            writePair(sheet.createRow(3), labels.instructions(), snapshot.responseInstructions(), labelStyle);
            Row header = sheet.createRow(5);
            for (int column = 0; column < headers.length; column++) {
                header.createCell(column).setCellValue(headers[column]);
                header.getCell(column).setCellStyle(headerStyle);
            }

            int rowIndex = 6;
            for (Position position : snapshot.positions()) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(text(position.rfqItemId()));
                row.createCell(1).setCellValue(text(position.materialCode()));
                row.createCell(2).setCellValue(text(position.materialName()));
                row.createCell(3).setCellValue(text(position.specification()));
                row.createCell(4).setCellValue(text(position.unit()));
                if (position.quantity() != null) {
                    row.createCell(5).setCellValue(position.quantity().doubleValue());
                }
                for (int column = 6; column < headers.length; column++) {
                    row.createCell(column).setCellStyle(responseStyle);
                }
            }
            sheet.setColumnHidden(0, true);
            sheet.createFreezePane(0, 6);
            sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(
                5,
                Math.max(5, rowIndex - 1),
                0,
                headers.length - 1
            ));
            for (int column = 0; column < headers.length; column++) {
                sheet.autoSizeColumn(column);
                sheet.setColumnWidth(column, Math.min(sheet.getColumnWidth(column) + 700, 18000));
            }
            sheet.protectSheet("rfq-template");
            writeMetadata(workbook, snapshot);
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new BusinessException("Cannot render RFQ Excel package");
        }
    }

    private String[] headers(Labels labels) {
        return new String[]{
            labels.rfqItemId(),
            labels.material() + " " + labels.code().toLowerCase(),
            labels.material(),
            labels.specification(),
            labels.unit(),
            labels.requestedQuantity(),
            labels.unitPrice(),
            labels.offeredQuantity(),
            labels.deliveryDays(),
            labels.tax(),
            labels.noBid(),
            labels.noBidReason(),
            labels.notes()
        };
    }

    private void writeMetadata(XSSFWorkbook workbook, RfqPackageSnapshot snapshot) {
        Sheet metadata = workbook.createSheet("_RFQ_META");
        writePair(metadata.createRow(0), "RFQ_ID", text(snapshot.rfqId()), null);
        writePair(metadata.createRow(1), "RFQ_CODE", snapshot.rfqCode(), null);
        writePair(metadata.createRow(2), "TEMPLATE_VERSION", "1", null);
        workbook.setSheetHidden(workbook.getSheetIndex(metadata), true);
    }

    private void writePair(Row row, String label, String value, CellStyle labelStyle) {
        row.createCell(0).setCellValue(label);
        if (labelStyle != null) {
            row.getCell(0).setCellStyle(labelStyle);
        }
        row.createCell(1).setCellValue(text(value));
    }

    private CellStyle labelStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        org.apache.poi.ss.usermodel.Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }

    private CellStyle headerStyle(Workbook workbook) {
        CellStyle style = labelStyle(workbook);
        style.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setWrapText(true);
        return style;
    }

    private CellStyle responseStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setLocked(false);
        style.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
