package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.enums.*;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.*;
import com.enterprise.mm.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class WarehouseStockFactService {
    public static final String REFERENCE_TYPE = "MANUAL_WAREHOUSE_FACT";
    private final RoleDataScopeService scope;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.enterprise.mm.erp.ErpExchangeService erpExchange;
    private final WarehouseRepository warehouses;
    private final MaterialCatalogGuard catalog;
    private final WarehouseReceiptEligibilityService receiptEligibility;
    private final InventoryScopeLockService inventoryLocks;
    private final StockBalanceRepository balances;
    private final StockMovementDocumentRepository documents;
    private final StockMovementService movements;
    private final AttachmentService attachments;
    private final IdempotencyService idempotency;
    private final EntityManager em;
    private final MaterialUnitRepository units;
    private final MaterialUnitEventRepository events;
    private final MaterialUnitEligibilityService unitEligibility;
    private final QualityInspectionService quality;
    private final AuditService audit;
    private final StockPickingAllocationService picking;
    private final MaterialStockAccountResolutionService materialAccounts;
    private final MaterialStockAccountRecommendationService accountRecommendations;
    private final ManualReceiptAccountDraftService accountDrafts;

    private void requireWarehouse(UUID warehouseId) {
        scope.assertWarehouseAccess(warehouseId);
        warehouses.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), warehouseId)
            .filter(w -> CompanyContext.currentCompany().equals(w.getCompanyId()) && "ACTIVE".equals(w.getStatus()))
            .orElseThrow(() -> new NotFoundException("Active warehouse not found"));
    }

    @Transactional(readOnly = true)
    public WarehouseStockFactPickingDtos.Options issueOptions(UUID warehouseId, UUID materialId, PickingMethod method, int page, int size) {
        requireWarehouse(warehouseId);
        catalog.requireActiveTmbMaterial(materialId, "materialId");
        return picking.issueOptions(warehouseId, materialId, method, page, size);
    }

    @Transactional(readOnly = true)
    public WarehouseStockFactPickingDtos.Preview previewIssue(WarehouseStockFactRequest request) {
        requireWarehouse(request.warehouseId());
        for (var line : request.lines()) catalog.requireActiveTmbMaterial(line.materialId(), "materialId");
        return new WarehouseStockFactPickingDtos.Preview(request.warehouseId(), picking.allocateIssue(request.warehouseId(), request.lines(), false));
    }

    @Transactional
    public ManualWarehouseDocumentResponse record(WarehouseStockFactRequest request,
        List<MultipartFile> files, String key, boolean receipt) {
        return record(request, files, key, receipt, MaterialType.TMB);
    }

    /** Quantity-only fixed asset facts use the same atomic stock ledger as material facts. */
    @Transactional
    public ManualWarehouseDocumentResponse recordFixedAssets(WarehouseStockFactRequest request,
        List<MultipartFile> files, String key, boolean receipt) {
        return record(request, files, key, receipt, MaterialType.FIXED_ASSET);
    }

    private ManualWarehouseDocumentResponse record(WarehouseStockFactRequest request,
        List<MultipartFile> files, String key, boolean receipt, MaterialType type) {
        requireWarehouse(request.warehouseId());
        var proofFiles = files == null ? List.<MultipartFile>of() : files;
        if (proofFiles.size() > 10 || proofFiles.stream().anyMatch(f -> f == null || f.isEmpty()))
            throw new BusinessException("Attach up to ten non-empty proof files");
        if (request.movementDate() == null || request.movementDate().isAfter(LocalDate.now()))
            throw new BusinessException("Enter the date of the completed operation");
        if (request.lines() == null || request.lines().isEmpty() || request.lines().size() > 100)
            throw new BusinessException("Enter between one and one hundred material lines");
        var fingerprint = new Submission(request, proofFiles.stream().map(this::fileIdentity).toList());
        // Serialize the idempotency lookup as well as posting for this warehouse.
        inventoryLocks.lock(CompanyContext.currentCompany(), List.of(request.warehouseId()));
        String operation = type == MaterialType.TMB ? "WAREHOUSE_STOCK_FACT" : "WAREHOUSE_FIXED_ASSET_STOCK_FACT";
        return idempotency.executeRequired(operation + (receipt ? "_RECEIPT" : "_ISSUE"),
            request.warehouseId(), key, fingerprint, ManualWarehouseDocumentResponse.class,
            () -> post(request, proofFiles, receipt, type));
    }

    private Material requireMaterial(UUID id, MaterialType type) {
        if (type == MaterialType.TMB) return catalog.requireActiveTmbMaterial(id, "materialId");
        Material material = catalog.requireActiveFixedAssetStockMaterial(id, "materialId");
        if (material.getMaterialType() != MaterialType.FIXED_ASSET)
            throw new BusinessException("Select a fixed-asset material", "FIXED_ASSET_MATERIAL_REQUIRED");
        return material;
    }

    private ManualWarehouseDocumentResponse post(WarehouseStockFactRequest request, List<MultipartFile> files, boolean receipt, MaterialType type) {
        Map<UUID, Material> materials = new TreeMap<>();
        for (var line : request.lines()) {
            if (line == null || line.materialId() == null || line.quantity() == null || line.quantity().signum() <= 0)
                throw new BusinessException("Select a material and enter a positive quantity on every line");
            materials.put(line.materialId(), requireMaterial(line.materialId(), type));
            if (type == MaterialType.FIXED_ASSET && line.quantity().stripTrailingZeros().scale() > 0)
                throw new BusinessException("Fixed asset quantity must be whole", "FIXED_ASSET_QUANTITY_WHOLE");
        }
        for (var material : materials.values()) {
            em.refresh(material, LockModeType.PESSIMISTIC_WRITE);
            requireMaterial(material.getId(), type);
            if (!CompanyContext.currentCompany().equals(material.getCompanyId()))
                throw new BusinessException("Material must belong to the selected company");
        }
        var postingLines = request.lines();
        List<WarehouseStockFactPickingDtos.Line> pickingDecisions = List.of();
        if (!receipt && request.lines().stream().anyMatch(l -> l.allocationMode() != null || l.pickingMethod() != null || l.allocations() != null)) {
            // Resolve against current stock under the same locks used by transfer dispatch.
            pickingDecisions = type == MaterialType.TMB
                ? picking.allocateIssue(request.warehouseId(), request.lines(), true)
                : picking.allocateFixedAssetIssue(request.warehouseId(), request.lines(), true);
            postingLines = pickingDecisions.stream().flatMap(line -> line.allocations().stream().map(a ->
                new WarehouseStockFactRequest.Line(line.materialId(), a.quantity(), null, a.stockBalanceId(), null, a.materialUnitIds()))).toList();
        }
        Map<UUID, StockBalance> positions = new TreeMap<>();
        if (!receipt) {
            for (var line : postingLines) {
                if (line.stockPositionId() == null) throw new BusinessException("Select a stock position for each issue line");
                positions.put(line.stockPositionId(), null);
            }
            for (var id : positions.keySet()) {
                positions.put(id, balances.findByIdAndIsDeletedFalseForUpdate(id)
                    .filter(b -> TenantContext.currentTenant().equals(b.getTenantId())
                        && CompanyContext.currentCompany().equals(b.getCompanyId())
                        && request.warehouseId().equals(b.getWarehouseId()))
                    .orElseThrow(() -> new NotFoundException("Stock position not found in your warehouse")));
            }
        }
        Set<UUID> lineKeys = new HashSet<>();
        Set<String> serials = new HashSet<>();
        Set<UUID> unitIds = new HashSet<>();
        Map<WarehouseStockFactRequest.Line, List<MaterialUnit>> selectedUnits = new HashMap<>();
        for (var line : postingLines) {
            Material material = materials.get(line.materialId());
            boolean serialized = type == MaterialType.TMB && material.getSerialTrackingPolicy() == SerialTrackingPolicy.REQUIRED;
            StockUomPolicy.quantity(material, line.quantity(), false);
            if (!lineKeys.add(receipt ? material.getId() : line.stockPositionId()))
                throw new BusinessException("Combine duplicate material or stock position lines");
            if (receipt) {
                if (type == MaterialType.TMB) receiptEligibility.validateMaterial(material);
                if (line.unitCost() == null || line.unitCost().signum() < 0)
                    throw new BusinessException("Enter a non-negative receipt unit value");
                if (serialized) validateSerials(material.getId(), line, serials);
            } else {
                StockBalance position = positions.get(line.stockPositionId());
                if (!material.getId().equals(position.getMaterialId()))
                    throw new BusinessException("Stock position does not match the selected material");
                if (position.getOwnerPartyId() != null || !"OWNED".equals(position.getStockCategory()))
                    throw new BusinessException("Manual issues require company-owned stock");
                String blocked = StockPositionPolicy.blockedReason(position, serialized || type == MaterialType.FIXED_ASSET);
                if (blocked != null) throw new BusinessException("Stock position is unavailable: " + blocked, "stockPicking.unavailable");
                if (position.getAvailableQuantity() == null || position.getAvailableQuantity().compareTo(line.quantity()) < 0)
                    throw new BusinessException("Not enough available stock in the selected position", "stockPicking.insufficient");
                if (serialized) {
                    var ids = line.materialUnitIds() == null ? List.<UUID>of() : line.materialUnitIds();
                    for (var id : ids) if (!unitIds.add(id)) throw new BusinessException("A physical unit may be issued only once");
                    var selected = unitEligibility.lockAndValidate(material.getId(), request.warehouseId(),
                        position.getStorageLocationId(), position.getStorageBinId(), line.quantity(), ids, MaterialUnitStatus.IN_STOCK);
                    if (selected.stream().anyMatch(u -> !Objects.equals(position.getStockBatchId(), u.getStockBatchId())))
                        throw new BusinessException("Select physical units from the selected stock position");
                    selectedUnits.put(line, selected);
                }
            }
        }

        StockMovementDocument document = new StockMovementDocument();
        document.setTenantId(TenantContext.currentTenant());
        document.setCompanyId(CompanyContext.currentCompany());
        document.setCode("WMF-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        document.setName(type == MaterialType.TMB
            ? (receipt ? "Manual material receipt" : "Manual material issue")
            : (receipt ? "Manual fixed asset receipt" : "Manual fixed asset issue"));
        document.setStatus("DRAFT");
        document.setWarehouseId(request.warehouseId());
        document.setMovementDate(request.movementDate());
        document.setMovementType(receipt ? MovementType.RECEIPT : MovementType.ISSUE);
        document.setReferenceType(REFERENCE_TYPE);
        document.setManualOperationPayload(type == MaterialType.TMB
            ? WarehouseStockFactMetadata.snapshot(request.source(), request.reference())
            : WarehouseStockFactMetadata.fixedAssetSnapshot(request.source(), request.reference()));
        document.setDescription(java.util.stream.Stream.of(request.source(), request.reference())
            .filter(s -> s != null && !s.isBlank()).map(String::trim).collect(Collectors.joining(" · ")));
        document = documents.saveAndFlush(document);
        // Store evidence before any stock mutation; storage is cleaned up if this transaction rolls back.
        for (var file : files) attachments.uploadForCommand("STOCK_MOVEMENT_DOCUMENT", document.getId(), "DOCUMENT", file);
        List<UUID> movementIds = new ArrayList<>();
        for (var line : postingLines) {
            Material material = materials.get(line.materialId());
            StockMovement movement = new StockMovement();
            movement.setCompanyId(CompanyContext.currentCompany());
            movement.setDocumentId(document.getId());
            movement.setMaterialId(material.getId());
            movement.setWarehouseId(request.warehouseId());
            movement.setMovementType(document.getMovementType());
            movement.setMovementDate(document.getMovementDate());
            movement.setQuantity(line.quantity());
            movement.setUnitCost(receipt ? line.unitCost() : BigDecimal.ZERO);
            if (receipt && type == MaterialType.TMB) {
                var resolution = materialAccounts.resolve(material);
                var account = materialAccounts.requireReceiptAccount(resolution, line.stockAccountId());
                if (line.recommendationJobId() != null) {
                    if (line.accountDraftId() == null)
                        throw new BusinessException("Receipt draft is required for an AI account choice");
                    accountDrafts.lockForPosting(line.accountDraftId(), request.warehouseId(), material.getId(),
                        request.movementDate(), line.purpose(), line.quantity(), line.unitCost());
                    accountRecommendations.verifyApplied(line.recommendationJobId(), material.getId(), account.id(),
                        resolution, line.accountDraftId());
                }
                movement.setAccountCodeSnapshot(account.code());
            }
            movement.setReferenceType(REFERENCE_TYPE);
            movement.setReferenceId(document.getId());
            movement.setDescription(document.getDescription());
            if (!receipt) {
                var position = positions.get(line.stockPositionId());
                movement.setStockBatchId(position.getStockBatchId());
                movement.setStorageLocationId(position.getStorageLocationId());
                movement.setStorageBinId(position.getStorageBinId());
            }
            var posted = type == MaterialType.TMB ? movements.postWarehouseStockFact(movement)
                : movements.postFixedAssetStockFact(movement);
            movementIds.add(posted.getId());
            if (receipt && type == MaterialType.TMB) {
                if (line.recommendationJobId() != null)
                    accountDrafts.markPosted(line.accountDraftId(), posted.getId());
                else if (line.purpose() != null && !line.purpose().isBlank())
                    accountDrafts.recordPostedContext(new ManualReceiptAccountDraftService.Create(
                        request.warehouseId(), material.getId(), request.movementDate(), line.purpose(),
                        line.quantity(), line.unitCost()), posted.getId());
            }
            if (receipt && type == MaterialType.TMB && line.stockAccountId() != null)
                audit.logInCurrentTransaction("StockMovement", posted.getId(),
                    line.recommendationJobId() == null ? "MANUAL_RECEIPT_ACCOUNT_CHOICE" : "AI_RECEIPT_ACCOUNT_CHOICE",
                    material.getCode(), null, line.recommendationJobId() == null ? line.stockAccountId()
                        : line.stockAccountId() + ":" + line.recommendationJobId());
            if (type == MaterialType.TMB && receipt && material.getSerialTrackingPolicy() == SerialTrackingPolicy.REQUIRED)
                registerUnits(material, request.warehouseId(), posted, line.serialNumbers());
            if (!receipt && selectedUnits.containsKey(line))
                unitEligibility.markIssued(selectedUnits.get(line), REFERENCE_TYPE, posted.getId(), posted.getId(),
                    request.warehouseId(), posted.getStorageLocationId(), posted.getStorageBinId());
            if (type == MaterialType.TMB && receipt && material.isIncomingInspectionRequired()) {
                var inspection = new QualityInspectionCreateRequest();
                inspection.setName("Incoming inspection · " + material.getName());
                inspection.setInspectionType("INCOMING");
                inspection.setStockMovementId(posted.getId());
                inspection.setReceivedQuantity(posted.getQuantity());
                inspection.setQuarantinedQuantity(posted.getQuantity());
                inspection.setDueDate(LocalDate.now().plusDays(1));
                quality.startQuarantine(quality.create(inspection).getId());
            }
        }
        document.setStatus("POSTED");
        document.setPostedAt(LocalDateTime.now());
        documents.saveAndFlush(document);
        if (type == MaterialType.TMB && erpExchange != null && erpExchange.captureEnabled())
            erpExchange.captureStockFact(document.getId());
        var response = new ManualWarehouseDocumentResponse(document.getId(), document.getCode(), document.getMovementType(),
            document.getStatus(), movementIds, movementIds.size());
        audit.log("StockMovementDocument", document.getId(), "POST", document.getCode(), null, response);
        if (!pickingDecisions.isEmpty()) audit.log("StockMovementDocument", document.getId(), "ALLOCATE", document.getCode(), null, pickingDecisions);
        return response;
    }

    private void validateSerials(UUID materialId, WarehouseStockFactRequest.Line line, Set<String> seen) {
        int count;
        try { count = line.quantity().intValueExact(); }
        catch (ArithmeticException e) { throw new BusinessException("Serialized quantity must be whole"); }
        var numbers = line.serialNumbers() == null ? List.<String>of() : line.serialNumbers();
        if (numbers.size() != count) throw new BusinessException("Enter one serial number per physical unit");
        for (var number : numbers) {
            var normalized = number == null ? "" : number.trim().toUpperCase(Locale.ROOT);
            if (normalized.isBlank() || !seen.add(materialId + ":" + normalized)
                || units.existsByTenantIdAndCompanyIdAndMaterialIdAndNormalizedSerialNumberAndIsDeletedFalse(
                    TenantContext.currentTenant(), CompanyContext.currentCompany(), materialId, normalized))
                throw new BusinessException("Serial number is empty or already registered: " + number);
        }
    }

    private void registerUnits(Material material, UUID warehouseId, StockMovementResponse posted, List<String> serials) {
        for (var serial : serials) {
            var unit = new MaterialUnit();
            unit.setCode("MU-" + UUID.randomUUID().toString().substring(0, 8));
            unit.setName("Material unit " + serial.trim());
            unit.setStatus("IN_STOCK");
            unit.setMaterialId(material.getId());
            unit.setWarehouseId(warehouseId);
            unit.setStockBatchId(posted.getStockBatchId());
            unit.setManufacturerSerialNumber(serial.trim());
            unit.setNormalizedSerialNumber(serial.trim().toUpperCase(Locale.ROOT));
            unit.setManualStockMovementId(posted.getId());
            unit.setReceivedAt(LocalDateTime.now());
            unit = units.save(unit);
            var event = new MaterialUnitEvent();
            event.setCode("MUE-" + UUID.randomUUID().toString().substring(0, 8));
            event.setName("Manual warehouse receipt");
            event.setStatus("POSTED");
            event.setMaterialUnitId(unit.getId());
            event.setEventType(MaterialUnitEventType.RECEIVED);
            event.setSourceType(REFERENCE_TYPE);
            event.setSourceId(posted.getId());
            event.setWarehouseId(warehouseId);
            event.setStockBatchId(posted.getStockBatchId());
            var actor = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            event.setPerformedBy(actor == null ? "system" : actor.getName());
            event.setOccurredAt(LocalDateTime.now());
            events.save(event);
        }
    }

    private FileIdentity fileIdentity(MultipartFile file) {
        try (var input = file.getInputStream()) {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            return new FileIdentity(file.getOriginalFilename(), file.getContentType(), file.getSize(), HexFormat.of().formatHex(digest.digest()));
        } catch (Exception e) { throw new BusinessException("Could not read proof file"); }
    }

    private record Submission(WarehouseStockFactRequest request, List<FileIdentity> files) {}
    private record FileIdentity(String name, String contentType, long size, String sha256) {}
}
