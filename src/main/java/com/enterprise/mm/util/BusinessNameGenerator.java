package com.enterprise.mm.util;

import com.enterprise.mm.common.LocalizedText;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Generates identifier-free fallback names for newly created business entities.
 */
public final class BusinessNameGenerator {
    private static final Pattern UUID = Pattern.compile(
        "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"
    );
    private static final Pattern TECHNICAL_CODE = Pattern.compile(
        "(?i)^[A-Z][A-Z0-9_ -]{1,30}-[0-9A-F]{4,}$"
    );
    private static final Pattern GENERATED_PHRASE = Pattern.compile(
        "(?iu)\\b(generated|approval|step|revision|invitation|quotation|protocol|conflict|"
            + "check|forecast|demand|plan|request|order|movement|receipt|evidence|line|layer|"
            + "valuation|reservation|return|transfer|evaluation|quarantine|observation|"
            + "preflight|reversal|execution|output|notification|document|payment|"
            + "reconciliation|assignment|shortage|case|decision|opening|cost|imported|"
            + "administrator|протокол|вскрытие|конфликт|проверка|потребность|заявка|"
            + "инвентаризация|оплата|доверенность|акт|решение|оценка|перемещение)\\b"
    );
    private static final Pattern EMBEDDED_LANGUAGE_MARKER = Pattern.compile(
        "(?i)(ru|en|uz)\\s*:"
    );
    private static final Pattern LANGUAGE_MARKER_AT_START = Pattern.compile(
        "(?i)^\\s*(ru|en|uz)\\s*:"
    );
    private static final Locale RU_LOCALE = Locale.forLanguageTag("ru");
    private static final Locale UZ_LOCALE = Locale.forLanguageTag("uz");
    private static final Set<String> WORKFLOW_DOCUMENT_LABELS = Set.of(
        "demand plan",
        "demand planning cycle approval",
        "plan approval",
        "approval task",
        "approval history",
        "purchase request",
        "purchase request approval",
        "purchase order",
        "purchase order approval",
        "purchase order revision",
        "goods receipt",
        "service request",
        "service request approval",
        "service order",
        "service order approval",
        "service finance approval",
        "service execution",
        "work order",
        "service acceptance act",
        "service acceptance act approval",
        "acceptance act",
        "work completion act",
        "work completion act approval",
        "issue request",
        "material reservation",
        "material return document",
        "asset request",
        "asset transfer",
        "asset acceptance",
        "maintenance request",
        "maintenance report",
        "rfq",
        "supplier quotation",
        "contract",
        "contract specification",
        "tender bid opening protocol",
        "tender bid opening line",
        "tender evaluation protocol",
        "tender conflict check",
        "tender price calculation",
        "rfq supplier invitation",
        "procurement procedure",
        "procurement procedure approval",
        "procurement campaign",
        "procurement method decision",
        "procurement method decision approval",
        "direct award",
        "procurement award outcome",
        "procurement award outcome line",
        "stock movement approval",
        "inventory document approval",
        "supplier accreditation approval",
        "supplier accreditation renewal",
        "supplier accreditation decision",
        "supplier corrective action",
        "supplier evaluation",
        "supplier unblock request",
        "service request remark",
        "quality claim",
        "quality nonconformance",
        "management opportunity approval",
        "non moving stock case",
        "non moving stock decision",
        "non moving stock decision approval",
        "supplier po dispatch",
        "supplier po response",
        "advance shipping notice",
        "delivery schedule"
    );
    private static final Map<String, String> WORKFLOW_DOCUMENT_LABEL_TRANSLATIONS = Map.ofEntries(
        workflowLabel("Demand Plan", "План потребности", "Talab rejasi"),
        workflowLabel("Demand Planning Cycle Approval", "Согласование цикла планирования потребностей", "Talab rejalashtirish siklini tasdiqlash"),
        workflowLabel("Plan Approval", "Согласование плана", "Rejani tasdiqlash"),
        workflowLabel("Approval Task", "Задача согласования", "Tasdiqlash vazifasi"),
        workflowLabel("Approval History", "История согласования", "Tasdiqlash tarixi"),
        workflowLabel("Purchase Request", "Заявка на закупку", "Xarid so'rovi"),
        workflowLabel("Purchase Request Approval", "Согласование заявки на закупку", "Xarid so'rovini tasdiqlash"),
        workflowLabel("Purchase Order", "Заказ на закупку", "Xarid buyurtmasi"),
        workflowLabel("Purchase Order Approval", "Согласование заказа на закупку", "Xarid buyurtmasini tasdiqlash"),
        workflowLabel("Purchase Order Revision", "Ревизия заказа на закупку", "Xarid buyurtmasi reviziyasi"),
        workflowLabel("Goods Receipt", "Приход товара", "Tovar kirimi"),
        workflowLabel("Service Request", "Заявка на услугу", "Xizmat so'rovi"),
        workflowLabel("Service Request Approval", "Согласование заявки на услугу", "Xizmat so'rovini tasdiqlash"),
        workflowLabel("Service Order", "Заказ услуги", "Xizmat buyurtmasi"),
        workflowLabel("Service Order Approval", "Согласование заказа услуги", "Xizmat buyurtmasini tasdiqlash"),
        workflowLabel("Service Finance Approval", "Финансовое согласование услуги", "Xizmat bo'yicha moliyaviy tasdiqlash"),
        workflowLabel("Service Execution", "Исполнение услуги", "Xizmat ijrosi"),
        workflowLabel("Work Order", "Заказ работ", "Ish buyurtmasi"),
        workflowLabel("Service Acceptance Act", "Акт приемки услуг", "Xizmat qabul qilish dalolatnomasi"),
        workflowLabel("Service Acceptance Act Approval", "Согласование акта приемки услуг", "Xizmat qabul qilish dalolatnomasini tasdiqlash"),
        workflowLabel("Acceptance Act", "Акт приемки", "Qabul dalolatnomasi"),
        workflowLabel("Work Completion Act", "Акт выполненных работ", "Bajarilgan ishlar dalolatnomasi"),
        workflowLabel("Work Completion Act Approval", "Согласование акта выполненных работ", "Bajarilgan ishlar dalolatnomasini tasdiqlash"),
        workflowLabel("Issue Request", "Заявка на выдачу", "Berish so'rovi"),
        workflowLabel("Material Reservation", "Резерв материала", "Material rezervi"),
        workflowLabel("Material Return Document", "Документ возврата материала", "Materialni qaytarish hujjati"),
        workflowLabel("Asset Request", "Заявка на актив", "Aktiv so'rovi"),
        workflowLabel("Asset Transfer", "Передача актива", "Aktivni o'tkazish"),
        workflowLabel("Asset Acceptance", "Приемка актива", "Aktivni qabul qilish"),
        workflowLabel("Maintenance Request", "Заявка на обслуживание", "Texnik xizmat so'rovi"),
        workflowLabel("Maintenance Report", "Отчет об обслуживании", "Texnik xizmat hisoboti"),
        workflowLabel("RFQ", "Запрос котировок", "Narx taklif so'rovi"),
        workflowLabel("Supplier Quotation", "Коммерческое предложение поставщика", "Yetkazib beruvchining tijorat taklifi"),
        workflowLabel("Contract", "Договор", "Shartnoma"),
        workflowLabel("Contract Specification", "Спецификация договора", "Shartnoma spetsifikatsiyasi"),
        workflowLabel("Tender Bid Opening Protocol", "Протокол вскрытия тендерных предложений", "Tender takliflarini ochish bayonnomasi"),
        workflowLabel("Tender Bid Opening Line", "Строка протокола вскрытия", "Ochish bayonnomasi qatori"),
        workflowLabel("Tender Evaluation Protocol", "Протокол оценки тендера", "Tender baholash bayonnomasi"),
        workflowLabel("Tender Conflict Check", "Проверка конфликта интересов тендера", "Tender manfaatlar to'qnashuvini tekshirish"),
        workflowLabel("Tender Price Calculation", "Расчет цены тендера", "Tender narxini hisoblash"),
        workflowLabel("RFQ Supplier Invitation", "Приглашение поставщика к запросу котировок", "Yetkazib beruvchini narx so'roviga taklif qilish"),
        workflowLabel("Procurement Procedure", "Закупочная процедура", "Xarid protsedurasi"),
        workflowLabel("Procurement Procedure Approval", "Согласование закупочной процедуры", "Xarid protsedurasini tasdiqlash"),
        workflowLabel("Procurement Campaign", "Закупочная кампания", "Xarid kampaniyasi"),
        workflowLabel("Procurement Method Decision", "Решение о способе закупки", "Xarid usuli bo'yicha qaror"),
        workflowLabel("Procurement Method Decision Approval", "Согласование решения о способе закупки", "Xarid usuli bo'yicha qarorni tasdiqlash"),
        workflowLabel("Direct Award", "Прямая закупка", "To'g'ridan-to'g'ri xarid"),
        workflowLabel("Procurement Award Outcome", "Итог присуждения закупки", "Xarid g'olibini aniqlash natijasi"),
        workflowLabel("Procurement Award Outcome Line", "Строка итога присуждения закупки", "Xarid natijasi qatori"),
        workflowLabel("Stock Movement Approval", "Согласование складского движения", "Ombor harakatini tasdiqlash"),
        workflowLabel("Inventory Document Approval", "Согласование складского документа", "Ombor hujjatini tasdiqlash"),
        workflowLabel("Supplier Accreditation Approval", "Согласование аккредитации поставщика", "Yetkazib beruvchi akkreditatsiyasini tasdiqlash"),
        workflowLabel("Supplier Accreditation Renewal", "Продление аккредитации поставщика", "Yetkazib beruvchi akkreditatsiyasini uzaytirish"),
        workflowLabel("Supplier Accreditation Decision", "Решение по аккредитации поставщика", "Yetkazib beruvchi akkreditatsiyasi bo'yicha qaror"),
        workflowLabel("Supplier Corrective Action", "Корректирующее действие поставщика", "Yetkazib beruvchining tuzatish harakati"),
        workflowLabel("Supplier Evaluation", "Оценка поставщика", "Yetkazib beruvchini baholash"),
        workflowLabel("Supplier Unblock Request", "Заявка на разблокировку поставщика", "Yetkazib beruvchini blokdan chiqarish so'rovi"),
        workflowLabel("Service Request Remark", "Замечание по заявке на услугу", "Xizmat so'rovi bo'yicha izoh"),
        workflowLabel("Quality Claim", "Претензия по качеству", "Sifat bo'yicha da'vo"),
        workflowLabel("Quality Nonconformance", "Несоответствие качества", "Sifat nomuvofiqligi"),
        workflowLabel("Management Opportunity Approval", "Согласование управленческой возможности", "Boshqaruv imkoniyatini tasdiqlash"),
        workflowLabel("Non Moving Stock Case", "Кейс неликвидного запаса", "Harakatsiz zaxira ishi"),
        workflowLabel("Non Moving Stock Decision", "Решение по неликвидному запасу", "Harakatsiz zaxira bo'yicha qaror"),
        workflowLabel("Non Moving Stock Decision Approval", "Согласование решения по неликвидному запасу", "Harakatsiz zaxira qarorini tasdiqlash"),
        workflowLabel("Supplier PO Dispatch", "Отправка заказа поставщику", "Yetkazib beruvchiga buyurtma yuborish"),
        workflowLabel("Supplier PO Response", "Ответ поставщика на заказ", "Yetkazib beruvchining buyurtmaga javobi"),
        workflowLabel("Advance Shipping Notice", "Предварительное уведомление об отгрузке", "Oldindan jo'natish xabarnomasi"),
        workflowLabel("Delivery Schedule", "График поставки", "Yetkazib berish jadvali")
    );

