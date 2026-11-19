package com.enterprise.mm.warehouse.reports;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;
import static com.enterprise.mm.warehouse.reports.WarehouseReport.*;

@Component
public class WarehouseReportExport {
    private record Copy(String[] titles,String[] identity,String quantity,String amount,String[] groups,
        String credit,String debit,String supplier,String recipient,String total,String generated,String history,String integratedHistory,String unmappedReferences,String accountWarning,String ledgerWarning,
        String sheet,String period,String date,String nomenclatureCode) {}

    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter DATE_TIME=DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private Copy copy(String lang) {
        return switch(lang) {
            case "en" -> new Copy(new String[]{
                "Material statement","Stock balances as of date","Receipt register","Issue register",
                "Turnover statement","Material group summary","Materials in use statement","Custody storage statement",
                "Equipment for installation statement","Inventory reconciliation statement","Non-moving stock report","Balance reconciliation report"
            },
                new String[]{"Warehouse","Group","Code","Material name","UOM","Price"},"Quantity","Amount",
                new String[]{"Opening balance","Receipts","Issues","Closing balance"},"Credit account","Debit account","Supplier","Recipient","Total by UOM","Generated",
                "Data is available from %s. Imported 1C opening stock is included in balances. Earlier movements are not reflected in this report.",
                "This report includes posted 1C material-account movements before the opening snapshot and subsequent MM movements. Earlier opening balances are reconstructed from the verified snapshot; current stock is unchanged.",
                "Legacy references use their original 1C names and codes. Where the unit was not preserved, quantity totals remain separate for each material (1C-UNKNOWN).",
                "Corresponding account data is not yet available. Missing accounts, suppliers, or recipients are shown as a dash.",
                "Warehouse accounting data differs from current balances. Reconcile opening stock and value adjustments before using this statement.",
                "Report","Period","As of date","Material code");
            case "uz" -> new Copy(new String[]{
                "Materiallar qaydnomasi","Sana bo‘yicha qoldiqlar","Kirim qaydnomasi","Chiqim qaydnomasi",
                "Aylanma qaydnomasi","Materiallar guruhlari bo‘yicha jami","Foydalanishdagi materiallar qaydnomasi","Mas’ul saqlashdagi tovarlar qaydnomasi",
                "O‘rnatishga berilgan uskunalar qaydnomasi","Inventarizatsiya taqqoslash qaydnomasi","Harakatsiz qoldiqlar hisoboti","Solishtirish hisoboti"
            },
                new String[]{"Ombor","Guruh","Kod","Material nomi","O‘lchov birligi","Narx"},"Miqdor","Summa",
                new String[]{"Boshlang‘ich qoldiq","Kirim","Chiqim","Yakuniy qoldiq"},"Kredit hisob","Debet hisob","Yetkazib beruvchi","Qabul qiluvchi","Birlik bo‘yicha jami","Shakllantirilgan",
                "Tarix %s sanasidan mavjud; avvalgi 1C harakatlari import qilinmagan.",
                "Hisobot boshlang‘ich qoldiq sanasigacha o‘tkazilgan 1C materiallar harakatlari va keyingi MM harakatlarini o‘z ichiga oladi. Oldingi davr qoldiqlari tekshirilgan nusxadan tiklanadi; joriy qoldiqlar o‘zgarmaydi.",
                "Eski ma’lumotnomalar 1C nomlari va kodlari bilan ko‘rsatiladi. Birlik saqlanmagan bo‘lsa, miqdor jami materiallar bo‘yicha alohida hisoblanadi (1C-UNKNOWN).",
                "MM harakatlarida korrespondensiya hisoblari saqlanmaydi. Mavjud bo‘lmagan kontragentlar tire bilan ko‘rsatiladi.",
                "Harakatlar registri joriy qoldiqlarga mos emas. Boshlang‘ich qoldiq yoki qiymat tuzatishlari solishtirilishi kerak.",
                "Hisobot","Davr","Sana bo‘yicha","Material kodi");
            default -> new Copy(new String[]{
                "Материальная ведомость","Остаточная ведомость на дату","Ведомость прихода номенклатуры","Ведомость расхода номенклатуры",
                "Оборотно-сальдовая ведомость","Свод по группам номенклатуры","Ведомость материалов в эксплуатации","Ведомость ответственного хранения",
                "Ведомость оборудования к установке","Сличительная ведомость инвентаризации","Отчёт по неликвидным и неходовым остаткам","Акт сверки остатков"
            },
                new String[]{"Склад","Гр.","Код","Наименование","Ед. изм.","Цена"},"Кол-во","Сумма",
                new String[]{"Сальдо на начало периода","Приход","Расход","Сальдо на конец периода"},"Счёт Кт","Счёт Дт","Поставщик","Получатель","Итого по единицам измерения","Сформировано",
                "Данные доступны с %s. Начальные остатки из 1С включены в сальдо. Движения до этой даты в отчёте не отражены.",
                "Отчёт включает проведённые движения 1С по счетам материалов до даты начальных остатков и последующие движения MM. Начальное сальдо прошлых периодов восстановлено от подтверждённого снимка; текущие остатки не меняются.",
                "Старые справочники показаны с исходными названиями и кодами 1С. Если единица измерения не сохранена, итоги количества разделены по материалам (1C-UNKNOWN).",
                "Данные о корреспондирующих счетах пока недоступны. Если счёт, поставщик или получатель не указан, в ведомости отображается тире (—).",
                "Данные складского учёта расходятся с текущими остатками. Перед использованием ведомости необходимо сверить начальные остатки и корректировки стоимости.",
                "Отчёт","Период","На дату","Номенкл. №");
        };
    }

