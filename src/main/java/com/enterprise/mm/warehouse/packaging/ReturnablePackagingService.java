package com.enterprise.mm.warehouse.packaging;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.GoodsReceiptLine;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.enums.MaterialSubtype;
import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.enums.SerialTrackingPolicy;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.repository.GoodsReceiptLineRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.repository.StockMovementRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.service.RoleDataScopeService;
import com.enterprise.mm.service.StockMovementService;
import com.enterprise.mm.service.StockAccountResolver;
import com.enterprise.mm.warehouse.custody.CustodyAgreementService;
import com.enterprise.mm.warehouse.posting.AccountAwareStockPostingService;
import com.enterprise.mm.warehouse.posting.PostingCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

@Service @RequiredArgsConstructor
public class ReturnablePackagingService {
    private final ReturnablePackagingLotRepository lots;
    private final ReturnablePackagingReturnRepository returns;
    private final ReturnablePackagingEventRepository events;
    private final GoodsReceiptLineRepository receiptLines;
    private final MaterialRepository materials;
    private final SupplierRepository suppliers;
    private final StockMovementRepository stockMovements;
    private final WarehouseRepository warehouses;
    private final StockMovementService movements;
    private final AccountAwareStockPostingService accountPosting;
    private final CustodyAgreementService custodyAgreements;
    private final RoleDataScopeService dataScope;
    private final ObjectMapper mapper;
    private final StockAccountResolver stockAccountResolver;

