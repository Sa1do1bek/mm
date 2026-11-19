package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.common.LocalizedText;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.payload.*;
import com.enterprise.mm.repository.StockBalanceReadQuery;
import com.enterprise.mm.security.AccessScope;
import com.enterprise.mm.security.CompanyScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Streaming XLSX over the exact read query, in one consistent read-only database snapshot. */
@Service @RequiredArgsConstructor
public class StockBalanceExcelExport {
    private final StockBalanceService balances;
    private final StockBalanceReadQuery query;
    private final StockExpiryPolicy expiry;
    private final ObjectMapper json;

    enum Column {
        expiryStatus("Holat", "Статус срока", "Expiry status", r -> r.getExpiryStatus()),
        daysUntilExpiry("Qolgan kun", "Осталось дней", "Days remaining", r -> r.getDaysUntilExpiry()),
        expirationDate("Yaroqlilik sanasi", "Годен до", "Expiry date", r -> r.getExpirationDate()),
        materialCode("Material kodi", "Код материала", "Material code", r -> r.getMaterialCode()),
        materialName("Material nomi", "Материал", "Material", r -> r.getMaterialName()),
        materialGroupCode("Guruh kodi", "Код группы", "Group code", r -> r.getMaterialGroupCode()),
        materialGroupName("Material guruhi", "Группа материалов", "Material group", r -> r.getMaterialGroupName()),
        stockBatchNumber("Partiya", "Партия", "Batch", r -> r.getStockBatchNumber()),
        manufacturerBatchNumber("Ishlab chiqaruvchi partiyasi", "Партия производителя", "Manufacturer batch", r -> r.getManufacturerBatchNumber()),
        warehouseCode("Ombor kodi", "Код склада", "Warehouse code", r -> r.getWarehouseCode()),
        warehouseName("Ombor", "Склад", "Warehouse", r -> r.getWarehouseName()),
        storageLocationName("Saqlash joyi", "Место хранения", "Storage location", r -> r.getStorageLocationName()),
        storageBinName("Katak", "Ячейка", "Storage bin", r -> r.getStorageBinName()),
        unitCode("O‘lchov birligi", "Единица измерения", "Unit of measure", r -> r.getUnitCode()),
        unitLabel("Birlik", "Единица", "UOM", r -> r.getUnitLabel()),
        quantity("Jismoniy qoldiq", "Физический остаток", "On hand", r -> r.getQuantity()),
        availableQuantity("Mavjud miqdor", "Доступно", "Available", r -> r.getAvailableQuantity()),
        reservedQuantity("Rezerv", "Резерв", "Reserved", r -> r.getReservedQuantity()),
        quarantineQuantity("Karantin", "Карантин", "Quarantine", r -> r.getQuarantineQuantity()),
        totalValue("Balans qiymati", "Балансовая стоимость", "Book value", r -> r.getTotalValue()),
        currencyCode("Valyuta", "Валюта", "Currency", r -> r.getCurrencyCode()),
        manufacturedOn("Ishlab chiqarilgan sana", "Дата изготовления", "Manufactured on", r -> r.getManufacturedOn()),
        receivedDate("Qabul qilingan sana", "Дата поступления", "Received date", r -> r.getReceivedDate()),
        balanceAt("Qoldiq yangilangan vaqt", "Остаток обновлен", "Balance updated", r -> r.getBalanceAt()),
        companyName("Kompaniya", "Компания", "Company", r -> r.getCompanyName()),
        id("Qoldiq ID", "ID остатка", "Balance ID", r -> r.getId());
        final String uz,ru,en; final Function<StockBalanceResponse,Object> value;
        Column(String uz,String ru,String en,Function<StockBalanceResponse,Object> value) { this.uz=uz;this.ru=ru;this.en=en;this.value=value; }
        String title(String language) { return language.equals("ru")?ru:language.equals("en")?en:uz; }
    }
    private static final List<Column> DEFAULT_COLUMNS = List.of(Column.expiryStatus,Column.daysUntilExpiry,Column.expirationDate,
        Column.materialCode,Column.materialName,Column.materialGroupName,Column.stockBatchNumber,Column.warehouseName,
        Column.storageLocationName,Column.storageBinName,Column.unitCode,Column.quantity,Column.availableQuantity,
        Column.reservedQuantity,Column.quarantineQuantity,Column.totalValue,Column.currencyCode,Column.manufacturedOn,Column.receivedDate,Column.balanceAt);

