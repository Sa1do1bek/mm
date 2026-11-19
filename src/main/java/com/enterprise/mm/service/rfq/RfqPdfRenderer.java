package com.enterprise.mm.service.rfq;

import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.service.rfq.RfqPackageLabels.Labels;
import com.enterprise.mm.service.rfq.RfqPackageSnapshot.Position;
import com.lowagie.text.Document;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
public class RfqPdfRenderer {
    public byte[] render(RfqPackageSnapshot snapshot) {
        Labels labels = RfqPackageLabels.forLanguage(snapshot.language());
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4.rotate(), 28, 28, 24, 24);
            PdfWriter.getInstance(document, output);
            document.open();
            document.add(new Paragraph(labels.heading().toUpperCase(), new Font(Font.HELVETICA, 16, Font.BOLD)));
            document.add(new Paragraph(text(snapshot.rfqCode()) + " — " + text(snapshot.title())));
            document.add(new Paragraph(labels.deadline() + ": " + text(snapshot.dueDate())));
            document.add(new Paragraph(labels.buyer() + ": " + text(snapshot.buyer() == null ? null : snapshot.buyer().companyName())));
            document.add(new Paragraph(text(snapshot.description())));
            document.add(new Paragraph(labels.responseInstructions() + ": " + text(snapshot.responseInstructions())));
            document.add(new Paragraph(" "));

            PdfPTable table = new PdfPTable(new float[]{0.5f, 1.1f, 2.2f, 2.8f, 0.7f, 0.9f, 1.1f});
            table.setWidthPercentage(100);
            addHeader(table, "#");
            addHeader(table, labels.material() + " " + labels.code().toLowerCase());
            addHeader(table, labels.material());
            addHeader(table, labels.specification());
            addHeader(table, labels.unit());
            addHeader(table, labels.quantity());
            addHeader(table, labels.requiredDate());
            int index = 1;
            for (Position position : snapshot.positions()) {
                addCell(table, String.valueOf(index++));
                addCell(table, position.materialCode());
                addCell(table, position.materialName());
                addCell(table, position.specification());
                addCell(table, position.unit());
                addCell(table, decimal(position.quantity()));
                addCell(table, text(position.requiredDate()));
            }
            document.add(table);
            document.close();
            return output.toByteArray();
        } catch (Exception exception) {
            throw new BusinessException("Cannot render RFQ PDF package");
        }
    }

    private void addHeader(PdfPTable table, String value) {
        PdfPCell cell = new PdfPCell(new Phrase(value, new Font(Font.HELVETICA, 8, Font.BOLD)));
        cell.setPadding(5);
        table.addCell(cell);
    }

    private void addCell(PdfPTable table, String value) {
        PdfPCell cell = new PdfPCell(new Phrase(text(value), new Font(Font.HELVETICA, 8)));
        cell.setPadding(4);
        table.addCell(cell);
    }

    private String decimal(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