    private BusinessNameGenerator() {
    }

    public static String defaultName(String entityType) {
        String english = humanize(entityType);
        String key = english.toLowerCase(Locale.ROOT);

        String russian = switchCategory(
            key,
            "Деловой документ",
            "Позиция",
            "Заявка",
            "Заказ",
            "Склад",
            "Материал",
            "Поставщик",
            "Услуга",
            "Согласование",
            "Финансовый документ",
            "Организация"
        );
        String uzbek = switchCategory(
            key,
            "Biznes hujjati",
            "Pozitsiya",
            "So‘rov",
            "Buyurtma",
            "Ombor",
            "Material",
            "Yetkazib beruvchi",
            "Xizmat",
            "Tasdiqlash",
            "Moliyaviy hujjat",
            "Tashkilot"
        );
        return LocalizedText.format(russian, english, uzbek);
    }

    public static String localized(String ru, String en, String uz) {
        return LocalizedText.format(ru, en, uz);
    }

    public static String workflowDocumentName(String documentType, String parentWorkflowName) {
        String ruTypeLabel = documentTypeLabel(documentType, RU_LOCALE);
        String enTypeLabel = documentTypeLabel(documentType, Locale.ENGLISH);
        String uzTypeLabel = documentTypeLabel(documentType, UZ_LOCALE);

        if (!LocalizedText.isMultilingual(parentWorkflowName)) {
            String parentBase = workflowBaseName(parentWorkflowName, enTypeLabel);
            return LocalizedText.format(
                composeWorkflowName(ruTypeLabel, parentBase),
                composeWorkflowName(enTypeLabel, parentBase),
                composeWorkflowName(uzTypeLabel, parentBase)
            );
        }

        Map<String, String> translations = LocalizedText.parse(parentWorkflowName);
        String ruBase = workflowBaseName(firstNonBlank(translations.get("ru"), translations.get("en"), translations.get("uz")), ruTypeLabel);
        String enBase = workflowBaseName(firstNonBlank(translations.get("en"), translations.get("ru"), translations.get("uz")), enTypeLabel);
        String uzBase = workflowBaseName(firstNonBlank(translations.get("uz"), translations.get("en"), translations.get("ru")), uzTypeLabel);
        return LocalizedText.format(
            composeWorkflowName(ruTypeLabel, ruBase),
            composeWorkflowName(enTypeLabel, enBase),
            composeWorkflowName(uzTypeLabel, uzBase)
        );
    }