    private String accountHeader(String language) {return switch(language) {case "en" -> "Stock account";case "uz" -> "Qoldiq hisobi";default -> "Счёт учёта";};}
    private String typeHeader(String language) {return switch(language) {case "en" -> "1C material type";case "uz" -> "1C nomenklatura turi";default -> "Вид номенклатуры (1С)";};}
    private String accountNote(WarehouseReport report,String language) {
        String date=report.accountSnapshotDate()==null?"—":report.accountSnapshotDate().format(DATE);
        return switch(language) {
            case "en" -> "1C warehouse account balances as of "+date+" and subsequent MM movements. Earlier balances are reconstructed from source postings. Totals are separate by account and unit. Custody, materials in use, shortages and asset acquisitions are accounting positions; they are not unrestricted available stock. Unassigned MM accounts are shown as a dash.";
            case "uz" -> "1C ombor hisoblari qoldiqlari: "+date+"; keyingi MM harakatlari ham kiritilgan. Oldingi qoldiqlar manba o‘tkazmalaridan tiklanadi. Jami hisob va birlik bo‘yicha alohida. Mas’ul saqlash, foydalanishdagi materiallar, kamomad va asosiy vositalar xaridi erkin mavjud zaxira emas. MM hisobi ko‘rsatilmagan bo‘lsa, tire qo‘yiladi.";
            default -> "Остатки по складским счетам 1С на "+date+" и последующие движения MM. Сальдо прошлых периодов восстановлено по исходным проводкам. Итоги разделены по счетам и единицам измерения. Ответственное хранение, материалы в эксплуатации, недостачи и приобретение ОС отражают учётные позиции и не означают свободный запас. Для движений MM без счёта указано тире (—).";
        };
    }
    private List<String> headers(Type type,Copy c,boolean accounts,String language) {
        var h=new ArrayList<>(baseHeaders(type,c));
        if(accounts) h.addAll(1,List.of(accountHeader(language),typeHeader(language)));
        return h;
    }
    private List<String> baseHeaders(Type type,Copy c) {
        if (type==Type.RECEIPTS || type==Type.ISSUES) return List.of(c.identity[0],type==Type.RECEIPTS?c.credit:c.debit,
            type==Type.RECEIPTS?c.supplier:c.recipient,c.nomenclatureCode,c.identity[3],c.identity[4],c.quantity,c.amount);
        var h=new ArrayList<>(List.of(c.identity));
        if (type==Type.STOCK_BALANCES) { h.add(c.quantity); h.add(c.amount); }
        else for(String group:c.groups) { h.add(group+" / "+c.quantity); h.add(group+" / "+c.amount); }
        return h;
    }

