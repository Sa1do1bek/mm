package com.enterprise.mm.warehouse.reports;

import java.math.BigDecimal;
import java.util.List;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFSheet;

/** Shared print layout used by the 1C-style warehouse reports. Column A is a gutter. */
public final class WarehouseReportSheet {
    private final Sheet sheet;
    private final CellStyle body, heading, title, note, subtotal, account;
    private final CellStyle[][] quantities;
    private final CellStyle[] amounts, exact;
    private final int columns;

    public WarehouseReportSheet(Workbook workbook, String name, int columns) {
        this.columns=columns;
        sheet=workbook.createSheet(name);
        Font regular=font(workbook,8,false), bold=font(workbook,8,true);
        body=workbook.createCellStyle(); body.setFont(regular); body.setWrapText(true);
        body.setAlignment(HorizontalAlignment.LEFT); body.setDataFormat(workbook.createDataFormat().getFormat("@"));
        body.setVerticalAlignment(VerticalAlignment.TOP);
        body.setBorderTop(BorderStyle.THIN); body.setBorderBottom(BorderStyle.THIN);
        body.setBorderLeft(BorderStyle.THIN); body.setBorderRight(BorderStyle.THIN);
        heading=clone(workbook,body); heading.setFont(bold); heading.setAlignment(HorizontalAlignment.CENTER);
        heading.setVerticalAlignment(VerticalAlignment.CENTER);
        subtotal=clone(workbook,body); subtotal.setFont(bold);
        account=clone(workbook,subtotal); ((org.apache.poi.xssf.usermodel.XSSFCellStyle)account).setFillForegroundColor(new org.apache.poi.xssf.usermodel.XSSFColor(new byte[]{(byte)0xFA,(byte)0xEB,(byte)0xD7},new org.apache.poi.xssf.usermodel.DefaultIndexedColorMap()));
        account.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        title=workbook.createCellStyle(); title.setFont(font(workbook,12,true)); title.setAlignment(HorizontalAlignment.CENTER);
        note=workbook.createCellStyle(); note.setFont(regular); note.setWrapText(true); note.setVerticalAlignment(VerticalAlignment.TOP);
        quantities=new CellStyle[8][3]; amounts=new CellStyle[3]; exact=new CellStyle[3];
        for(int i=0;i<3;i++) {
            CellStyle base=style(i);
            for(int scale=0;scale<8;scale++) {
                String format="#,##0"+(scale==0?"":"."+"0".repeat(scale));
                quantities[scale][i]=clone(workbook,base); quantities[scale][i].setAlignment(HorizontalAlignment.RIGHT);
                quantities[scale][i].setDataFormat(workbook.createDataFormat().getFormat(format+";[Red]-"+format));
            }
            amounts[i]=clone(workbook,quantities[2][i]);
            exact[i]=clone(workbook,base); exact[i].setAlignment(HorizontalAlignment.RIGHT);
        }
        sheet.setDefaultRowHeightInPoints(12); sheet.setColumnWidth(0,597);
        sheet.setRowSumsBelow(false); sheet.setDisplayGridlines(false);
        for(int col=1;col<=columns;col++) sheet.setColumnWidth(col,18*256);
        sheet.setMargin(PageMargin.LEFT,.3937); sheet.setMargin(PageMargin.RIGHT,.3937);
        sheet.setMargin(PageMargin.TOP,.3937); sheet.setMargin(PageMargin.BOTTOM,.3937);
        sheet.setFitToPage(true); sheet.getPrintSetup().setFitWidth((short)1); sheet.getPrintSetup().setFitHeight((short)0);
        sheet.getPrintSetup().setPaperSize(PrintSetup.A4_PAPERSIZE);
    }

    public void title(String text) { note(0,text); sheet.getRow(0).getCell(1).setCellStyle(title); sheet.getRow(0).setHeightInPoints(19); }

    public void note(int line,String text) {
        Row row=sheet.createRow(line); Cell cell=row.createCell(1); cell.setCellValue(text); cell.setCellStyle(note);
        sheet.addMergedRegion(new CellRangeAddress(line,line,1,columns));
        row.setHeightInPoints(text.length()>160?32:14);
    }