    public static String workflowBaseName(String workflowName, String fallbackDocumentType) {
        String normalized = firstNonBlank(
            LocalizedText.translate(workflowName, Locale.ENGLISH),
            workflowName
        );
        if (normalized == null) {
            return null;
        }
        return stripWorkflowDocumentPrefix(normalized.trim(), fallbackDocumentType);
    }

    /**
     * Normalizes any persistence-bound name. New clients already send tagged
     * values. Legacy human names are retained, while technical/generated labels
     * are replaced by an identifier-free multilingual business default.
     */
    public static String normalize(String value, String entityType) {
        if (value == null || value.isBlank()) {
            return defaultName(entityType);
        }
        if (LANGUAGE_MARKER_AT_START.matcher(value).find() && !LocalizedText.parse(value).isEmpty()) {
            String complete = LocalizedText.ensureComplete(value);
            return complete == null ? defaultName(entityType) : complete;
        }

        String trimmed = value.trim();
        if (isWorkflowDocumentName(trimmed)) {
            return LocalizedText.ensureComplete(trimmed);
        }
        if (UUID.matcher(trimmed).find()
            || TECHNICAL_CODE.matcher(trimmed).matches()
            || EMBEDDED_LANGUAGE_MARKER.matcher(trimmed).find()
            || GENERATED_PHRASE.matcher(trimmed).find()) {
            return defaultName(entityType);
        }
        return LocalizedText.ensureComplete(trimmed);
    }