    private List<String> values(Type type,WarehouseReport.Row r,String language,boolean accounts) {
        var v=new ArrayList<>(baseValues(type,r,language));
        if(accounts) v.addAll(1,List.of(text(r.accountCode()),text(r.sourceMaterialType())));
        return v;
    }
    private List<String> baseValues(Type type,WarehouseReport.Row r,String language) {
        if(type==Type.RECEIPTS || type==Type.ISSUES) return List.of(label(r.warehouseName(),language),r.correspondingAccount(),
            label(r.counterparty(),language),text(r.materialCode()),label(r.materialName(),language),unitLabel(r.uom(),r.unitOfMeasureShortName(),r.unitOfMeasureShortNameRu(),r.unitOfMeasureShortNameUz(),language),
            type==Type.RECEIPTS?r.receiptQuantity():r.issueQuantity(),type==Type.RECEIPTS?r.receiptAmount():r.issueAmount());
        var v=new ArrayList<>(List.of(label(r.warehouseName(),language),text(r.groupCode()),text(r.materialCode()),label(r.materialName(),language),unitLabel(r.uom(),r.unitOfMeasureShortName(),r.unitOfMeasureShortNameRu(),r.unitOfMeasureShortNameUz(),language),r.price()));
        if(type==Type.STOCK_BALANCES) { v.add(r.closingQuantity()); v.add(r.closingAmount()); }
        else v.addAll(List.of(r.openingQuantity(),r.openingAmount(),r.receiptQuantity(),r.receiptAmount(),r.issueQuantity(),r.issueAmount(),r.closingQuantity(),r.closingAmount()));
        return v;
    }

    public byte[] xlsx(WarehouseReport report,String language) {
        Copy c=copy(language); boolean accounts=report.accountSnapshotDate()!=null;
        boolean register=report.type()==Type.RECEIPTS || report.type()==Type.ISSUES;
        List<String> identity=register ? List.of(report.type()==Type.RECEIPTS?c.credit:c.debit,
            report.type()==Type.RECEIPTS?c.supplier:c.recipient,c.nomenclatureCode,c.identity[3],c.identity[4])
            : List.of(c.identity[1],c.identity[2],c.identity[3],c.identity[4],c.identity[5]);
        List<String> groups=switch(report.type()) {
            case STOCK_BALANCES -> List.of(c.groups[3]); case RECEIPTS -> List.of(c.groups[1]);
            case ISSUES -> List.of(c.groups[2]); default -> List.of(c.groups);
        };
        List<String> trailing=accounts?List.of(typeHeader(language)):List.of();
        int numericStart=identity.size()+1, columns=identity.size()+groups.size()*2+trailing.size();
        SXSSFWorkbook workbook=new SXSSFWorkbook(200);
        try(workbook;var out=new ByteArrayOutputStream()) {
            workbook.setCompressTempFiles(true);
            var layout=new WarehouseReportSheet(workbook,c.sheet,columns);
            layout.title(c.titles[report.type().ordinal()]+" · "+(report.type()==Type.STOCK_BALANCES?report.to().format(DATE):report.from().format(DATE)+" — "+report.to().format(DATE)));
            layout.note(1,label(report.companyName(),language)+" · "+report.currencyCode());
            layout.note(2,period(report,c)+(report.warehouseName()==null?"":" · "+c.identity[0]+": "+label(report.warehouseName(),language)));
            layout.width(1,register?14:10);layout.width(2,register?28:14);layout.width(3,52.5);layout.width(4,10.5);layout.width(5,14);
            if(register) {layout.width(3,14);layout.width(4,52.5);layout.width(5,10.5);}
            for(int col=numericStart;col<numericStart+groups.size()*2;col++)layout.width(col,(col-numericStart)%2==0?16:20);
            if(accounts)layout.width(columns,24);
            layout.headers(identity,groups,c.quantity,c.amount,trailing);
            var byAccount=new java.util.TreeMap<String,List<WarehouseReport.Row>>();
            for(var row:report.content())byAccount.computeIfAbsent(row.accountCode()==null?"":row.accountCode(),key->new ArrayList<>()).add(row);
            int line=5;
            for(var entry:byAccount.entrySet()) {
                var rows=entry.getValue(); boolean accountGroup=accounts || !entry.getKey().isEmpty();
                if(accountGroup) {
                    var summary=layout.row(line++,0,2);layout.text(summary,1,(entry.getKey().isEmpty()?"—":entry.getKey()));
                    layout.merge(summary.getRowNum(),summary.getRowNum(),1,identity.size());
                    writeMetrics(layout,summary,numericStart,summaryValues(rows,report.type()),2);
                }
                var byWarehouse=new java.util.TreeMap<String,List<WarehouseReport.Row>>();
                for(var row:rows) {
                    String key=text(row.warehouseCode())+"\n"+(row.warehouseId()==null?text(row.warehouseName()):row.warehouseId().toString());
                    byWarehouse.computeIfAbsent(key,k->new ArrayList<>()).add(row);
                }
                for(var warehouse:byWarehouse.values()) {
                    warehouse.sort(java.util.Comparator.comparing((WarehouseReport.Row row)->text(row.materialCode())).thenComparing(WarehouseReport.Row::key));
                    var first=warehouse.getFirst();var summary=layout.row(line++,accountGroup?1:0,1);
                    layout.text(summary,2,text(first.warehouseCode()));layout.text(summary,3,label(first.warehouseName(),language));
                    layout.merge(summary.getRowNum(),summary.getRowNum(),3,identity.size());
                    writeMetrics(layout,summary,numericStart,summaryValues(warehouse,report.type()),1);
                    for(var record:warehouse) {
                        var detail=layout.row(line++,accountGroup?2:1,0);
                        List<String> values=new ArrayList<>(baseValues(report.type(),record,language));values.removeFirst();
                        for(int col=1;col<numericStart;col++)layout.text(detail,col,values.get(col-1));
                        for(int col=numericStart;col<values.size()+1;col++)layout.number(detail,col,values.get(col-1),(col-numericStart)%2==1,0);
                        if(!register)layout.number(detail,5,record.price(),true,0);
                        if(accounts)layout.text(detail,columns,text(record.sourceMaterialType()));
                    }
                }
            }
            line++;
            for(Total total:report.totals()) {
                var summary=layout.row(line++,0,2);
                String unit=unitLabel(total.uom(),total.unitOfMeasureShortName(),total.unitOfMeasureShortNameRu(),total.unitOfMeasureShortNameUz(),language);
                layout.text(summary,1,c.total+(accounts?" · "+text(total.accountCode()):"")+" · "+unit);
                layout.merge(summary.getRowNum(),summary.getRowNum(),1,identity.size());
                var values=new ArrayList<>(baseTotalValues(report.type(),total,language));values.removeFirst();
                writeMetrics(layout,summary,numericStart,values,2);
            }
            line++;
            if(report.materialSubtype()!=null || report.materialType()!=null || report.sourceType()!=null)layout.note(line++,materialFilterLabel(report,language));
            if(report.accountCode()!=null)layout.note(line++,accountHeader(language)+": "+report.accountCode());
            for(String warning:report.warnings()) {String value=warningLabel(warning,report,c,language);if(value!=null)layout.note(line++,value);}
            layout.note(line++,c.generated+": "+report.generatedAt().format(DATE_TIME));
            layout.finish(line-1);workbook.write(out);return out.toByteArray();
        } catch(IOException ex) {throw new IllegalStateException("Unable to export warehouse report",ex);}
        finally {workbook.dispose();}
    }

