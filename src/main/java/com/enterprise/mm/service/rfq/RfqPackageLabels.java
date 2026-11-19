package com.enterprise.mm.service.rfq;

import java.util.Locale;

final class RfqPackageLabels {
    private RfqPackageLabels() {
    }

    static Labels forLanguage(String language) {
        String normalized = language == null ? "ru" : language.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "en" -> ENGLISH;
            case "uz" -> UZBEK;
            default -> RUSSIAN;
        };
    }

    private static final Labels ENGLISH = new Labels(
        "Request for commercial proposal", "Dear", "Buyer", "Contact", "Email", "Deadline",
        "Response instructions", "Code", "Material", "Specification", "Unit", "Quantity",
        "Required date", "This RFQ contains %d positions. Every line is included in the attached official PDF and Excel quotation template.",
        "You may respond by email or use the secure supplier portal:", "Open RFQ in supplier portal",
        "The secure link is valid for 24 hours and can be used during that period. You can request a new link from the supplier portal.",
        "Open supplier portal", "Title", "Instructions", "Quotation", "RFQ item ID",
        "Requested quantity", "Unit price", "Offered quantity", "Delivery days", "Tax %", "No bid",
        "No bid reason", "Notes"
    );

    private static final Labels RUSSIAN = new Labels(
        "Запрос коммерческого предложения", "Уважаемый(ая)", "Покупатель", "Контактное лицо",
        "Электронная почта", "Срок ответа", "Инструкции по ответу", "Код", "Материал",
        "Спецификация", "Единица", "Количество", "Требуемая дата",
        "RFQ содержит %d позиций. Все позиции включены в официальный PDF и шаблон коммерческого предложения Excel.",
        "Вы можете ответить по электронной почте или воспользоваться защищённым порталом поставщика:",
        "Открыть RFQ на портале поставщика",
        "Защищённая ссылка действительна 24 часа. Новую ссылку можно запросить на портале поставщика.",
        "Открыть портал поставщика", "Название", "Инструкции", "Коммерческое предложение", "ID позиции RFQ",
        "Запрошенное количество", "Цена за единицу", "Предлагаемое количество", "Срок поставки, дней",
        "Налог, %", "Не предлагать", "Причина отказа", "Примечания"
    );

    private static final Labels UZBEK = new Labels(
        "Tijorat taklifi uchun so‘rov", "Hurmatli", "Xaridor", "Mas’ul shaxs", "Elektron pochta",
        "Javob muddati", "Javob berish bo‘yicha yo‘riqnoma", "Kod", "Material", "Spetsifikatsiya",
        "O‘lchov birligi", "Miqdor", "Talab qilinadigan sana",
        "RFQ %d ta pozitsiyani o‘z ichiga oladi. Barcha pozitsiyalar rasmiy PDF va Excel tijorat taklifi shabloniga kiritilgan.",
        "Elektron pochta orqali javob berishingiz yoki xavfsiz yetkazib beruvchi portalidan foydalanishingiz mumkin:",
        "RFQni yetkazib beruvchi portalida ochish",
        "Xavfsiz havola 24 soat davomida amal qiladi. Yangi havolani yetkazib beruvchi portalidan so‘rashingiz mumkin.",
        "Yetkazib beruvchi portalini ochish", "Nomi", "Yo‘riqnoma", "Tijorat taklifi", "RFQ pozitsiyasi IDsi",
        "So‘ralgan miqdor", "Birlik narxi", "Taklif etilgan miqdor", "Yetkazib berish kunlari",
        "Soliq, %", "Taklif yo‘q", "Taklif bermaslik sababi", "Izohlar"
    );

    record Labels(
        String heading,
        String dear,
        String buyer,
        String contact,
        String email,
        String deadline,
        String responseInstructions,
        String code,
        String material,
        String specification,
        String unit,
        String quantity,
        String requiredDate,
        String omittedPositions,
        String responsePrompt,
        String openPortal,
        String linkValidity,
        String openPortalPlainText,
        String title,
        String instructions,
        String quotationSheet,
        String rfqItemId,
        String requestedQuantity,
        String unitPrice,
        String offeredQuantity,
        String deliveryDays,
        String tax,
        String noBid,
        String noBidReason,
        String notes
    ) {
    }
}
