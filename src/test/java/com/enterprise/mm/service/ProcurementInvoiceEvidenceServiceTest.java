package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AssetAcceptance;
import com.enterprise.mm.entity.AssetAcceptanceLine;
import com.enterprise.mm.entity.AssetAcceptanceUnit;
import com.enterprise.mm.entity.ContractInvoice;
import com.enterprise.mm.entity.ContractInvoiceEvidenceSnapshot;
import com.enterprise.mm.entity.GoodsReceipt;
import com.enterprise.mm.entity.GoodsReceiptLine;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.QualityInspection;
import com.enterprise.mm.enums.AssetAcceptanceApprovalStatus;
import com.enterprise.mm.enums.AssetAcceptancePostingStatus;
import com.enterprise.mm.enums.AssetAcceptanceUnitQualityDecision;
import com.enterprise.mm.enums.CommercialInstrumentType;
import com.enterprise.mm.enums.ContractInvoiceStatus;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.ProcurementSourceDocumentType;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.AssetAcceptanceLineRepository;
import com.enterprise.mm.repository.AssetAcceptanceRepository;
import com.enterprise.mm.repository.AssetAcceptanceUnitRepository;
import com.enterprise.mm.repository.ContractInvoiceEvidenceSnapshotRepository;
import com.enterprise.mm.repository.ContractInvoiceRepository;
import com.enterprise.mm.repository.ContractPaymentRepository;
import com.enterprise.mm.repository.GoodsReceiptLineRepository;
import com.enterprise.mm.repository.GoodsReceiptRepository;
import com.enterprise.mm.repository.ProcurementAwardOutcomeRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.ServiceAcceptanceActRepository;
import com.enterprise.mm.repository.WorkCompletionActRepository;

@ExtendWith(MockitoExtension.class)
class ProcurementInvoiceEvidenceServiceTest {
    @Mock private GoodsReceiptRepository goodsReceiptRepository;
    @Mock private GoodsReceiptLineRepository goodsReceiptLineRepository;
    @Mock private PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Mock private ServiceAcceptanceActRepository serviceAcceptanceActRepository;
    @Mock private WorkCompletionActRepository workCompletionActRepository;
    @Mock private ContractInvoiceRepository contractInvoiceRepository;
    @Mock private ContractPaymentRepository contractPaymentRepository;
    @Mock private ContractInvoiceEvidenceSnapshotRepository evidenceSnapshotRepository;
    @Mock private com.enterprise.mm.repository.QualityInspectionRepository qualityInspectionRepository;
    @Mock private ProcurementAwardOutcomeRepository awardOutcomeRepository;
    @Mock private AssetAcceptanceRepository assetAcceptanceRepository;
    @Mock private AssetAcceptanceLineRepository assetAcceptanceLineRepository;
    @Mock private AssetAcceptanceUnitRepository assetAcceptanceUnitRepository;

    private UUID companyId;
    private UUID purchaseOrderId;
    private UUID purchaseOrderItemId;
    private UUID goodsReceiptId;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("default");
        companyId = CompanyContext.DEFAULT_COMPANY_ID;
        CompanyContext.setCompany(companyId);
        purchaseOrderId = UUID.randomUUID();
        purchaseOrderItemId = UUID.randomUUID();
        goodsReceiptId = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void goodsEvidenceUsesOnlyInvoiceableQualityReceiptLinesWhenLinesExist() {
        PurchaseOrder purchaseOrder = purchaseOrder();
        stubNoExistingInvoices();
        when(goodsReceiptLineRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByCreatedAtAsc(
            "default", companyId, purchaseOrderId))
            .thenReturn(List.of(
                receiptLine("ACCEPTED", new BigDecimal("2.0000")),
                receiptLine("HOLD", new BigDecimal("7.0000"))
            ));
        when(purchaseOrderItemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("default", purchaseOrderId))
            .thenReturn(List.of(purchaseOrderItem(new BigDecimal("5.0000"))));

        ProcurementInvoiceEvidenceService.EvidenceSummary summary = service().summarize(purchaseOrder);

        assertThat(summary.evidenceAvailable()).isTrue();
        assertThat(summary.evidenceAmount()).isEqualByComparingTo("10.0000");
        assertThat(summary.primarySourceDocumentType()).isEqualTo(ProcurementSourceDocumentType.GOODS_RECEIPT);
        assertThat(summary.primarySourceDocumentId()).isEqualTo(goodsReceiptId);
    }

