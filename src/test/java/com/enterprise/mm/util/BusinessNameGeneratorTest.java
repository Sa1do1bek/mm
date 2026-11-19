package com.enterprise.mm.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.common.LocalizedText;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class BusinessNameGeneratorTest {

    @Test
    void generatesCompleteIdentifierFreeNamesInEverySupportedLanguage() {
        String name = BusinessNameGenerator.defaultName("PurchaseRequest");

        assertThat(LocalizedText.translate(name, Locale.forLanguageTag("ru"))).isEqualTo("Заявка");
        assertThat(LocalizedText.translate(name, Locale.ENGLISH)).isEqualTo("Purchase Request");
        assertThat(LocalizedText.translate(name, Locale.forLanguageTag("uz"))).isEqualTo("So‘rov");
        assertThat(name).doesNotContainPattern(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
        );
    }

    @Test
    void replacesTechnicalAndConcatenatedGeneratedLabelsWithBusinessDefaults() {
        String generated = BusinessNameGenerator.normalize(
            "Forecast ru: Материал\nen: Material\nuz: Material",
            "DemandForecast"
        );

        assertThat(LocalizedText.parse(generated))
            .containsOnlyKeys("ru", "en", "uz")
            .doesNotContainValue("Forecast ru: Материал");
        assertThat(BusinessNameGenerator.normalize(
            "DOC-550E8400-E29B-41D4-A716-446655440000",
            "InventoryDocument"
        )).doesNotContain("550E8400");
    }

    @Test
    void workflowChildNamesReuseBaseBusinessNameWithoutNestedDocumentTypes() {
        String base = BusinessNameGenerator.localized(
            "Chemical Procurement for Production",
            "Chemical Procurement for Production",
            "Chemical Procurement for Production"
        );

        String purchaseRequest = BusinessNameGenerator.workflowDocumentName("PurchaseRequest", base);
        String planApproval = BusinessNameGenerator.workflowDocumentName("PlanApproval", base);
        String rfq = BusinessNameGenerator.workflowDocumentName("RFQ", purchaseRequest);
        String supplierQuotation = BusinessNameGenerator.workflowDocumentName("SupplierQuotation", rfq);
        String contract = BusinessNameGenerator.workflowDocumentName("Contract", supplierQuotation);
        String purchaseOrder = BusinessNameGenerator.workflowDocumentName("PurchaseOrder", purchaseRequest);
        String goodsReceipt = BusinessNameGenerator.workflowDocumentName("GoodsReceipt", purchaseOrder);

        assertThat(LocalizedText.translate(planApproval, Locale.ENGLISH))
            .isEqualTo("Plan Approval - Chemical Procurement for Production");
        assertThat(LocalizedText.translate(purchaseRequest, Locale.ENGLISH))
            .isEqualTo("Purchase Request - Chemical Procurement for Production");
        assertThat(LocalizedText.translate(purchaseRequest, Locale.forLanguageTag("ru")))
            .isEqualTo("Заявка на закупку - Chemical Procurement for Production");
        assertThat(LocalizedText.translate(purchaseRequest, Locale.forLanguageTag("uz")))
            .isEqualTo("Xarid so'rovi - Chemical Procurement for Production");
        assertThat(LocalizedText.translate(rfq, Locale.ENGLISH))
            .isEqualTo("RFQ - Chemical Procurement for Production");
        assertThat(LocalizedText.translate(supplierQuotation, Locale.ENGLISH))
            .isEqualTo("Supplier Quotation - Chemical Procurement for Production");
        assertThat(LocalizedText.translate(contract, Locale.ENGLISH))
            .isEqualTo("Contract - Chemical Procurement for Production");
        assertThat(LocalizedText.translate(purchaseOrder, Locale.ENGLISH))
            .isEqualTo("Purchase Order - Chemical Procurement for Production");
        assertThat(LocalizedText.translate(goodsReceipt, Locale.ENGLISH))
            .isEqualTo("Goods Receipt - Chemical Procurement for Production");
        assertThat(LocalizedText.translate(goodsReceipt, Locale.forLanguageTag("ru")))
            .isEqualTo("Приход товара - Chemical Procurement for Production");
        assertThat(LocalizedText.translate(goodsReceipt, Locale.forLanguageTag("uz")))
            .isEqualTo("Tovar kirimi - Chemical Procurement for Production");
        assertThat(LocalizedText.translate(goodsReceipt, Locale.ENGLISH))
            .doesNotContain("Purchase Request")
            .doesNotContain("Purchase Order")
            .doesNotContainPattern("\\d{4}-\\d{2}-\\d{2}")
            .doesNotContainPattern("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}");
        assertThat(LocalizedText.translate(goodsReceipt, Locale.forLanguageTag("ru")))
            .doesNotContain("Purchase Request")
            .doesNotContain("Purchase Order")
            .doesNotContain("Goods Receipt");
    }

    @Test
    void normalizationPreservesWorkflowChildBusinessBase() {
        String childName = "Purchase Request - Chemical Procurement for Production";

        String normalized = BusinessNameGenerator.normalize(childName, "PurchaseRequest");

        assertThat(LocalizedText.translate(normalized, Locale.ENGLISH))
            .isEqualTo(childName);
    }

    @Test
    void workflowDocumentNamesKeepPrefixAndBusinessNameInOneLanguage() {
        String base = BusinessNameGenerator.localized(
            "Годовой план производства",
            "Annual production plan",
            "Yillik ishlab chiqarish rejasi"
        );

        String purchaseRequest = BusinessNameGenerator.workflowDocumentName("PurchaseRequest", base);

        assertThat(LocalizedText.translate(purchaseRequest, Locale.forLanguageTag("ru")))
            .isEqualTo("Заявка на закупку - Годовой план производства")
            .doesNotContain("Purchase Request");
        assertThat(LocalizedText.translate(purchaseRequest, Locale.ENGLISH))
            .isEqualTo("Purchase Request - Annual production plan");
        assertThat(LocalizedText.translate(purchaseRequest, Locale.forLanguageTag("uz")))
            .isEqualTo("Xarid so'rovi - Yillik ishlab chiqarish rejasi")
            .doesNotContain("Purchase Request");
    }
}
