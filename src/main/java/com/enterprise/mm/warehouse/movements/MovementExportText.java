package com.enterprise.mm.warehouse.movements;

import com.enterprise.mm.error.BadRequestException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.context.i18n.LocaleContextHolder;

public final class MovementExportText {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern MAP_ENTRY = Pattern.compile("(?:^|[,;{])\\s*(uz|ru|en)\\s*[=:]\\s*(.*?)(?=\\s*[,;]\\s*(?:uz|ru|en)\\s*[=:]|}$|$)");
    private static final Map<String,List<String>> WORDS = Map.ofEntries(
        Map.entry("title", List.of("Movements", "Harakatlar", "Движения")),
        Map.entry("generated", List.of("Generated", "Yaratilgan vaqt", "Время создания")),
        Map.entry("filters", List.of("Filters", "Filtrlar", "Фильтры")),
        Map.entry("sort", List.of("Sort", "Saralash", "Сортировка")),
        Map.entry("rows", List.of("Rows", "Yozuvlar", "Записи")),
        Map.entry("none", List.of("No matching movements", "Mos harakatlar topilmadi", "Движения не найдены")),
        Map.entry("RECEIPT", List.of("Receipt", "Kirim", "Приход")),
        Map.entry("ISSUE", List.of("Issue", "Chiqim", "Расход")),
        Map.entry("TRANSFER", List.of("Transfer", "Ko‘chirish", "Перемещение")),
        Map.entry("RETURN", List.of("Return", "Qaytarish", "Возврат")),
        Map.entry("WRITE_OFF", List.of("Write-off", "Hisobdan chiqarish", "Списание")),
        Map.entry("ADJUSTMENT", List.of("Adjustment", "Tuzatish", "Корректировка")),
        Map.entry("REVERSAL", List.of("Reversal", "Bekor qiluvchi harakat", "Сторно")),
        Map.entry("Inbound Receipt", List.of("Inbound Receipt", "Kirim", "Приход")),
        Map.entry("Outbound Issue", List.of("Outbound Issue", "Chiqim", "Расход")),
        Map.entry("Stock Movement", List.of("Stock Movement", "Ombor harakati", "Движение запасов")),
        Map.entry("Opening Stock Balance", List.of("Opening Stock Balance", "Boshlang‘ich qoldiq", "Начальные остатки")),
        Map.entry("Opening Stock", List.of("Opening Stock", "Boshlang‘ich qoldiq", "Начальные остатки")),
        Map.entry("1C Historical Receipt", List.of("1C Historical Receipt", "1C tarixiy kirimi", "Исторический приход 1С")),
        Map.entry("1C Historical Intake", List.of("1C Historical Intake", "1C tarixiy kirimi", "Исторический приход 1С")),
        Map.entry("Production / Department", List.of("Production / Department", "Ishlab chiqarish / Bo‘lim", "Производство / Подразделение")),
        Map.entry("1C Valuation Adjustment", List.of("1C Valuation Adjustment", "1C qiymat tuzatishi", "Корректировка стоимости 1С")),
        Map.entry("DRAFT", List.of("Draft", "Qoralama", "Черновик")),
        Map.entry("PENDING", List.of("Pending", "Kutilmoqda", "Ожидает")),
        Map.entry("APPROVED", List.of("Approved", "Tasdiqlangan", "Утверждён")),
        Map.entry("RETURNED", List.of("Returned", "Qaytarilgan", "Возвращён")),
        Map.entry("POSTED", List.of("Posted", "O‘tkazilgan", "Проведён")),
        Map.entry("CANCELLED", List.of("Cancelled", "Bekor qilingan", "Отменён")),
        Map.entry("search", List.of("Search", "Qidiruv", "Поиск")),
        Map.entry("warehouseId", List.of("Warehouse", "Ombor", "Склад")),
        Map.entry("warehouseIds", List.of("Warehouses", "Omborlar", "Склады")),
        Map.entry("movementType", List.of("Movement type", "Harakat turi", "Вид движения")),
        Map.entry("movementTypes", List.of("Movement types", "Harakat turlari", "Виды движений")),
        Map.entry("dateFrom", List.of("From date", "Boshlanish sanasi", "Дата с")),
        Map.entry("dateTo", List.of("To date", "Tugash sanasi", "Дата по")),
        Map.entry("movementDate", List.of("Date", "Sana", "Дата")),
        Map.entry("code", List.of("Document no.", "Hujjat raqami", "Номер документа")),
        Map.entry("createdAt", List.of("Created at", "Yaratilgan vaqt", "Дата создания")),
        Map.entry("materialCode", List.of("Material code", "Material kodi", "Код материала")),
        Map.entry("materialName", List.of("Material", "Material", "Материал")),
        Map.entry("materialId", List.of("Material ID", "Material ID", "ID материала")),
        Map.entry("materialIds", List.of("Material IDs", "Material IDlari", "ID материалов")),
        Map.entry("quantity", List.of("Quantity", "Miqdor", "Количество")),
        Map.entry("status", List.of("Status", "Holat", "Статус")),
        Map.entry("batch", List.of("Batch", "Partiya", "Партия")),
        Map.entry("actor", List.of("Responsible person", "Mas’ul shaxs", "Ответственный")),
        Map.entry("includeDeleted", List.of("Include deleted", "O‘chirilganlarni qo‘shish", "Включая удалённые")),
        Map.entry("inventoryId", List.of("Inventory ID", "Inventarizatsiya ID", "ID инвентаризации")),
        Map.entry("referenceId", List.of("Reference ID", "Bog‘langan hujjat ID", "ID связанного документа")),
        Map.entry("referenceType", List.of("Reference type", "Bog‘langan hujjat turi", "Тип связанного документа")),
        Map.entry("asc", List.of("ascending", "o‘sish bo‘yicha", "по возрастанию")),
        Map.entry("desc", List.of("descending", "kamayish bo‘yicha", "по убыванию"))
    );
    public static String language(String requested) {
        String value = requested == null || requested.isBlank() ? LocaleContextHolder.getLocale().getLanguage() : requested.trim().toLowerCase(Locale.ROOT);
        value = Locale.forLanguageTag(value.replace('_','-')).getLanguage();
        if (!Set.of("uz", "ru", "en").contains(value)) throw new BadRequestException("Supported languages: uz, ru, en");
        return value;
    }
    public static String word(String key, String language) {
        var values = WORDS.get(key);
        return values == null ? key : values.get(language.equals("uz") ? 1 : language.equals("ru") ? 2 : 0);
    }
    public static String label(String value, String language) {
        if (value == null || value.isBlank()) return "";
        if (com.enterprise.mm.common.LocalizedText.isMultilingual(value))
            return com.enterprise.mm.common.LocalizedText.translate(value,Locale.forLanguageTag(language));
        if (WORDS.containsKey(value)) return word(value,language);
        Map<String,String> names = new LinkedHashMap<>();
        try {
            var root = JSON.readTree(value);
            if(root != null && root.isObject()) for(String code : List.of("uz","ru","en")) {
                if(root.path(code).isTextual() && !root.path(code).asText().isBlank()) names.put(code, root.path(code).asText());
            }
        } catch(Exception ignored) { /* Legacy map-style names are also supported. */ }
        if(names.isEmpty()) {
            var matcher = MAP_ENTRY.matcher(value);
            while(matcher.find()) if(!matcher.group(2).isBlank()) names.put(matcher.group(1), matcher.group(2).trim());
        }
        return names.getOrDefault(language, names.getOrDefault("ru",names.getOrDefault("uz",names.getOrDefault("en",value))));
    }
    public static List<String> headers(String language) {
        return switch(language) {
            case "uz" -> List.of("Sana","Hujjat raqami","Harakat turi","Kontragent","Qayerdan","Qayerga","Material kodi","Material","Miqdor","O‘lchov birligi","Holat","Partiya","Mas’ul shaxs");
            case "ru" -> List.of("Дата","Номер документа","Вид движения","Контрагент","Откуда","Куда","Код материала","Материал","Количество","Единица измерения","Статус","Партия","Ответственный");
            default -> List.of("Date","Document no.","Movement type","Counterparty","From","To","Material code","Material","Quantity","Unit of measure","Status","Batch","Responsible person");
        };
    }
}