    /** Runs inside the PO receiving transaction, after the content receipt lines were persisted. */
    @Transactional
    public void recordReceipt(UUID receiptId, UUID supplierId, List<PackagingReceiptRequest> inputs) {
        if (inputs == null || inputs.isEmpty()) return;
        var supplier = suppliers.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), supplierId)
            .filter(value -> Objects.equals(value.getCompanyId(), CompanyContext.currentCompany()))
            .orElseThrow(() -> new BadRequestException("Receipt supplier is unavailable"));
        Map<UUID, GoodsReceiptLine> byItem = new HashMap<>();
        for (GoodsReceiptLine line : receiptLines.findByTenantIdAndCompanyIdAndGoodsReceiptIdAndIsDeletedFalseOrderByCreatedAtAsc(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), receiptId)) {
            byItem.put(line.getPurchaseOrderItemId(), line);
        }
        Set<String> unique = new HashSet<>();
        for (PackagingReceiptRequest input : inputs) {
            if (input == null || input.contentPurchaseOrderItemId() == null || input.packagingMaterialId() == null)
                throw new BadRequestException("Packaging content line and material are required");
            GoodsReceiptLine content = byItem.get(input.contentPurchaseOrderItemId());
            if (content == null || content.getAcceptedQuantity().signum() <= 0)
                throw new BadRequestException("Packaging must link to an accepted content line in this receipt");
            if (!unique.add(content.getId() + ":" + input.packagingMaterialId()))
                throw new BadRequestException("Duplicate packaging material for receipt line");
            if (Objects.equals(content.getMaterialId(), input.packagingMaterialId()))
                throw new BadRequestException("Packaging and contents require different nomenclature");
            if (!dataScope.canAccessWarehouse(content.getWarehouseId()))
                throw new BusinessException("Warehouse is outside the user's scope");
            Material material = material(input.packagingMaterialId());
            if (material.getMaterialSubtype() != MaterialSubtype.RETURNABLE_PACKAGING
                || material.getSerialTrackingPolicy() == SerialTrackingPolicy.REQUIRED)
                throw new BadRequestException("Select a quantity-tracked returnable packaging material");
            BigDecimal quantity = positiveWhole(input.quantity());
            String ownership = input.ownership() == null ? "" : input.ownership().trim().toUpperCase(Locale.ROOT);
            if (!Set.of("SUPPLIER", "COMPANY").contains(ownership))
                throw new BadRequestException("Packaging ownership must be SUPPLIER or COMPANY");
            String account = input.accountCode() == null ? "" : input.accountCode().trim().toUpperCase(Locale.ROOT);
            BigDecimal unitCost = unitCost(input.unitCost());
            if ("SUPPLIER".equals(ownership)) {
                if (!account.isEmpty() && !"002".equals(account))
                    throw new BadRequestException("Supplier-owned packaging must use custody account 002");
                account = "002";
                custodyAgreements.validateAgreementForCustodyOperation(
                    TenantContext.currentTenant(), CompanyContext.currentCompany(), supplier.getId(), LocalDate.now());
            } else {
                account = configuredCompanyAccount(material.getId(), account);
            }
            if (lots.findByTenantIdAndCompanyIdAndGoodsReceiptLineIdAndMaterialIdAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), content.getId(), material.getId()).isPresent())
                throw new ConflictException("Packaging receipt already exists");

            ReturnablePackagingLot lot = new ReturnablePackagingLot();
            lot.setCode("PKG-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
            lot.setName("Returnable packaging from " + supplier.getName());
            lot.setStatus("OPEN");
            lot.setSupplierId(supplier.getId());
            lot.setOwnership(ownership);
            lot.setAccountCode(account);
            lot.setUnitCost(unitCost);
            lot.setMaterialId(material.getId());
            lot.setContentMaterialId(content.getMaterialId());
            lot.setPurchaseOrderItemId(content.getPurchaseOrderItemId());
            lot.setGoodsReceiptId(receiptId);
            lot.setGoodsReceiptLineId(content.getId());
            lot.setWarehouseId(content.getWarehouseId());
            lot.setStorageLocationId(content.getStorageLocationId());
            lot.setStorageBinId(content.getStorageBinId());
            lot.setReceivedQuantity(quantity);
            lot.setFilledQuantity(quantity);
            lot.setDueDate(input.dueDate());
            String stockCategory = "SUPPLIER".equals(ownership) ? "CUSTODY" : "OWNED";
            UUID ownerId = "SUPPLIER".equals(ownership) ? supplier.getId() : null;
            var receipt = accountPosting.postReceipt(new AccountAwareStockPostingService.ReceiptRequest(
                postingCommand("RECEIPT", stockCategory, content.getId(),
                    "PKG-RCPT-" + content.getId() + "-" + material.getId(), quantity, account),
                material.getId(), content.getWarehouseId(), content.getStorageLocationId(), content.getStorageBinId(),
                null, ownerId, stockCategory, "AVAILABLE", account, unitCode(material),
                quantity, unitCost, "UZS", "PACKAGING_RECEIPT:" + content.getId()));
            lot.setReceiptPostingBatchId(receipt.batchId());
            ReturnablePackagingLot saved = lots.save(lot);
            event(saved, null, "RECEIVED", quantity, null, "FILLED", saved.getReceiptStockMovementId(),
                saved.getReceiptPostingBatchId());
        }
    }

    @Transactional(readOnly = true)
    public void validateManualRequest(UUID supplierId, List<ManualPackagingReceiptRequest> inputs,
        List<UUID> contentMaterialIds) {
        if (inputs == null || inputs.isEmpty()) return;
        suppliers.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), supplierId)
            .filter(value -> Objects.equals(value.getCompanyId(), CompanyContext.currentCompany()))
            .orElseThrow(() -> new BadRequestException("Receipt supplier is unavailable"));
        for (ManualPackagingReceiptRequest input : inputs) {
            if (input == null || input.contentLineIndex() == null || input.contentLineIndex() < 0 || input.contentLineIndex() >= contentMaterialIds.size()
                || input.packagingMaterialId() == null)
                throw new BadRequestException("Packaging needs a valid content line and material");
            if (Objects.equals(contentMaterialIds.get(input.contentLineIndex()), input.packagingMaterialId()))
                throw new BadRequestException("Packaging and contents require different nomenclature");
            Material packaging = material(input.packagingMaterialId());
            if (packaging.getMaterialSubtype() != MaterialSubtype.RETURNABLE_PACKAGING
                || packaging.getSerialTrackingPolicy() == SerialTrackingPolicy.REQUIRED)
                throw new BadRequestException("Select a quantity-tracked returnable packaging material");
            positiveWhole(input.quantity());
            String ownership = input.ownership() == null ? "" : input.ownership().trim().toUpperCase(Locale.ROOT);
            String account = input.accountCode() == null ? "" : input.accountCode().trim().toUpperCase(Locale.ROOT);
            BigDecimal cost = unitCost(input.unitCost());
            if ("COMPANY".equals(ownership)) {
                configuredCompanyAccount(packaging.getId(), account);
            } else if ("SUPPLIER".equals(ownership)) {
                if (!account.isEmpty() && !"002".equals(account))
                    throw new BadRequestException("Supplier-owned packaging must use custody account 002");
                custodyAgreements.validateAgreementForCustodyOperation(TenantContext.currentTenant(),
                    CompanyContext.currentCompany(), supplierId, LocalDate.now());
            } else throw new BadRequestException("Packaging ownership must be SUPPLIER or COMPANY");
        }
    }

    /** Adds packaging only after the manual content movements have posted at final approval. */
    @Transactional
    public void recordManualReceipt(UUID documentId, UUID supplierId, List<ManualPackagingReceiptRequest> inputs,
        List<StockMovement> contentMovements) {
        if (inputs == null || inputs.isEmpty()) return;
        var supplier = suppliers.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), supplierId)
            .filter(value -> Objects.equals(value.getCompanyId(), CompanyContext.currentCompany()))
            .orElseThrow(() -> new BadRequestException("Receipt supplier is unavailable"));
        Set<String> unique = new HashSet<>();
        for (ManualPackagingReceiptRequest input : inputs) {
            if (input == null || input.contentLineIndex() == null || input.contentLineIndex() < 0 || input.contentLineIndex() >= contentMovements.size()
                || input.packagingMaterialId() == null)
                throw new BadRequestException("Packaging needs a valid content line and material");
            StockMovement content = contentMovements.get(input.contentLineIndex());
            if (content.getId() == null || content.getQuantity().signum() <= 0
                || content.getMovementType() != MovementType.RECEIPT)
                throw new BadRequestException("Packaging needs a posted content receipt");
            if (!unique.add(content.getId() + ":" + input.packagingMaterialId()))
                throw new BadRequestException("Duplicate packaging material for content line");
            if (Objects.equals(content.getMaterialId(), input.packagingMaterialId()))
                throw new BadRequestException("Packaging and contents require different nomenclature");
            if (!dataScope.canAccessWarehouse(content.getWarehouseId()))
                throw new BusinessException("Warehouse is outside the user's scope");
            Material material = material(input.packagingMaterialId());
            if (material.getMaterialSubtype() != MaterialSubtype.RETURNABLE_PACKAGING
                || material.getSerialTrackingPolicy() == SerialTrackingPolicy.REQUIRED)
                throw new BadRequestException("Select a quantity-tracked returnable packaging material");
            BigDecimal quantity = positiveWhole(input.quantity());
            String ownership = input.ownership() == null ? "" : input.ownership().trim().toUpperCase(Locale.ROOT);
            if (!Set.of("SUPPLIER", "COMPANY").contains(ownership))
                throw new BadRequestException("Packaging ownership must be SUPPLIER or COMPANY");
            String account = input.accountCode() == null ? "" : input.accountCode().trim().toUpperCase(Locale.ROOT);
            BigDecimal unitCost = unitCost(input.unitCost());
            if ("SUPPLIER".equals(ownership)) {
                if (!account.isEmpty() && !"002".equals(account))
                    throw new BadRequestException("Supplier-owned packaging must use custody account 002");
                account = "002";
                custodyAgreements.validateAgreementForCustodyOperation(TenantContext.currentTenant(),
                    CompanyContext.currentCompany(), supplierId, LocalDate.now());
            } else {
                account = configuredCompanyAccount(material.getId(), account);
            }
            ReturnablePackagingLot lot = new ReturnablePackagingLot();
            lot.setCode("PKG-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
            lot.setName("Returnable packaging from " + supplier.getName());
            lot.setStatus("OPEN");
            lot.setSupplierId(supplierId);
            lot.setOwnership(ownership);
            lot.setAccountCode(account);
            lot.setUnitCost(unitCost);
            lot.setMaterialId(material.getId());
            lot.setContentMaterialId(content.getMaterialId());
            lot.setSourceType("MANUAL_WAREHOUSE");
            lot.setManualDocumentId(documentId);
            lot.setManualContentMovementId(content.getId());
            lot.setDueDate(input.dueDate());
            lot.setWarehouseId(content.getWarehouseId());
            lot.setStorageLocationId(content.getStorageLocationId());
            lot.setStorageBinId(content.getStorageBinId());
            lot.setReceivedQuantity(quantity);
            lot.setFilledQuantity(quantity);
            String stockCategory = "SUPPLIER".equals(ownership) ? "CUSTODY" : "OWNED";
            UUID ownerId = "SUPPLIER".equals(ownership) ? supplierId : null;
            var receipt = accountPosting.postReceipt(new AccountAwareStockPostingService.ReceiptRequest(
                postingCommand("RECEIPT", stockCategory, content.getId(),
                    "PKG-MANUAL-RCPT-" + content.getId() + "-" + material.getId(), quantity, account),
                material.getId(), content.getWarehouseId(), content.getStorageLocationId(), content.getStorageBinId(),
                null, ownerId, stockCategory, "AVAILABLE", account, unitCode(material), quantity,
                unitCost, "UZS", "PACKAGING_MANUAL_RECEIPT:" + content.getId()));
            lot.setReceiptPostingBatchId(receipt.batchId());
            ReturnablePackagingLot saved = lots.save(lot);
            event(saved, null, "RECEIVED", quantity, null, "FILLED", saved.getReceiptStockMovementId(),
                saved.getReceiptPostingBatchId());
        }
    }

    @Transactional(readOnly = true)
    public Page<ReturnablePackagingDtos.LotView> register(ReturnablePackagingDtos.LotFilters filter, Pageable pageable) {
        UUID effectiveWarehouseId = dataScope.effectiveWarehouseId(filter.warehouseId());
        return lots.search(TenantContext.currentTenant(), CompanyContext.currentCompany(), filter.supplierId(),
            filter.materialId(), filter.contentMaterialId(), effectiveWarehouseId,
            enumFilter(filter.ownership(), Set.of("COMPANY", "SUPPLIER"), "ownership"),
            enumFilter(filter.sourceType(), Set.of("PURCHASE_ORDER", "MANUAL_WAREHOUSE", "LEGACY_OPENING"), "sourceType"),
            enumFilter(filter.state(), Set.of("FILLED", "PARTIAL", "EMPTY", "DAMAGED", "UNKNOWN"), "state"),
            filter.dueFrom(), filter.dueTo(), filter.onlyOnHand(), search(filter.search()), pageable).map(this::view);
    }

    @Transactional(readOnly = true)
    public Page<ReturnablePackagingDtos.ReturnRegisterView> returnRegister(ReturnablePackagingDtos.ReturnFilters filter,
        Pageable pageable) {
        UUID warehouseId = dataScope.effectiveWarehouseId(filter.warehouseId());
        return returns.search(TenantContext.currentTenant(), CompanyContext.currentCompany(), warehouseId,
            filter.supplierId(), filter.materialId(),
            enumFilter(filter.status(), Set.of("DRAFT", "APPROVED", "DISPATCHED", "PARTIALLY_CONFIRMED",
                "CONFIRMED", "CANCELLED", "RECALLED", "PARTIALLY_RECALLED"), "status"),
            enumFilter(filter.ownership(), Set.of("COMPANY", "SUPPLIER"), "ownership"),
            search(filter.search()), pageable)
            .map(value -> new ReturnablePackagingDtos.ReturnRegisterView(view(value), view(lot(value.getLotId(), false))));
    }

    @Transactional(readOnly = true)
    public String exportLots(ReturnablePackagingDtos.LotFilters filter, Sort sort) {
        StringBuilder csv = new StringBuilder("lotCode,supplier,packagingMaterial,contentMaterial,ownership,sourceType,warehouseId,received,filled,partial,empty,damaged,reserved,dispatched,confirmed,onHand,dueDate,accountCode,unitCost,amount,unknown,legacyStockBalanceId\r\n");
        int page = 0;
        Page<ReturnablePackagingDtos.LotView> result;
        do {
            result = register(filter, PageRequest.of(page++, 100, sort));
            for (var value : result) {
                csv.append(csv(value.code())).append(',').append(csv(value.supplierName())).append(',')
                    .append(csv(value.materialName())).append(',').append(csv(value.contentMaterialName())).append(',')
                    .append(csv(value.ownership())).append(',').append(csv(value.sourceType())).append(',')
                    .append(csv(value.warehouseId())).append(',').append(csv(value.receivedQuantity())).append(',')
                    .append(csv(value.filledQuantity())).append(',').append(csv(value.partialQuantity())).append(',')
                    .append(csv(value.emptyQuantity())).append(',').append(csv(value.damagedQuantity())).append(',')
                    .append(csv(value.reservedQuantity())).append(',').append(csv(value.dispatchedQuantity())).append(',')
                    .append(csv(value.confirmedQuantity())).append(',').append(csv(value.onHandQuantity())).append(',')
                    .append(csv(value.dueDate())).append(',').append(csv(value.accountCode())).append(',')
                    .append(csv(value.unitCost())).append(',').append(csv(value.amount())).append(',')
                    .append(csv(value.unknownQuantity())).append(',')
                    .append(csv(value.legacyStockBalanceId())).append("\r\n");
            }
        } while (result.hasNext());
        return csv.toString();
    }

    @Transactional(readOnly = true)
    public String exportReturns(ReturnablePackagingDtos.ReturnFilters filter, Sort sort) {
        StringBuilder csv = new StringBuilder("returnCode,lotCode,supplier,packagingMaterial,ownership,unitCost,amount,status,quantity,confirmed,recalled,documentNumber,createdAt,dispatchedAt,confirmedAt\r\n");
        int page = 0;
        Page<ReturnablePackagingDtos.ReturnRegisterView> result;
        do {
            result = returnRegister(filter, PageRequest.of(page++, 100, sort));
            for (var row : result) {
                var doc = row.document(); var lot = row.lot();
                csv.append(csv(doc.code())).append(',').append(csv(lot.code())).append(',')
                    .append(csv(lot.supplierName())).append(',').append(csv(lot.materialName())).append(',')
                    .append(csv(lot.ownership())).append(',').append(csv(lot.unitCost())).append(',')
                    .append(csv(lot.amount())).append(',').append(csv(doc.status())).append(',')
                    .append(csv(doc.quantity())).append(',').append(csv(doc.confirmedQuantity())).append(',')
                    .append(csv(doc.recalledQuantity())).append(',').append(csv(doc.documentNumber())).append(',')
                    .append(csv(doc.createdAt())).append(',').append(csv(doc.dispatchedAt())).append(',')
                    .append(csv(doc.confirmedAt())).append("\r\n");
            }
        } while (result.hasNext());
        return csv.toString();
    }

    private static String csv(Object value) {
        if (value == null) return "";
        String raw = value.toString().replace("\r", " ").replace("\n", " ");
        String leading = raw.stripLeading();
        if (!leading.isEmpty() && "=+-@".indexOf(leading.charAt(0)) >= 0) raw = "'" + raw;
        return "\"" + raw.replace("\"", "\"\"") + "\"";
    }

    private static String enumFilter(String value, Set<String> allowed, String label) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw new BadRequestException("Unsupported " + label + ": " + value);
        return normalized;
    }

    private static String search(String value) {
        if (value == null || value.isBlank()) return null;
        if (value.length() > 120) throw new BadRequestException("Search is too long");
        return "%" + value.trim().toLowerCase().replace("\\", "\\\\")
            .replace("%", "\\%").replace("_", "\\_") + "%";
    }

    @Transactional(readOnly = true)
    public ReturnablePackagingDtos.LotView get(UUID id) { return view(lot(id, false)); }

    @Transactional
    public ReturnablePackagingDtos.LotView changeState(UUID id, ReturnablePackagingDtos.ChangeStateRequest request) {
        ReturnablePackagingLot lot = lot(id, true);
        version(lot.getVersion(), request == null ? null : request.expectedVersion());
        BigDecimal quantity = positiveWhole(request.quantity());
        String from = request.fromState() == null ? "" : request.fromState().toUpperCase(Locale.ROOT);
        String to = request.toState() == null ? "" : request.toState().toUpperCase(Locale.ROOT);
        if (!("FILLED".equals(from) && Set.of("PARTIAL", "EMPTY", "DAMAGED").contains(to))
            && !("PARTIAL".equals(from) && Set.of("EMPTY", "DAMAGED").contains(to))
            && !("EMPTY".equals(from) && "DAMAGED".equals(to))
            && !("UNKNOWN".equals(from) && Set.of("FILLED", "PARTIAL", "EMPTY", "DAMAGED").contains(to)))
            throw new BadRequestException("Unsupported packaging state transition");
        if ("UNKNOWN".equals(from) && (request.reason() == null || request.reason().isBlank()))
            throw new BadRequestException("Record the reason for classifying an opening packaging balance");
        if ("DAMAGED".equals(to) && (request.reason() == null || request.reason().isBlank()))
            throw new BadRequestException("Damage reason is required");
        if (request.contentMovementId() != null) {
            StockMovement evidence = stockMovements.findById(request.contentMovementId())
                .orElseThrow(() -> new BadRequestException("Content movement was not found"));
            if (!Objects.equals(evidence.getTenantId(), TenantContext.currentTenant())
                || !Objects.equals(evidence.getCompanyId(), CompanyContext.currentCompany())
                || !Objects.equals(evidence.getMaterialId(), lot.getContentMaterialId())
                || !Objects.equals(evidence.getWarehouseId(), lot.getWarehouseId())
                || evidence.getMovementType() != MovementType.ISSUE || !"POSTED".equals(evidence.getStatus()))
                throw new BadRequestException("Link a posted issue of this lot's contents in the same warehouse");
        }
        BigDecimal available = state(lot, from);
        if ("EMPTY".equals(from)) available = available.subtract(lot.getReservedQuantity());
        if (available.compareTo(quantity) < 0) throw new ConflictException("Packaging quantity is not available in source state");
        setState(lot, from, state(lot, from).subtract(quantity));
        setState(lot, to, state(lot, to).add(quantity));
        ReturnablePackagingLot saved = lots.saveAndFlush(lot);
        event(saved, null, "STATE_CHANGED", quantity, from, to, null, null,
            request.reason(), request.contentMovementId());
        return view(saved);
    }

    @Transactional
    public ReturnablePackagingDtos.ReturnView createReturn(ReturnablePackagingDtos.CreateReturnRequest request) {
        if (request == null || request.lotId() == null) throw new BadRequestException("Packaging lot is required");
        ReturnablePackagingLot lot = lot(request.lotId(), true);
        BigDecimal quantity = positiveWhole(request.quantity());
        if (lot.getEmptyQuantity().subtract(lot.getReservedQuantity()).compareTo(quantity) < 0)
            throw new ConflictException("Not enough empty packaging to return");
        UUID receivingSupplierId = lot.getSupplierId();
        if (receivingSupplierId == null) {
            if (!"LEGACY_OPENING".equals(lot.getSourceType()) || request.supplierId() == null)
                throw new BadRequestException("Select the receiving supplier for this opening packaging balance");
            var supplier = suppliers.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), request.supplierId())
                .filter(value -> Objects.equals(value.getCompanyId(), CompanyContext.currentCompany()))
                .orElseThrow(() -> new BadRequestException("Receiving supplier is unavailable"));
            receivingSupplierId = supplier.getId();
        } else if (request.supplierId() != null && !request.supplierId().equals(lot.getSupplierId())) {
            throw new BadRequestException("The receiving supplier differs from the packaging lot");
        }
        ReturnablePackagingReturn value = new ReturnablePackagingReturn();
        value.setCode("PKG-RT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        value.setName("Packaging return to supplier");
        value.setStatus("DRAFT");
        value.setLotId(lot.getId());
        value.setSupplierId(receivingSupplierId);
        value.setQuantity(quantity);
        value.setReason(request.reason());
        ReturnablePackagingReturn saved = returns.save(value);
        event(lot, saved, "RETURN_CREATED", quantity, "EMPTY", "DRAFT", null, null);
        return view(saved);
    }

    @Transactional
    public ReturnablePackagingDtos.ReturnView approve(UUID id, ReturnablePackagingDtos.VersionRequest request) {
        ReturnablePackagingReturn value = returnDoc(id, true);
        version(value.getVersion(), request == null ? null : request.expectedVersion());
        if (!"DRAFT".equals(value.getStatus())) throw new ConflictException("Only draft return can be approved");
        ReturnablePackagingLot lot = lot(value.getLotId(), true);
        if (lot.getEmptyQuantity().subtract(lot.getReservedQuantity()).compareTo(value.getQuantity()) < 0)
            throw new ConflictException("Not enough unreserved empty packaging");
        lot.setReservedQuantity(lot.getReservedQuantity().add(value.getQuantity()));
        lots.save(lot);
        value.setStatus("APPROVED");
        value.setApprovedAt(LocalDateTime.now());
        event(lot, value, "RETURN_APPROVED", value.getQuantity(), "EMPTY", "RESERVED", null, null);
        return view(returns.saveAndFlush(value));
    }

    @Transactional
    public ReturnablePackagingDtos.ReturnView dispatch(UUID id, ReturnablePackagingDtos.DispatchRequest request) {
        ReturnablePackagingReturn value = returnDoc(id, true);
        version(value.getVersion(), request == null ? null : request.expectedVersion());
        if (!"APPROVED".equals(value.getStatus())) throw new ConflictException("Only approved return can be dispatched");
        if (request.documentNumber() == null || request.documentNumber().isBlank())
            throw new BadRequestException("Dispatch document number is required");
        ReturnablePackagingLot lot = lot(value.getLotId(), true);
        if (lot.getReservedQuantity().compareTo(value.getQuantity()) < 0
            || lot.getEmptyQuantity().compareTo(value.getQuantity()) < 0)
            throw new ConflictException("Approved packaging is no longer available");
        if ("COMPANY".equals(lot.getOwnership()) && lot.getReceiptPostingBatchId() == null) {
            StockMovement movement = movement(lot, MovementType.ISSUE, value.getQuantity(),
                "RETURNABLE_PACKAGING_RETURN", value.getId());
            movement.setStockBatchId(lot.getStockBatchId());
            movement.setUnitCost(lot.getUnitCost());
            value.setDispatchStockMovementId(movements.postReturnablePackagingMovement(movement).getId());
        } else {
            boolean supplierOwned = "SUPPLIER".equals(lot.getOwnership());
            if (supplierOwned) custodyAgreements.validateAgreementForCustodyOperation(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), lot.getSupplierId(), LocalDate.now());
            Material material = material(lot.getMaterialId());
            String stockCategory = supplierOwned ? "CUSTODY" : "OWNED";
            var posting = accountPosting.postIssue(new AccountAwareStockPostingService.IssueRequest(
                postingCommand("ISSUE", stockCategory, value.getId(), "PKG-RETURN-" + value.getId(),
                    value.getQuantity(), lot.getAccountCode()),
                lot.getMaterialId(), lot.getWarehouseId(), lot.getStorageLocationId(), lot.getStorageBinId(),
                lot.getStockBatchId(), supplierOwned ? lot.getSupplierId() : null, stockCategory, "AVAILABLE", lot.getAccountCode(),
                unitCode(material), value.getQuantity(), lot.getUnitCost(), "UZS", "PACKAGING_RETURN:" + value.getId()));
            value.setDispatchPostingBatchId(posting.batchId());
        }
        lot.setReservedQuantity(lot.getReservedQuantity().subtract(value.getQuantity()));
        lot.setEmptyQuantity(lot.getEmptyQuantity().subtract(value.getQuantity()));
        lot.setDispatchedQuantity(lot.getDispatchedQuantity().add(value.getQuantity()));
        lots.save(lot);
        value.setStatus("DISPATCHED");
        value.setDocumentNumber(request.documentNumber().trim());
        value.setDispatchedAt(LocalDateTime.now());
        event(lot, value, "DISPATCHED", value.getQuantity(), "RESERVED", "DISPATCHED",
            value.getDispatchStockMovementId(), value.getDispatchPostingBatchId());
        return view(returns.saveAndFlush(value));
    }

    @Transactional
    public ReturnablePackagingDtos.ReturnView confirm(UUID id, ReturnablePackagingDtos.ConfirmRequest request) {
        ReturnablePackagingReturn value = returnDoc(id, true);
        version(value.getVersion(), request == null ? null : request.expectedVersion());
        if (!Set.of("DISPATCHED", "PARTIALLY_CONFIRMED").contains(value.getStatus()))
            throw new ConflictException("Only dispatched packaging can be confirmed");
        BigDecimal quantity = positiveWhole(request.acceptedQuantity());
        if (value.getConfirmedQuantity().add(value.getRecalledQuantity()).add(quantity).compareTo(value.getQuantity()) > 0)
            throw new ConflictException("Supplier confirmation exceeds dispatched quantity");
        ReturnablePackagingLot lot = lot(value.getLotId(), true);
        value.setConfirmedQuantity(value.getConfirmedQuantity().add(quantity));
        lot.setConfirmedQuantity(lot.getConfirmedQuantity().add(quantity));
        value.setStatus(value.getConfirmedQuantity().compareTo(value.getQuantity()) == 0 ? "CONFIRMED" : "PARTIALLY_CONFIRMED");
        if ("CONFIRMED".equals(value.getStatus())) value.setConfirmedAt(LocalDateTime.now());
        lots.save(lot);
        event(lot, value, "SUPPLIER_CONFIRMED", quantity, "DISPATCHED", "CONFIRMED", null, null);
        return view(returns.saveAndFlush(value));
    }

    @Transactional
    public ReturnablePackagingDtos.ReturnView cancel(UUID id, ReturnablePackagingDtos.VersionRequest request) {
        ReturnablePackagingReturn value = returnDoc(id, true);
        version(value.getVersion(), request == null ? null : request.expectedVersion());
        if (!Set.of("DRAFT", "APPROVED").contains(value.getStatus()))
            throw new ConflictException("Dispatched return requires a documented correction");
        if ("APPROVED".equals(value.getStatus())) {
            ReturnablePackagingLot lot = lot(value.getLotId(), true);
            lot.setReservedQuantity(lot.getReservedQuantity().subtract(value.getQuantity()));
            lots.save(lot);
            event(lot, value, "RETURN_CANCELLED", value.getQuantity(), "RESERVED", "EMPTY", null, null);
        } else {
            event(lot(value.getLotId(), true), value, "RETURN_CANCELLED", value.getQuantity(), "DRAFT", "EMPTY", null, null);
        }
        value.setStatus("CANCELLED");
        return view(returns.saveAndFlush(value));
    }

    @Transactional
    public ReturnablePackagingDtos.ReturnView recall(UUID id, ReturnablePackagingDtos.RecallRequest request) {
        ReturnablePackagingReturn value = returnDoc(id, true);
        version(value.getVersion(), request == null ? null : request.expectedVersion());
        if (!Set.of("DISPATCHED", "PARTIALLY_CONFIRMED").contains(value.getStatus()))
            throw new ConflictException("Only an unresolved dispatch can be recalled");
        if (request.reason() == null || request.reason().isBlank())
            throw new BadRequestException("Recall reason is required");
        ReturnablePackagingLot lot = lot(value.getLotId(), true);
        BigDecimal outstanding = value.getQuantity().subtract(value.getConfirmedQuantity()).subtract(value.getRecalledQuantity());
        BigDecimal recallQuantity = request.quantity() == null ? outstanding : positiveWhole(request.quantity());
        if (outstanding.signum() <= 0 || recallQuantity.compareTo(outstanding) != 0)
            throw new ConflictException("Recall must resolve the full outstanding quantity");
        if ("COMPANY".equals(lot.getOwnership()) && value.getDispatchPostingBatchId() == null) {
            StockMovement movement = movement(lot, MovementType.RECEIPT, recallQuantity,
                "RETURNABLE_PACKAGING_RECALL", value.getId());
            movement.setStockBatchId(lot.getStockBatchId());
            movement.setUnitCost(lot.getUnitCost());
            var posted = movements.postReturnablePackagingMovement(movement);
            value.setRecallStockMovementId(posted.getId());
            lot.setStockBatchId(posted.getStockBatchId());
        } else {
            boolean supplierOwned = "SUPPLIER".equals(lot.getOwnership());
            if (supplierOwned) custodyAgreements.validateAgreementForCustodyOperation(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), lot.getSupplierId(), LocalDate.now());
            Material material = material(lot.getMaterialId());
            String stockCategory = supplierOwned ? "CUSTODY" : "OWNED";
            var receipt = accountPosting.postReceipt(new AccountAwareStockPostingService.ReceiptRequest(
                postingCommand("RECEIPT", stockCategory, value.getId(), "PKG-RECALL-" + value.getId(),
                    recallQuantity, lot.getAccountCode()),
                lot.getMaterialId(), lot.getWarehouseId(), lot.getStorageLocationId(), lot.getStorageBinId(),
                lot.getStockBatchId(), supplierOwned ? lot.getSupplierId() : null, stockCategory, "AVAILABLE", lot.getAccountCode(),
                unitCode(material), recallQuantity, lot.getUnitCost(), "UZS", "PACKAGING_RECALL:" + value.getId()));
            value.setRecallPostingBatchId(receipt.batchId());
        }
        lot.setDispatchedQuantity(lot.getDispatchedQuantity().subtract(recallQuantity));
        lot.setEmptyQuantity(lot.getEmptyQuantity().add(recallQuantity));
        lots.save(lot);
        value.setRecalledQuantity(recallQuantity);
        value.setStatus(value.getConfirmedQuantity().signum() == 0 ? "RECALLED" : "PARTIALLY_RECALLED");
        value.setRecallReason(request.reason().trim());
        value.setRecalledAt(LocalDateTime.now());
        event(lot, value, "DISPATCH_RECALLED", recallQuantity, "DISPATCHED", "EMPTY",
            value.getRecallStockMovementId(), value.getRecallPostingBatchId(), request.reason(), null);
        return view(returns.saveAndFlush(value));
    }

    @Transactional(readOnly = true)
    public List<ReturnablePackagingDtos.ReturnView> returns(UUID lotId) {
        lot(lotId, false);
        return returns.findByTenantIdAndCompanyIdAndLotIdAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), lotId).stream()
            .map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public List<ReturnablePackagingDtos.EventView> events(UUID lotId) {
        lot(lotId, false);
        return events.findByTenantIdAndCompanyIdAndLotIdAndIsDeletedFalseOrderByCreatedAtDescIdDesc(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), lotId).stream()
            .map(value -> new ReturnablePackagingDtos.EventView(value.getId(), value.getEventType(),
                value.getQuantity(), value.getFromState(), value.getToState(), value.getReturnId(),
                value.getStockMovementId(), value.getPostingBatchId(), value.getCreatedAt(), value.getCreatedBy(),
                value.getReason(), value.getContentMovementId())).toList();
    }

    private ReturnablePackagingLot lot(UUID id, boolean lock) {
        var found = lock
            ? lots.findWithLockByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany(), id)
            : lots.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany(), id);
        ReturnablePackagingLot lot = found.orElseThrow(() -> new NotFoundException("Packaging lot not found"));
        if (!dataScope.canAccessWarehouse(lot.getWarehouseId())) throw new BusinessException("Warehouse is outside the user's scope");
        return lot;
    }

    private ReturnablePackagingReturn returnDoc(UUID id, boolean lock) {
        var found = lock
            ? returns.findWithLockByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany(), id)
            : returns.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany(), id);
        ReturnablePackagingReturn value = found.orElseThrow(() -> new NotFoundException("Packaging return not found"));
        lot(value.getLotId(), false);
        return value;
    }

    private Material material(UUID id) {
        return materials.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), id)
            .filter(value -> Objects.equals(value.getCompanyId(), CompanyContext.currentCompany()))
            .orElseThrow(() -> new NotFoundException("Packaging material not found"));
    }

    private String configuredCompanyAccount(UUID materialId, String submittedAccount) {
        String resolved = stockAccountResolver.resolveForMaterial(materialId).accountCode();
        if (submittedAccount != null && !submittedAccount.isBlank()
            && !resolved.equalsIgnoreCase(submittedAccount.trim())) {
            throw new ConflictException("Configured stock account changed; refresh the receipt and retry");
        }
        return resolved;
    }

    private static BigDecimal positiveWhole(BigDecimal value) {
        if (value == null || value.signum() <= 0 || value.stripTrailingZeros().scale() > 0)
            throw new BadRequestException("Packaging quantity must be a positive whole number");
        return value;
    }

    private static BigDecimal unitCost(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.stripTrailingZeros().scale() > 4)
            throw new BadRequestException("Packaging unit cost is required and must be non-negative with at most four decimals");
        return value;
    }

    private static void version(Long actual, Long expected) {
        if (expected == null || !Objects.equals(actual, expected))
            throw new ConflictException("Packaging record changed; refresh and retry");
    }

    private static BigDecimal state(ReturnablePackagingLot lot, String state) {
        return switch (state) {
            case "FILLED" -> lot.getFilledQuantity();
            case "PARTIAL" -> lot.getPartialQuantity();
            case "EMPTY" -> lot.getEmptyQuantity();
            case "DAMAGED" -> lot.getDamagedQuantity();
            case "UNKNOWN" -> lot.getUnknownQuantity();
            default -> throw new BadRequestException("Unknown packaging state");
        };
    }

    private static void setState(ReturnablePackagingLot lot, String state, BigDecimal value) {
        switch (state) {
            case "FILLED" -> lot.setFilledQuantity(value);
            case "PARTIAL" -> lot.setPartialQuantity(value);
            case "EMPTY" -> lot.setEmptyQuantity(value);
            case "DAMAGED" -> lot.setDamagedQuantity(value);
            case "UNKNOWN" -> lot.setUnknownQuantity(value);
            default -> throw new BadRequestException("Unknown packaging state");
        }
    }

    private static StockMovement movement(ReturnablePackagingLot lot, MovementType type, BigDecimal quantity,
        String referenceType, UUID referenceId) {
        StockMovement value = new StockMovement();
        value.setCode("PKG-MV-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        value.setName("Returnable packaging " + type.name().toLowerCase());
        value.setMaterialId(lot.getMaterialId());
        value.setWarehouseId(lot.getWarehouseId());
        value.setStorageLocationId(lot.getStorageLocationId());
        value.setStorageBinId(lot.getStorageBinId());
        value.setMovementType(type);
        value.setQuantity(quantity);
        value.setReferenceType(referenceType);
        value.setReferenceId(referenceId);
        value.setMovementDate(LocalDate.now());
        return value;
    }

    private void event(ReturnablePackagingLot lot, ReturnablePackagingReturn document, String type, BigDecimal quantity,
        String from, String to, UUID movementId, UUID postingBatchId) {
        event(lot, document, type, quantity, from, to, movementId, postingBatchId, null, null);
    }

    private void event(ReturnablePackagingLot lot, ReturnablePackagingReturn document, String type, BigDecimal quantity,
        String from, String to, UUID movementId, UUID postingBatchId, String reason, UUID contentMovementId) {
        ReturnablePackagingEvent value = new ReturnablePackagingEvent();
        value.setName(type);
        value.setStatus("POSTED");
        value.setLotId(lot.getId());
        value.setReturnId(document == null ? null : document.getId());
        value.setEventType(type);
        value.setQuantity(quantity);
        value.setFromState(from);
        value.setToState(to);
        value.setStockMovementId(movementId);
        value.setPostingBatchId(postingBatchId);
        value.setReason(reason == null ? null : reason.trim());
        value.setContentMovementId(contentMovementId);
        events.save(value);
    }

    private PostingCommand postingCommand(String operation, String contextKey, UUID resource, String key,
        BigDecimal quantity, String account) {
        return new PostingCommand("WAREHOUSE", operation, contextKey, resource, key, LocalDate.now(), 1L,
            mapper.createObjectNode().put("qty", quantity.toPlainString()).put("accountCode", account));
    }

    private static String unitCode(Material material) {
        if (material.getUnitOfMeasure() == null || material.getUnitOfMeasure().getCode() == null)
            throw new BadRequestException("Packaging material needs a unit of measure");
        return material.getUnitOfMeasure().getCode();
    }

    private ReturnablePackagingDtos.LotView view(ReturnablePackagingLot lot) {
        String supplier = lot.getSupplierId() == null ? null
            : suppliers.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), lot.getSupplierId())
                .map(value -> value.getName()).orElse("");
        String material = material(lot.getMaterialId()).getName();
        String content = lot.getContentMaterialId() == null ? null : material(lot.getContentMaterialId()).getName();
        String warehouseName = warehouses.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), lot.getWarehouseId())
            .map(value -> value.getName()).orElse(null);
        return new ReturnablePackagingDtos.LotView(lot.getId(), lot.getCode(), lot.getVersion(), lot.getSupplierId(), supplier,
            lot.getMaterialId(), material, lot.getContentMaterialId(), content, lot.getGoodsReceiptId(),
            lot.getGoodsReceiptLineId(), lot.getWarehouseId(), warehouseName, lot.getOwnership(), lot.getAccountCode(), lot.getUnitCost(),
            packagingAmount(lot.getReceivedQuantity(), lot.getUnitCost()),
            lot.getReceivedQuantity(), lot.getFilledQuantity(), lot.getPartialQuantity(), lot.getEmptyQuantity(),
            lot.getDamagedQuantity(), lot.getReservedQuantity(), lot.getDispatchedQuantity(), lot.getConfirmedQuantity(),
            lot.getReceivedQuantity().subtract(lot.getDispatchedQuantity()),
            lot.getDispatchedQuantity().subtract(lot.getConfirmedQuantity()), lot.getSourceType(),
            lot.getManualDocumentId(), lot.getManualContentMovementId(), lot.getDueDate(),
            accountPostingMode(lot),
            lot.getCreatedAt(), lot.getUnknownQuantity(), lot.getLegacyStockBalanceId(),
            lot.getReceiptStockMovementId());
    }

    private static BigDecimal packagingAmount(BigDecimal quantity, BigDecimal unitCost) {
        if (quantity == null || unitCost == null) return null;
        return quantity.multiply(unitCost).setScale(4, RoundingMode.HALF_UP);
    }

    private static String accountPostingMode(ReturnablePackagingLot lot) {
        if ("SUPPLIER".equals(lot.getOwnership())) return "CUSTODY_SUBLEDGER";
        return lot.getReceiptPostingBatchId() == null ? "LEGACY_STOCK_MOVEMENT" : "OWNED_ACCOUNT_SUBLEDGER";
    }

    private ReturnablePackagingDtos.ReturnView view(ReturnablePackagingReturn value) {
        String supplierName = suppliers.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), value.getSupplierId())
            .map(supplier -> supplier.getName()).orElse(null);
        return new ReturnablePackagingDtos.ReturnView(value.getId(), value.getCode(), value.getVersion(), value.getLotId(),
            value.getSupplierId(), value.getQuantity(), value.getConfirmedQuantity(), value.getRecalledQuantity(), value.getStatus(),
            value.getDocumentNumber(), value.getReason(), value.getApprovedAt(), value.getDispatchedAt(), value.getConfirmedAt(),
            value.getRecallReason(), value.getRecalledAt(), value.getCreatedAt(), supplierName);
    }
}
