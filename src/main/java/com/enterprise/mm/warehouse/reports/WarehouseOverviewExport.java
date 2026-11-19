package com.enterprise.mm.warehouse.reports;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

@Component
public class WarehouseOverviewExport {
    private record Copy(String title,String period,String currency,String code,String warehouse,
        String unit,String[] metrics,String status,String approved,String pending,String revision,String by,String at,
        String unitNote,String warning) {}

    private Copy copy(String language) {
        return switch(language) {
            case "en" -> new Copy("Warehouse overview","Period","Currency","Code","Warehouse","UOM",
                new String[]{"Opening balance","Receipts","Issues","Closing balance"},"Status","Approved copy","Pending",
                "Revision","Approved by","Approved at","Amounts show current figures; quantities with different units are shown separately. Approval refers to a saved copy.",
                "The data contains warnings that require review.");
            case "uz" -> new Copy("Omborlar bo‘yicha hisobot","Davr","Valyuta","Kod","Ombor","O‘lchov birligi",
                new String[]{"Boshlang‘ich qoldiq","Kirim","Chiqim","Yakuniy qoldiq"},"Holat","Tasdiqlangan nusxa","Tasdiqlanmagan",
                "Versiya","Tasdiqlagan","Tasdiqlangan sana","Summalar joriy hisobni ko‘rsatadi; turli birliklardagi miqdorlar alohida ko‘rsatiladi. Tasdiqlash saqlangan nusxaga tegishli.",
                "Ma’lumotlarda tekshirishni talab qiladigan ogohlantirish bor.");
            default -> new Copy("Отчёт по складам","Период","Валюта","Код","Склад","Ед. изм.",
                new String[]{"Начальный остаток","Приход","Расход","Конечный остаток"},"Статус","Есть подтверждённая копия","Не подтверждено",
                "Версия","Подтвердил","Дата подтверждения","Суммы показывают текущие расчёты; количество в разных единицах показано отдельно. Подтверждение относится к сохранённой копии.",
                "Данные содержат предупреждения, требующие проверки.");
        };
    }

    public byte[] xlsx(WarehouseOverview report,String language) {
        Copy c=copy(language);
        try(var workbook=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            var layout=new WarehouseReportSheet(workbook,c.title,15);
            layout.title(c.title+" · "+report.from().format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))+" — "+report.to().format(DateTimeFormatter.ofPattern("dd.MM.yyyy")));
            layout.note(1,c.period+": "+report.from()+" — "+report.to()+" | "+c.currency+": "+report.currencyCode());
            layout.width(1,14);layout.width(2,40);layout.width(3,10.5);
            for(int col=4;col<=11;col++)layout.width(col,col%2==0?16:20);
            layout.width(12,28);layout.width(13,10);layout.width(14,22);layout.width(15,22);
            layout.headers(List.of(c.code,c.warehouse,c.unit),List.of(c.metrics),
                language.equals("en")?"Quantity":language.equals("uz")?"Miqdor":"Кол-во",
                language.equals("en")?"Amount":language.equals("uz")?"Summa":"Сумма",List.of(c.status,c.revision,c.by,c.at));
            int line=5;
            for(var warehouse:report.warehouses()) {
                var row=layout.row(line++,0,2);
                layout.text(row,1,warehouse.warehouseCode());layout.text(row,2,WarehouseReportExport.label(warehouse.warehouseName(),language));
                var units=warehouse.quantities();boolean single=units.size()==1;
                if(single) {
                    var unit=units.getFirst();layout.text(row,3,WarehouseReportExport.unitLabel(unit.uom(),unit.unitOfMeasureShortName(),unit.unitOfMeasureShortNameRu(),unit.unitOfMeasureShortNameUz(),language));
                    quantities(layout,row,unit,2);
                }
                var amounts=List.of(warehouse.openingAmount(),warehouse.receiptAmount(),warehouse.issueAmount(),warehouse.closingAmount());
                for(int i=0;i<4;i++)layout.number(row,5+i*2,amounts.get(i),true,2);
                var approval=warehouse.approval();layout.text(row,12,approval==null?c.pending:c.approved);
                if(approval!=null) {
                    layout.number(row,13,String.valueOf(approval.revision()),false,2);layout.text(row,14,approval.approvedBy());
                    layout.text(row,15,approval.approvedAt().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")));
                }
                if(!single)for(var unit:units) {
                    var detail=layout.row(line++,1,0);
                    layout.text(detail,3,WarehouseReportExport.unitLabel(unit.uom(),unit.unitOfMeasureShortName(),unit.unitOfMeasureShortNameRu(),unit.unitOfMeasureShortNameUz(),language));
                    quantities(layout,detail,unit,0);
                }
            }
            line++;layout.note(line++,c.unitNote);
            if(!report.warnings().isEmpty())layout.note(line++,c.warning);
            layout.finish(line-1);workbook.write(out);return out.toByteArray();
        } catch(IOException ex) {throw new IllegalStateException("Unable to export warehouse overview",ex);}
    }

    private void quantities(WarehouseReportSheet layout,org.apache.poi.ss.usermodel.Row row,WarehouseOverview.UnitTotal unit,int emphasis) {
        var values=List.of(unit.openingQuantity(),unit.receiptQuantity(),unit.issueQuantity(),unit.closingQuantity());
        for(int i=0;i<4;i++)layout.number(row,4+i*2,values.get(i),false,emphasis);
    }
}
