package com.enterprise.mm.service;

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
import com.enterprise.mm.entity.ServiceAcceptanceAct;
import com.enterprise.mm.entity.WorkCompletionAct;
import com.enterprise.mm.enums.AssetAcceptanceApprovalStatus;
import com.enterprise.mm.enums.AssetAcceptancePostingStatus;
import com.enterprise.mm.enums.AssetAcceptanceUnitQualityDecision;
import com.enterprise.mm.enums.CommercialInstrumentType;
import com.enterprise.mm.enums.ContractInvoiceStatus;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.ProcurementSourceDocumentType;
import com.enterprise.mm.enums.QualityInspectionStatus;
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
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.QualityInspectionRepository;
import com.enterprise.mm.repository.ServiceAcceptanceActRepository;
import com.enterprise.mm.repository.WorkCompletionActRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProcurementInvoiceEvidenceService {
    private static final List<String> ACCEPTED_SERVICE_ACT_STATUSES = List.of("APPROVED", "SENT_TO_PAYMENT", "PAID");
    private static final List<String> ACCEPTED_WORK_ACT_STATUSES = List.of("APPROVED", "SENT_TO_PAYMENT", "PAID");
    private static final List<String> INVOICED_STATUSES = List.of(
        ContractInvoiceStatus.ISSUED.name(),
        ContractInvoiceStatus.PARTIALLY_PAID.name(),
        ContractInvoiceStatus.PAID.name()
    );
    private static final List<String> RESERVED_INVOICE_STATUSES = List.of(
        ContractInvoiceStatus.DRAFT.name(),
        ContractInvoiceStatus.ISSUED.name(),
        ContractInvoiceStatus.PARTIALLY_PAID.name(),
        ContractInvoiceStatus.PAID.name()
    );

    private final GoodsReceiptRepository goodsReceiptRepository;
    private final GoodsReceiptLineRepository goodsReceiptLineRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final ServiceAcceptanceActRepository serviceAcceptanceActRepository;
    private final WorkCompletionActRepository workCompletionActRepository;
    private final ContractInvoiceRepository contractInvoiceRepository;
    private final ContractPaymentRepository contractPaymentRepository;
    private final ContractInvoiceEvidenceSnapshotRepository evidenceSnapshotRepository;
    private final QualityInspectionRepository qualityInspectionRepository;
    private final CommercialInstrumentTypeResolver commercialInstrumentTypeResolver;
    private final AssetAcceptanceRepository assetAcceptanceRepository;
    private final AssetAcceptanceLineRepository assetAcceptanceLineRepository;
    private final AssetAcceptanceUnitRepository assetAcceptanceUnitRepository;

    public record EvidenceSummary(
        UUID purchaseOrderId,
        RequestType requestType,
        boolean evidenceAvailable,
        BigDecimal evidenceAmount,
        BigDecimal invoicedAmount,
        BigDecimal remainingInvoiceableAmount,
        BigDecimal paidAmount,
        BigDecimal outstandingPayableAmount,
        ProcurementSourceDocumentType primarySourceDocumentType,
        UUID primarySourceDocumentId
    ) {
    }

    @Transactional(readOnly = true)
    public EvidenceSummary summarize(PurchaseOrder purchaseOrder) {
        RequestType requestType = purchaseOrder.getRequestType() == null ? RequestType.GOODS : purchaseOrder.getRequestType();
        EvidenceAmount evidenceAmount = resolveEvidenceAmount(purchaseOrder, requestType);
        BigDecimal invoicedAmount = sumInvoicedAmount(purchaseOrder.getId(), null);
        BigDecimal reservedAmount = sumReservedInvoiceAmount(purchaseOrder.getId(), null);
        BigDecimal paidAmount = sumPaidAmount(purchaseOrder.getId());
        BigDecimal remainingInvoiceable = evidenceAmount.amount().subtract(reservedAmount);
        if (remainingInvoiceable.signum() < 0) {
            remainingInvoiceable = BigDecimal.ZERO;
        }
        BigDecimal outstandingPayable = invoicedAmount.subtract(paidAmount);
        if (outstandingPayable.signum() < 0) {
            outstandingPayable = BigDecimal.ZERO;
        }
        return new EvidenceSummary(
            purchaseOrder.getId(),
            requestType,
            evidenceAmount.amount().signum() > 0,
            evidenceAmount.amount(),
            invoicedAmount,
            remainingInvoiceable,
            paidAmount,
            outstandingPayable,
            evidenceAmount.sourceType(),
            evidenceAmount.sourceId()
        );
    }

    @Transactional(readOnly = true)
    public boolean hasEvidence(PurchaseOrder purchaseOrder) {
        return summarize(purchaseOrder).evidenceAvailable();
    }

    @Transactional(readOnly = true)
    public void validateInvoiceAmount(PurchaseOrder purchaseOrder, BigDecimal invoiceAmount, UUID excludeInvoiceId) {
        if (invoiceAmount == null || invoiceAmount.signum() <= 0) {
            throw new BusinessException("Invoice amount must be greater than zero");
        }
        EvidenceSummary summary = summarize(purchaseOrder);
        if (!summary.evidenceAvailable()) {
            if (summary.primarySourceDocumentType() == ProcurementSourceDocumentType.ASSET_ACCEPTANCE) {
                throw new BusinessException("Fixed asset purchase order requires posted asset acceptance evidence before invoice issuance");
            }
            throw new BusinessException("Purchase order requires receipt or completion evidence before invoice issuance");
        }
        BigDecimal alreadyReserved = sumReservedInvoiceAmount(purchaseOrder.getId(), excludeInvoiceId);
        BigDecimal remaining = summary.evidenceAmount().subtract(alreadyReserved);
        if (invoiceAmount.compareTo(remaining) > 0) {
            throw new BusinessException("Invoice amount exceeds remaining invoiceable amount for purchase order");
        }
    }

    @Transactional
    public ContractInvoiceEvidenceSnapshot recordInvoiceEvidenceSnapshot(
        ContractInvoice invoice,
        PurchaseOrder purchaseOrder
    ) {
        if (invoice == null || invoice.getId() == null || purchaseOrder == null || purchaseOrder.getId() == null) {
            return null;
        }
        return evidenceSnapshotRepository
            .findByTenantIdAndCompanyIdAndContractInvoiceIdAndIsDeletedFalse(
                invoice.getTenantId(),
                invoice.getCompanyId(),
                invoice.getId()
            )
            .orElseGet(() -> createEvidenceSnapshot(invoice, purchaseOrder));
    }

    private BigDecimal sumInvoicedAmount(UUID purchaseOrderId, UUID excludeInvoiceId) {
        return contractInvoiceRepository
            .findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalseOrderByCreatedAtDesc(TenantContext.currentTenant(), purchaseOrderId)
            .stream()
            .filter(invoice -> excludeInvoiceId == null || !Objects.equals(invoice.getId(), excludeInvoiceId))
            .filter(invoice -> INVOICED_STATUSES.contains(normalizedStatus(invoice.getStatus())))
            .map(invoice -> invoice.getAmount() == null ? BigDecimal.ZERO : invoice.getAmount())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal sumReservedInvoiceAmount(UUID purchaseOrderId, UUID excludeInvoiceId) {
        return contractInvoiceRepository
            .findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalseOrderByCreatedAtDesc(TenantContext.currentTenant(), purchaseOrderId)
            .stream()
            .filter(invoice -> excludeInvoiceId == null || !Objects.equals(invoice.getId(), excludeInvoiceId))
            .filter(invoice -> RESERVED_INVOICE_STATUSES.contains(normalizedStatus(invoice.getStatus())))
            .map(invoice -> invoice.getAmount() == null ? BigDecimal.ZERO : invoice.getAmount())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal sumPaidAmount(UUID purchaseOrderId) {
        return contractInvoiceRepository
            .findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalseOrderByCreatedAtDesc(TenantContext.currentTenant(), purchaseOrderId)
            .stream()
            .map(invoice -> invoice.getPaidAmount() == null ? BigDecimal.ZERO : invoice.getPaidAmount())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private EvidenceAmount resolveEvidenceAmount(PurchaseOrder purchaseOrder, RequestType requestType) {
        List<PurchaseOrderItem> items = purchaseOrderItems(purchaseOrder);
        if (hasFixedAssetLines(items)) {
            return assetAcceptanceEvidence(purchaseOrder, items);
        }
        if (hasWorkLines(purchaseOrder)) {
            return worksEvidence(purchaseOrder);
        }
        return switch (requestType) {
            case GOODS -> goodsEvidence(purchaseOrder);
            case SERVICE -> serviceEvidence(purchaseOrder);
        };
    }

    private boolean hasWorkLines(PurchaseOrder purchaseOrder) {
        if (purchaseOrder == null || purchaseOrder.getId() == null) {
            return false;
        }
        return purchaseOrderItems(purchaseOrder)
            .stream()
            .anyMatch(item -> item.getLineType() == ProcurementLineType.WORK);
    }

    private boolean hasFixedAssetLines(List<PurchaseOrderItem> items) {
        return items.stream()
            .anyMatch(item -> item.getLineType() == ProcurementLineType.MATERIAL
                && item.getMaterialTypeSnapshot() == MaterialType.FIXED_ASSET);
    }

    private List<PurchaseOrderItem> purchaseOrderItems(PurchaseOrder purchaseOrder) {
        if (purchaseOrder == null || purchaseOrder.getId() == null) {
            return List.of();
        }
        return purchaseOrderItemRepository
            .findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(TenantContext.currentTenant(), purchaseOrder.getId());
    }

    private EvidenceAmount assetAcceptanceEvidence(PurchaseOrder purchaseOrder, List<PurchaseOrderItem> purchaseOrderItems) {
        Map<UUID, PurchaseOrderItem> fixedAssetItemsById = purchaseOrderItems.stream()
            .filter(item -> item.getLineType() == ProcurementLineType.MATERIAL)
            .filter(item -> item.getMaterialTypeSnapshot() == MaterialType.FIXED_ASSET)
            .collect(Collectors.toMap(PurchaseOrderItem::getId, Function.identity(), (first, second) -> first));
        if (fixedAssetItemsById.isEmpty()) {
            return EvidenceAmount.empty(ProcurementSourceDocumentType.ASSET_ACCEPTANCE);
        }
        List<AssetAcceptance> acceptances = assetAcceptanceRepository
            .findByTenantIdAndCompanyIdAndPurchaseOrderIdAndApprovalStatusAndPostingStatusAndIsDeletedFalseOrderByCreatedAtAsc(
                TenantContext.currentTenant(),
                purchaseOrder.getCompanyId(),
                purchaseOrder.getId(),
                AssetAcceptanceApprovalStatus.APPROVED,
                AssetAcceptancePostingStatus.POSTED
            );
        BigDecimal amount = BigDecimal.ZERO;
        UUID sourceId = null;
        String currencyCode = null;
        BigDecimal exchangeRate = null;
        String exchangeRateSource = null;
        for (AssetAcceptance acceptance : acceptances) {
            for (AssetAcceptanceLine line : assetAcceptanceLineRepository.findByTenantIdAndCompanyIdAndAssetAcceptanceIdAndIsDeletedFalse(
                TenantContext.currentTenant(), purchaseOrder.getCompanyId(), acceptance.getId())) {
                if (line.getMaterialTypeSnapshot() != MaterialType.FIXED_ASSET
                    || !fixedAssetItemsById.containsKey(line.getPurchaseOrderItemId())) {
                    continue;
                }
                long eligibleUnits = assetAcceptanceUnitRepository
                    .findByTenantIdAndCompanyIdAndAssetAcceptanceLineIdAndIsDeletedFalse(
                        TenantContext.currentTenant(), purchaseOrder.getCompanyId(), line.getId())
                    .stream()
                    .filter(unit -> unit.getQualityDecision() == AssetAcceptanceUnitQualityDecision.ACCEPTED)
                    .filter(unit -> unit.getPostedFixedAssetId() != null)
                    .count();
                if (eligibleUnits <= 0) {
                    continue;
                }
                BigDecimal lineAmount = assetAcceptedAmount(line, fixedAssetItemsById.get(line.getPurchaseOrderItemId()), eligibleUnits);
                if (lineAmount.signum() <= 0) {
                    continue;
                }
                amount = amount.add(lineAmount);
                if (sourceId == null) {
                    sourceId = acceptance.getId();
                    currencyCode = firstNonBlank(line.getAcceptedCurrencyCode(), acceptance.getCurrencyCode(), purchaseOrder.getCurrencyCode());
                    exchangeRate = acceptance.getExchangeRate();
                    exchangeRateSource = acceptance.getExchangeRateSource();
                }
            }
        }
        return new EvidenceAmount(
            amount,
            ProcurementSourceDocumentType.ASSET_ACCEPTANCE,
            sourceId,
            currencyCode,
            exchangeRate,
            exchangeRateSource,
            "ASSET_ACCEPTANCE"
        );
    }

    private BigDecimal assetAcceptedAmount(AssetAcceptanceLine line, PurchaseOrderItem item, long eligibleUnits) {
        BigDecimal delivered = line.getDeliveredQuantity() == null ? BigDecimal.ZERO : line.getDeliveredQuantity();
        if (line.getAcceptedValue() != null && delivered.signum() > 0) {
            return line.getAcceptedValue()
                .multiply(BigDecimal.valueOf(eligibleUnits))
                .divide(delivered, 4, java.math.RoundingMode.HALF_UP);
        }
        BigDecimal unitPrice = item == null || item.getUnitPrice() == null ? BigDecimal.ZERO : item.getUnitPrice();
        return unitPrice.multiply(BigDecimal.valueOf(eligibleUnits));
    }

    private EvidenceAmount goodsEvidence(PurchaseOrder purchaseOrder) {
        List<GoodsReceiptLine> lines = goodsReceiptLineRepository
            .findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByCreatedAtAsc(
                TenantContext.currentTenant(), purchaseOrder.getCompanyId(), purchaseOrder.getId());
        if (!lines.isEmpty()) {
            Map<UUID, PurchaseOrderItem> itemsById = purchaseOrderItemRepository
                .findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(TenantContext.currentTenant(), purchaseOrder.getId())
                .stream()
                .collect(Collectors.toMap(PurchaseOrderItem::getId, Function.identity(), (first, second) -> first));
            List<UUID> openInspectionItemIds = qualityInspectionRepository
                .findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByCreatedAtAsc(
                    TenantContext.currentTenant(), purchaseOrder.getCompanyId(), purchaseOrder.getId())
                .stream()
                .filter(this::isOpenInspection)
                .map(QualityInspection::getPurchaseOrderItemId)
                .filter(Objects::nonNull)
                .toList();
            BigDecimal amount = lines.stream()
                .filter(line -> isInvoiceableQualityLine(line, openInspectionItemIds))
                .map(line -> receiptLineAmount(line, itemsById.get(line.getPurchaseOrderItemId())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            UUID sourceId = lines.stream()
                .filter(line -> isInvoiceableQualityLine(line, openInspectionItemIds))
                .map(GoodsReceiptLine::getGoodsReceiptId)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
            return new EvidenceAmount(amount, ProcurementSourceDocumentType.GOODS_RECEIPT, sourceId);
        }
        List<GoodsReceipt> receipts = goodsReceiptRepository
            .findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(), purchaseOrder.getCompanyId(), purchaseOrder.getId());
        BigDecimal amount = receipts.stream()
            .map(GoodsReceipt::getAcceptedAmount)
            .filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (amount.signum() <= 0) {
            amount = purchaseOrderItemRepository
                .findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(TenantContext.currentTenant(), purchaseOrder.getId())
                .stream()
                .map(this::acceptedLineAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
        UUID sourceId = receipts.isEmpty() ? null : receipts.get(0).getId();
        return new EvidenceAmount(amount, ProcurementSourceDocumentType.GOODS_RECEIPT, sourceId);
    }

    private EvidenceAmount serviceEvidence(PurchaseOrder purchaseOrder) {
        List<ServiceAcceptanceAct> acts = serviceAcceptanceActRepository
            .findByTenantIdAndPurchaseOrderIdAndStatusInAndIsDeletedFalse(
                TenantContext.currentTenant(), purchaseOrder.getId(), ACCEPTED_SERVICE_ACT_STATUSES);
        BigDecimal amount = acts.stream()
            .map(ServiceAcceptanceAct::getAmount)
            .filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        UUID sourceId = acts.isEmpty() ? null : acts.get(0).getId();
        return new EvidenceAmount(amount, ProcurementSourceDocumentType.SERVICE_ACCEPTANCE_ACT, sourceId);
    }

    private EvidenceAmount worksEvidence(PurchaseOrder purchaseOrder) {
        List<WorkCompletionAct> acts = workCompletionActRepository
            .findByTenantIdAndPurchaseOrderIdAndStatusInAndIsDeletedFalseOrderByCreatedAtAsc(
                TenantContext.currentTenant(), purchaseOrder.getId(), ACCEPTED_WORK_ACT_STATUSES);
        BigDecimal amount = acts.stream()
            .map(WorkCompletionAct::getAmount)
            .filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        UUID sourceId = acts.isEmpty() ? null : acts.get(0).getId();
        return new EvidenceAmount(amount, ProcurementSourceDocumentType.WORK_COMPLETION_ACT, sourceId);
    }

    private BigDecimal acceptedLineAmount(PurchaseOrderItem item) {
        BigDecimal accepted = item.getAcceptedQuantity() == null ? BigDecimal.ZERO : item.getAcceptedQuantity();
        BigDecimal unitPrice = item.getUnitPrice() == null ? BigDecimal.ZERO : item.getUnitPrice();
        return accepted.multiply(unitPrice);
    }

    private BigDecimal receiptLineAmount(GoodsReceiptLine line, PurchaseOrderItem item) {
        BigDecimal accepted = line.getAcceptedQuantity() == null ? BigDecimal.ZERO : line.getAcceptedQuantity();
        BigDecimal unitPrice = item == null || item.getUnitPrice() == null ? BigDecimal.ZERO : item.getUnitPrice();
        return accepted.multiply(unitPrice);
    }

    private boolean isInvoiceableQualityLine(GoodsReceiptLine line, List<UUID> openInspectionItemIds) {
        if (line.getAcceptedQuantity() == null || line.getAcceptedQuantity().signum() <= 0) {
            return false;
        }
        if (line.getPurchaseOrderItemId() != null && openInspectionItemIds.contains(line.getPurchaseOrderItemId())) {
            return false;
        }
        String qualityStatus = normalizedStatus(line.getQualityStatus());
        if (qualityStatus.isEmpty()
            || "ACCEPTED".equals(qualityStatus)
            || "ACCEPTED_WITH_DEVIATION".equals(qualityStatus)
            || "PASSED".equals(qualityStatus)
            || "RELEASED".equals(qualityStatus)
            || "NOT_REQUIRED".equals(qualityStatus)) {
            return true;
        }
        return false;
    }

    private boolean isOpenInspection(QualityInspection inspection) {
        String status = normalizedStatus(inspection.getStatus());
        return !QualityInspectionStatus.RELEASED.name().equals(status)
            && !QualityInspectionStatus.PASSED.name().equals(status)
            && !QualityInspectionStatus.REJECTED.name().equals(status)
            && !QualityInspectionStatus.CANCELLED.name().equals(status);
    }

    private ContractInvoiceEvidenceSnapshot createEvidenceSnapshot(
        ContractInvoice invoice,
        PurchaseOrder purchaseOrder
    ) {
        RequestType requestType = purchaseOrder.getRequestType() == null ? RequestType.GOODS : purchaseOrder.getRequestType();
        EvidenceAmount evidenceAmount = resolveEvidenceAmount(purchaseOrder, requestType);
        BigDecimal invoiceAmount = invoice.getAmount() == null ? BigDecimal.ZERO : invoice.getAmount();
        BigDecimal invoicedBefore = sumInvoicedAmount(purchaseOrder.getId(), invoice.getId());
        BigDecimal reservedBefore = sumReservedInvoiceAmount(purchaseOrder.getId(), invoice.getId());
        BigDecimal remaining = evidenceAmount.amount().subtract(reservedBefore).subtract(invoiceAmount);
        if (remaining.signum() < 0) {
            remaining = BigDecimal.ZERO;
        }

        ContractInvoiceEvidenceSnapshot snapshot = new ContractInvoiceEvidenceSnapshot();
        snapshot.setTenantId(invoice.getTenantId());
        snapshot.setCompanyId(invoice.getCompanyId());
        snapshot.setCode("CIES-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        snapshot.setName("Invoice evidence snapshot");
        snapshot.setStatus("RECORDED");
        snapshot.setContractInvoiceId(invoice.getId());
        snapshot.setPurchaseOrderId(purchaseOrder.getId());
        snapshot.setCommercialInstrumentType(resolveCommercialInstrumentType(invoice));
        snapshot.setRequestType(requestType);
        snapshot.setSourceDocumentType(evidenceAmount.sourceType());
        snapshot.setSourceDocumentId(evidenceAmount.sourceId());
        snapshot.setEvidenceAmount(evidenceAmount.amount());
        snapshot.setEvidenceCurrencyCode(firstNonBlank(evidenceAmount.currencyCode(), purchaseOrder.getCurrencyCode()));
        snapshot.setEvidenceExchangeRate(evidenceAmount.exchangeRate());
        snapshot.setEvidenceExchangeRateSource(evidenceAmount.exchangeRateSource());
        snapshot.setAcquisitionEvidenceType(evidenceAmount.acquisitionEvidenceType());
        snapshot.setInvoicedAmountBefore(invoicedBefore);
        snapshot.setInvoiceAmount(invoiceAmount);
        snapshot.setRemainingInvoiceableAmount(remaining);
        snapshot.setCurrencyCode(normalizedCurrency(invoice.getCurrencyCode()));
        snapshot.setInvoiceStatusSnapshot(invoice.getStatus());
        snapshot.setRecordedAt(LocalDateTime.now());
        return evidenceSnapshotRepository.save(snapshot);
    }

    private CommercialInstrumentType resolveCommercialInstrumentType(ContractInvoice invoice) {
        return commercialInstrumentTypeResolver.resolve(
            invoice.getCommercialInstrumentType(),
            invoice.getContractId(),
            invoice.getPurchaseOrderId()
        );
    }

    private String normalizedCurrency(String value) {
        return value == null || value.isBlank() ? "UZS" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizedStatus(String status) {
        return status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private record EvidenceAmount(
        BigDecimal amount,
        ProcurementSourceDocumentType sourceType,
        UUID sourceId,
        String currencyCode,
        BigDecimal exchangeRate,
        String exchangeRateSource,
        String acquisitionEvidenceType
    ) {
        private EvidenceAmount {
            amount = amount == null ? BigDecimal.ZERO : amount;
        }

        private EvidenceAmount(
            BigDecimal amount,
            ProcurementSourceDocumentType sourceType,
            UUID sourceId
        ) {
            this(amount, sourceType, sourceId, null, null, null, null);
        }

        private static EvidenceAmount empty(ProcurementSourceDocumentType sourceType) {
            return new EvidenceAmount(BigDecimal.ZERO, sourceType, null);
        }
    }
}