    private List<String> summaryValues(List<WarehouseReport.Row> rows,Type type) {
        boolean sameUnit=rows.stream().map(WarehouseReport.Row::uom).distinct().count()==1;
        var values=new ArrayList<String>();
        for(int i=0;i<(type==Type.MATERIAL_STATEMENT?8:2);i++) {
            final int index=i;
            if(i%2==0 && !sameUnit)values.add(null);
            else values.add(rows.stream().map(row->new BigDecimal(metricValues(type,row).get(index))).reduce(BigDecimal.ZERO,BigDecimal::add).toPlainString());
        }
        return values;
    }

    private List<String> metricValues(Type type,WarehouseReport.Row row) {
        return switch(type) {
            case STOCK_BALANCES -> List.of(row.closingQuantity(),row.closingAmount());
            case RECEIPTS -> List.of(row.receiptQuantity(),row.receiptAmount());
            case ISSUES -> List.of(row.issueQuantity(),row.issueAmount());
            default -> List.of(row.openingQuantity(),row.openingAmount(),row.receiptQuantity(),row.receiptAmount(),row.issueQuantity(),row.issueAmount(),row.closingQuantity(),row.closingAmount());
        };
    }

    private void writeMetrics(WarehouseReportSheet layout,Row row,int start,List<String> values,int emphasis) {
        for(int i=0;i<values.size();i++)layout.number(row,start+i,values.get(i),i%2==1,emphasis);
    }