    public void headers(List<String> identity,List<String> groups,String quantity,String amount,List<String> trailing) {
        Row top=row(3,0,0), bottom=row(4,0,0); top.setHeightInPoints(22); bottom.setHeightInPoints(22);
        for(int col=1;col<=columns;col++) {top.getCell(col).setCellStyle(heading);bottom.getCell(col).setCellStyle(heading);}
        int col=1;
        for(String label:identity) {top.getCell(col).setCellValue(label);merge(3,4,col,col);col++;}
        for(String group:groups) {
            top.getCell(col).setCellValue(group);merge(3,3,col,col+1);
            bottom.getCell(col).setCellValue(quantity);bottom.getCell(col+1).setCellValue(amount);col+=2;
        }
        for(String label:trailing) {top.getCell(col).setCellValue(label);merge(3,4,col,col);col++;}
        sheet.createFreezePane(Math.min(identity.size()+1,4),5);
        sheet.setRepeatingRows(new CellRangeAddress(3,4,-1,-1));
        sheet.getWorkbook().setPrintArea(sheet.getWorkbook().getSheetIndex(sheet),1,columns,0,4);
        sheet.getPrintSetup().setLandscape(columns>10);
    }

    public Row row(int line,int level,int emphasis) {
        Row row=sheet.createRow(line);
        for(int col=1;col<=columns;col++) row.createCell(col).setCellStyle(style(emphasis));
        if(sheet instanceof SXSSFSheet streaming) streaming.setRowOutlineLevel(line,level);
        else if(level>0) for(int i=0;i<level;i++) sheet.groupRow(line,line);
        return row;
    }

    public void text(Row row,int col,String text) {
        row.getCell(col).setCellValue(text==null?"—":text);
        double width=sheet.getColumnWidth(col)/256.0;
        int lines=text==null?1:text.lines().mapToInt(line->Math.max(1,(int)Math.ceil(line.length()/Math.max(1,width*1.15)))).sum();
        row.setHeightInPoints(Math.max(row.getHeightInPoints(),Math.min(120,lines*12)));
    }

    public void number(Row row,int col,String value,boolean money,int emphasis) {
        if(value==null || value.isBlank()) return;
        BigDecimal decimal=new BigDecimal(value); Cell cell=row.getCell(col);
        // Excel stores only 15 significant digits; keep larger source values exact.
        if(decimal.stripTrailingZeros().precision()>15) {cell.setCellValue(decimal.toPlainString());cell.setCellStyle(exact[emphasis]);}
        else {cell.setCellValue(decimal.doubleValue());cell.setCellStyle(money?amounts[emphasis]:quantities[Math.min(7,Math.max(0,decimal.stripTrailingZeros().scale()))][emphasis]);}
    }

    public void merge(int firstRow,int lastRow,int firstCol,int lastCol) {
        if(firstRow!=lastRow || firstCol!=lastCol) sheet.addMergedRegion(new CellRangeAddress(firstRow,lastRow,firstCol,lastCol));
    }

    public void finish(int lastRow) {
        sheet.getWorkbook().setPrintArea(sheet.getWorkbook().getSheetIndex(sheet),1,columns,0,Math.max(4,lastRow));
        int width=0;for(int col=1;col<=columns;col++)width+=sheet.getColumnWidth(col);
        sheet.getPrintSetup().setLandscape(width>190*256);
    }
    public void width(int col,double width) {sheet.setColumnWidth(col,(int)(width*256));}
    private CellStyle style(int emphasis) {return emphasis==2?account:emphasis==1?subtotal:body;}
    private static CellStyle clone(Workbook workbook,CellStyle original) {CellStyle copy=workbook.createCellStyle();copy.cloneStyleFrom(original);return copy;}
    private static Font font(Workbook workbook,int size,boolean bold) {Font font=workbook.createFont();font.setFontName("Arial");font.setFontHeightInPoints((short)size);font.setBold(bold);return font;}
}