    @Test
    void heldReceiptLinesDoNotFallBackToHeaderAcceptedAmount() {
        PurchaseOrder purchaseOrder = purchaseOrder();
        stubNoExistingInvoices();
        when(goodsReceiptLineRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByCreatedAtAsc(
            "default", companyId, purchaseOrderId))
            .thenReturn(List.of(receiptLine("PENDING_INSPECTION", new BigDecimal("3.0000"))));
        when(purchaseOrderItemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("default", purchaseOrderId))
            .thenReturn(List.of(purchaseOrderItem(new BigDecimal("5.0000"))));

        assertThatThrownBy(() -> service().validateInvoiceAmount(purchaseOrder, BigDecimal.TEN, null))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Purchase order requires receipt or completion evidence before invoice issuance");
    }

    @Test
    void unresolvedRequiredInspectionHardBlocksOtherwiseAcceptedReceiptEvidence() {
        PurchaseOrder purchaseOrder = purchaseOrder();
        stubNoExistingInvoices();
        when(goodsReceiptLineRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByCreatedAtAsc(
            "default", companyId, purchaseOrderId))
            .thenReturn(List.of(receiptLine("ACCEPTED_WITH_DEVIATION", new BigDecimal("23.0000"))));
        when(purchaseOrderItemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("default", purchaseOrderId))
            .thenReturn(List.of(purchaseOrderItem(new BigDecimal("735000.0000"))));
        QualityInspection inspection = new QualityInspection();
        inspection.setPurchaseOrderItemId(purchaseOrderItemId);
        inspection.setStatus("IN_QUARANTINE");
        when(qualityInspectionRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByCreatedAtAsc(
            "default", companyId, purchaseOrderId)).thenReturn(List.of(inspection));

        ProcurementInvoiceEvidenceService.EvidenceSummary summary = service().summarize(purchaseOrder);

        assertThat(summary.evidenceAvailable()).isFalse();
        assertThat(summary.remainingInvoiceableAmount()).isEqualByComparingTo("0");
        assertThatThrownBy(() -> service().validateInvoiceAmount(purchaseOrder, new BigDecimal("16905000"), null))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Purchase order requires receipt or completion evidence before invoice issuance");
    }

    @Test
    void goodsEvidenceFallsBackToLegacyHeaderOnlyWhenReceiptLinesAreMissing() {
        PurchaseOrder purchaseOrder = purchaseOrder();
        stubNoExistingInvoices();
        GoodsReceipt receipt = new GoodsReceipt();
        receipt.setId(goodsReceiptId);
        receipt.setAcceptedAmount(new BigDecimal("25.0000"));
        when(goodsReceiptLineRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByCreatedAtAsc(
            "default", companyId, purchaseOrderId))
            .thenReturn(List.of());
        when(goodsReceiptRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByCreatedAtDesc(
            "default", companyId, purchaseOrderId))
            .thenReturn(List.of(receipt));

        ProcurementInvoiceEvidenceService.EvidenceSummary summary = service().summarize(purchaseOrder);

        assertThat(summary.evidenceAmount()).isEqualByComparingTo("25.0000");
        assertThat(summary.primarySourceDocumentId()).isEqualTo(goodsReceiptId);
    }

    @Test
    void goodsEvidenceDoesNotTreatPhysicalReceiptAsInvoiceableWhenLineEvidenceIsMissing() {
        PurchaseOrder purchaseOrder = purchaseOrder();
        stubNoExistingInvoices();
        when(goodsReceiptLineRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByCreatedAtAsc(
            "default", companyId, purchaseOrderId))
            .thenReturn(List.of());
        when(goodsReceiptRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByCreatedAtDesc(
            "default", companyId, purchaseOrderId))
            .thenReturn(List.of());
        when(purchaseOrderItemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("default", purchaseOrderId))
            .thenReturn(List.of(purchaseOrderItem(new BigDecimal("5.0000"), new BigDecimal("10.0000"), BigDecimal.ZERO)));

        ProcurementInvoiceEvidenceService.EvidenceSummary summary = service().summarize(purchaseOrder);

        assertThat(summary.evidenceAvailable()).isFalse();
        assertThat(summary.evidenceAmount()).isEqualByComparingTo("0.0000");
        assertThatThrownBy(() -> service().validateInvoiceAmount(purchaseOrder, BigDecimal.TEN, null))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Purchase order requires receipt or completion evidence before invoice issuance");
    }

