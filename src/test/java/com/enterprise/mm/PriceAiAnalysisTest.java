package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.payload.PriceAiDtos.PriceAiAnalyzeRequest;
import com.enterprise.mm.service.PriceAiAnalysisService;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierQuotation;
import com.enterprise.mm.entity.SupplierQuotationItem;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.repository.SupplierQuotationItemRepository;
import com.enterprise.mm.repository.SupplierQuotationRepository;
import com.enterprise.mm.repository.SupplierRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@WithMockUser(username = "tester", roles = "ADMIN")
class PriceAiAnalysisTest {
    @Autowired MaterialRepository materialRepository;
    @Autowired SupplierRepository supplierRepository;
    @Autowired PurchaseOrderRepository purchaseOrderRepository;
    @Autowired PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Autowired RFQRepository rfqRepository;
    @Autowired SupplierQuotationRepository quotationRepository;
    @Autowired SupplierQuotationItemRepository quotationItemRepository;
    @Autowired PriceAiAnalysisService priceAiAnalysisService;

    @Test
    void priceAiAnalysisComparesProposedPriceWithHistoryAndQuotations() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Material material = material("MAT-PRICE-AI-" + suffix);
        Supplier supplier = supplier("SUP-PRICE-AI-" + suffix);
        PurchaseOrder po = purchaseOrder("PO-PRICE-AI-" + suffix, supplier, BigDecimal.valueOf(1_200));
        purchaseOrderItem("POI-PRICE-AI-" + suffix, po, material, BigDecimal.TEN, BigDecimal.valueOf(120));
        RFQ rfq = rfq("RFQ-PRICE-AI-" + suffix);
        SupplierQuotation quotation = quotation("QUO-PRICE-AI-" + suffix, rfq, supplier, BigDecimal.valueOf(950));
        quotationItem("QIT-PRICE-AI-" + suffix, quotation, material, BigDecimal.TEN, BigDecimal.valueOf(95));

        var response = priceAiAnalysisService.analyze(new PriceAiAnalyzeRequest(
            material.getId(),
            supplier.getId(),
            null,
            BigDecimal.valueOf(150),
            BigDecimal.TEN,
            "UZS",
            "CHATGPT",
            20
        ));

        assertThat(response.providerSystem()).isEqualTo("CHATGPT");
        assertThat(response.providerStatus()).isIn("MOCK_OK", "CONFIG_ONLY", "LIVE_OK", "LIVE_ERROR", "AI_ERROR");
        assertThat(response.minHistoricalPrice()).isEqualByComparingTo("95");
        assertThat(response.maxHistoricalPrice()).isEqualByComparingTo("120");
        assertThat(response.avgHistoricalPrice()).isEqualByComparingTo("107.5000");
        assertThat(response.lastPurchasePrice()).isEqualByComparingTo("120");
        assertThat(response.quotationMinPrice()).isEqualByComparingTo("95");
        assertThat(response.deviationFromAveragePercent()).isEqualByComparingTo("39.53");
        assertThat(response.pricePoints()).extracting("source").contains("PURCHASE_ORDER", "SUPPLIER_QUOTATION");
        assertThat(response.findings()).anyMatch(finding -> "PRICE_HISTORY".equals(finding.category()));
        assertThat(response.recommendations()).isNotEmpty();
    }

    private Material material(String code) {
        Material material = new Material();
        material.setCode(code);
        material.setName(code);
        material.setStatus("ACTIVE");
        return materialRepository.save(material);
    }

    private Supplier supplier(String code) {
        Supplier supplier = new Supplier();
        supplier.setCode(code);
        supplier.setName(code);
        supplier.setStatus("ACTIVE");
        supplier.setRating(BigDecimal.valueOf(92));
        supplier.setCategory("A");
        return supplierRepository.save(supplier);
    }

    private PurchaseOrder purchaseOrder(String code, Supplier supplier, BigDecimal totalAmount) {
        PurchaseOrder po = new PurchaseOrder();
        po.setCode(code);
        po.setName(code);
        po.setStatus("SENT");
        po.setSupplierId(supplier.getId());
        po.setOrderDate(LocalDate.now().minusDays(2));
        po.setExpectedDeliveryDate(LocalDate.now().plusDays(7));
        po.setTotalAmount(totalAmount);
        po.setCurrencyCode("UZS");
        return purchaseOrderRepository.save(po);
    }

    private PurchaseOrderItem purchaseOrderItem(String code, PurchaseOrder po, Material material, BigDecimal quantity, BigDecimal unitPrice) {
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setCode(code);
        item.setName(code);
        item.setStatus("ACTIVE");
        item.setPurchaseOrderId(po.getId());
        item.setMaterialId(material.getId());
        item.setQuantity(quantity);
        item.setUnitPrice(unitPrice);
        return purchaseOrderItemRepository.save(item);
    }

    private RFQ rfq(String code) {
        RFQ rfq = new RFQ();
        rfq.setCode(code);
        rfq.setName(code);
        rfq.setStatus("OPEN");
        rfq.setDueDate(LocalDate.now().plusDays(3));
        return rfqRepository.save(rfq);
    }

    private SupplierQuotation quotation(String code, RFQ rfq, Supplier supplier, BigDecimal totalAmount) {
        SupplierQuotation quotation = new SupplierQuotation();
        quotation.setCode(code);
        quotation.setName(code);
        quotation.setStatus("SUBMITTED");
        quotation.setRfqId(rfq.getId());
        quotation.setSupplierId(supplier.getId());
        quotation.setTotalAmount(totalAmount);
        quotation.setCurrencyCode("UZS");
        quotation.setSubmittedAt(LocalDateTime.now().minusDays(1));
        return quotationRepository.save(quotation);
    }

    private SupplierQuotationItem quotationItem(String code, SupplierQuotation quotation, Material material, BigDecimal quantity, BigDecimal unitPrice) {
        SupplierQuotationItem item = new SupplierQuotationItem();
        item.setCode(code);
        item.setName(code);
        item.setStatus("ACTIVE");
        item.setQuotationId(quotation.getId());
        item.setMaterialId(material.getId());
        item.setQuantity(quantity);
        item.setUnitPrice(unitPrice);
        item.setDeliveryDays(4);
        return quotationItemRepository.save(item);
    }
}