    public void validate(StockBalanceSearchRequest request,List<String> columns,String language) {
        query.validate(request,expiry.today()); selectedColumns(columns); language(language); balances.authorizeRead(request);
    }
    private List<Column> selectedColumns(List<String> columns) {
        if(columns==null || columns.isEmpty()) return DEFAULT_COLUMNS;
        if(columns.size()>Column.values().length) throw new BadRequestException("Too many export columns");
        var result=new ArrayList<Column>(); var seen=new HashSet<String>();
        for(String key:columns) {
            if(!seen.add(key)) throw new BadRequestException("Duplicate export column: "+key);
            try { result.add(Column.valueOf(key)); }
            catch(RuntimeException ex) { throw new BadRequestException("Unsupported export column: "+key); }
        }
        return result;
    }
    private String language(String value) {
        String lang=value==null?LocaleContextHolder.getLocale().getLanguage():Locale.forLanguageTag(value).getLanguage();
        if(!Set.of("uz","ru","en").contains(lang)) throw new BadRequestException("Export language must be uz, ru or en");
        return lang;
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,rollbackFor=Exception.class)
    public void write(StockBalanceSearchRequest request,List<String> columns,String language,OutputStream output) throws IOException {
        validate(request,columns,language);
        String lang=language(language); List<Column> selected=selectedColumns(columns); LocalDate day=expiry.today();
        var previousLocale = LocaleContextHolder.getLocaleContext();
        LocaleContextHolder.setLocale(Locale.forLanguageTag(lang));
        try(var workbook=new SXSSFWorkbook(100)) {
            workbook.setCompressTempFiles(true);
            try {
                var writer=new Writer(workbook,selected,lang);
                balances.exportRows(request,day,writer::row);
                writer.finish();
                Sheet metadata=workbook.createSheet(lang.equals("ru")?"О отчете":lang.equals("en")?"Report info":"Hisobot haqida");
                String actualSort=request.options()!=null && request.options().getSortBy()!=null?request.options().getSortBy():"daysUntilExpiry";
                String direction=request.options()!=null && request.options().getSortDir()!=null?request.options().getSortDir():"ASC";
                List<List<String>> info=List.of(
                    List.of(tr(lang,"Hisoblash sanasi","Дата расчета срока","Expiry calculation date"),day.toString()),
                    List.of(tr(lang,"Yaratilgan vaqt","Время создания","Generated at"),
                        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").format(ZonedDateTime.now(expiry.zoneId()))),
                    List.of(tr(lang,"Vaqt mintaqasi","Часовой пояс","Timezone"),expiry.zoneId().toString()),
                    List.of(tr(lang,"Tashkilot muhiti","Контур организации","Tenant"),TenantContext.currentTenant()),
                    List.of(tr(lang,"Kompaniya ID","ID компании","Requested company"),CompanyContext.currentCompany().toString()),
                    List.of(tr(lang,"Yozuvlar","Записи","Rows"),Long.toString(writer.count)),
                    List.of(tr(lang,"Saralash","Сортировка","Sort"),sortTitle(actualSort,lang)+" — "+
                        (direction.equalsIgnoreCase("DESC")?tr(lang,"kamayish bo‘yicha","по убыванию","descending"):
                            tr(lang,"o‘sish bo‘yicha","по возрастанию","ascending"))+"; "+
                        tr(lang,"teng qiymatlarda ID bo‘yicha; bo‘sh sanalar oxirida","при равенстве по ID; пустые даты в конце","ties by ID; blank dates last")),
                    List.of(tr(lang,"Ustunlar","Столбцы","Columns"),selected.stream().map(c->c.title(lang)).collect(java.util.stream.Collectors.joining(", "))),
                    List.of(tr(lang,"Filtrlar","Фильтры","Filters"),filterDescription(json.valueToTree(request),lang)),
                    List.of(tr(lang,"Ma’lumotlar yaxlitligi","Согласованность данных","Data consistency"),
                        tr(lang,"Barcha yozuvlar bitta izchil ma’lumotlar holatidan olindi","Все записи прочитаны из одного согласованного снимка","All rows read from one repeatable-read database snapshot")),
                    List.of(tr(lang,"Aniqlik","Точность","Precision"),tr(lang,"15 ta muhim raqamdan uzun sonlar aniq matn sifatida saqlanadi","Числа длиннее 15 значащих цифр сохраняются точным текстом","Decimals exceeding 15 significant digits are exported as exact text")),
                    List.of(tr(lang,"Jami hisoblash","Итоги","Totals"),tr(lang,"Turli birliklar va noma’lum yoki turli valyutalar qo‘shilmaydi","Разные единицы и неизвестные или разные валюты не суммируются","Quantities of different UOMs and values of unknown/different currencies are not summed")));
                int metadataRow=0;
                for(var entry:info) {
                    String value=entry.get(1);
                    for(int offset=0;offset<Math.max(1,value.length());offset+=32000) {
                        Row row=metadata.createRow(metadataRow++);
                        row.createCell(0).setCellValue(entry.get(0)+(offset==0?"":tr(lang," (davomi)"," (продолжение)"," (continued)")));
                        row.createCell(1).setCellValue(value.substring(offset,Math.min(value.length(),offset+32000)));
                        row.getCell(0).setCellStyle(writer.style("header",false,false));
                        row.getCell(1).setCellStyle(writer.style("text",false,false));
                        row.setHeightInPoints(42);
                    }
                }
                metadata.setColumnWidth(0,28*256); metadata.setColumnWidth(1,100*256);metadata.createFreezePane(1,0);
                metadata.setDisplayGridlines(false);
                workbook.write(output);
            } finally { workbook.dispose(); }
        } finally { LocaleContextHolder.setLocaleContext(previousLocale); }
    }

    private static String tr(String lang,String uz,String ru,String en) { return lang.equals("ru")?ru:lang.equals("en")?en:uz; }

    private static String filterDescription(com.fasterxml.jackson.databind.JsonNode node,String lang) {
        var entries=new ArrayList<String>();
        node.fields().forEachRemaining(entry->{
            var value=entry.getValue();String key=entry.getKey();
            if(value.isNull() || value.isTextual() && value.asText().isBlank() || value.isArray() && value.isEmpty()) return;
            if(value.isObject()) {String nested=filterDescription(value,lang);if(!nested.isBlank())entries.add(nested);return;}
            String title=filterTitle(key,lang);
            String text=value.isBoolean()?(value.asBoolean()?tr(lang,"Ha","Да","Yes"):tr(lang,"Yo‘q","Нет","No")):
                value.isTextual()?value.asText():value.toString();
            if(key.equals("sortDir")) text=text.equalsIgnoreCase("DESC")?tr(lang,"Kamayish","По убыванию","Descending"):tr(lang,"O‘sish","По возрастанию","Ascending");
            if(key.equals("sortBy")) text=sortTitle(text,lang);
            entries.add(title+": "+text);
        });
        return String.join("; ",entries);
    }
    private static String sortTitle(String key,String lang) {
        try {return Column.valueOf(key).title(lang);} catch(IllegalArgumentException ex) {return filterTitle(key,lang);}
    }
    private static String filterTitle(String key,String lang) {
        try {return Column.valueOf(key).title(lang);} catch(IllegalArgumentException ignored) { }
        return switch(key) {
            case "search" -> tr(lang,"Qidiruv","Поиск","Search");
            case "warehouseId" -> tr(lang,"Ombor ID","ID склада","Warehouse ID");
            case "stockBatchId" -> tr(lang,"Partiya ID","ID партии","Batch ID");
            case "materialGroupId" -> tr(lang,"Material guruhi ID","ID группы материалов","Material group ID");
            case "materialIds","materialId" -> tr(lang,"Material ID","ID материала","Material ID");
            case "storageLocationId" -> tr(lang,"Saqlash joyi ID","ID места хранения","Storage location ID");
            case "storageBinId" -> tr(lang,"Katak ID","ID ячейки","Storage bin ID");
            case "dateFrom","expiryDateFrom" -> tr(lang,"Boshlanish sanasi","Начальная дата","From date");
            case "dateTo","expiryDateTo" -> tr(lang,"Tugash sanasi","Конечная дата","To date");
            case "includeDeleted","deleted" -> tr(lang,"O‘chirilganlar","Удаленные записи","Deleted records");
            case "status" -> tr(lang,"Yozuv holati","Статус записи","Record status");
            case "name" -> tr(lang,"Nom","Название","Name");
            case "code" -> tr(lang,"Kod","Код","Code");
            case "description" -> tr(lang,"Izoh","Описание","Description");
            case "createdBy" -> tr(lang,"Yaratgan foydalanuvchi","Автор","Created by");
            case "updatedBy" -> tr(lang,"Yangilagan foydalanuvchi","Обновил","Updated by");
            case "createdAt" -> tr(lang,"Yaratilgan vaqt","Время создания","Created at");
            case "version" -> tr(lang,"Versiya","Версия","Version");
            case "expiresWithinDays" -> tr(lang,"Muddati tugashigacha kun","Дней до истечения","Expires within days");
            case "includeExpired" -> tr(lang,"Muddati o‘tganlarni qo‘shish","Включая просроченные","Include expired");
            case "positiveOnly" -> tr(lang,"Faqat musbat qoldiq","Только положительный остаток","Positive stock only");
            case "expiryWarning" -> tr(lang,"Yaroqlilik ogohlantirishi","Предупреждение о сроке","Expiry warning");
            case "hasReserved" -> tr(lang,"Rezerv mavjud","Есть резерв","Has reservations");
            case "hasQuarantine" -> tr(lang,"Karantin mavjud","Есть карантин","Has quarantine");
            case "unitOfMeasureId" -> tr(lang,"Birlik ID","ID единицы","Unit ID");
            case "sortBy" -> tr(lang,"Saralash maydoni","Поле сортировки","Sort field");
            case "sortDir" -> tr(lang,"Saralash yo‘nalishi","Направление сортировки","Sort direction");
            default -> {
                if(key.endsWith("Min") || key.endsWith("Max")) yield filterTitle(key.substring(0,key.length()-3),lang)+" "+
                    (key.endsWith("Min")?tr(lang,"eng kam","минимум","minimum"):tr(lang,"eng ko‘p","максимум","maximum"));
                yield key;
            }
        };
    }

    static final class Writer {
        final SXSSFWorkbook book; final List<Column> columns; final String language;
        final Map<String,CellStyle> styles=new HashMap<>(); Sheet sheet; int rowIndex; int sheetNumber; long count;
        Writer(SXSSFWorkbook b,List<Column> c,String lang) { book=b;columns=c;language=lang;newSheet(); }
        void newSheet() {
            String name=language.equals("ru")?"Остатки":language.equals("en")?"Balances":"Qoldiqlar";
            sheet=book.createSheet(name+(++sheetNumber==1?"":" "+sheetNumber)); rowIndex=0;
            sheet.setDisplayGridlines(false);
            Row header=sheet.createRow(rowIndex++);
            header.setHeightInPoints(36);
            for(int i=0;i<columns.size();i++) { var cell=header.createCell(i);cell.setCellValue(columns.get(i).title(language));cell.setCellStyle(style("header",false,false));sheet.setColumnWidth(i,width(columns.get(i))*256); }
            // Keep the material identity visible instead of freezing only expiry status/days.
            int identity=columns.indexOf(Column.materialName);
            sheet.createFreezePane(identity>=0?identity+1:Math.min(2,columns.size()),1);
            sheet.setRepeatingRows(new CellRangeAddress(0,0,-1,-1));
            sheet.setFitToPage(true);
            var print=sheet.getPrintSetup();print.setLandscape(true);print.setPaperSize(PrintSetup.A3_PAPERSIZE);
            print.setFitWidth((short)1);print.setFitHeight((short)0);
        }
        int width(Column column) {
            return switch(column) {
                case materialName -> 52;
                case materialGroupName -> 38;
                case warehouseName,storageLocationName,storageBinName,companyName -> 30;
                case stockBatchNumber,manufacturerBatchNumber,id -> 28;
                case balanceAt -> 24;
                case unitCode,unitLabel,currencyCode -> 16;
                default -> 21;
            };
        }
        void row(StockBalanceResponse source) {
            if(rowIndex>=SpreadsheetVersion.EXCEL2007.getMaxRows()) { finish();newSheet(); }
            Row row=sheet.createRow(rowIndex++);count++;int lines=1;
            for(int i=0;i<columns.size();i++) {
                Column column=columns.get(i);Object value=displayValue(column,source); Cell cell=row.createCell(i);
                String format="text";
                if(value instanceof BigDecimal d) {
                    if(d.precision()<=15) {cell.setCellValue(d.doubleValue());format=column==Column.totalValue?"money":"quantity";}
                    else cell.setCellValue(d.toPlainString());
                } else if(value instanceof Number n) {cell.setCellValue(n.doubleValue());format="integer";}
                else if(value instanceof LocalDate date) {cell.setCellValue(date);format="date";}
                else if(value instanceof LocalDateTime date) {cell.setCellValue(date);format="datetime";}
                else if(value!=null) cell.setCellValue(column==Column.expiryStatus?status(source):value.toString());
                cell.setCellStyle(style(format,source.isExpiryWarning(),source.getExpiryStatus()==com.enterprise.mm.enums.StockExpiryStatus.EXPIRED));
                if(value instanceof String text) {
                    int wrapped=0;
                    for(String line:text.split("\\R",-1))wrapped+=Math.max(1,(int)Math.ceil(line.length()/(width(column)*0.85)));
                    lines=Math.max(lines,wrapped);
                }
            }
            row.setHeightInPoints(Math.min(409,Math.max(28,lines*15+8)));
        }
        Object displayValue(Column column,StockBalanceResponse source) {
            Object value=column.value.apply(source);
            return switch(column) {
                case materialName,materialGroupName,warehouseName,storageLocationName,storageBinName,companyName ->
                    value==null?null:LocalizedText.translate(value.toString(),Locale.forLanguageTag(language));
                // Legacy clients select unitCode; both supported keys must produce the readable unit.
                case unitCode,unitLabel -> {
                    String label=source.getUnitLabel();
                    if(label!=null && !label.isBlank()) yield LocalizedText.translate(label,Locale.forLanguageTag(language));
                    String code=source.getUnitCode();
                    yield code!=null && !code.regionMatches(true,0,"1C-UOM-",0,7)?code:null;
                }
                default -> value;
            };
        }
        String status(StockBalanceResponse r) {
            if(r.getExpiryStatus()==null)return "";
            return switch(r.getExpiryStatus()) {
                case EXPIRED -> language.equals("ru")?"Просрочено":language.equals("en")?"Expired":"Muddati o‘tgan";
                case EXPIRES_TODAY -> language.equals("ru")?"Истекает сегодня":language.equals("en")?"Expires today":"Bugun tugaydi";
                case EXPIRING_SOON -> language.equals("ru")?"Срок истекает":language.equals("en")?"Expiring soon":"Muddati tugamoqda";
                case VALID -> language.equals("ru")?"Срок действителен":language.equals("en")?"Valid":"Yaroqli";
                case UNKNOWN -> language.equals("ru")?"Дата не указана":language.equals("en")?"Date unknown":"Sana ko‘rsatilmagan";
            };
        }
        CellStyle style(String type,boolean warning,boolean expired) {
            String key=type+":"+warning+":"+expired;
            return styles.computeIfAbsent(key,k->{
                var s=book.createCellStyle();s.setVerticalAlignment(VerticalAlignment.CENTER);s.setWrapText(true);
                // Explicit borders remain visible with gridlines hidden, including in printouts.
                s.setBorderTop(BorderStyle.THIN);s.setBorderBottom(BorderStyle.THIN);
                s.setBorderLeft(BorderStyle.THIN);s.setBorderRight(BorderStyle.THIN);
                short borderColor=IndexedColors.GREY_50_PERCENT.getIndex();
                s.setTopBorderColor(borderColor);s.setBottomBorderColor(borderColor);
                s.setLeftBorderColor(borderColor);s.setRightBorderColor(borderColor);
                String pattern=switch(type) {case "date"->"dd.mm.yyyy";case "datetime"->"dd.mm.yyyy hh:mm:ss";
                    case "money"->"#,##0.00##";case "quantity"->"#,##0.#######";case "integer"->"0";default->"@";};
                s.setDataFormat(book.createDataFormat().getFormat(pattern));
                var f=book.createFont();f.setFontName("Arial");f.setFontHeightInPoints((short)10);s.setFont(f);
                s.setAlignment(Set.of("money","quantity","integer").contains(type)?HorizontalAlignment.RIGHT:HorizontalAlignment.LEFT);
                if(type.equals("header")) {f.setBold(true);f.setColor(IndexedColors.WHITE.getIndex());s.setAlignment(HorizontalAlignment.CENTER);s.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());s.setFillPattern(FillPatternType.SOLID_FOREGROUND);}
                else if(warning) {s.setFillForegroundColor((expired?IndexedColors.ROSE:IndexedColors.LIGHT_YELLOW).getIndex());s.setFillPattern(FillPatternType.SOLID_FOREGROUND);}
                return s;
            });
        }
        void finish() {
            sheet.setAutoFilter(new CellRangeAddress(0,Math.max(0,rowIndex-1),0,columns.size()-1));
            book.setPrintArea(book.getSheetIndex(sheet),0,columns.size()-1,0,Math.max(0,rowIndex-1));
        }
    }