    public byte[] csv(WarehouseReport report,String language) {
        Copy c=copy(language); boolean accounts=report.accountSnapshotDate()!=null; StringBuilder out=new StringBuilder("\ufeff");
        csvLine(out,List.of(c.titles[report.type().ordinal()],label(report.companyName(),language),period(report,c),report.currencyCode(),c.generated+": "+report.generatedAt().format(DATE_TIME)));
        if(report.warehouseName()!=null) csvLine(out,List.of(c.identity[0]+": "+label(report.warehouseName(),language)));
        if(report.materialSubtype()!=null || report.materialType()!=null || report.sourceType()!=null) csvLine(out,List.of(materialFilterLabel(report,language)));
        for(String warning:report.warnings()) {
            String value=warningLabel(warning,report,c,language);
            if(value!=null) csvLine(out,List.of(value));
        }
        if(report.accountCode()!=null) csvLine(out,List.of(accountHeader(language)+": "+report.accountCode()));
        csvLine(out,headers(report.type(),c,accounts,language));
        for(var row:report.content()) csvLine(out,values(report.type(),row,language,accounts));
        csvLine(out,List.of(c.total));
        csvLine(out,totalHeaders(report.type(),c,accounts,language));
        for(Total total:report.totals()) csvLine(out,totalValues(report.type(),total,language,accounts));
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private String warningLabel(String warning,WarehouseReport report,Copy c,String language) {
        return switch(warning) {
            case "ACCOUNT_WAREHOUSE_BALANCES" -> accountNote(report,language);
            case "HISTORY_STARTS_AT_OPENING" -> c.history.formatted(report.coverageStart().format(DATE));
            case "ONE_C_HISTORY_INTEGRATED" -> c.integratedHistory;
            case "UNMAPPED_1C_REFERENCES" -> c.unmappedReferences;
            case "CORRESPONDING_ACCOUNTS_UNAVAILABLE" -> c.accountWarning;
            case "LEDGER_DIFFERS_FROM_CURRENT_BALANCES" -> c.ledgerWarning;
            default -> null;
        };
    }

    private String materialFilterLabel(WarehouseReport report,String language) {
        String prefix=switch(language) { case "en" -> "Material group"; case "uz" -> "Materiallar guruhi"; default -> "Группа материалов"; };
        String value=report.sourceType()!=null ? switch(report.sourceType()) {
            case "FINISHED_GOODS" -> switch(language) {case "en" -> "Finished goods";case "uz" -> "Tayyor mahsulot";default -> "Готовая продукция";};
            case "SEMI_FINISHED_GOODS" -> switch(language) {case "en" -> "Semi-finished goods";case "uz" -> "Yarim tayyor mahsulot";default -> "Полуфабрикаты";};
            case "GOODS" -> switch(language) {case "en" -> "Goods";case "uz" -> "Tovarlar";default -> "Товары";};
            case "VALUE_ONLY" -> switch(language) {case "en" -> "Warehouse valuations without quantity";case "uz" -> "Miqdorsiz ombor qiymatlari";default -> "Стоимостные остатки без количества";};
            case "UNSPECIFIED" -> switch(language) {case "en" -> "Unspecified (1C)";case "uz" -> "Ko‘rsatilmagan (1C)";default -> "Не указан (1С)";};
            default -> report.sourceType();
        } : report.materialSubtype()!=null ? switch(report.materialSubtype()) {
            case MATERIAL -> switch(language) { case "en" -> "Materials"; case "uz" -> "Materiallar"; default -> "Материалы"; };
            case EQUIPMENT -> switch(language) { case "en" -> "Equipment"; case "uz" -> "Uskunalar"; default -> "Оборудование"; };
            case ANIMALS -> switch(language) { case "en" -> "Animals"; case "uz" -> "Hayvonlar"; default -> "Животные"; };
            case FINISHED_GOODS -> switch(language) {case "en" -> "Finished goods";case "uz" -> "Tayyor mahsulot";default -> "Готовая продукция";};
            case SEMI_FINISHED_GOODS -> switch(language) {case "en" -> "Semi-finished goods";case "uz" -> "Yarim tayyor mahsulot";default -> "Полуфабрикаты";};
            case GOODS -> switch(language) {case "en" -> "Goods";case "uz" -> "Tovarlar";default -> "Товары";};
            case RETURNABLE_PACKAGING -> switch(language) {case "en" -> "Returnable packaging";case "uz" -> "Qaytariladigan tara";default -> "Возвратная тара";};
        } : switch(report.materialType()) {
            case FIXED_ASSET -> switch(language) { case "en" -> "Fixed assets"; case "uz" -> "Asosiy vositalar"; default -> "Основные средства"; };
            case SERVICES -> switch(language) { case "en" -> "Services"; case "uz" -> "Xizmatlar"; default -> "Услуги"; };
            case TMB -> switch(language) { case "en" -> "Inventory materials"; case "uz" -> "Tovar-moddiy boyliklar"; default -> "Товарно-материальные ценности"; };
        };
        return prefix+": "+value;
    }

    private List<String> totalHeaders(Type type,Copy c,boolean accounts,String language) {
        var h=new ArrayList<>(baseTotalHeaders(type,c));if(accounts)h.add(0,accountHeader(language));return h;
    }
    private List<String> baseTotalHeaders(Type type,Copy c) {
        if(type==Type.STOCK_BALANCES || type==Type.RECEIPTS || type==Type.ISSUES) return List.of(c.identity[4],c.quantity,c.amount);
        return List.of(c.identity[4],c.groups[0]+" / "+c.quantity,c.groups[0]+" / "+c.amount,c.groups[1]+" / "+c.quantity,c.groups[1]+" / "+c.amount,
            c.groups[2]+" / "+c.quantity,c.groups[2]+" / "+c.amount,c.groups[3]+" / "+c.quantity,c.groups[3]+" / "+c.amount);
    }
    private String period(WarehouseReport report,Copy c) {
        return report.type()==Type.STOCK_BALANCES ? c.date+": "+report.to().format(DATE)
            : c.period+": "+report.from().format(DATE)+" — "+report.to().format(DATE);
    }
    private List<String> totalValues(Type type,Total t,String language,boolean accounts) {
        var v=new ArrayList<>(baseTotalValues(type,t,language));if(accounts)v.add(0,text(t.accountCode()));return v;
    }
    private List<String> baseTotalValues(Type type,Total t,String language) {
        String unit=unitLabel(t.uom(),t.unitOfMeasureShortName(),t.unitOfMeasureShortNameRu(),t.unitOfMeasureShortNameUz(),language);
        return switch(type) {
            case STOCK_BALANCES -> List.of(unit,t.closingQuantity(),t.closingAmount());
            case RECEIPTS -> List.of(unit,t.receiptQuantity(),t.receiptAmount());
            case ISSUES -> List.of(unit,t.issueQuantity(),t.issueAmount());
            default -> List.of(unit,t.openingQuantity(),t.openingAmount(),t.receiptQuantity(),t.receiptAmount(),t.issueQuantity(),t.issueAmount(),t.closingQuantity(),t.closingAmount());
        };
    }
    static String unitLabel(String code,String shortName,String shortNameRu,String shortNameUz,String language) {
        String localized="uz".equals(language)?shortNameUz:"ru".equals(language)?shortNameRu:null;
        // Imported generic names carry source annotations; the Russian name is the original 1C symbol.
        String imported=code!=null && code.startsWith("1C-UOM-")?shortNameRu:null;
        for(String value:new String[]{localized,imported,shortName,shortNameRu,shortNameUz,code}) {
            if(value!=null && !value.isBlank()) return value.trim();
        }
        return "—";
    }
    private void csvLine(StringBuilder out,List<String> values) {
        out.append(values.stream().map(v -> {
            String value=text(v);
            if(value.matches("^[=+@\\t\\r].*") || (value.startsWith("-") && !value.matches("-\\d+(\\.\\d+)?"))) value="'"+value;
            return "\""+value.replace("\"","\"\"")+"\"";
        }).collect(java.util.stream.Collectors.joining(","))).append("\r\n");
    }
    private static String text(String value) { return value==null?"—":value; }
    static String label(String value,String language) {
        if(value==null) return "—";
        try { var node=new com.fasterxml.jackson.databind.ObjectMapper().readTree(value); if(node.isObject()) return node.path(language).asText(node.path("ru").asText(value)); } catch(Exception ignored) { }
        var pattern=java.util.regex.Pattern.compile("(?:^|\\n)\\s*(ru|en|uz)\\s*:\\s*([\\s\\S]*?)(?=\\n\\s*(?:ru|en|uz)\\s*:|$)");
        var matcher=pattern.matcher(value); String fallback=null;
        while(matcher.find()) { if(fallback==null || matcher.group(1).equals("ru")) fallback=matcher.group(2).trim(); if(matcher.group(1).equals(language)) return matcher.group(2).trim(); }
        return fallback==null?value:fallback;
    }
}