    @Test
    void recordsInvoiceEvidenceSnapshotOncePerInvoice() {
        PurchaseOrder purchaseOrder = purchaseOrder();
        ContractInvoice invoice = new ContractInvoice();
        invoice.setId(UUID.randomUUID());
        invoice.setTenantId("default");
        invoice.setCompanyId(companyId);
        invoice.setPurchaseOrderId(purchaseOrderId);
        invoice.setCommercialInstrumentType(CommercialInstrumentType.STANDALONE_PO);
        invoice.setStatus(ContractInvoiceStatus.ISSUED.name());
        invoice.setAmount(new BigDecimal("10.0000"));
        invoice.setCurrencyCode("USD");
        stubNoExistingInvoices();
        when(goodsReceiptLineRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByCreatedAtAsc(
            "default", companyId, purchaseOrderId))
            .thenReturn(List.of(receiptLine("ACCEPTED", new BigDecimal("2.0000"))));
        when(purchaseOrderItemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("default", purchaseOrderId))
            .thenReturn(List.of(purchaseOrderItem(new BigDecimal("5.0000"))));
        when(evidenceSnapshotRepository.findByTenantIdAndCompanyIdAndContractInvoiceIdAndIsDeletedFalse(
            "default", companyId, invoice.getId()))
            .thenReturn(Optional.empty());
        when(evidenceSnapshotRepository.save(any(ContractInvoiceEvidenceSnapshot.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        ContractInvoiceEvidenceSnapshot snapshot = service().recordInvoiceEvidenceSnapshot(invoice, purchaseOrder);

        assertThat(snapshot.getContractInvoiceId()).isEqualTo(invoice.getId());
        assertThat(snapshot.getCommercialInstrumentType()).isEqualTo(CommercialInstrumentType.STANDALONE_PO);
        assertThat(snapshot.getRequestType()).isEqualTo(RequestType.GOODS);
        assertThat(snapshot.getSourceDocumentType()).isEqualTo(ProcurementSourceDocumentType.GOODS_RECEIPT);
        assertThat(snapshot.getEvidenceAmount()).isEqualByComparingTo("10.0000");
        assertThat(snapshot.getInvoiceAmount()).isEqualByComparingTo("10.0000");
        assertThat(snapshot.getRemainingInvoiceableAmount()).isEqualByComparingTo("0.0000");
        assertThat(snapshot.getCurrencyCode()).isEqualTo("USD");
    }

    @Test
    void draftInvoicesReserveAcceptedGoodsEvidenceBeforeIssuance() {
        PurchaseOrder purchaseOrder = purchaseOrder();
        ContractInvoice draft = invoice(UUID.randomUUID(), ContractInvoiceStatus.DRAFT, new BigDecimal("8.0000"));
        when(contractInvoiceRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalseOrderByCreatedAtDesc(
            "default", purchaseOrderId))
            .thenReturn(List.of(draft));
        when(goodsReceiptLineRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByCreatedAtAsc(
            "default", companyId, purchaseOrderId))
            .thenReturn(List.of(receiptLine("ACCEPTED", new BigDecimal("2.0000"))));
        when(purchaseOrderItemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("default", purchaseOrderId))
            .thenReturn(List.of(purchaseOrderItem(new BigDecimal("5.0000"))));

        ProcurementInvoiceEvidenceService.EvidenceSummary summary = service().summarize(purchaseOrder);

        assertThat(summary.evidenceAmount()).isEqualByComparingTo("10.0000");
        assertThat(summary.invoicedAmount()).isEqualByComparingTo("0.0000");
        assertThat(summary.remainingInvoiceableAmount()).isEqualByComparingTo("2.0000");
        assertThat(summary.outstandingPayableAmount()).isEqualByComparingTo("0.0000");
        assertThatThrownBy(() -> service().validateInvoiceAmount(purchaseOrder, new BigDecimal("3.0000"), null))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Invoice amount exceeds remaining invoiceable amount for purchase order");
    }

    @Test
    void sharedPurchaseRequestDoesNotCollapseReceiptEvidenceAcrossPurchaseOrders() {
        UUID sharedPurchaseRequestId = UUID.randomUUID();
        UUID oldPurchaseOrderId = UUID.randomUUID();
        PurchaseOrder freshPurchaseOrder = purchaseOrder();
        freshPurchaseOrder.setPurchaseRequestId(sharedPurchaseRequestId);

        when(goodsReceiptLineRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByCreatedAtAsc(
            "default", companyId, purchaseOrderId))
            .thenReturn(List.of(receiptLine("ACCEPTED_WITH_DEVIATION", new BigDecimal("23.0000"))));
        when(purchaseOrderItemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("default", purchaseOrderId))
            .thenReturn(List.of(purchaseOrderItem(new BigDecimal("735000.0000"))));
        when(contractInvoiceRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalseOrderByCreatedAtDesc(
            "default", purchaseOrderId)).thenReturn(List.of());

        ProcurementInvoiceEvidenceService.EvidenceSummary summary = service().summarize(freshPurchaseOrder);

        assertThat(summary.purchaseOrderId()).isEqualTo(purchaseOrderId);
        assertThat(summary.evidenceAmount()).isEqualByComparingTo("16905000.0000");
        assertThat(summary.remainingInvoiceableAmount()).isEqualByComparingTo("16905000.0000");
        service().validateInvoiceAmount(freshPurchaseOrder, new BigDecimal("16905000.0000"), null);
        org.mockito.Mockito.verify(goodsReceiptLineRepository, org.mockito.Mockito.never())
            .findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByCreatedAtAsc(
                "default", companyId, oldPurchaseOrderId);
    }

    @Test
    void fixedAssetPurchaseOrderUsesPostedAssetAcceptanceEvidenceOnly() {
        PurchaseOrder purchaseOrder = fixedAssetPurchaseOrder();
        stubNoExistingInvoices();
        AssetAcceptance acceptance = postedApprovedAcceptance(companyId);
        AssetAcceptanceLine line = assetLine(new BigDecimal("5.0000"), new BigDecimal("100.0000"));
        when(purchaseOrderItemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("default", purchaseOrderId))
            .thenReturn(List.of(fixedAssetItem(new BigDecimal("20.0000"))));
        when(assetAcceptanceRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndApprovalStatusAndPostingStatusAndIsDeletedFalseOrderByCreatedAtAsc(
            "default", companyId, purchaseOrderId, AssetAcceptanceApprovalStatus.APPROVED, AssetAcceptancePostingStatus.POSTED))
            .thenReturn(List.of(acceptance));
        when(assetAcceptanceLineRepository.findByTenantIdAndCompanyIdAndAssetAcceptanceIdAndIsDeletedFalse(
            "default", companyId, acceptance.getId()))
            .thenReturn(List.of(line));
        when(assetAcceptanceUnitRepository.findByTenantIdAndCompanyIdAndAssetAcceptanceLineIdAndIsDeletedFalse(
            "default", companyId, line.getId()))
            .thenReturn(List.of(
                assetUnit(line, AssetAcceptanceUnitQualityDecision.ACCEPTED, UUID.randomUUID()),
                assetUnit(line, AssetAcceptanceUnitQualityDecision.ACCEPTED, UUID.randomUUID()),
                assetUnit(line, AssetAcceptanceUnitQualityDecision.ACCEPTED, UUID.randomUUID()),
                assetUnit(line, AssetAcceptanceUnitQualityDecision.HOLD, null),
                assetUnit(line, AssetAcceptanceUnitQualityDecision.REJECTED, null)
            ));

        ProcurementInvoiceEvidenceService.EvidenceSummary summary = service().summarize(purchaseOrder);

        assertThat(summary.evidenceAvailable()).isTrue();
        assertThat(summary.evidenceAmount()).isEqualByComparingTo("60.0000");
        assertThat(summary.primarySourceDocumentType()).isEqualTo(ProcurementSourceDocumentType.ASSET_ACCEPTANCE);
        assertThat(summary.primarySourceDocumentId()).isEqualTo(acceptance.getId());
    }

    @Test
    void fixedAssetPurchaseOrderRejectsGoodsReceiptFallbackAndUnpostedAcceptance() {
        PurchaseOrder purchaseOrder = fixedAssetPurchaseOrder();
        stubNoExistingInvoices();
        GoodsReceipt receipt = new GoodsReceipt();
        receipt.setId(goodsReceiptId);
        receipt.setAcceptedAmount(new BigDecimal("100.0000"));
        PurchaseOrderItem item = fixedAssetItem(new BigDecimal("20.0000"));
        item.setAcceptedQuantity(new BigDecimal("5.0000"));
        when(purchaseOrderItemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("default", purchaseOrderId))
            .thenReturn(List.of(item));
        when(assetAcceptanceRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndApprovalStatusAndPostingStatusAndIsDeletedFalseOrderByCreatedAtAsc(
            "default", companyId, purchaseOrderId, AssetAcceptanceApprovalStatus.APPROVED, AssetAcceptancePostingStatus.POSTED))
            .thenReturn(List.of());

        ProcurementInvoiceEvidenceService.EvidenceSummary summary = service().summarize(purchaseOrder);

        assertThat(summary.evidenceAvailable()).isFalse();
        assertThat(summary.evidenceAmount()).isEqualByComparingTo("0.0000");
        assertThat(summary.primarySourceDocumentType()).isEqualTo(ProcurementSourceDocumentType.ASSET_ACCEPTANCE);
        assertThat(summary.primarySourceDocumentId()).isNull();
        assertThatThrownBy(() -> service().validateInvoiceAmount(purchaseOrder, BigDecimal.TEN, null))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Fixed asset purchase order requires posted asset acceptance evidence before invoice issuance");
        verifyNoInteractions(
            goodsReceiptRepository,
            goodsReceiptLineRepository,
            serviceAcceptanceActRepository,
            workCompletionActRepository
        );
    }

    @Test
    void fixedAssetEvidenceFromAnotherCompanyIsNotSubstitutable() {
        PurchaseOrder purchaseOrder = fixedAssetPurchaseOrder();
        stubNoExistingInvoices();
        UUID otherCompanyId = UUID.randomUUID();
        AssetAcceptance otherCompanyAcceptance = postedApprovedAcceptance(otherCompanyId);
        when(purchaseOrderItemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("default", purchaseOrderId))
            .thenReturn(List.of(fixedAssetItem(new BigDecimal("20.0000"))));
        when(assetAcceptanceRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndApprovalStatusAndPostingStatusAndIsDeletedFalseOrderByCreatedAtAsc(
            "default", companyId, purchaseOrderId, AssetAcceptanceApprovalStatus.APPROVED, AssetAcceptancePostingStatus.POSTED))
            .thenReturn(List.of());

        ProcurementInvoiceEvidenceService.EvidenceSummary summary = service().summarize(purchaseOrder);

        assertThat(summary.evidenceAvailable()).isFalse();
        assertThat(summary.primarySourceDocumentType()).isEqualTo(ProcurementSourceDocumentType.ASSET_ACCEPTANCE);
        assertThat(summary.primarySourceDocumentId()).isNull();
        org.mockito.Mockito.verify(assetAcceptanceRepository, org.mockito.Mockito.never())
            .findByTenantIdAndCompanyIdAndPurchaseOrderIdAndApprovalStatusAndPostingStatusAndIsDeletedFalseOrderByCreatedAtAsc(
                "default", otherCompanyId, purchaseOrderId, AssetAcceptanceApprovalStatus.APPROVED, AssetAcceptancePostingStatus.POSTED);
    }

    @Test
    void fixedAssetInvoiceSnapshotRecordsAssetAcceptanceEvidenceOnce() {
        PurchaseOrder purchaseOrder = fixedAssetPurchaseOrder();
        ContractInvoice invoice = invoice(UUID.randomUUID(), ContractInvoiceStatus.ISSUED, new BigDecimal("60.0000"));
        invoice.setCompanyId(companyId);
        invoice.setCommercialInstrumentType(CommercialInstrumentType.STANDALONE_PO);
        invoice.setCurrencyCode("USD");
        AssetAcceptance acceptance = postedApprovedAcceptance(companyId);
        AssetAcceptanceLine line = assetLine(new BigDecimal("3.0000"), new BigDecimal("60.0000"));
        stubNoExistingInvoices();
        when(purchaseOrderItemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("default", purchaseOrderId))
            .thenReturn(List.of(fixedAssetItem(new BigDecimal("20.0000"))));
        when(assetAcceptanceRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndApprovalStatusAndPostingStatusAndIsDeletedFalseOrderByCreatedAtAsc(
            "default", companyId, purchaseOrderId, AssetAcceptanceApprovalStatus.APPROVED, AssetAcceptancePostingStatus.POSTED))
            .thenReturn(List.of(acceptance));
        when(assetAcceptanceLineRepository.findByTenantIdAndCompanyIdAndAssetAcceptanceIdAndIsDeletedFalse(
            "default", companyId, acceptance.getId()))
            .thenReturn(List.of(line));
        when(assetAcceptanceUnitRepository.findByTenantIdAndCompanyIdAndAssetAcceptanceLineIdAndIsDeletedFalse(
            "default", companyId, line.getId()))
            .thenReturn(List.of(
                assetUnit(line, AssetAcceptanceUnitQualityDecision.ACCEPTED, UUID.randomUUID()),
                assetUnit(line, AssetAcceptanceUnitQualityDecision.ACCEPTED, UUID.randomUUID()),
                assetUnit(line, AssetAcceptanceUnitQualityDecision.ACCEPTED, UUID.randomUUID())
            ));
        ContractInvoiceEvidenceSnapshot[] storedSnapshot = new ContractInvoiceEvidenceSnapshot[1];
        when(evidenceSnapshotRepository.findByTenantIdAndCompanyIdAndContractInvoiceIdAndIsDeletedFalse(
            "default", companyId, invoice.getId()))
            .thenAnswer(invocation -> Optional.ofNullable(storedSnapshot[0]));
        when(evidenceSnapshotRepository.save(any(ContractInvoiceEvidenceSnapshot.class)))
            .thenAnswer(invocation -> {
                storedSnapshot[0] = invocation.getArgument(0);
                return storedSnapshot[0];
            });

        ContractInvoiceEvidenceSnapshot snapshot = service().recordInvoiceEvidenceSnapshot(invoice, purchaseOrder);
        ContractInvoiceEvidenceSnapshot retry = service().recordInvoiceEvidenceSnapshot(invoice, purchaseOrder);

        assertThat(snapshot.getSourceDocumentType()).isEqualTo(ProcurementSourceDocumentType.ASSET_ACCEPTANCE);
        assertThat(snapshot.getSourceDocumentId()).isEqualTo(acceptance.getId());
        assertThat(snapshot.getEvidenceAmount()).isEqualByComparingTo("60.0000");
        assertThat(retry).isSameAs(snapshot);
    }

    private void stubNoExistingInvoices() {
        when(contractInvoiceRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalseOrderByCreatedAtDesc(
            "default", purchaseOrderId))
            .thenReturn(List.of());
    }

    private PurchaseOrder purchaseOrder() {
        PurchaseOrder purchaseOrder = new PurchaseOrder();
        purchaseOrder.setId(purchaseOrderId);
        purchaseOrder.setTenantId("default");
        purchaseOrder.setCompanyId(companyId);
        purchaseOrder.setRequestType(RequestType.GOODS);
        purchaseOrder.setCurrencyCode("USD");
        return purchaseOrder;
    }

    private PurchaseOrder fixedAssetPurchaseOrder() {
        PurchaseOrder purchaseOrder = purchaseOrder();
        purchaseOrder.setRequestType(RequestType.GOODS);
        return purchaseOrder;
    }

    private PurchaseOrderItem purchaseOrderItem(BigDecimal unitPrice) {
        return purchaseOrderItem(unitPrice, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private PurchaseOrderItem purchaseOrderItem(BigDecimal unitPrice, BigDecimal receivedQuantity, BigDecimal acceptedQuantity) {
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setId(purchaseOrderItemId);
        item.setPurchaseOrderId(purchaseOrderId);
        item.setUnitPrice(unitPrice);
        item.setReceivedQuantity(receivedQuantity);
        item.setAcceptedQuantity(acceptedQuantity);
        return item;
    }

    private PurchaseOrderItem fixedAssetItem(BigDecimal unitPrice) {
        PurchaseOrderItem item = purchaseOrderItem(unitPrice);
        item.setLineType(ProcurementLineType.MATERIAL);
        item.setMaterialTypeSnapshot(MaterialType.FIXED_ASSET);
        item.setQuantity(new BigDecimal("5.0000"));
        return item;
    }

    private AssetAcceptance postedApprovedAcceptance(UUID acceptanceCompanyId) {
        AssetAcceptance acceptance = new AssetAcceptance();
        acceptance.setId(UUID.randomUUID());
        acceptance.setTenantId("default");
        acceptance.setCompanyId(acceptanceCompanyId);
        acceptance.setPurchaseOrderId(purchaseOrderId);
        acceptance.setApprovalStatus(AssetAcceptanceApprovalStatus.APPROVED);
        acceptance.setPostingStatus(AssetAcceptancePostingStatus.POSTED);
        acceptance.setCurrencyCode("USD");
        acceptance.setExchangeRate(new BigDecimal("1.000000"));
        acceptance.setExchangeRateSource("PO");
        return acceptance;
    }

    private AssetAcceptanceLine assetLine(BigDecimal deliveredQuantity, BigDecimal acceptedValue) {
        AssetAcceptanceLine line = new AssetAcceptanceLine();
        line.setId(UUID.randomUUID());
        line.setTenantId("default");
        line.setCompanyId(companyId);
        line.setAssetAcceptanceId(UUID.randomUUID());
        line.setPurchaseOrderItemId(purchaseOrderItemId);
        line.setMaterialId(UUID.randomUUID());
        line.setMaterialTypeSnapshot(MaterialType.FIXED_ASSET);
        line.setDeliveredQuantity(deliveredQuantity);
        line.setAcceptedQuantity(BigDecimal.ZERO);
        line.setAcceptedValue(acceptedValue);
        line.setAcceptedCurrencyCode("USD");
        return line;
    }

    private AssetAcceptanceUnit assetUnit(
        AssetAcceptanceLine line,
        AssetAcceptanceUnitQualityDecision decision,
        UUID postedFixedAssetId
    ) {
        AssetAcceptanceUnit unit = new AssetAcceptanceUnit();
        unit.setId(UUID.randomUUID());
        unit.setTenantId("default");
        unit.setCompanyId(companyId);
        unit.setAssetAcceptanceLineId(line.getId());
        unit.setPurchaseOrderItemId(purchaseOrderItemId);
        unit.setMaterialId(line.getMaterialId());
        unit.setMaterialTypeSnapshot(MaterialType.FIXED_ASSET);
        unit.setQualityDecision(decision);
        unit.setPostedFixedAssetId(postedFixedAssetId);
        return unit;
    }

    private GoodsReceiptLine receiptLine(String qualityStatus, BigDecimal acceptedQuantity) {
        GoodsReceiptLine line = new GoodsReceiptLine();
        line.setGoodsReceiptId(goodsReceiptId);
        line.setPurchaseOrderId(purchaseOrderId);
        line.setPurchaseOrderItemId(purchaseOrderItemId);
        line.setAcceptedQuantity(acceptedQuantity);
        line.setQualityStatus(qualityStatus);
        return line;
    }

    private ContractInvoice invoice(UUID id, ContractInvoiceStatus status, BigDecimal amount) {
        ContractInvoice invoice = new ContractInvoice();
        invoice.setId(id);
        invoice.setTenantId("default");
        invoice.setCompanyId(companyId);
        invoice.setPurchaseOrderId(purchaseOrderId);
        invoice.setStatus(status.name());
        invoice.setAmount(amount);
        return invoice;
    }

    private ProcurementInvoiceEvidenceService service() {
        return new ProcurementInvoiceEvidenceService(
            goodsReceiptRepository,
            goodsReceiptLineRepository,
            purchaseOrderItemRepository,
            serviceAcceptanceActRepository,
            workCompletionActRepository,
            contractInvoiceRepository,
            contractPaymentRepository,
            evidenceSnapshotRepository,
            qualityInspectionRepository,
            new CommercialInstrumentTypeResolver(awardOutcomeRepository),
            assetAcceptanceRepository,
            assetAcceptanceLineRepository,
            assetAcceptanceUnitRepository
        );
    }
}
