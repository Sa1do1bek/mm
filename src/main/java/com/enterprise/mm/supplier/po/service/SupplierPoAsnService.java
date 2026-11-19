package com.enterprise.mm.supplier.po.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AdvanceShippingNotice;
import com.enterprise.mm.entity.AdvanceShippingNoticeBatch;
import com.enterprise.mm.entity.AdvanceShippingNoticeItem;
import com.enterprise.mm.entity.AdvanceShippingNoticeSerial;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.SupplierPoCommitment;
import com.enterprise.mm.enums.AdvanceShippingNoticeStatus;
import com.enterprise.mm.enums.BatchTrackingPolicy;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.enums.SerialTrackingPolicy;
import com.enterprise.mm.enums.TrackingMode;
import com.enterprise.mm.repository.AdvanceShippingNoticeBatchRepository;
import com.enterprise.mm.repository.AdvanceShippingNoticeItemRepository;
import com.enterprise.mm.repository.AdvanceShippingNoticeRepository;
import com.enterprise.mm.repository.AdvanceShippingNoticeSerialRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.SupplierPoCommitmentRepository;
import com.enterprise.mm.enums.NotificationEntityType;
import com.enterprise.mm.enums.RoleName;
import com.enterprise.mm.service.AuditService;
import com.enterprise.mm.service.IdempotencyService;
import com.enterprise.mm.service.NotificationService;
import com.enterprise.mm.util.BusinessNameGenerator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ASN-from-commitment: supplier-portal draft/submit lifecycle for an
 * {@link AdvanceShippingNotice} whose lines each fulfil one confirmed
 * {@link SupplierPoCommitment}. Quantity per commitment is capped at
 * {@code confirmedQuantity - sum(quantity across submitted ASN item rows
 * referencing that commitment)} -- a real query
 * ({@link AdvanceShippingNoticeItemRepository#sumActiveQuantityForCommitment}), not an
 * approximation. Header fields are limited to what the entity actually supports
 * (batch/serial/expiry/package are intentionally exposed since the entity has them;
 * see the controller-facing DTOs for exactly what is surfaced to the frontend).
 */
@Service
@RequiredArgsConstructor
public class SupplierPoAsnService {
    public static final String SERVICE_OR_WORK_PO_DOES_NOT_USE_SHIPMENT = "SERVICE_OR_WORK_PO_DOES_NOT_USE_SHIPMENT";

    private final AdvanceShippingNoticeRepository asnRepository;
    private final AdvanceShippingNoticeItemRepository asnItemRepository;
    private final AdvanceShippingNoticeBatchRepository asnBatchRepository;
    private final AdvanceShippingNoticeSerialRepository asnSerialRepository;
    private final SupplierPoCommitmentRepository commitmentRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final IdempotencyService idempotencyService;
    private final AuditService auditService;
    private final NotificationService notificationService;

    public record AsnBatchCommand(
        String batchNumber,
        BigDecimal quantity,
        LocalDate expirationDate
    ) {
    }

    public record AsnSerialCommand(
        String manufacturerSerialNumber,
        String batchNumber
    ) {
    }

    public record AsnLineCommand(
        UUID supplierPoCommitmentId,
        BigDecimal quantity,
        BigDecimal packageCount,
        String packageType,
        BigDecimal grossWeight,
        BigDecimal netWeight,
        String batchNumber,
        List<String> serialNumbers,
        LocalDate expirationDate,
        List<AsnBatchCommand> batches,
        List<AsnSerialCommand> serials
    ) {
        public AsnLineCommand(
            UUID supplierPoCommitmentId,
            BigDecimal quantity,
            BigDecimal packageCount,
            String packageType,
            BigDecimal grossWeight,
            BigDecimal netWeight,
            String batchNumber,
            List<String> serialNumbers,
            LocalDate expirationDate
        ) {
            this(
                supplierPoCommitmentId, quantity, packageCount, packageType, grossWeight, netWeight,
                batchNumber, serialNumbers, expirationDate, List.of(), List.of()
            );
        }

        public AsnLineCommand(UUID supplierPoCommitmentId, BigDecimal quantity, String batchNumber, LocalDate expirationDate) {
            this(supplierPoCommitmentId, quantity, null, null, null, null, batchNumber, null, expirationDate, List.of(), List.of());
        }
    }

    public record AsnDraftCommand(
        String asnNumber,
        LocalDate noticeDate,
        LocalDate expectedShipDate,
        LocalDate expectedArrivalDate,
        String transportMode,
        String carrierName,
        String vehicleNumber,
        String containerNumber,
        String trackingNumber,
        List<AsnLineCommand> lines
    ) {
    }

    /** Remaining shippable quantity for a commitment, ignoring any in-flight draft. */
    @Transactional(readOnly = true)
    public BigDecimal remainingQuantity(SupplierPoCommitment commitment) {
        return remainingQuantity(commitment, null);
    }

    private BigDecimal remainingQuantity(SupplierPoCommitment commitment, UUID excludeAsnId) {
        BigDecimal confirmed = commitment.getConfirmedQuantity() == null ? BigDecimal.ZERO : commitment.getConfirmedQuantity();
        BigDecimal submittedQuantity = asnItemRepository.sumActiveQuantityForCommitment(
            commitment.getTenantId(), commitment.getCompanyId(), commitment.getId(), excludeAsnId
        );
        return confirmed.subtract(submittedQuantity == null ? BigDecimal.ZERO : submittedQuantity).max(BigDecimal.ZERO);
    }

    @Transactional
    public AdvanceShippingNotice createDraft(
        UUID purchaseOrderId,
        UUID supplierId,
        UUID portalAccountId,
        AsnDraftCommand command
    ) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        PurchaseOrder purchaseOrder = requirePurchaseOrder(purchaseOrderId, supplierId, tenantId, companyId);
        requireGoodsShipmentPurchaseOrder(purchaseOrder);

        AdvanceShippingNotice asn = new AdvanceShippingNotice();
        asn.setCode("ASN-PORTAL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        String asnNameSource = purchaseOrder.getCode() == null || purchaseOrder.getCode().isBlank()
            ? purchaseOrder.getId().toString()
            : purchaseOrder.getCode();
        asn.setName(BusinessNameGenerator.workflowDocumentName("AdvanceShippingNotice", asnNameSource));
        asn.setStatus(AdvanceShippingNoticeStatus.DRAFT.name());
        asn.setSupplierId(supplierId);
        asn.setPurchaseOrderId(purchaseOrderId);
        asn.setSource("SUPPLIER_PORTAL");
        asn.setPortalAccountId(portalAccountId);
        asn.setCurrencyCode("UZS");
        applyHeader(asn, command, true);
        AdvanceShippingNotice saved = asnRepository.save(asn);
        replaceLines(saved, supplierId, command.lines(), tenantId, companyId, null);
        recalculateTotals(saved.getId(), tenantId, companyId);
        return asnRepository.findByIdAndIsDeletedFalse(saved.getId()).orElseThrow();
    }

    @Transactional
    public AdvanceShippingNotice updateDraft(
        UUID purchaseOrderId,
        UUID asnId,
        UUID supplierId,
        AsnDraftCommand command
    ) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        requireGoodsShipmentPurchaseOrder(requirePurchaseOrder(purchaseOrderId, supplierId, tenantId, companyId));
        AdvanceShippingNotice asn = requireOwnedDraft(purchaseOrderId, asnId, supplierId, tenantId, companyId);
        applyHeader(asn, command, false);
        AdvanceShippingNotice saved = asnRepository.save(asn);
        replaceLines(saved, supplierId, command.lines(), tenantId, companyId, asnId);
        recalculateTotals(saved.getId(), tenantId, companyId);
        return asnRepository.findByIdAndIsDeletedFalse(saved.getId()).orElseThrow();
    }

    @Transactional
    public AdvanceShippingNotice submit(UUID purchaseOrderId, UUID asnId, UUID supplierId, String idempotencyKey) {
        AsnSubmitResult result = idempotencyService.execute(
            "SUPPLIER_PORTAL_PO_ASN_SUBMIT",
            asnId,
            idempotencyKey,
            java.util.Map.of("purchaseOrderId", purchaseOrderId, "asnId", asnId),
            AsnSubmitResult.class,
            () -> {
                AdvanceShippingNotice submitted = submitInternal(purchaseOrderId, asnId, supplierId);
                return new AsnSubmitResult(submitted.getId());
            }
        );
        return asnRepository.findByIdAndIsDeletedFalse(result.asnId())
            .orElseThrow(() -> new NotFoundException("Advance shipping notice not found: " + result.asnId()));
    }

    private record AsnSubmitResult(UUID asnId) {
    }

    private AdvanceShippingNotice submitInternal(UUID purchaseOrderId, UUID asnId, UUID supplierId) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        requireGoodsShipmentPurchaseOrder(requirePurchaseOrder(purchaseOrderId, supplierId, tenantId, companyId));
        AdvanceShippingNotice asn = requireOwned(purchaseOrderId, asnId, supplierId, tenantId, companyId);
        if (!AdvanceShippingNoticeStatus.DRAFT.name().equals(asn.getStatus())) {
            // Idempotent replay: submitting an already-submitted ASN again is a no-op success.
            return asn;
        }
        List<AdvanceShippingNoticeItem> items = asnItemRepository
            .findByTenantIdAndCompanyIdAndAsn_IdAndIsDeletedFalse(tenantId, companyId, asnId);
        if (items.isEmpty()) {
            throw new BusinessException("ASN must have at least one line before submit");
        }
        validateTrackingForSubmit(items, tenantId, companyId);
        asn.setStatus(AdvanceShippingNoticeStatus.SENT.name());
        asn.setSentAt(LocalDateTime.now());
        AdvanceShippingNotice saved = asnRepository.save(asn);
        auditService.log("AdvanceShippingNotice", saved.getId(), "PO_SUPPLIER_ASN_SUBMITTED", saved.getCode(), null, null);
        notificationService.createForRole(
            RoleName.PROCUREMENT_MANAGER,
            "Supplier ASN submitted",
            "Supplier submitted advance shipping notice " + saved.getCode() + ".",
            "Supplier ASN " + saved.getCode(),
            NotificationEntityType.PURCHASE_ORDER.name(),
            purchaseOrderId
        );
        return saved;
    }

    /** {@link SupplierPoAsnCancellationHook} implementation entry point: cancels
     * draft (never-submitted) portal ASNs tied to a cancelled PO's commitments. Does
     * NOT touch already-submitted ASNs -- see {@link SupplierPoAsnCancellationHookImpl}
     * for why (submitted ASNs get explicit-handling treatment, not silent cancellation). */
    @Transactional
    public int cancelDraftAsns(UUID purchaseOrderId) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        int cancelled = 0;
        for (AdvanceShippingNotice asn : asnRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndSourceAndIsDeletedFalse(
            tenantId, companyId, purchaseOrderId, "SUPPLIER_PORTAL"
        )) {
            if (AdvanceShippingNoticeStatus.DRAFT.name().equals(asn.getStatus())) {
                asn.setStatus(AdvanceShippingNoticeStatus.CANCELLED.name());
                asnRepository.save(asn);
                auditService.log("AdvanceShippingNotice", asn.getId(), "PO_SUPPLIER_ASN_DRAFT_CANCELLED", asn.getCode(), null, null);
                cancelled++;
            }
        }
        return cancelled;
    }

    /** True when the PO has submitted-but-not-yet-shipped/received portal ASNs
     * ({@code SENT} or {@code CONFIRMED}) against it -- used to block PO cancellation
     * per the "require explicit handling for already-submitted ASNs" business rule. */
    @Transactional(readOnly = true)
    public boolean hasUnresolvedSubmittedAsns(UUID purchaseOrderId) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        return !asnRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndStatusInAndIsDeletedFalse(
            tenantId, companyId, purchaseOrderId,
            List.of(AdvanceShippingNoticeStatus.SENT.name(), AdvanceShippingNoticeStatus.CONFIRMED.name())
        ).isEmpty();
    }

    private void applyHeader(AdvanceShippingNotice asn, AsnDraftCommand command, boolean creating) {
        if (command == null) {
            throw new BusinessException("ASN request body is required");
        }
        if (command.asnNumber() != null && !command.asnNumber().isBlank()) {
            asn.setAsnNumber(command.asnNumber().trim());
        } else if (creating) {
            asn.setAsnNumber(asn.getCode());
        }
        asn.setNoticeDate(command.noticeDate() == null ? LocalDate.now() : command.noticeDate());
        asn.setExpectedShipDate(command.expectedShipDate());
        asn.setExpectedArrivalDate(command.expectedArrivalDate());
        if (command.expectedShipDate() != null && command.expectedArrivalDate() != null
            && command.expectedArrivalDate().isBefore(command.expectedShipDate())) {
            throw new BusinessException("expectedArrivalDate must be on or after expectedShipDate");
        }
        asn.setTransportMode(blankToNull(command.transportMode()));
        asn.setCarrierName(blankToNull(command.carrierName()));
        asn.setVehicleNumber(blankToNull(command.vehicleNumber()));
        asn.setContainerNumber(blankToNull(command.containerNumber()));
        asn.setTrackingNumber(blankToNull(command.trackingNumber()));
    }

    private void replaceLines(
        AdvanceShippingNotice asn,
        UUID supplierId,
        List<AsnLineCommand> lines,
        String tenantId,
        UUID companyId,
        UUID selfAsnIdForRemainingCheck
    ) {
        if (lines == null || lines.isEmpty()) {
            throw new BusinessException("At least one ASN line is required");
        }
        for (AdvanceShippingNoticeItem existing : asnItemRepository
            .findByTenantIdAndCompanyIdAndAsn_IdAndIsDeletedFalse(tenantId, companyId, asn.getId())) {
            softDeleteTracking(existing, tenantId, companyId);
            existing.softDelete();
            asnItemRepository.save(existing);
        }
        for (AsnLineCommand line : lines) {
            if (line.supplierPoCommitmentId() == null) {
                throw new BusinessException("supplierPoCommitmentId is required for every ASN line");
            }
            BigDecimal quantity = line.quantity() == null ? BigDecimal.ZERO : line.quantity();
            if (quantity.signum() <= 0) {
                throw new BusinessException("ASN line quantity must be positive");
            }
            SupplierPoCommitment commitment = commitmentRepository.findByIdAndIsDeletedFalse(line.supplierPoCommitmentId())
                .filter(found -> tenantId.equals(found.getTenantId()) && Objects.equals(companyId, found.getCompanyId()))
                .orElseThrow(() -> new BusinessException("Commitment not found: " + line.supplierPoCommitmentId()));
            if (!Objects.equals(commitment.getSupplierId(), supplierId)) {
                throw new BusinessException("Commitment does not belong to the authenticated supplier");
            }
            if (!Objects.equals(commitment.getPurchaseOrderId(), asn.getPurchaseOrderId())) {
                throw new BusinessException("Commitment does not belong to this purchase order");
            }
            if (!"CONFIRMED".equals(commitment.getStatus())) {
                throw new BusinessException("Commitment is not CONFIRMED and cannot be shipped: " + commitment.getCode());
            }
            BigDecimal remaining = remainingQuantity(commitment, selfAsnIdForRemainingCheck);
            if (quantity.compareTo(remaining) > 0) {
                throw new BusinessException(
                    "ASN quantity " + quantity + " exceeds remaining confirmed quantity " + remaining
                        + " for commitment " + commitment.getCode()
                );
            }
            UUID materialId = resolveMaterialIdFromItem(commitment);
            if (materialId == null) {
                throw new BusinessException("Purchase order item has no material and cannot be shipped as goods ASN");
            }
            var poItem = commitment.getPurchaseOrderItem();
            com.enterprise.mm.service.StockUomPolicy.purchasing(poItem.getMaterial(), poItem.getUnit(), quantity);
            AdvanceShippingNoticeItem item = new AdvanceShippingNoticeItem();
            item.setCode("ASN-ITEM-PORTAL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
            item.setName("Portal ASN line - " + commitmentName(commitment));
            item.setStatus("ACTIVE");
            item.setAsnId(asn.getId());
            item.setPurchaseOrderItemId(commitment.getPurchaseOrderItemId());
            item.setMaterialId(materialId);
            item.setQuantity(quantity);
            item.setPackageCount(line.packageCount());
            item.setPackageType(blankToNull(line.packageType()));
            item.setGrossWeight(line.grossWeight());
            item.setNetWeight(line.netWeight());
            item.setBatchNumber(blankToNull(line.batchNumber()));
            item.setSerialNumbers(joinSerialNumbers(line.serialNumbers()));
            item.setExpirationDate(line.expirationDate());
            item.setSupplierPoCommitmentId(commitment.getId());
            AdvanceShippingNoticeItem savedItem = asnItemRepository.save(item);
            saveTrackingRows(savedItem, line, tenantId, companyId);
        }
    }

    private String joinSerialNumbers(List<String> serialNumbers) {
        if (serialNumbers == null) {
            return null;
        }
        String joined = serialNumbers.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .reduce((left, right) -> left + "\n" + right)
            .orElse(null);
        return blankToNull(joined);
    }

    private void saveTrackingRows(
        AdvanceShippingNoticeItem item,
        AsnLineCommand line,
        String tenantId,
        UUID companyId
    ) {
        List<AsnBatchCommand> batches = normalizedBatches(line);
        Map<String, AdvanceShippingNoticeBatch> savedBatchesByNumber = new java.util.LinkedHashMap<>();
        for (AsnBatchCommand batch : batches) {
            AdvanceShippingNoticeBatch entity = new AdvanceShippingNoticeBatch();
            entity.setTenantId(tenantId);
            entity.setCompanyId(companyId);
            entity.setCode("ASN-BATCH-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
            entity.setName("ASN expected batch");
            entity.setStatus("DECLARED");
            entity.setAsnItemId(item.getId());
            entity.setBatchNumber(blankToNull(batch.batchNumber()));
            entity.setQuantity(batch.quantity() == null ? BigDecimal.ZERO : batch.quantity());
            entity.setExpirationDate(batch.expirationDate());
            AdvanceShippingNoticeBatch saved = asnBatchRepository.save(entity);
            if (saved == null) {
                saved = entity;
            }
            String key = normalizeKey(saved.getBatchNumber());
            if (key != null) {
                savedBatchesByNumber.putIfAbsent(key, saved);
            }
        }
        for (AsnSerialCommand serial : normalizedSerials(line)) {
            AdvanceShippingNoticeSerial entity = new AdvanceShippingNoticeSerial();
            entity.setTenantId(tenantId);
            entity.setCompanyId(companyId);
            entity.setCode("ASN-SERIAL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
            entity.setName("ASN expected manufacturer serial");
            entity.setStatus("DECLARED");
            entity.setAsnItemId(item.getId());
            entity.setManufacturerSerialNumber(blankToNull(serial.manufacturerSerialNumber()));
            entity.setNormalizedSerialNumber(normalizeKey(serial.manufacturerSerialNumber()));
            entity.setBatchNumber(blankToNull(serial.batchNumber()));
            AdvanceShippingNoticeBatch batch = savedBatchesByNumber.get(normalizeKey(serial.batchNumber()));
            entity.setAsnBatchId(batch == null ? null : batch.getId());
            asnSerialRepository.save(entity);
        }
    }

    private List<AsnBatchCommand> normalizedBatches(AsnLineCommand line) {
        if (line.batches() != null && !line.batches().isEmpty()) {
            return line.batches().stream().filter(Objects::nonNull).toList();
        }
        if (blankToNull(line.batchNumber()) != null || line.expirationDate() != null) {
            return List.of(new AsnBatchCommand(line.batchNumber(), line.quantity(), line.expirationDate()));
        }
        return List.of();
    }

    private List<AsnSerialCommand> normalizedSerials(AsnLineCommand line) {
        if (line.serials() != null && !line.serials().isEmpty()) {
            return line.serials().stream().filter(Objects::nonNull).toList();
        }
        if (line.serialNumbers() == null) {
            return List.of();
        }
        return line.serialNumbers().stream()
            .filter(Objects::nonNull)
            .map(value -> new AsnSerialCommand(value, line.batchNumber()))
            .toList();
    }

    private void softDeleteTracking(AdvanceShippingNoticeItem item, String tenantId, UUID companyId) {
        for (AdvanceShippingNoticeSerial serial : asnSerialRepository
            .findByTenantIdAndCompanyIdAndAsnItemIdAndIsDeletedFalseOrderByCreatedAtAsc(tenantId, companyId, item.getId())) {
            serial.softDelete();
            asnSerialRepository.save(serial);
        }
        for (AdvanceShippingNoticeBatch batch : asnBatchRepository
            .findByTenantIdAndCompanyIdAndAsnItemIdAndIsDeletedFalseOrderByCreatedAtAsc(tenantId, companyId, item.getId())) {
            batch.softDelete();
            asnBatchRepository.save(batch);
        }
    }

    private void validateTrackingForSubmit(List<AdvanceShippingNoticeItem> items, String tenantId, UUID companyId) {
        for (AdvanceShippingNoticeItem item : items) {
            PurchaseOrderItem poItem = item.getPurchaseOrderItem();
            BatchTrackingPolicy batchPolicy = batchPolicy(poItem);
            SerialTrackingPolicy serialPolicy = serialPolicy(poItem);
            BigDecimal quantity = item.getQuantity() == null ? BigDecimal.ZERO : item.getQuantity();
            List<AdvanceShippingNoticeBatch> batches = asnBatchRepository
                .findByTenantIdAndCompanyIdAndAsnItemIdAndIsDeletedFalseOrderByCreatedAtAsc(tenantId, companyId, item.getId());
            List<AdvanceShippingNoticeSerial> serials = asnSerialRepository
                .findByTenantIdAndCompanyIdAndAsnItemIdAndIsDeletedFalseOrderByCreatedAtAsc(tenantId, companyId, item.getId());

            if (batchPolicy == BatchTrackingPolicy.REQUIRED) {
                validateRequiredBatches(quantity, batches);
            }
            if (serialPolicy == SerialTrackingPolicy.REQUIRED) {
                validateRequiredSerials(quantity, serials);
            }
            if (batchPolicy == BatchTrackingPolicy.REQUIRED && serialPolicy == SerialTrackingPolicy.REQUIRED) {
                validateSerialBatchRelation(batches, serials);
            }
        }
    }

    private void validateRequiredBatches(BigDecimal quantity, List<AdvanceShippingNoticeBatch> batches) {
        if (batches.isEmpty()) {
            throw new BusinessException("ASN_BATCH_REQUIRED");
        }
        BigDecimal total = batches.stream()
            .map(AdvanceShippingNoticeBatch::getQuantity)
            .map(value -> value == null ? BigDecimal.ZERO : value)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(quantity) != 0) {
            throw new BusinessException("ASN_BATCH_QUANTITY_MISMATCH");
        }
        Set<String> batchNumbers = new java.util.HashSet<>();
        for (AdvanceShippingNoticeBatch batch : batches) {
            if (batch.getQuantity() == null || batch.getQuantity().signum() <= 0) {
                throw new BusinessException("ASN_BATCH_QUANTITY_POSITIVE_REQUIRED");
            }
            String normalized = normalizeKey(batch.getBatchNumber());
            if (normalized == null) {
                throw new BusinessException("ASN_BATCH_NUMBER_REQUIRED");
            }
            if (!batchNumbers.add(normalized)) {
                throw new BusinessException("ASN_DUPLICATE_BATCH_NUMBER");
            }
        }
    }

    private void validateRequiredSerials(BigDecimal quantity, List<AdvanceShippingNoticeSerial> serials) {
        if (BigDecimal.valueOf(serials.size()).compareTo(quantity.stripTrailingZeros()) != 0) {
            throw new BusinessException("ASN_SERIAL_COUNT_MISMATCH");
        }
        Set<String> serialNumbers = new java.util.HashSet<>();
        for (AdvanceShippingNoticeSerial serial : serials) {
            String normalized = normalizeKey(serial.getManufacturerSerialNumber());
            if (normalized == null) {
                throw new BusinessException("ASN_SERIAL_NUMBER_REQUIRED");
            }
            if (!serialNumbers.add(normalized)) {
                throw new BusinessException("ASN_DUPLICATE_SERIAL_NUMBER");
            }
        }
    }

    private void validateSerialBatchRelation(
        List<AdvanceShippingNoticeBatch> batches,
        List<AdvanceShippingNoticeSerial> serials
    ) {
        Map<String, AdvanceShippingNoticeBatch> batchesByNumber = batches.stream()
            .filter(batch -> normalizeKey(batch.getBatchNumber()) != null)
            .collect(Collectors.toMap(
                batch -> normalizeKey(batch.getBatchNumber()),
                Function.identity(),
                (left, right) -> left,
                java.util.LinkedHashMap::new
            ));
        Map<String, Long> serialCountByBatch = new java.util.HashMap<>();
        for (AdvanceShippingNoticeSerial serial : serials) {
            String batchNumber = normalizeKey(serial.getBatchNumber());
            if (batchNumber == null || !batchesByNumber.containsKey(batchNumber)) {
                throw new BusinessException("ASN_SERIAL_BATCH_NOT_DECLARED");
            }
            serialCountByBatch.merge(batchNumber, 1L, Long::sum);
        }
        for (AdvanceShippingNoticeBatch batch : batches) {
            String batchNumber = normalizeKey(batch.getBatchNumber());
            long count = serialCountByBatch.getOrDefault(batchNumber, 0L);
            if (BigDecimal.valueOf(count).compareTo((batch.getQuantity() == null ? BigDecimal.ZERO : batch.getQuantity()).stripTrailingZeros()) != 0) {
                throw new BusinessException("ASN_SERIAL_BATCH_COUNT_MISMATCH");
            }
        }
    }

    private BatchTrackingPolicy batchPolicy(PurchaseOrderItem item) {
        if (item == null) {
            return BatchTrackingPolicy.NONE;
        }
        if (item.getBatchTrackingSnapshot() != null) {
            return item.getBatchTrackingSnapshot();
        }
        return TrackingMode.batchTrackingPolicy(item.getTrackingModeSnapshot());
    }

    private SerialTrackingPolicy serialPolicy(PurchaseOrderItem item) {
        if (item == null) {
            return SerialTrackingPolicy.NONE;
        }
        if (item.getSerialTrackingSnapshot() != null) {
            return item.getSerialTrackingSnapshot();
        }
        return TrackingMode.serialTrackingPolicy(item.getTrackingModeSnapshot());
    }

    private String normalizeKey(String value) {
        String normalized = blankToNull(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private UUID resolveMaterialIdFromItem(SupplierPoCommitment commitment) {
        return commitment.getPurchaseOrderItem() == null
            ? null
            : commitment.getPurchaseOrderItem().getMaterialId();
    }

    private String commitmentName(SupplierPoCommitment commitment) {
        return commitment.getName() == null || commitment.getName().isBlank() ? "Supplier commitment" : commitment.getName().trim();
    }

    private void recalculateTotals(UUID asnId, String tenantId, UUID companyId) {
        AdvanceShippingNotice asn = asnRepository.findByIdAndIsDeletedFalse(asnId).orElseThrow();
        BigDecimal total = asnItemRepository.findByTenantIdAndCompanyIdAndAsn_IdAndIsDeletedFalse(tenantId, companyId, asnId)
            .stream()
            .map(item -> item.getQuantity() == null ? BigDecimal.ZERO : item.getQuantity())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        asn.setTotalQuantity(total);
        asnRepository.save(asn);
    }

    private PurchaseOrder requirePurchaseOrder(UUID purchaseOrderId, UUID supplierId, String tenantId, UUID companyId) {
        PurchaseOrder purchaseOrder = purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)
            .filter(found -> tenantId.equals(found.getTenantId()) && Objects.equals(companyId, found.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("Purchase order not found: " + purchaseOrderId));
        if (!Objects.equals(purchaseOrder.getSupplierId(), supplierId)) {
            throw new BusinessException("Purchase order does not belong to the authenticated supplier");
        }
        return purchaseOrder;
    }

    private void requireGoodsShipmentPurchaseOrder(PurchaseOrder purchaseOrder) {
        RequestType requestType = purchaseOrder == null || purchaseOrder.getRequestType() == null
            ? RequestType.GOODS
            : purchaseOrder.getRequestType();
        if (requestType.isServiceStyle()) {
            throw new BusinessException(
                SERVICE_OR_WORK_PO_DOES_NOT_USE_SHIPMENT,
                SERVICE_OR_WORK_PO_DOES_NOT_USE_SHIPMENT
            );
        }
    }

    private AdvanceShippingNotice requireOwned(UUID purchaseOrderId, UUID asnId, UUID supplierId, String tenantId, UUID companyId) {
        AdvanceShippingNotice asn = asnRepository.findByIdAndIsDeletedFalse(asnId)
            .filter(found -> tenantId.equals(found.getTenantId()) && Objects.equals(companyId, found.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("ASN not found: " + asnId));
        if (!Objects.equals(asn.getPurchaseOrderId(), purchaseOrderId) || !Objects.equals(asn.getSupplierId(), supplierId)) {
            throw new BusinessException("ASN does not belong to this purchase order / supplier");
        }
        return asn;
    }

    private AdvanceShippingNotice requireOwnedDraft(UUID purchaseOrderId, UUID asnId, UUID supplierId, String tenantId, UUID companyId) {
        AdvanceShippingNotice asn = requireOwned(purchaseOrderId, asnId, supplierId, tenantId, companyId);
        if (!AdvanceShippingNoticeStatus.DRAFT.name().equals(asn.getStatus())) {
            throw new BusinessException("Only a DRAFT ASN can be edited");
        }
        return asn;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
