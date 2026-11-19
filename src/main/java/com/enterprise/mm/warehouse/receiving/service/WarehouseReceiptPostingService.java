package com.enterprise.mm.warehouse.receiving.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.erp.ErpExchangeHooks;
import com.enterprise.mm.erp.ErpExchangeService;
import com.enterprise.mm.entity.GoodsReceipt;
import com.enterprise.mm.entity.GoodsReceiptBatch;
import com.enterprise.mm.entity.GoodsReceiptEvidence;
import com.enterprise.mm.entity.GoodsReceiptLine;
import com.enterprise.mm.entity.GoodsReceiptUnit;
import com.enterprise.mm.entity.MaterialUnit;
import com.enterprise.mm.entity.MaterialUnitEvent;
import com.enterprise.mm.enums.MaterialUnitEventType;
import com.enterprise.mm.enums.MaterialUnitStatus;
import com.enterprise.mm.enums.TrackingMode;
import com.enterprise.mm.repository.GoodsReceiptBatchRepository;
import com.enterprise.mm.repository.GoodsReceiptEvidenceRepository;
import com.enterprise.mm.repository.GoodsReceiptLineRepository;
import com.enterprise.mm.repository.GoodsReceiptRepository;
import com.enterprise.mm.repository.GoodsReceiptUnitRepository;
import com.enterprise.mm.repository.MaterialUnitEventRepository;
import com.enterprise.mm.repository.MaterialUnitRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.service.AuditService;
import com.enterprise.mm.util.BusinessNameGenerator;
import com.enterprise.mm.warehouse.receiving.model.ReceiptBatchPostingDraft;
import com.enterprise.mm.warehouse.receiving.model.ReceiptLinePostingDraft;
import com.enterprise.mm.warehouse.receiving.model.ReceiptPostingDraft;
import com.enterprise.mm.warehouse.receiving.model.ReceiptPostingResult;
import com.enterprise.mm.warehouse.receiving.model.ReceiptUnitPostingDraft;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WarehouseReceiptPostingService {
    private final GoodsReceiptRepository receiptRepository;
    private final GoodsReceiptLineRepository lineRepository;
    private final GoodsReceiptBatchRepository receiptBatchRepository;
    private final GoodsReceiptEvidenceRepository evidenceRepository;
    private final GoodsReceiptUnitRepository receiptUnitRepository;
    private final MaterialUnitRepository materialUnitRepository;
    private final MaterialUnitEventRepository materialUnitEventRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final AuditService auditService;
    private final com.enterprise.mm.repository.MaterialRepository materialRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ErpExchangeService erpExchange;

    public WarehouseReceiptPostingService(
        GoodsReceiptRepository receiptRepository,
        GoodsReceiptLineRepository lineRepository,
        GoodsReceiptBatchRepository receiptBatchRepository,
        GoodsReceiptEvidenceRepository evidenceRepository,
        GoodsReceiptUnitRepository receiptUnitRepository,
        MaterialUnitRepository materialUnitRepository,
        MaterialUnitEventRepository materialUnitEventRepository,
        PurchaseOrderRepository purchaseOrderRepository,
        AuditService auditService
    ) {
        this(
            receiptRepository,
            lineRepository,
            receiptBatchRepository,
            evidenceRepository,
            receiptUnitRepository,
            materialUnitRepository,
            materialUnitEventRepository,
            purchaseOrderRepository,
            auditService,
            null
        );
    }

    @org.springframework.beans.factory.annotation.Autowired
    public WarehouseReceiptPostingService(
        GoodsReceiptRepository receiptRepository,
        GoodsReceiptLineRepository lineRepository,
        GoodsReceiptBatchRepository receiptBatchRepository,
        GoodsReceiptEvidenceRepository evidenceRepository,
        GoodsReceiptUnitRepository receiptUnitRepository,
        MaterialUnitRepository materialUnitRepository,
        MaterialUnitEventRepository materialUnitEventRepository,
        PurchaseOrderRepository purchaseOrderRepository,
        AuditService auditService,
        com.enterprise.mm.repository.MaterialRepository materialRepository
    ) {
        this.receiptRepository = receiptRepository;
        this.lineRepository = lineRepository;
        this.receiptBatchRepository = receiptBatchRepository;
        this.evidenceRepository = evidenceRepository;
        this.receiptUnitRepository = receiptUnitRepository;
        this.materialUnitRepository = materialUnitRepository;
        this.materialUnitEventRepository = materialUnitEventRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.auditService = auditService;
        this.materialRepository = materialRepository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ReceiptPostingResult record(ReceiptPostingDraft draft) {
        if (draft == null || draft.supplierId() == null) {
            throw new IllegalArgumentException("Receipt supplier is required");
        }
        List<ReceiptLinePostingDraft> lines = draft.lines() == null ? List.of() : List.copyOf(draft.lines());
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("Receipt posting lines are required");
        }
        UUID warehouseId = lines.get(0).warehouseId();
        if (warehouseId == null || lines.stream().anyMatch(line -> !warehouseId.equals(line.warehouseId()))) {
            throw new IllegalArgumentException("All receipt lines must belong to one warehouse");
        }
        lines.forEach(line -> validateLine(draft.purchaseOrderId(), line));
        if (nvl(draft.acceptedAmount()).signum() < 0 || nvl(draft.baseAcceptedAmount()).signum() < 0) {
            throw new IllegalArgumentException("Receipt monetary amounts cannot be negative");
        }
        BigDecimal accepted = sum(lines, ReceiptLinePostingDraft::acceptedQuantity);
        BigDecimal rejected = sum(lines, ReceiptLinePostingDraft::rejectedQuantity);
        BigDecimal damaged = sum(lines, ReceiptLinePostingDraft::damagedQuantity);
        BigDecimal excess = sum(lines, ReceiptLinePostingDraft::excessQuantity);
        BigDecimal shortage = sum(lines, ReceiptLinePostingDraft::shortageQuantity);

        GoodsReceipt receipt = new GoodsReceipt();
        receipt.setTenantId(TenantContext.currentTenant());
        receipt.setCompanyId(CompanyContext.currentCompany());
        receipt.setCode("GR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        receipt.setName(BusinessNameGenerator.workflowDocumentName("GoodsReceipt", purchaseOrderName(draft.purchaseOrderId())));
        receipt.setStatus(rejected.signum() > 0 || damaged.signum() > 0 || excess.signum() > 0 || shortage.signum() > 0
            ? "POSTED_WITH_EXCEPTIONS" : "POSTED");
        receipt.setContractId(purchaseOrderContractId(draft.purchaseOrderId()));
        receipt.setPurchaseOrderId(draft.purchaseOrderId());
        receipt.setSupplierId(draft.supplierId());
        receipt.setAsnId(draft.asnId());
        receipt.setWarehouseId(warehouseId);
        receipt.setIdempotencyKey(normalize(draft.idempotencyKey()));
        receipt.setCurrencyCode(normalize(draft.currencyCode()) == null ? "UZS" : draft.currencyCode().trim().toUpperCase());
        receipt.setExchangeRate(positiveOrOne(draft.exchangeRate()));
        receipt.setBaseCurrencyCode("UZS");
        receipt.setAcceptedQuantity(accepted);
        receipt.setRejectedQuantity(rejected);
        receipt.setDamagedQuantity(damaged);
        receipt.setExcessQuantity(excess);
        receipt.setShortageQuantity(shortage);
        receipt.setAcceptedAmount(nvl(draft.acceptedAmount()));
        receipt.setBaseAcceptedAmount(nvl(draft.baseAcceptedAmount()));
        receipt.setPostedAt(LocalDateTime.now());
        receipt.setPostedBy(currentUsername());
        GoodsReceipt savedReceipt = receiptRepository.save(receipt);
        auditService.logInCurrentTransaction(
            "GoodsReceipt",
            savedReceipt.getId(),
            "CREATE",
            savedReceipt.getCode(),
            null,
            savedReceipt
        );

        List<UUID> evidenceIds = draft.evidenceAttachmentIds() == null
            ? List.of()
            : draft.evidenceAttachmentIds().stream().filter(java.util.Objects::nonNull).distinct().toList();
        LinkedHashSet<UUID> approvedExceptions = new LinkedHashSet<>();
        int receiptUnitsRecorded = 0;
        int materialUnitsCreated = 0;
        int sequence = 0;
        for (ReceiptLinePostingDraft draftLine : lines) {
            GoodsReceiptLine line = toLine(savedReceipt, draft, draftLine, ++sequence);
            GoodsReceiptLine savedLine = lineRepository.save(line);
            auditService.logInCurrentTransaction(
                "GoodsReceiptLine",
                savedLine.getId(),
                "CREATE",
                savedLine.getCode(),
                null,
                savedLine
            );
            if (draftLine.accreditationExceptionId() != null) {
                approvedExceptions.add(draftLine.accreditationExceptionId());
            }
            UnitRecordCount unitCounts = recordLineDetails(savedReceipt, savedLine, draftLine);
            receiptUnitsRecorded += unitCounts.receiptUnits();
            materialUnitsCreated += unitCounts.materialUnits();
            for (UUID evidenceId : evidenceIds) {
                GoodsReceiptEvidence evidence = new GoodsReceiptEvidence();
                evidence.setTenantId(TenantContext.currentTenant());
                evidence.setCompanyId(CompanyContext.currentCompany());
                evidence.setCode("GRE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
                evidence.setName("Receipt evidence");
                evidence.setStatus("LINKED");
                evidence.setGoodsReceiptId(savedReceipt.getId());
                evidence.setGoodsReceiptLineId(savedLine.getId());
                evidence.setAttachmentId(evidenceId);
                evidence.setPurchaseOrderId(draft.purchaseOrderId());
                evidence.setPurchaseOrderItemId(draftLine.purchaseOrderItemId());
                evidence.setSupplierId(draft.supplierId());
                evidence.setMaterialId(draftLine.materialId());
                evidence.setMaterialGroupId(draftLine.materialGroupId());
                evidence.setStockMovementId(draftLine.stockMovementId());
                GoodsReceiptEvidence savedEvidence = evidenceRepository.save(evidence);
                auditService.logInCurrentTransaction(
                    GoodsReceiptEvidence.class.getSimpleName(),
                    savedEvidence.getId(),
                    "CREATE",
                    savedEvidence.getCode(),
                    null,
                    savedEvidence
                );
            }
        }
        List<ErpExchangeService.Line> exchangeLines = new java.util.ArrayList<>();
        java.time.LocalDate exchangeDate = null;
        if (erpExchange != null && erpExchange.captureEnabled()) for (ReceiptLinePostingDraft line : lines) {
            List<UUID> movementIds = new java.util.ArrayList<>();
            List<BigDecimal> quantities = new java.util.ArrayList<>();
            if (!line.batches().isEmpty()) {
                for (ReceiptBatchPostingDraft batch : line.batches()) if (batch.acceptedQuantity().signum() > 0) {
                    movementIds.add(batch.stockMovementId()); quantities.add(batch.acceptedQuantity());
                }
            } else if (line.acceptedQuantity().signum() > 0) {
                movementIds.add(line.stockMovementId()); quantities.add(line.acceptedQuantity());
            }
            for (int i = 0; i < movementIds.size(); i++) {
                UUID movementId = movementIds.get(i);
                java.time.LocalDate date = erpExchange.movementDate(movementId);
                if (exchangeDate != null && !exchangeDate.equals(date)) throw new IllegalArgumentException("Receipt movements have different posting dates");
                exchangeDate = date;
                exchangeLines.add(new ErpExchangeService.Line(line.materialId(), line.warehouseId(), quantities.get(i), erpExchange.movementAmount(movementId)));
            }
        }
        if (!exchangeLines.isEmpty()) ErpExchangeHooks.accept(erpExchange, "RECEIPT", savedReceipt.getId(), savedReceipt.getVersion(),
            exchangeDate, "RECEIPT", null, null, exchangeLines);
        return new ReceiptPostingResult(
            savedReceipt.getId(), savedReceipt.getCode(), accepted, rejected, damaged, excess, shortage,
            materialUnitsCreated, receiptUnitsRecorded,
            List.copyOf(approvedExceptions));
    }

    private UnitRecordCount recordLineDetails(GoodsReceipt receipt, GoodsReceiptLine line, ReceiptLinePostingDraft draftLine) {
        List<ReceiptBatchPostingDraft> batches = draftLine.batches() == null ? List.of() : draftLine.batches();
        if (!batches.isEmpty()) {
            int receiptUnits = 0;
            int materialUnits = 0;
            int sequence = 0;
            for (ReceiptBatchPostingDraft batchDraft : batches) {
                GoodsReceiptBatch savedBatch = receiptBatchRepository.save(toBatch(line, batchDraft, ++sequence));
                UnitRecordCount counts = recordUnits(
                    receipt,
                    line,
                    draftLine,
                    batchDraft.units(),
                    savedBatch.getId(),
                    batchDraft.stockBatchId()
                );
                receiptUnits += counts.receiptUnits();
                materialUnits += counts.materialUnits();
            }
            return new UnitRecordCount(receiptUnits, materialUnits);
        }
        return recordUnits(receipt, line, draftLine, draftLine.units(), null, draftLine.stockBatchId());
    }

    private UnitRecordCount recordUnits(
        GoodsReceipt receipt,
        GoodsReceiptLine line,
        ReceiptLinePostingDraft draftLine,
        List<ReceiptUnitPostingDraft> unitDrafts,
        UUID receiptBatchId,
        UUID stockBatchId
    ) {
        if (draftLine.trackingMode() != TrackingMode.SERIAL) {
            return new UnitRecordCount(0, 0);
        }
        List<ReceiptUnitPostingDraft> units = unitDrafts == null ? List.of() : unitDrafts;
        int materialUnits = 0;
        for (ReceiptUnitPostingDraft unitDraft : units) {
            String serial = normalize(unitDraft.manufacturerSerialNumber());
            String normalizedSerial = normalizeSerial(serial);
            String decision = normalizeDecision(unitDraft.qualityDecision());
            GoodsReceiptUnit receiptUnit = new GoodsReceiptUnit();
            receiptUnit.setTenantId(TenantContext.currentTenant());
            receiptUnit.setCompanyId(CompanyContext.currentCompany());
            receiptUnit.setCode("GRU-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
            receiptUnit.setName("Goods receipt unit");
            receiptUnit.setStatus(decision);
            receiptUnit.setGoodsReceiptLineId(line.getId());
            receiptUnit.setGoodsReceiptBatchId(receiptBatchId);
            receiptUnit.setManufacturerSerialNumber(serial);
            receiptUnit.setNormalizedSerialNumber(normalizedSerial);
            receiptUnit.setCondition(normalize(unitDraft.condition()));
            receiptUnit.setQualityDecision(decision);
            receiptUnit.setNotes(normalize(unitDraft.notes()));
            GoodsReceiptUnit savedReceiptUnit = receiptUnitRepository.save(receiptUnit);
            if ("ACCEPTED".equals(decision) && isPhysicalStockMaterial(line.getMaterialId())) {
                MaterialUnit materialUnit = new MaterialUnit();
                materialUnit.setTenantId(TenantContext.currentTenant());
                materialUnit.setCompanyId(CompanyContext.currentCompany());
                materialUnit.setCode("MU-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
                materialUnit.setName("Material unit " + serial);
                materialUnit.setStatus(MaterialUnitStatus.IN_STOCK.name());
                materialUnit.setMaterialId(line.getMaterialId());
                materialUnit.setManufacturerSerialNumber(serial);
                materialUnit.setNormalizedSerialNumber(normalizedSerial);
                materialUnit.setUnitStatus(MaterialUnitStatus.IN_STOCK);
                materialUnit.setWarehouseId(line.getWarehouseId());
                materialUnit.setStorageLocationId(line.getStorageLocationId());
                materialUnit.setStorageBinId(line.getStorageBinId());
                materialUnit.setStockBatchId(stockBatchId);
                materialUnit.setGoodsReceiptLineId(line.getId());
                materialUnit.setGoodsReceiptUnitId(savedReceiptUnit.getId());
                materialUnit.setReceivedAt(receipt.getPostedAt());
                MaterialUnit savedMaterialUnit = materialUnitRepository.save(materialUnit);
                savedReceiptUnit.setMaterialUnitId(savedMaterialUnit.getId());
                receiptUnitRepository.save(savedReceiptUnit);
                recordReceivedEvent(receipt, line, savedMaterialUnit);
                materialUnits++;
            }
        }
        return new UnitRecordCount(units.size(), materialUnits);
    }

    private GoodsReceiptBatch toBatch(GoodsReceiptLine line, ReceiptBatchPostingDraft source, int sequence) {
        GoodsReceiptBatch batch = new GoodsReceiptBatch();
        batch.setTenantId(TenantContext.currentTenant());
        batch.setCompanyId(CompanyContext.currentCompany());
        batch.setCode(line.getCode() + "-B" + sequence);
        batch.setName("Goods receipt batch");
        batch.setStatus(hasException(source) ? "POSTED_WITH_EXCEPTIONS" : "POSTED");
        batch.setGoodsReceiptLineId(line.getId());
        batch.setStockBatchId(source.stockBatchId());
        batch.setStockMovementId(source.stockMovementId());
        batch.setManufacturerBatchNumber(normalize(source.manufacturerBatchNumber()));
        batch.setManufacturedOn(source.manufacturedOn());
        batch.setExpiresOn(source.expiresOn());
        batch.setAcceptedQuantity(nvl(source.acceptedQuantity()));
        batch.setRejectedQuantity(nvl(source.rejectedQuantity()));
        batch.setDamagedQuantity(nvl(source.damagedQuantity()));
        return batch;
    }

    private void recordReceivedEvent(GoodsReceipt receipt, GoodsReceiptLine line, MaterialUnit unit) {
        MaterialUnitEvent event = new MaterialUnitEvent();
        event.setTenantId(TenantContext.currentTenant());
        event.setCompanyId(CompanyContext.currentCompany());
        event.setCode("MUE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        event.setName("Material unit received");
        event.setStatus("RECORDED");
        event.setMaterialUnitId(unit.getId());
        event.setEventType(MaterialUnitEventType.RECEIVED);
        event.setSourceType("GOODS_RECEIPT");
        event.setSourceId(receipt.getId());
        event.setWarehouseId(line.getWarehouseId());
        event.setStorageLocationId(line.getStorageLocationId());
        event.setStorageBinId(line.getStorageBinId());
        event.setStockBatchId(unit.getStockBatchId());
        event.setPerformedBy(currentUsername());
        event.setOccurredAt(receipt.getPostedAt());
        materialUnitEventRepository.save(event);
    }

    private GoodsReceiptLine toLine(
        GoodsReceipt receipt,
        ReceiptPostingDraft draft,
        ReceiptLinePostingDraft source,
        int sequence
    ) {
        GoodsReceiptLine line = new GoodsReceiptLine();
        line.setTenantId(TenantContext.currentTenant());
        line.setCompanyId(CompanyContext.currentCompany());
        line.setCode(receipt.getCode() + "-" + sequence);
        line.setName("Goods receipt line");
        line.setStatus(hasException(source) ? "POSTED_WITH_EXCEPTIONS" : "POSTED");
        line.setGoodsReceiptId(receipt.getId());
        line.setPurchaseOrderId(draft.purchaseOrderId());
        line.setPurchaseOrderItemId(source.purchaseOrderItemId());
        line.setSupplierId(draft.supplierId());
        line.setMaterialId(source.materialId());
        line.setMaterialGroupId(source.materialGroupId());
        line.setUomCode(normalize(source.uomCode()));
        line.setWarehouseId(source.warehouseId());
        line.setStorageLocationId(source.storageLocationId());
        line.setStorageBinId(source.storageBinId());
        line.setBatchNumber(normalize(source.batchNumber()));
        line.setOpenQuantityBefore(nvl(source.openQuantityBefore()));
        line.setPhysicalReceivedQuantity(nvl(source.physicalReceivedQuantity()));
        line.setAcceptedQuantity(nvl(source.acceptedQuantity()));
        line.setRejectedQuantity(nvl(source.rejectedQuantity()));
        line.setDamagedQuantity(nvl(source.damagedQuantity()));
        line.setExcessQuantity(nvl(source.excessQuantity()));
        line.setShortageQuantity(nvl(source.shortageQuantity()));
        line.setShortageReason(normalize(source.shortageReason()));
        line.setQualityStatus(normalize(source.qualityStatus()));
        line.setStockMovementId(source.stockMovementId());
        line.setAccreditationStatus(normalize(source.accreditationStatus()) == null ? "ELIGIBLE" : source.accreditationStatus());
        line.setAccreditationMessage(normalize(source.accreditationMessage()));
        line.setAccreditationExceptionId(source.accreditationExceptionId());
        return line;
    }

    private boolean hasException(ReceiptLinePostingDraft line) {
        return nvl(line.rejectedQuantity()).signum() > 0
            || nvl(line.damagedQuantity()).signum() > 0
            || nvl(line.excessQuantity()).signum() > 0
            || nvl(line.shortageQuantity()).signum() > 0
            || line.accreditationExceptionId() != null;
    }

    private boolean hasException(ReceiptBatchPostingDraft batch) {
        return nvl(batch.rejectedQuantity()).signum() > 0
            || nvl(batch.damagedQuantity()).signum() > 0;
    }

    private boolean isPhysicalStockMaterial(UUID materialId) {
        if (materialRepository == null || materialId == null) {
            return true;
        }
        return materialRepository.findByIdAndIsDeletedFalse(materialId)
            .map(m -> m.getMaterialType() != com.enterprise.mm.enums.MaterialType.SERVICES)
            .orElse(true);
    }

    private void validateLine(UUID purchaseOrderId, ReceiptLinePostingDraft line) {
        if (line == null || line.materialId() == null) {
            throw new IllegalArgumentException("Receipt line and material are required");
        }
        if (purchaseOrderId != null && line.purchaseOrderItemId() == null) {
            throw new IllegalArgumentException("Receipt line, purchase order item and material are required");
        }
        List<BigDecimal> quantities = List.of(
            nvl(line.openQuantityBefore()), nvl(line.physicalReceivedQuantity()), nvl(line.acceptedQuantity()), nvl(line.rejectedQuantity()),
            nvl(line.damagedQuantity()), nvl(line.excessQuantity()), nvl(line.shortageQuantity()));
        if (quantities.stream().anyMatch(value -> value.signum() < 0)) {
            throw new IllegalArgumentException("Receipt line quantities cannot be negative");
        }
        validateBatchQuantities(line);
        boolean hasBatchMovement = line.batches().stream()
            .anyMatch(batch -> nvl(batch.acceptedQuantity()).signum() > 0 && batch.stockMovementId() != null);
        if (nvl(line.acceptedQuantity()).signum() > 0 && line.stockMovementId() == null && !hasBatchMovement) {
            throw new IllegalArgumentException("Accepted receipt quantity requires stock movement provenance");
        }
        if (line.trackingMode() == TrackingMode.SERIAL) {
            validateSerializedLine(line);
        }
    }

    private void validateSerializedLine(ReceiptLinePostingDraft line) {
        List<ReceiptUnitPostingDraft> units = serializedUnits(line);
        BigDecimal unitCount = BigDecimal.valueOf(units.size());
        BigDecimal physicalReceived = nvl(line.physicalReceivedQuantity()).stripTrailingZeros();
        if (unitCount.compareTo(physicalReceived) != 0) {
            throw new IllegalArgumentException("SERIAL_UNIT_COUNT_MISMATCH");
        }
        java.util.HashSet<String> requestSerials = new java.util.HashSet<>();
        long acceptedUnits = 0;
        for (ReceiptUnitPostingDraft unit : units) {
            String decision = normalizeDecision(unit.qualityDecision());
            if ("ACCEPTED".equals(decision)) {
                acceptedUnits++;
            }
            String normalized = normalizeSerial(unit.manufacturerSerialNumber());
            if (normalized == null) {
                throw new IllegalArgumentException("SERIAL_NUMBER_REQUIRED");
            }
            if (!requestSerials.add(normalized)) {
                throw new IllegalArgumentException("DUPLICATE_SERIAL_NUMBER");
            }
            if (materialUnitRepository.existsByTenantIdAndCompanyIdAndMaterialIdAndNormalizedSerialNumberAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), line.materialId(), normalized)) {
                throw new IllegalArgumentException("SERIAL_ALREADY_REGISTERED");
            }
        }
        if (BigDecimal.valueOf(acceptedUnits).compareTo(nvl(line.acceptedQuantity()).stripTrailingZeros()) != 0) {
            throw new IllegalArgumentException("SERIAL_UNIT_COUNT_MISMATCH");
        }
        for (ReceiptBatchPostingDraft batch : line.batches()) {
            long batchAcceptedUnits = batch.units().stream()
                .filter(unit -> "ACCEPTED".equals(normalizeDecision(unit.qualityDecision())))
                .count();
            if (BigDecimal.valueOf(batchAcceptedUnits).compareTo(nvl(batch.acceptedQuantity()).stripTrailingZeros()) != 0) {
                throw new IllegalArgumentException("SERIAL_BATCH_UNIT_COUNT_MISMATCH");
            }
        }
    }

    private List<ReceiptUnitPostingDraft> serializedUnits(ReceiptLinePostingDraft line) {
        if (!line.batches().isEmpty()) {
            return line.batches().stream()
                .flatMap(batch -> batch.units().stream())
                .toList();
        }
        return line.units() == null ? List.of() : line.units();
    }

    private void validateBatchQuantities(ReceiptLinePostingDraft line) {
        if (line.batches().isEmpty()) {
            return;
        }
        if (line.batches().stream().anyMatch(batch ->
            nvl(batch.acceptedQuantity()).signum() < 0
                || nvl(batch.rejectedQuantity()).signum() < 0
                || nvl(batch.damagedQuantity()).signum() < 0)) {
            throw new IllegalArgumentException("Receipt batch quantities cannot be negative");
        }
        if (sumBatches(line, ReceiptBatchPostingDraft::acceptedQuantity).compareTo(nvl(line.acceptedQuantity())) != 0
            || sumBatches(line, ReceiptBatchPostingDraft::rejectedQuantity).compareTo(nvl(line.rejectedQuantity())) != 0
            || sumBatches(line, ReceiptBatchPostingDraft::damagedQuantity).compareTo(nvl(line.damagedQuantity())) != 0) {
            throw new IllegalArgumentException("BATCH_QUANTITY_MISMATCH");
        }
    }

    private BigDecimal sumBatches(
        ReceiptLinePostingDraft line,
        java.util.function.Function<ReceiptBatchPostingDraft, BigDecimal> value
    ) {
        return line.batches().stream().map(value).map(this::nvl).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal sum(List<ReceiptLinePostingDraft> lines, java.util.function.Function<ReceiptLinePostingDraft, BigDecimal> value) {
        return lines.stream().map(value).map(this::nvl).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal positiveOrOne(BigDecimal value) {
        return value == null || value.signum() <= 0 ? BigDecimal.ONE : value;
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String normalizeSerial(String value) {
        String normalized = normalize(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private String normalizeDecision(String value) {
        String normalized = normalize(value);
        return normalized == null ? "ACCEPTED" : normalized.toUpperCase(Locale.ROOT);
    }

    private record UnitRecordCount(int receiptUnits, int materialUnits) {
    }

    private String purchaseOrderName(UUID purchaseOrderId) {
        if (purchaseOrderId == null) {
            return "STANDALONE";
        }
        return purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)
            .filter(order -> TenantContext.currentTenant().equals(order.getTenantId()))
            .filter(order -> CompanyContext.currentCompany().equals(order.getCompanyId()))
            .map(order -> order.getName())
            .orElse(purchaseOrderId.toString());
    }

    private UUID purchaseOrderContractId(UUID purchaseOrderId) {
        if (purchaseOrderId == null) {
            return null;
        }
        return purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)
            .filter(order -> TenantContext.currentTenant().equals(order.getTenantId()))
            .filter(order -> CompanyContext.currentCompany().equals(order.getCompanyId()))
            .map(order -> order.getContractId())
            .orElse(null);
    }

    private String currentUsername() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || authentication.getName() == null || authentication.getName().isBlank()
            ? "system" : authentication.getName();
    }
}