    private static boolean isWorkflowDocumentName(String value) {
        String normalized = firstNonBlank(
            LocalizedText.translate(value, Locale.ENGLISH),
            value
        );
        if (normalized == null) {
            return false;
        }
        String trimmed = normalized.trim();
        int separator = trimmed.indexOf(" - ");
        if (separator <= 0 || separator + 3 >= trimmed.length()) {
            return false;
        }
        String prefix = trimmed.substring(0, separator).trim();
        String base = trimmed.substring(separator + 3).trim();
        return WORKFLOW_DOCUMENT_LABELS.contains(prefix.toLowerCase(Locale.ROOT))
            && !base.isBlank()
            && !UUID.matcher(base).find()
            && !TECHNICAL_CODE.matcher(base).matches();
    }

    private static String humanize(String entityType) {
        if (entityType == null || entityType.isBlank()) {
            return "Business Document";
        }
        return entityType
            .trim()
            .replace('_', ' ')
            .replace('-', ' ')
            .replaceAll("(?<=[a-z0-9])(?=[A-Z])", " ")
            .replaceAll("(?<=[A-Z])(?=[A-Z][a-z])", " ")
            .replaceAll("\\s+", " ");
    }

    private static String documentTypeLabel(String documentType) {
        String humanized = humanize(documentType);
        String key = humanized.toLowerCase(Locale.ROOT);
        String label = switch (key) {
            case "rfq", "r f q" -> "RFQ";
            case "po", "p o" -> "Purchase Order";
            case "pr", "p r" -> "Purchase Request";
            case "gr", "g r" -> "Goods Receipt";
            case "service acceptance act" -> "Service Acceptance Act";
            default -> humanized;
        };
        return label
            .replaceAll("\\bRfq\\b", "RFQ")
            .replaceAll("\\bPo\\b", "PO");
    }

