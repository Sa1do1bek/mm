package com.enterprise.mm.warehouse.nonmoving;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Company;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.common.LocalizedText;
import com.enterprise.mm.enums.RoleName;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.repository.CompanyRepository;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.security.AccessScope;
import com.enterprise.mm.security.AuthenticatedTokenState;
import com.enterprise.mm.service.InventoryAccountingSettingsService;
import com.enterprise.mm.service.RoleDataScopeService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.i18n.LocaleContextHolder;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static com.enterprise.mm.warehouse.nonmoving.WarehouseNonMovingDtos.*;

@Service
@RequiredArgsConstructor
public class WarehouseNonMovingService {
    private final WarehouseNonMovingPreferenceStore preferences;
    private final WarehouseNonMovingQuery query;
    private final UserRepository users;
    private final CompanyRepository companies;
    private final WarehouseRepository warehouses;
    private final RoleDataScopeService access;
    private final InventoryAccountingSettingsService accounting;
    private final Clock materialCommandViewClock;
    @Value("${warehouse.non-moving.export.max-rows:100000}")
    private int exportMaxRows = 100000;
    private static final int EXPORT_BATCH_SIZE = 200;
    private static final com.fasterxml.jackson.databind.ObjectMapper EXPORT_JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    private static final java.util.regex.Pattern EXPORT_TRANSLATION = java.util.regex.Pattern.compile(
        "(?:^|[,;{])\\s*(uz|ru|en)\\s*[=:]\\s*(.*?)(?=\\s*[,;]\\s*(?:uz|ru|en)\\s*[=:]|}$|$)");

    @Transactional(readOnly = true)
    public Preference preference() {
        return effectivePreference(currentUser());
    }

