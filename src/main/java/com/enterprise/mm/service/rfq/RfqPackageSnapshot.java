package com.enterprise.mm.service.rfq;

import com.enterprise.mm.common.LocalizedText;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public record RfqPackageSnapshot(
    UUID rfqId,
    String rfqCode,
    String title,
    String description,
    String language,
    LocalDate dueDate,
    Buyer buyer,
    String responseInstructions,
    List<Position> positions,
    List<SourceReference> sources,
    List<SupplierRecipient> suppliers,
    List<SupportingDocument> documents
) {
    public RfqPackageSnapshot {
        String selectedLanguage = normalizeLanguage(language);
        language = selectedLanguage;
        title = localized(title, selectedLanguage);
        description = localized(description, selectedLanguage);
        responseInstructions = localized(responseInstructions, selectedLanguage);
        buyer = localizeBuyer(buyer, selectedLanguage);
        positions = positions == null ? List.of() : positions.stream()
            .map(position -> localizePosition(position, selectedLanguage))
            .toList();
        sources = sources == null ? List.of() : sources.stream()
            .map(source -> new SourceReference(
                source.purchaseRequestCode(),
                localized(source.purchaseRequestName(), selectedLanguage)
            ))
            .toList();
        suppliers = suppliers == null ? List.of() : suppliers.stream()
            .map(supplier -> new SupplierRecipient(
                supplier.supplierId(),
                localized(supplier.supplierName(), selectedLanguage),
                localized(supplier.contactName(), selectedLanguage),
                supplier.email()
            ))
            .toList();
        documents = documents == null ? List.of() : List.copyOf(documents);
    }

    private static Buyer localizeBuyer(Buyer buyer, String language) {
        if (buyer == null) {
            return null;
        }
        return new Buyer(
            localized(buyer.companyName(), language),
            localized(buyer.address(), language),
            localized(buyer.contactName(), language),
            buyer.email(),
            buyer.phone()
        );
    }

    private static Position localizePosition(Position position, String language) {
        return new Position(
            position.rfqItemId(),
            position.materialCode(),
            localized(position.materialName(), language),
            localized(position.specification(), language),
            position.unit(),
            position.quantity(),
            position.requiredDate()
        );
    }

    private static String localized(String value, String language) {
        return LocalizedText.translate(value, Locale.forLanguageTag(language));
    }

    private static String normalizeLanguage(String language) {
        if (language == null) {
            return LocalizedText.DEFAULT_LANGUAGE;
        }
        return switch (language.trim().toLowerCase(Locale.ROOT)) {
            case "en" -> "en";
            case "uz" -> "uz";
            default -> LocalizedText.DEFAULT_LANGUAGE;
        };
    }

    public record Buyer(
        String companyName,
        String address,
        String contactName,
        String email,
        String phone
    ) {
    }

    public record Position(
        UUID rfqItemId,
        String materialCode,
        String materialName,
        String specification,
        String unit,
        BigDecimal quantity,
        LocalDate requiredDate
    ) {
    }

    public record SourceReference(String purchaseRequestCode, String purchaseRequestName) {
    }

    public record SupplierRecipient(
        UUID supplierId,
        String supplierName,
        String contactName,
        String email
    ) {
    }

    public record SupportingDocument(UUID attachmentId, String fileName, String contentType) {
    }
}