    private static String documentTypeLabel(String documentType, Locale locale) {
        String english = documentTypeLabel(documentType);
        String localizedLabels = WORKFLOW_DOCUMENT_LABEL_TRANSLATIONS.get(english.toLowerCase(Locale.ROOT));
        if (localizedLabels != null) {
            return LocalizedText.translate(localizedLabels, locale);
        }
        return fallbackDocumentTypeLabel(english, locale);
    }

    private static String composeWorkflowName(String typeLabel, String baseName) {
        String base = baseName == null ? "" : baseName.trim();
        if (base.isEmpty()) {
            return typeLabel;
        }
        return typeLabel + " - " + base;
    }

    private static String stripWorkflowDocumentPrefix(String value) {
        return stripWorkflowDocumentPrefix(value, null);
    }

    private static String stripWorkflowDocumentPrefix(String value, String fallbackDocumentType) {
        String normalized = value == null ? "" : value.trim();
        int separator = normalized.indexOf(" - ");
        if (separator <= 0 || separator + 3 >= normalized.length()) {
            return normalized;
        }
        String prefix = normalized.substring(0, separator).trim();
        if (isWorkflowDocumentLabel(prefix, fallbackDocumentType)) {
            return normalized.substring(separator + 3).trim();
        }
        return normalized;
    }

    private static boolean isWorkflowDocumentLabel(String prefix, String fallbackDocumentType) {
        String normalized = normalizeLabel(prefix);
        if (WORKFLOW_DOCUMENT_LABELS.contains(normalized)) {
            return true;
        }
        String fallback = fallbackDocumentType == null ? "" : fallbackDocumentType.trim();
        if (!fallback.isBlank()
            && (normalized.equals(normalizeLabel(fallback))
                || normalized.equals(normalizeLabel(documentTypeLabel(fallback))))) {
            return true;
        }
        return WORKFLOW_DOCUMENT_LABEL_TRANSLATIONS.values().stream()
            .flatMap(value -> LocalizedText.parse(value).values().stream())
            .map(BusinessNameGenerator::normalizeLabel)
            .anyMatch(normalized::equals);
    }

    private static String fallbackDocumentTypeLabel(String english, Locale locale) {
        String language = locale == null ? "" : locale.getLanguage().toLowerCase(Locale.ROOT);
        String key = english.toLowerCase(Locale.ROOT);
        return switch (language) {
            case "ru" -> switchCategory(
                key,
                "Деловой документ",
                "Позиция",
                "Заявка",
                "Заказ",
                "Складской документ",
                "Материал",
                "Поставщик",
                "Услуга",
                "Согласование",
                "Финансовый документ",
                "Организация"
            );
            case "uz" -> switchCategory(
                key,
                "Biznes hujjati",
                "Pozitsiya",
                "So'rov",
                "Buyurtma",
                "Ombor hujjati",
                "Material",
                "Yetkazib beruvchi",
                "Xizmat",
                "Tasdiqlash",
                "Moliyaviy hujjat",
                "Tashkilot"
            );
            default -> english;
        };
    }

    private static String normalizeLabel(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static Map.Entry<String, String> workflowLabel(String en, String ru, String uz) {
        return Map.entry(en.toLowerCase(Locale.ROOT), LocalizedText.format(ru, en, uz));
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String switchCategory(
        String key,
        String fallback,
        String item,
        String request,
        String order,
        String warehouse,
        String material,
        String supplier,
        String service,
        String approval,
        String finance,
        String organization
    ) {
        if (containsAny(key, "item", "line", "position")) return item;
        if (containsAny(key, "request", "requisition", "demand")) return request;
        if (containsAny(key, "order", "purchase")) return order;
        if (containsAny(key, "warehouse", "storage", "location", "movement", "receipt", "transfer", "reservation", "batch")) return warehouse;
        if (containsAny(key, "material", "inventory", "stock", "product", "classifier", "mxik")) return material;
        if (containsAny(key, "supplier", "vendor", "counterparty")) return supplier;
        if (containsAny(key, "service", "work")) return service;
        if (containsAny(key, "approval", "review", "decision")) return approval;
        if (containsAny(key, "invoice", "payment", "finance", "valuation", "budget", "bank", "currency")) return finance;
        if (containsAny(key, "company", "organization", "department", "branch")) return organization;
        return fallback;
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) return true;
        }
        return false;
    }
}