    @Transactional
    public Preference savePreference(PreferenceRequest request) {
        if (request == null || request.thresholdMonths() == null || request.thresholdMonths() < 1
            || request.thresholdMonths() > 1200 || request.expectedVersion() == null || request.expectedVersion() < 0) {
            throw new BadRequestException("thresholdMonths must be between 1 and 1200 and expectedVersion must be nonnegative");
        }
        User user = currentUser();
        requireCompany();
        return preferences.save(TenantContext.currentTenant(), CompanyContext.currentCompany(), user.getId(),
            request.thresholdMonths(), request.expectedVersion(), LocalDateTime.now(materialCommandViewClock));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Candidates candidates(UUID warehouseId, Filter filter) {
        return candidates(warehouseId, filter, null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Candidates candidates(UUID warehouseId, Filter filter, String language) {
        String resolved = exportLanguage(language);
        CandidateContext context = candidateContext(warehouseId, filter);
        return new Candidates(context.preference().thresholdMonths(), context.preference().source(),
            context.calculatedAt().toLocalDate(), context.calculatedAt(),
            query.find(TenantContext.currentTenant(), CompanyContext.currentCompany(), warehouseId,
                context.preference().thresholdMonths(), context.calculatedAt().toLocalDate(), context.currency(), filter, resolved));
    }

    private record CandidateContext(Warehouse warehouse, Preference preference, LocalDateTime calculatedAt, String currency) {}

    private CandidateContext candidateContext(UUID warehouseId, Filter filter) {
        if (filter == null || filter.sortBy() == null || filter.direction() == null
            || filter.page() < 0 || filter.size() < 1 || filter.size() > 200
            || (filter.search() != null && filter.search().length() > 240)) {
            throw new BadRequestException("Invalid candidate filter: page >= 0, size 1..200, search at most 240 characters");
        }
        access.availabilityWarehouseId(warehouseId);
        Warehouse warehouse = warehouses.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), warehouseId)
            .orElseThrow(() -> new NotFoundException("Warehouse not found in the current company"));
        access.assertWarehouseAccess(warehouseId);
        return new CandidateContext(warehouse, effectivePreference(currentUser()),
            LocalDateTime.now(materialCommandViewClock), accounting.findOrDefault().getBaseCurrencyCode());
    }

    /** One snapshot and one preference/date context for the entire download. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public byte[] export(UUID warehouseId, Filter filter, String language) {
        String lang = exportLanguage(language);
        CandidateContext context = candidateContext(warehouseId, filter);
        LocalDate asOf = context.calculatedAt().toLocalDate();
        int months = context.preference().thresholdMonths();
        Filter pageFilter = new Filter(filter.search(), filter.materialGroupId(), filter.includeUnknownHistory(),
            0, EXPORT_BATCH_SIZE, filter.sortBy(), filter.direction());
        var page = query.find(TenantContext.currentTenant(), CompanyContext.currentCompany(), warehouseId,
            months, asOf, context.currency(), pageFilter, lang);
        long total = page.totalElements();
        int limit = Math.max(1, Math.min(exportMaxRows, 1_048_000));
        if (total > limit) throw new BadRequestException(copy(lang,
            "Export limit is " + limit + " rows. Narrow the filters.",
            "Eksport chegarasi " + limit + " ta yozuv. Filtrlarni toraytiring.",
            "Лимит экспорта: " + limit + " записей. Уточните фильтры."));

        SXSSFWorkbook workbook = new SXSSFWorkbook(100);
        workbook.setCompressTempFiles(true);
        try (workbook; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet(copy(lang, "Non-moving", "Harakatsiz qoldiqlar", "Неподвижные запасы"));
            var headerStyle = workbook.createCellStyle();
            var font = workbook.createFont(); font.setBold(true); headerStyle.setFont(font);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            var dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.createDataFormat().getFormat("dd.mm.yyyy"));
            var numberStyle = workbook.createCellStyle();
            numberStyle.setDataFormat(workbook.createDataFormat().getFormat("#,##0.############"));
            excelText(sheet.createRow(0), 0, copy(lang, "Non-moving stock", "Harakatsiz qoldiqlar", "Неподвижные запасы"));
            excelText(sheet.createRow(1), 0, copy(lang, "Warehouse", "Ombor", "Склад") + ": "
                + localized(context.warehouse().getName(), lang) + " (" + context.warehouse().getCode() + ")");
            excelText(sheet.createRow(2), 0, copy(lang, "Threshold (months)", "Chegara (oy)", "Порог (месяцы)") + ": "
                + months + " · " + copy(lang, "As of", "Hisoblash sanasi", "По состоянию на") + ": " + asOf);
            excelText(sheet.createRow(3), 0, copy(lang, "Calculated at", "Hisoblangan vaqt", "Время расчёта") + ": "
                + context.calculatedAt() + " (" + materialCommandViewClock.getZone() + ")");
            excelText(sheet.createRow(4), 0, copy(lang, "Filters", "Filtrlar", "Фильтры") + ": "
                + copy(lang, "Search", "Qidiruv", "Поиск") + "=" + Objects.toString(filter.search(), "") + "; "
                + copy(lang, "Material group", "Materiallar guruhi", "Группа материалов") + "=" + Objects.toString(filter.materialGroupId(), "") + "; "
                + copy(lang, "Include unknown history", "Noma’lum tarixni qo‘shish", "Включая неизвестную историю") + "="
                + (filter.includeUnknownHistory() ? copy(lang,"Yes","Ha","Да") : copy(lang,"No","Yo‘q","Нет")));
            var headers = exportHeaders(lang);
            int sortColumn = switch(filter.sortBy()) {
                case MATERIAL_CODE -> 0; case MATERIAL_NAME -> 1; case QUANTITY -> 4;
                case LAST_MOVEMENT_DATE -> 8; case BOOK_VALUE -> 11;
            };
            excelText(sheet.createRow(5), 0, copy(lang, "Sort", "Saralash", "Сортировка") + ": " + headers.get(sortColumn) + " "
                + (filter.direction().isAscending() ? copy(lang,"ascending","o‘sish bo‘yicha","по возрастанию")
                    : copy(lang,"descending","kamayish bo‘yicha","по убыванию")) + " · "
                + copy(lang,"Rows","Yozuvlar","Записи") + ": " + total);
            var header = sheet.createRow(7);
            for (int column = 0; column < headers.size(); column++) {
                excelText(header, column, headers.get(column)); header.getCell(column).setCellStyle(headerStyle);
            }
            int rowIndex = 8;
            while (true) {
                for (Candidate candidate : page.content()) {
                    var row = sheet.createRow(rowIndex++);
                    excelText(row, 0, candidate.materialCode());
                    excelText(row, 1, localized(candidate.materialName(), lang));
                    excelText(row, 2, localized(candidate.materialGroupName(), lang));
                    excelText(row, 3, localized(candidate.unitName() == null || candidate.unitName().isBlank() ? candidate.unitCode() : candidate.unitName(), lang));
                    excelNumber(row, 4, candidate.quantity(), numberStyle);
                    excelNumber(row, 5, candidate.availableQuantity(), numberStyle);
                    excelNumber(row, 6, candidate.reservedQuantity(), numberStyle);
                    excelNumber(row, 7, candidate.quarantineQuantity(), numberStyle);
                    excelDate(row, 8, candidate.lastMovementDate(), dateStyle);
                    if (candidate.monthsWithoutMovement() != null) row.createCell(9).setCellValue(candidate.monthsWithoutMovement());
                    excelDate(row, 10, candidate.eligibleSince(), dateStyle);
                    excelNumber(row, 11, candidate.bookValue(), numberStyle);
                    excelText(row, 12, candidate.currencyCode());
                    excelText(row, 13, reviewStatus(candidate.activeCaseStatus(), lang));
                    excelText(row, 14, candidate.activeCaseNo());
                    excelText(row, 15, historyLabel(candidate, lang));
                }
                int next = page.page() + 1;
                if ((long) next * EXPORT_BATCH_SIZE >= total) break;
                pageFilter = new Filter(filter.search(), filter.materialGroupId(), filter.includeUnknownHistory(),
                    next, EXPORT_BATCH_SIZE, filter.sortBy(), filter.direction());
                page = query.find(TenantContext.currentTenant(), CompanyContext.currentCompany(), warehouseId,
                    months, asOf, context.currency(), pageFilter, lang, total);
            }
            if (total == 0) excelText(sheet.createRow(rowIndex), 0, copy(lang,
                "No matching non-moving stock", "Mos harakatsiz qoldiqlar topilmadi", "Подходящие запасы не найдены"));
            sheet.createFreezePane(0, 8);
            sheet.setAutoFilter(new CellRangeAddress(7, Math.max(7, rowIndex - 1), 0, headers.size() - 1));
            int[] widths = {20, 50, 35, 20, 20, 20, 20, 20, 18, 25, 18, 24, 14, 30, 25, 40};
            for (int column = 0; column < widths.length; column++) sheet.setColumnWidth(column, widths[column] * 256);
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Non-moving workbook could not be written", ex);
        } finally { workbook.dispose(); }
    }

    static String exportLanguage(String requested) {
        String language = requested == null || requested.isBlank()
            ? LocaleContextHolder.getLocale().getLanguage() : requested.trim().replace('_', '-');
        language = Locale.forLanguageTag(language).getLanguage();
        if (!Set.of("uz", "ru", "en").contains(language)) throw new BadRequestException("Supported languages: uz, ru, en");
        return language;
    }

    private static String copy(String language, String en, String uz, String ru) {
        return switch(language) { case "uz" -> uz; case "ru" -> ru; default -> en; };
    }

    private static List<String> exportHeaders(String language) {
        return switch(language) {
            case "uz" -> List.of("Material kodi", "Material nomi", "Guruh", "O‘lchov birligi", "Umumiy qoldiq", "Mavjud qoldiq", "Band qoldiq", "Karantin", "Oxirgi harakat", "Harakatsiz oylar", "Mezonga tushgan sana", "Balans qiymati", "Valyuta", "Ko‘rib chiqish holati", "Ish raqami", "Tarix holati");
            case "ru" -> List.of("Код материала", "Наименование материала", "Группа", "Единица измерения", "Общий остаток", "Доступно", "Зарезервировано", "Карантин", "Последнее движение", "Месяцы без движения", "Дата соответствия критерию", "Балансовая стоимость", "Валюта", "Статус рассмотрения", "Номер дела", "Состояние истории");
            default -> List.of("Material code", "Material name", "Group", "Unit", "Total stock", "Available", "Reserved", "Quarantine", "Last movement", "Months without movement", "Eligible since", "Book value", "Currency", "Review status", "Case no.", "History status");
        };
    }

    private static String reviewStatus(String status, String language) {
        if (status == null || status.isBlank()) return copy(language,"No case opened","Ish ochilmagan","Дело не открыто");
        return switch(status) {
            case "DETECTED" -> copy(language,"Detected","Aniqlangan","Выявлено");
            case "CASE_CREATED" -> copy(language,"Case created","Ish ochilgan","Дело создано");
            case "UNDER_REVIEW" -> copy(language,"Under review","Ko‘rib chiqilmoqda","На рассмотрении");
            case "REVIEW_DEFERRED" -> copy(language,"Review deferred","Ko‘rib chiqish kechiktirilgan","Рассмотрение отложено");
            case "COMMISSION_REVIEW" -> copy(language,"Commission review","Komissiyada ko‘rilmoqda","Рассмотрение комиссией");
            case "APPROVAL_PENDING" -> copy(language,"Approval pending","Tasdiqlash kutilmoqda","Ожидает утверждения");
            case "DECISION_APPROVED" -> copy(language,"Decision approved","Qaror tasdiqlangan","Решение утверждено");
            case "IN_EXECUTION" -> copy(language,"In execution","Ijro etilmoqda","В исполнении");
            case "EXECUTED" -> copy(language,"Executed","Ijro etilgan","Исполнено");
            case "CLOSED" -> copy(language,"Closed","Yopilgan","Закрыто");
            case "CANCELLED" -> copy(language,"Cancelled","Bekor qilingan","Отменено");
            default -> status;
        };
    }

    private static String historyLabel(Candidate candidate, String language) {
        if ("INSUFFICIENT_HISTORY".equals(candidate.historyStatus()))
            return copy(language,"Insufficient history","Tarix yetarli emas","Недостаточно истории");
        return "OPENING_BASELINE".equals(candidate.ageBasis())
            ? copy(language,"Opening balance baseline","Boshlang‘ich qoldiq sanasi asosida","По дате начального остатка")
            : copy(language,"Known movement history","Harakat tarixi mavjud","История движений известна");
    }

    private static String localized(String value, String language) {
        if (value == null || value.isBlank()) return "";
        if (LocalizedText.isMultilingual(value)) return LocalizedText.translate(value, Locale.forLanguageTag(language));
        Map<String, String> translations = new LinkedHashMap<>();
        try {
            var node = EXPORT_JSON.readTree(value);
            if (node != null && node.isObject()) for (String code : List.of("uz", "ru", "en")) {
                if (node.path(code).isTextual() && !node.path(code).asText().isBlank()) translations.put(code, node.path(code).asText());
            }
        } catch (IOException ignored) { /* Legacy map-style and plain names remain valid. */ }
        if (translations.isEmpty()) {
            var matcher = EXPORT_TRANSLATION.matcher(value);
            while (matcher.find()) if (!matcher.group(2).isBlank()) translations.put(matcher.group(1), matcher.group(2).trim());
        }
        return translations.getOrDefault(language, translations.getOrDefault("ru", translations.getOrDefault("en", translations.getOrDefault("uz", value))));
    }

    private static void excelText(org.apache.poi.ss.usermodel.Row row, int column, String value) {
        row.createCell(column, CellType.STRING).setCellValue(value == null ? "" : value.substring(0, Math.min(value.length(), 32767)));
    }

    private static void excelNumber(org.apache.poi.ss.usermodel.Row row, int column, java.math.BigDecimal value, org.apache.poi.ss.usermodel.CellStyle style) {
        if (value == null) return;
        var cell = row.createCell(column); cell.setCellValue(value.doubleValue()); cell.setCellStyle(style);
    }

    private static void excelDate(org.apache.poi.ss.usermodel.Row row, int column, LocalDate value, org.apache.poi.ss.usermodel.CellStyle style) {
        if (value == null) return;
        var cell = row.createCell(column); cell.setCellValue(value); cell.setCellStyle(style);
    }

    private Preference effectivePreference(User user) {
        var company = requireCompany();
        return preferences.find(TenantContext.currentTenant(), company.getId(), user.getId())
            .orElseGet(() -> {
                Integer threshold = company.getNonMovingStockThresholdMonths();
                return threshold != null && threshold >= 1 && threshold <= 1200
                    ? new Preference(threshold, "COMPANY", 0, null)
                    : new Preference(12, "DEFAULT", 0, null);
            });
    }

    private Company requireCompany() {
        return companies.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany())
            .orElseThrow(() -> new NotFoundException("Company not found"));
    }

    private User currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            throw new AccessDeniedException("Authenticated user required");
        }
        // JWT credentials carry the immutable ID; never accept a user ID from the request.
        var user = auth.getCredentials() instanceof AuthenticatedTokenState token
            ? users.findByIdAndIsDeletedFalse(token.userId())
            : users.findByTenantIdAndCompanyIdAndUsernameAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), auth.getName());
        return user.filter(User::isEnabled)
            .filter(u -> Objects.equals(u.getTenantId(), TenantContext.currentTenant()))
            .filter(u -> Objects.equals(u.getCompanyId(), CompanyContext.currentCompany())
                || (u.getCompanyId() == null && u.getRoles().contains(RoleName.ADMIN) && AccessScope.globalAdmin()))
            .orElseThrow(() -> new AccessDeniedException("Active user in the current scope required"));
    }
}