    @FunctionalInterface public interface IoAction { void run() throws IOException; }
    public record RequestContext(String tenant,UUID company,Authentication authentication,boolean currentCompany,Locale locale) {
        public static RequestContext capture() {
            return new RequestContext(TenantContext.currentTenant(),CompanyContext.currentCompany(),SecurityContextHolder.getContext().getAuthentication(),
                AccessScope.currentCompanyRequested(),LocaleContextHolder.getLocale());
        }
        public void run(IoAction action) throws IOException {
            String oldTenant=TenantContext.currentTenant();UUID oldCompany=CompanyContext.currentCompany();var oldSecurity=SecurityContextHolder.getContext();
            boolean oldScope=AccessScope.currentCompanyRequested();Locale oldLocale=LocaleContextHolder.getLocale();
            try {
                TenantContext.setTenant(tenant);CompanyContext.setCompany(company);
                var security=SecurityContextHolder.createEmptyContext();security.setAuthentication(authentication);SecurityContextHolder.setContext(security);
                AccessScope.setRequestedCompanyScope(currentCompany?CompanyScope.CURRENT_COMPANY:null);LocaleContextHolder.setLocale(locale);
                action.run();
            } finally {
                TenantContext.setTenant(oldTenant);CompanyContext.setCompany(oldCompany);SecurityContextHolder.setContext(oldSecurity);
                AccessScope.setRequestedCompanyScope(oldScope?CompanyScope.CURRENT_COMPANY:null);LocaleContextHolder.setLocale(oldLocale);
            }
        }
    }
}
