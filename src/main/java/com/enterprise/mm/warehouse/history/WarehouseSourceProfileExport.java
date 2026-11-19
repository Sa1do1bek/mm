package com.enterprise.mm.warehouse.history;

import com.enterprise.mm.error.BadRequestException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.math.BigDecimal;
import com.enterprise.mm.warehouse.reports.WarehouseReportSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;

@Component
public class WarehouseSourceProfileExport {
    static final String[] FIELDS={"code","name","inventoryNumber","serialNumber","stateName","locationName","initialValue","result","coefficient","mxikCode","packagingName","workshopName","warehouseKind","ownership","address","parentName","sourceAccount","documentNumber","uomNameRu","quantity","amount","sourceId"};
    static final String[] RU={"Код 1С","Наименование","Инвентарный номер","Заводской номер","Состояние в 1С","Местонахождение","Первоначальная стоимость","Результат испытания","Дополнительный коэффициент","ИКПУ","Упаковка","Цех","Вид склада","Принадлежность","Адрес","Родительская группа","Счёт 1С","Документ 1С","Единица измерения (1С)","Количество","Сумма, UZS","Идентификатор источника"};
    static final String[] EN={"1C code","Name","Inventory number","Factory number","1C state","Location","Original cost","Test result","Additional coefficient","IKPU","Packaging","Workshop","Warehouse kind","Ownership","Address","Parent group","1C account","1C document","Unit of measure (1C)","Quantity","Amount, UZS","Source identity"};
    static final String[] UZ={"1C kodi","Nomi","Inventar raqam","Zavod raqami","1C holati","Joylashuvi","Boshlang‘ich qiymat","Sinov natijasi","Qo‘shimcha koeffitsiyent","IKPU","Qadoq","Sex","Ombor turi","Tegishliligi","Manzil","Yuqori guruh","1C hisobi","1C hujjati","O‘lchov birligi (1C)","Miqdor","Summa, UZS","Manba identifikatori"};
    public String title(String category,String language){
        boolean ru=!Set.of("en","uz").contains(language);
        return switch(category){case "ASSET_CARD"->ru?"Инвентарные карточки ОС (1С)":"1C fixed asset cards";case "QUALITY_RESULT"->ru?"Результаты испытаний (1С)":"1C quality results";case "WAREHOUSE_DETAILS"->ru?"Склады и группы складов (1С)":"1C warehouses";case "GROUP_DETAILS"->ru?"Группы материалов (1С)":"1C material groups";default->ru?"Данные склада (1С)":"1C warehouse data";};
    }
    @SuppressWarnings("unchecked")
    public byte[] export(Map<String,Object> report,String category,String format,String language){
        if(!Set.of("csv","xlsx").contains(format))throw new BadRequestException("Choose CSV or Excel");
        var rows=(List<Map<String,Object>>)report.get("content");String[] headers=language.equals("en")?EN:language.equals("uz")?UZ:RU;
        if(category.equals("BALANCE_DETAILS") && format.equals("xlsx"))return balanceWorkbook(rows,language,headers);
        var indexes=new ArrayList<Integer>();for(int i=0;i<FIELDS.length;i++){final String field=FIELDS[i];if(rows.stream().anyMatch(r->r.get(field)!=null))indexes.add(i);}
        if(indexes.isEmpty())indexes.addAll(List.of(0,1));
        try {
            if(format.equals("csv")){
                var out=new StringBuilder("\ufeff");out.append(line(indexes.stream().map(i->headers[i]).toList())).append('\n');
                for(var row:rows)out.append(line(indexes.stream().map(i->text(row.get(FIELDS[i]))).toList())).append('\n');return out.toString().getBytes(StandardCharsets.UTF_8);
            }
            try(var workbook=new SXSSFWorkbook(100);var out=new ByteArrayOutputStream()){
                var sheet=workbook.createSheet("1C");var h=sheet.createRow(0);int column=0;for(int i:indexes)h.createCell(column++).setCellValue(headers[i]);
                int number=1;for(var row:rows){var r=sheet.createRow(number++);column=0;for(int i:indexes)r.createCell(column++).setCellValue(text(row.get(FIELDS[i])));}
                sheet.createFreezePane(0,1);for(int i=0;i<indexes.size();i++)sheet.setColumnWidth(i,(i==1?50:25)*256);
                if(number>1)sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0,number-1,0,indexes.size()-1));
                workbook.write(out);workbook.dispose();return out.toByteArray();
            }
        }catch(IOException e){throw new IllegalStateException("Source export could not be written",e);}
    }
    private byte[] balanceWorkbook(List<Map<String,Object>> rows,String language,String[] headers) {
        var workbook=new SXSSFWorkbook(200);
        try(workbook;var out=new ByteArrayOutputStream()) {
            workbook.setCompressTempFiles(true);
            var layout=new WarehouseReportSheet(workbook,"1C",9);
            String title=switch(language) {case "en"->"Stock balances by 1C analytics";case "uz"->"1C analitikasi bo‘yicha qoldiqlar";default->"Остаточная ведомость по аналитикам 1С";};
            var dates=rows.stream().map(row->text(row.get("sourceDate"))).filter(value->!value.isBlank()).distinct().sorted().map(value->java.time.LocalDate.parse(value).format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"))).toList();
            layout.title(title+(dates.isEmpty()?"":" · "+String.join(" — ",dates)));
            layout.note(1,"UZS");
            String group=language.equals("en")?"Group":language.equals("uz")?"Guruh":"Гр.";
            String price=language.equals("en")?"Price":language.equals("uz")?"Narx":"Цена";
            String type=language.equals("en")?"1C material type":language.equals("uz")?"1C nomenklatura turi":"Вид номенклатуры (1С)";
            String closing=language.equals("en")?"Closing balance":language.equals("uz")?"Yakuniy qoldiq":"Сальдо на конец периода";
            layout.width(1,10);layout.width(2,14);layout.width(3,52.5);layout.width(4,10.5);layout.width(5,14);
            layout.width(6,16);layout.width(7,20);layout.width(8,28);layout.width(9,52);
            layout.headers(List.of(group,language.equals("en")?"Code":language.equals("uz")?"Kod":"Код",headers[1],headers[18],price),List.of(closing),headers[19],headers[20],List.of(type,headers[21]));
            var accounts=new TreeMap<String,List<Map<String,Object>>>();
            for(var row:rows)accounts.computeIfAbsent(text(row.getOrDefault("accountCode",row.get("code"))),key->new ArrayList<>()).add(row);
            int line=5;
            for(var account:accounts.entrySet()) {
                var summary=layout.row(line++,0,2);layout.text(summary,1,account.getKey());layout.merge(summary.getRowNum(),summary.getRowNum(),1,5);
                balanceTotals(layout,summary,account.getValue(),2);
                var warehouses=new TreeMap<String,List<Map<String,Object>>>();
                for(var row:account.getValue())warehouses.computeIfAbsent(text(row.get("warehouseCode"))+"|"+text(row.get("warehouseName"))+"|"+text(row.get("warehouseSourceId")),key->new ArrayList<>()).add(row);
                for(var warehouse:warehouses.values()) {
                    warehouse.sort(Comparator.comparing(row->text(row.get("materialCode"))));
                    var first=warehouse.getFirst();var subtotal=layout.row(line++,1,1);
                    layout.text(subtotal,2,text(first.get("warehouseCode")));layout.text(subtotal,3,text(first.getOrDefault("warehouseName",first.get("locationName"))));
                    layout.merge(subtotal.getRowNum(),subtotal.getRowNum(),3,5);balanceTotals(layout,subtotal,warehouse,1);
                    for(var record:warehouse) {
                        var detail=layout.row(line++,2,0);
                        layout.text(detail,1,text(record.get("materialGroupCode")));layout.text(detail,2,text(record.get("materialCode")));
                        layout.text(detail,3,text(record.getOrDefault("materialName",record.get("name"))));layout.text(detail,4,text(record.getOrDefault("uomNameRu",record.get("uom"))));
                        layout.number(detail,5,text(record.get("price")),true,0);
                        if(!"VALUE_ONLY".equals(record.get("sourceType")))layout.number(detail,6,text(record.get("quantity")),false,0);
                        layout.number(detail,7,text(record.get("amount")),true,0);layout.text(detail,8,text(record.get("sourceMaterialType")));
                        layout.text(detail,9,text(record.get("sourceId")));
                    }
                }
            }
            var total=layout.row(line++,0,2);layout.text(total,1,language.equals("en")?"Total":language.equals("uz")?"Jami":"Итого");
            layout.merge(total.getRowNum(),total.getRowNum(),1,5);balanceTotals(layout,total,rows,2);
            layout.finish(line-1);workbook.write(out);return out.toByteArray();
        } catch(IOException ex) {throw new IllegalStateException("Source export could not be written",ex);}
        finally {workbook.dispose();}
    }
    private void balanceTotals(WarehouseReportSheet layout,org.apache.poi.ss.usermodel.Row row,List<Map<String,Object>> records,int emphasis) {
        BigDecimal amount=BigDecimal.ZERO,quantity=BigDecimal.ZERO;
        for(var record:records) {
            if(record.get("amount")!=null)amount=amount.add(new BigDecimal(text(record.get("amount"))));
            if(record.get("quantity")!=null)quantity=quantity.add(new BigDecimal(text(record.get("quantity"))));
        }
        boolean singleUnit=!records.isEmpty() && records.stream().noneMatch(record->"VALUE_ONLY".equals(record.get("sourceType")))
            && records.stream().map(record->text(record.get("uom"))+"|"+text(record.get("uomNameRu"))).distinct().count()==1;
        if(singleUnit)layout.number(row,6,quantity.toPlainString(),false,emphasis);
        layout.number(row,7,amount.toPlainString(),true,emphasis);
    }
    private static String text(Object value){return value==null?"":String.valueOf(value);}
    private static String line(List<String> cells){return cells.stream().map(s->{String v=s.matches("^[=+@].*") || (s.startsWith("-")&&!s.matches("-[0-9]+(\\.[0-9]+)?"))?"'"+s:s;return "\""+v.replace("\"","\"\"")+"\"";}).collect(java.util.stream.Collectors.joining(";"));}
}
