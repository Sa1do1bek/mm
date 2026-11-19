package com.enterprise.mm.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collection;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import com.enterprise.mm.error.BadRequestException;
import static com.enterprise.mm.warehouse.movements.MovementExportText.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.enterprise.mm.warehouse.movements.MovementQuerySupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.erp.ErpExchangeService;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.entity.StockBatch;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.entity.StockMovementAllocation;
import com.enterprise.mm.entity.StockMovementAllocationCostLayer;
import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.enums.InventoryCostLayerType;
import com.enterprise.mm.enums.SerialTrackingPolicy;
import com.enterprise.mm.enums.StockMovementStatus;
import com.enterprise.mm.enums.ValuationMethod;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.InsufficientStockException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.inventory.allocation.StockMovementAllocationDraft;
import com.enterprise.mm.inventory.allocation.StockMovementAllocationEvidence;
import com.enterprise.mm.inventory.allocation.StockMovementAllocationWriter;
import com.enterprise.mm.inventory.allocation.StockMovementCostLineageDraft;
import com.enterprise.mm.inventory.allocation.StockMovementStockKeySnapshot;
import com.enterprise.mm.mapper.StockMovementMapper;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.payload.StockMovementCreateRequest;
import com.enterprise.mm.payload.StockMovementResponse;
import com.enterprise.mm.payload.StockMovementUpdateRequest;
import com.enterprise.mm.payload.StockMovementSearchRequest;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.StockBatchRepository;
import com.enterprise.mm.repository.StockMovementRepository;
import com.enterprise.mm.repository.StockMovementAllocationRepository;
import com.enterprise.mm.repository.StockMovementAllocationCostLayerRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import com.enterprise.mm.service.approval.StockMovementApprovalAdapter;

@Service
public class StockMovementService {
    private final StockMovementRepository repository;
    private final StockMovementMapper mapper;
    @Autowired
    private com.enterprise.mm.warehouse.history.ImportedStockMovementQuery importedMovements;
    @Value("${warehouse.movements.export.max-rows:100000}")
    private int maxRows = 100000;
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;
    private static final int BATCH = 500;
    private final String entityName = "StockMovement";
    private final StockBalanceRepository stockBalanceRepository;
    private final StockBatchRepository stockBatchRepository;
    private final MaterialRepository materialRepository;
    private final MaterialCatalogGuard materialCatalogGuard;
    private final WarehouseRepository warehouseRepository;
    private final StockBalanceMutationService stockBalanceMutationService;
    private final InventoryPeriodGuardService periodGuardService;
    private final InventoryValuationPolicyService valuationPolicyService;
    private final StockValuationService stockValuationService;
    private final InventoryCostingService inventoryCostingService;
    private final StockMovementAllocationWriter allocationWriter;
    private final StockMovementAllocationRepository allocationRepository;
    private final StockMovementAllocationCostLayerRepository allocationCostLayerRepository;
    private final AuditService auditService;
    private final RoleDataScopeService roleDataScopeService;
    private final InventoryFreezeGuardService inventoryFreezeGuardService;
    private final MaterialCostObservationService materialCostObservationService;
    private final ApprovalEngineService approvalEngineService;
    private final boolean allowNegativeStock;
    @Autowired(required=false)
    private ExactOpeningImportGuard openingImportGuard;
    @Autowired(required=false)
    private ErpExchangeService erpExchangeService;
    @Autowired(required=false)
    private com.enterprise.mm.erp.warehouse.outbound.WarehouseOutboundService warehouseOutbound;
    @Autowired(required=false)
    private org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbcTemplate;

    public StockMovementService(
        StockMovementRepository repository,
        StockMovementMapper mapper,
        StockBalanceRepository stockBalanceRepository,
        StockBatchRepository stockBatchRepository,
        MaterialRepository materialRepository,
        MaterialCatalogGuard materialCatalogGuard,
        WarehouseRepository warehouseRepository,
        StockBalanceMutationService stockBalanceMutationService,
        InventoryPeriodGuardService periodGuardService,
        InventoryValuationPolicyService valuationPolicyService,
        StockValuationService stockValuationService,
        InventoryCostingService inventoryCostingService,
        StockMovementAllocationWriter allocationWriter,
        StockMovementAllocationRepository allocationRepository,
        StockMovementAllocationCostLayerRepository allocationCostLayerRepository,
        AuditService auditService,
        RoleDataScopeService roleDataScopeService,
        InventoryFreezeGuardService inventoryFreezeGuardService,
        boolean allowNegativeStock
    ) {
        this(
            repository,
            mapper,
            stockBalanceRepository,
            stockBatchRepository,
            materialRepository,
            materialCatalogGuard,
            warehouseRepository,
            stockBalanceMutationService,
            periodGuardService,
            valuationPolicyService,
            stockValuationService,
            inventoryCostingService,
            allocationWriter,
            allocationRepository,
            allocationCostLayerRepository,
            auditService,
            roleDataScopeService,
            inventoryFreezeGuardService,
            null,
            null,
            allowNegativeStock
        );
    }

    @org.springframework.beans.factory.annotation.Autowired
    public StockMovementService(
        StockMovementRepository repository,
        StockMovementMapper mapper,
        StockBalanceRepository stockBalanceRepository,
        StockBatchRepository stockBatchRepository,
        MaterialRepository materialRepository,
        MaterialCatalogGuard materialCatalogGuard,
        WarehouseRepository warehouseRepository,
        StockBalanceMutationService stockBalanceMutationService,
        InventoryPeriodGuardService periodGuardService,
        InventoryValuationPolicyService valuationPolicyService,
        StockValuationService stockValuationService,
        InventoryCostingService inventoryCostingService,
        StockMovementAllocationWriter allocationWriter,
        StockMovementAllocationRepository allocationRepository,
        StockMovementAllocationCostLayerRepository allocationCostLayerRepository,
        AuditService auditService,
        RoleDataScopeService roleDataScopeService,
        InventoryFreezeGuardService inventoryFreezeGuardService,
        MaterialCostObservationService materialCostObservationService,
        ApprovalEngineService approvalEngineService,
        @Value("${app.inventory.allow-negative-stock:false}") boolean allowNegativeStock
    ) {
        this.repository = repository;
        this.mapper = mapper;
        this.stockBalanceRepository = stockBalanceRepository;
        this.stockBatchRepository = stockBatchRepository;
        this.materialRepository = materialRepository;
        this.materialCatalogGuard = materialCatalogGuard;
        this.warehouseRepository = warehouseRepository;
        this.stockBalanceMutationService = stockBalanceMutationService;
        this.periodGuardService = periodGuardService;
        this.valuationPolicyService = valuationPolicyService;
        this.stockValuationService = stockValuationService;
        this.inventoryCostingService = inventoryCostingService;
        this.allocationWriter = allocationWriter;
        this.allocationRepository = allocationRepository;
        this.allocationCostLayerRepository = allocationCostLayerRepository;
        this.auditService = auditService;
        this.roleDataScopeService = roleDataScopeService;
        this.inventoryFreezeGuardService = inventoryFreezeGuardService;
        this.materialCostObservationService = materialCostObservationService;
        this.approvalEngineService = approvalEngineService;
        this.allowNegativeStock = allowNegativeStock;
    }

    protected void beforeCreate(StockMovement entity, StockMovementCreateRequest dto) {
        initializeMovement(entity, materialCatalogGuard.requireActiveTmbMaterial(entity.getMaterialId(), "materialId"));
    }

    private void initializeMovement(StockMovement entity, Material material) {
        if (openingImportGuard != null) openingImportGuard.assertOperationsAllowed(
            entity.getCompanyId() == null ? CompanyContext.currentCompany() : entity.getCompanyId());
        StockUomPolicy.quantity(material, entity.getQuantity(), false);
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus("ACTIVE");
        }
        if (entity.getCompanyId() == null) {
            entity.setCompanyId(CompanyContext.currentCompany());
        }
        if (entity.getCode() == null || entity.getCode().isBlank()) {
            entity.setCode(entityName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_") + "-" + UUID.randomUUID().toString().substring(0, 8));
        }
        normalizeDraftMovement(entity);
        entity.setName(generateMovementName(entity));
    }

    @Transactional
    public StockMovementResponse update(UUID id, StockMovementUpdateRequest dto) {
        rejectManagedWorkflowReference(dto.getReferenceType());
        StockMovement entity = findEntity(id);
        if (openingImportGuard != null) openingImportGuard.assertOperationsAllowed(entity.getCompanyId());
        rejectManagedWorkflowReference(entity.getReferenceType());
        if (!StockMovementStatus.DRAFT.name().equals(entity.getStatus())) {
            throw new BusinessException("Only DRAFT stock movement can be edited");
        }
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        StockMovement saved = repository.save(entity);
        auditService.log("StockMovement", saved.getId(), "UPDATE", saved.getCode(), null, null);
        return mapper.toResponse(saved);
    }

    protected void beforeUpdate(StockMovement entity, StockMovementUpdateRequest dto) {
        StockUomPolicy.quantity(materialCatalogGuard.requireActiveTmbMaterial(entity.getMaterialId(), "materialId"), entity.getQuantity(), false);
        normalizeDraftMovement(entity);
        entity.setName(generateMovementName(entity));
    }

    private void normalizeDraftMovement(StockMovement entity) {
        entity.setStatus(StockMovementStatus.DRAFT.name());
        entity.setPostedAt(null);
        if (entity.getMovementType() == null) {
            entity.setMovementType(MovementType.RECEIPT);
        }
        entity.setQuantity(nvl(entity.getQuantity()));
        entity.setUnitCost(nvl(entity.getUnitCost()));
        entity.setTotalCost(entity.getUnitCost().multiply(entity.getQuantity()));
        if (entity.getMovementDate() == null) {
            entity.setMovementDate(LocalDate.now());
        }
        if (entity.getRequestDate() == null) {
            entity.setRequestDate(entity.getMovementDate().atStartOfDay());
        }
    }

    @Transactional
    public StockMovementResponse post(UUID id) {
        StockMovement movement = findEntity(id);
        rejectManagedWorkflowReference(movement.getReferenceType());
        boolean alreadyPosted = StockMovementStatus.POSTED.name().equals(movement.getStatus());
        postInternal(movement);
        if (!alreadyPosted && erpExchangeService != null && movement.getDocumentId() == null
            && (movement.getReferenceType() == null || movement.getReferenceType().isBlank())
            && (movement.getMovementType() == MovementType.RECEIPT || movement.getMovementType() == MovementType.ISSUE)) {
            // Capture after costing, in the same transaction as the stock mutation.
            repository.flush();
            String kind = movement.getMovementType().name();
            erpExchangeService.acceptWarehouseDocument(kind, movement.getId(), movement.getVersion(),
                movement.getMovementDate(), kind,
                movement.getMovementType() == MovementType.ISSUE ? movement.getWarehouseId() : null,
                movement.getMovementType() == MovementType.RECEIPT ? movement.getWarehouseId() : null,
                List.of(new ErpExchangeService.Line(movement.getMaterialId(), movement.getWarehouseId(),
                    movement.getQuantity(), movement.getTotalCost())));
        }
        return mapper.toResponse(movement);
    }

    private StockMovementResponse postInternal(StockMovement movement) {
        if (StockMovementStatus.POSTED.name().equals(movement.getStatus())) {
            return mapper.toResponse(movement);
        }
        if (StockMovementStatus.APPROVED.name().equals(movement.getStatus())) {
            postMovement(movement);
        } else {
            postDraftMovement(movement);
        }
        auditService.log("StockMovement", movement.getId(), "POST", movement.getMovementType().name(), null, null);
        return mapper.toResponse(movement);
    }

    void postDraftMovement(StockMovement movement) {
        if (!StockMovementStatus.DRAFT.name().equals(movement.getStatus())) {
            throw new BusinessException("Only DRAFT stock movement can be posted");
        }
        postMovement(movement);
    }

    void postApprovedDocumentLine(StockMovement movement) {
        if (StockMovementStatus.POSTED.name().equals(movement.getStatus())) {
            return;
        }
        if (!StockMovementStatus.APPROVED.name().equals(movement.getStatus())) {
            throw new BusinessException("Only APPROVED stock movement document items can be posted");
        }
        postMovement(movement);
    }

    void postManagedIssueDocumentLine(StockMovement movement) {
        if (StockMovementStatus.POSTED.name().equals(movement.getStatus())) {
            return;
        }
        if (!StockMovementStatus.APPROVED.name().equals(movement.getStatus())) {
            throw new BusinessException("Only APPROVED stock movement document items can be posted");
        }
        postMovement(movement, true);
    }

    void postManagedReturnDocumentLine(StockMovement movement) {
        if (StockMovementStatus.POSTED.name().equals(movement.getStatus())) {
            return;
        }
        if (!StockMovementStatus.APPROVED.name().equals(movement.getStatus())) {
            throw new BusinessException("Only APPROVED stock movement document items can be posted");
        }
        postMovement(movement, true);
    }

    public void approveDocumentLine(StockMovement movement) {
        if (StockMovementStatus.APPROVED.name().equals(movement.getStatus())) {
            return;
        }
        if (!StockMovementStatus.PENDING.name().equals(movement.getStatus())) {
            throw new BusinessException("Only PENDING stock movement document items can be approved");
        }
        movement.setStatus(StockMovementStatus.APPROVED.name());
        movement.setPostedAt(null);
        repository.save(movement);
    }

    private void postMovement(StockMovement movement) {
        postMovement(movement, false);
    }

    private void postMovement(StockMovement movement, boolean managedSerializedIssue) {
        postMovement(movement, managedSerializedIssue, false);
    }

    private void postMovement(StockMovement movement, boolean managedSerializedIssue, boolean managedReceiving) {
        Material stockMaterial = materialCatalogGuard.requireActiveTmbMaterial(movement.getMaterialId(), "materialId");
        postMovement(movement, managedSerializedIssue, managedReceiving, stockMaterial, false);
    }

    private void postMovement(StockMovement movement, boolean managedSerializedIssue, boolean managedReceiving,
        Material stockMaterial, boolean quantityOnlyFixedAsset) {
        if (stockMaterial.getMaterialSubtype() == com.enterprise.mm.enums.MaterialSubtype.RETURNABLE_PACKAGING
            && (movement.getReferenceType() == null
                || !java.util.Set.of("RETURNABLE_PACKAGING_RECEIPT", "RETURNABLE_PACKAGING_RETURN", "RETURNABLE_PACKAGING_RECALL")
                    .contains(movement.getReferenceType())))
            throw new BusinessException("Returnable packaging stock must use its linked register workflow");
        StockUomPolicy.quantity(stockMaterial, movement.getQuantity(), false);
        if (stockMaterial.isIncomingInspectionRequired() && movement.getMovementType() == MovementType.RECEIPT && !managedReceiving)
            throw new BusinessException("QC-required receipt must use controlled purchase-order receiving");
        if (!quantityOnlyFixedAsset) guardSerializedMovement(movement, managedSerializedIssue);
        ensurePostingPeriodOpen(movement);
        ensureNotFrozen(movement);
        applyMovement(movement);
        finalizePostedMovement(movement);
    }

    private void guardSerializedMovement(StockMovement movement, boolean managedSerializedIssue) {
        Material material = materialRepository.findByIdAndIsDeletedFalse(movement.getMaterialId()).orElse(null);
        if (material == null || material.getSerialTrackingPolicy() != SerialTrackingPolicy.REQUIRED) {
            return;
        }
        if (movement.getMovementType() == MovementType.RECEIPT) {
            return;
        }
        if (managedSerializedIssue
            && movement.getMovementType() == MovementType.ISSUE
            && ("ISSUE_REQUEST".equals(movement.getReferenceType()) || "MANUAL_WAREHOUSE".equals(movement.getReferenceType())
                || WarehouseStockFactService.REFERENCE_TYPE.equals(movement.getReferenceType())
                || "PRODUCTION_OPERATION".equals(movement.getReferenceType()))) {
            return;
        }
        if (managedSerializedIssue
            && movement.getMovementType() == MovementType.RETURN
            && "MATERIAL_RETURN".equals(movement.getReferenceType())) {
            return;
        }
        if (managedSerializedIssue
            && (movement.getMovementType() == MovementType.WRITE_OFF || movement.getMovementType() == MovementType.ADJUSTMENT)
            && "InventoryDocument".equals(movement.getReferenceType())) {
            return;
        }
        throw new BusinessException(
            "SERIAL_UNIT_SELECTION_REQUIRED: serialized material stock changes require physical unit selection",
            "serial.unit_selection_required",
            movement.getMaterialId(),
            movement.getMovementType()
        );
    }

    private void guardSerializedCancellation(StockMovement movement) {
        Material material = materialRepository.findByIdAndIsDeletedFalse(movement.getMaterialId()).orElse(null);
        if (material == null || material.getSerialTrackingPolicy() != SerialTrackingPolicy.REQUIRED) {
            return;
        }
        throw new BusinessException(
            "SERIAL_UNIT_SELECTION_REQUIRED: serialized material receipt reversal requires physical unit selection",
            "serial.unit_selection_required",
            movement.getMaterialId(),
            movement.getMovementType()
        );
    }

    private void finalizePostedMovement(StockMovement movement) {
        movement.setStatus(StockMovementStatus.POSTED.name());
        if (movement.getPostedAt() == null) {
            movement.setPostedAt(LocalDateTime.now());
        }
        repository.save(movement);
        if (warehouseOutbound != null && warehouseOutbound.captureEnabled()) {
            if (entityManager != null) entityManager.flush();
            else repository.flush();
            warehouseOutbound.captureStockMovement(movement.getId());
        }
        recordReceiptCostObservation(movement);
    }
    @Transactional
    public StockMovementResponse createAndPost(StockMovement movement) {
        rejectManagedWorkflowReference(movement.getReferenceType());
        beforeCreate(movement, new StockMovementCreateRequest());
        StockMovement saved = repository.save(movement);
        return postInternal(saved);
    }

    /** Native 1C already posted this fact. Import through normal valuation without echoing it back. */
    @Transactional
    public StockMovementResponse importOneCApiMovement(StockMovement movement) {
        if (!"ONE_C_API".equals(movement.getReferenceType()) || movement.getReferenceId() == null
            || (movement.getMovementType() != MovementType.RECEIPT && movement.getMovementType() != MovementType.ISSUE
                && movement.getMovementType() != MovementType.TRANSFER))
            throw new BusinessException("Invalid native 1C stock fact");
        beforeCreate(movement, new StockMovementCreateRequest());
        return postInternal(repository.save(movement));
    }

    /** Called only by the scoped production document transaction; inputs use normal valuation and allocations. */
    @Transactional
    StockMovementResponse postProductionMovement(StockMovement movement) {
        if (!"PRODUCTION_OPERATION".equals(movement.getReferenceType()) || movement.getReferenceId() == null
            || (movement.getMovementType() != MovementType.RECEIPT && movement.getMovementType() != MovementType.ISSUE))
            throw new BusinessException("Production stock requires its linked document");
        BigDecimal exactValue = movement.getTotalCost();
        beforeCreate(movement, new StockMovementCreateRequest());
        if (movement.getMovementType() == MovementType.RECEIPT) {
            if (exactValue == null || exactValue.signum() < 0 || exactValue.stripTrailingZeros().scale() > 4)
                throw new BusinessException("Production output requires exact allocated value");
            movement.setTotalCost(exactValue);
        }
        StockMovement saved = repository.save(movement);
        postMovement(saved, true, true);
        auditService.log("StockMovement", saved.getId(), "POST", "Production operation", null, null);
        // Production document lines use JDBC foreign keys in the same transaction.
        repository.flush();
        return mapper.toResponse(saved);
    }

    @Transactional
    StockMovementResponse reverseProductionMovement(UUID movementId, String reason) {
        StockMovement movement = findEntity(movementId);
        if (!"PRODUCTION_OPERATION".equals(movement.getReferenceType()))
            throw new BusinessException("Not a production movement");
        StockMovementResponse result = cancelPosted(movementId, null, reason, true);
        repository.flush();
        return result;
    }

    /** Exact, source-valued opening; caller holds the company import lock and internal capability. */
    StockMovement postExactOpening(StockMovement movement, BigDecimal exactValue) {
        UUID companyId = CompanyContext.currentCompany();
        if (!OpeningImportContext.allows(companyId, movement.getReferenceId())
            || !"OPENING_STOCK_EXACT".equals(movement.getReferenceType())
            || movement.getMovementType() != MovementType.RECEIPT || !companyId.equals(movement.getCompanyId()))
            throw new BusinessException("Exact opening stock requires its managed import workflow");
        beforeCreate(movement, new StockMovementCreateRequest());
        movement.setTotalCost(exactValue);
        movement.setUnitCost(exactValue.divide(movement.getQuantity(), 6, RoundingMode.HALF_UP));
        movement.setSourceCurrencyCodeSnapshot("UZS");
        movement.setSourceExchangeRateSnapshot(BigDecimal.ONE);
        movement = repository.saveAndFlush(movement);
        ensurePostingPeriodOpen(movement);
        ensureNotFrozen(movement);

        StockBatch batch = new StockBatch();
        batch.setCode("OPEN-BATCH-" + UUID.randomUUID().toString().substring(0, 8));
        batch.setName("1C opening stock batch"); batch.setStatus("ACTIVE");
        batch.setTenantId(movement.getTenantId()); batch.setCompanyId(companyId);
        batch.setMaterialId(movement.getMaterialId()); batch.setWarehouseId(movement.getWarehouseId());
        batch.setBatchNumber(movement.getBatchNumber()); batch.setReceivedDate(movement.getMovementDate());
        batch.setQuantity(movement.getQuantity()); batch.setUnitCost(movement.getUnitCost());
        batch.setPurchaseUnitCost(movement.getUnitCost()); batch.setFinalUnitCost(movement.getUnitCost());
        batch = stockBatchRepository.saveAndFlush(batch); movement.setStockBatchId(batch.getId());
        StockBalance balance = findOrCreateBalanceForUpdate(movement, companyId, movement.getWarehouseId(),
            movement.getStorageLocationId(), movement.getStorageBinId(), batch.getId());
        var mutation = stockBalanceMutationService.applyExactDelta(balance, movement.getQuantity(), movement.getQuantity(),
            BigDecimal.ZERO, BigDecimal.ZERO, exactValue);
        balance = stockBalanceRepository.saveAndFlush(balance);
        var cost = inventoryCostingService.recordExactOpeningLayer(movement, exactValue);
        validateInboundEvidence(mutation, cost);
        UomSnapshot uom = resolveUomSnapshot(movement);
        movement.setBatchNumberSnapshot(batch.getBatchNumber()); movement.setUomIdSnapshot(uom.id());
        movement.setUomCodeSnapshot(uom.code()); movement.setTargetDispositionSnapshot("AVAILABLE");
        var targetKey = new StockMovementStockKeySnapshot(balance.getWarehouseId(), balance.getStorageLocationId(),
            balance.getStorageBinId(), balance.getStockBatchId(), batch.getBatchNumber(), "AVAILABLE");
        var lineage = new StockMovementCostLineageDraft("CREATED", cost.costLayerId(), cost.parentCostLayerId(),
            cost.costMovementId(), cost.quantityDelta(), cost.valueDelta(), cost.unitCost(), cost.currencyCode(), cost.exchangeRate());
        var draft = new StockMovementAllocationDraft("TARGET", balance.getId(), StockMovementStockKeySnapshot.empty(), targetKey,
            uom.id(), uom.code(), mutation.onHandDelta(), mutation.availableDelta(), mutation.reservedDelta(), mutation.quarantineDelta(),
            mutation.totalValueDelta(), movement.getQuantity(), cost.unitCost(), cost.currencyCode(), cost.exchangeRate(), List.of(lineage));
        allocationWriter.write(movement, new StockMovementAllocationEvidence(movement.getTenantId(), companyId,
            movement.getId(), movement.getMaterialId(), null, List.of(draft)));
        finalizePostedMovement(movement);
        auditService.logInCurrentTransaction("StockMovement", movement.getId(), "POST", "Exact 1C opening", null, null);
        return movement;
    }

    @Transactional
    StockMovementResponse postPurchasingReceipt(StockMovement movement) {
        if (!"PurchaseOrder".equals(movement.getReferenceType()) || movement.getMovementType() != MovementType.RECEIPT)
            throw new BusinessException("Managed purchasing receipt requires PurchaseOrder receipt reference");
        beforeCreate(movement, new StockMovementCreateRequest());
        StockMovement saved = repository.save(movement);
        postMovement(saved, false, true);
        auditService.log("StockMovement", saved.getId(), "POST", "PurchaseOrder receipt", null, null);
        return mapper.toResponse(saved);
    }

    /** Packaging stock changes originate only from the linked receipt/return register. */
    @Transactional
    public StockMovementResponse postReturnablePackagingMovement(StockMovement movement) {
        if (!("RETURNABLE_PACKAGING_RECEIPT".equals(movement.getReferenceType())
            && movement.getMovementType() == MovementType.RECEIPT)
            && !("RETURNABLE_PACKAGING_RETURN".equals(movement.getReferenceType())
            && movement.getMovementType() == MovementType.ISSUE)
            && !("RETURNABLE_PACKAGING_RECALL".equals(movement.getReferenceType())
            && movement.getMovementType() == MovementType.RECEIPT)) {
            throw new BusinessException("Invalid returnable packaging movement");
        }
        Material material = materialCatalogGuard.requireActiveTmbMaterial(movement.getMaterialId(), "materialId");
        if (material.getMaterialSubtype() != com.enterprise.mm.enums.MaterialSubtype.RETURNABLE_PACKAGING
            || material.getSerialTrackingPolicy() == SerialTrackingPolicy.REQUIRED) {
            throw new BusinessException("Packaging movement requires a quantity-tracked returnable packaging material");
        }
        beforeCreate(movement, new StockMovementCreateRequest());
        StockMovement saved = repository.save(movement);
        postMovement(saved, false, movement.getMovementType() == MovementType.RECEIPT);
        auditService.log("StockMovement", saved.getId(), "POST", movement.getReferenceType(), null, null);
        return mapper.toResponse(saved);
    }

    @Transactional
    StockMovementResponse postManualWarehouseMovement(StockMovement movement) {
        if (!"MANUAL_WAREHOUSE".equals(movement.getReferenceType())
            || (movement.getMovementType() != MovementType.RECEIPT && movement.getMovementType() != MovementType.ISSUE))
            throw new BusinessException("Manual warehouse command requires a receipt or issue");
        beforeCreate(movement, new StockMovementCreateRequest());
        StockMovement saved = repository.save(movement);
        postMovement(saved, true, true);
        auditService.log("StockMovement", saved.getId(), "POST", "Manual warehouse operation", null, null);
        return mapper.toResponse(saved);
    }

    /** Persists a validated manual operation line without changing stock. */
    StockMovementResponse createManualWarehouseDraft(StockMovement movement) {
        if (!"MANUAL_WAREHOUSE".equals(movement.getReferenceType())
            || (movement.getMovementType() != MovementType.RECEIPT && movement.getMovementType() != MovementType.ISSUE))
            throw new BusinessException("Manual warehouse draft requires a receipt or issue");
        StockUomPolicy.quantity(materialCatalogGuard.requireActiveTmbMaterial(movement.getMaterialId(), "materialId"), movement.getQuantity(), false);
        if (movement.getCompanyId() == null) movement.setCompanyId(CompanyContext.currentCompany());
        if (movement.getCode() == null || movement.getCode().isBlank())
            movement.setCode("MANUAL_WAREHOUSE-" + UUID.randomUUID().toString().substring(0, 8));
        normalizeDraftMovement(movement);
        movement.setName(generateMovementName(movement));
        return mapper.toResponse(repository.save(movement));
    }

    StockMovementResponse response(StockMovement movement) {
        return mapper.toResponse(movement);
    }

    /** Called only by the scoped physical-fact transaction. */
    @Transactional
    StockMovementResponse postWarehouseStockFact(StockMovement movement) {
        if (!WarehouseStockFactService.REFERENCE_TYPE.equals(movement.getReferenceType())
            || movement.getDocumentId() == null || !movement.getDocumentId().equals(movement.getReferenceId())
            || (movement.getMovementType() != MovementType.RECEIPT && movement.getMovementType() != MovementType.ISSUE))
            throw new BusinessException("Physical warehouse facts require their linked receipt or issue document");
        beforeCreate(movement, new StockMovementCreateRequest());
        StockMovement saved = repository.save(movement);
        postMovement(saved, true, true);
        auditService.log("StockMovement", saved.getId(), "POST", "Manual physical warehouse operation", null, null);
        return mapper.toResponse(saved);
    }

    /** Post an approved damage case against its quarantined stock and cost layers. */
    @Transactional
    UUID postDamagedStockWriteOff(com.enterprise.mm.entity.DamagedStockCase stockCase, StockBalance source, LocalDate postingDate) {
        if (stockCase == null || source == null || postingDate == null || postingDate.isAfter(LocalDate.now())
            || !"APPROVED".equals(stockCase.getStatus()) || stockCase.getId() == null
            || !Objects.equals(stockCase.getBalanceId(), source.getId())
            || !Objects.equals(stockCase.getWarehouseId(), source.getWarehouseId())
            || !Objects.equals(stockCase.getTenantId(), TenantContext.currentTenant())
            || !Objects.equals(stockCase.getCompanyId(), CompanyContext.currentCompany())
            || !Objects.equals(source.getTenantId(), TenantContext.currentTenant())
            || !Objects.equals(source.getCompanyId(), CompanyContext.currentCompany())
            || !"OWNED".equals(source.getStockCategory()) || source.getOwnerPartyId() != null)
            throw new BusinessException("Approved damage case and owned stock in the current scope are required");
        var material = materialCatalogGuard.requireActiveTmbMaterial(source.getMaterialId(), "materialId");
        if (material.getMaterialSubtype() == com.enterprise.mm.enums.MaterialSubtype.RETURNABLE_PACKAGING
            || material.getSerialTrackingPolicy() == SerialTrackingPolicy.REQUIRED)
            throw new BusinessException("This material requires a specialized stock workflow");
        StockUomPolicy.quantity(material, stockCase.getQuantity(), false);
        StockMovement movement = new StockMovement();
        movement.setTenantId(TenantContext.currentTenant()); movement.setCompanyId(CompanyContext.currentCompany());
        movement.setMaterialId(source.getMaterialId()); movement.setWarehouseId(source.getWarehouseId());
        movement.setStorageLocationId(source.getStorageLocationId()); movement.setStorageBinId(source.getStorageBinId());
        movement.setStockBatchId(source.getStockBatchId()); movement.setMovementType(MovementType.WRITE_OFF);
        movement.setMovementDate(postingDate); movement.setQuantity(stockCase.getQuantity()); movement.setUnitCost(BigDecimal.ZERO);
        movement.setReferenceType("DAMAGED_STOCK"); movement.setReferenceId(stockCase.getId());
        movement.setDescription(stockCase.getDescription());
        initializeMovement(movement, material);
        movement = repository.save(movement);
        ensurePostingPeriodOpen(movement); ensureNotFrozen(movement);
        OutboundCostResult outbound = prepareOutboundCost(movement, movement.getQuantity(), true);
        if (outbound.consumptions().stream().anyMatch(layer -> !Objects.equals(layer.stockBatchId(), source.getStockBatchId())))
            throw new BusinessException("Damage write-off cost layers do not match the quarantined stock batch");
        StockBalance balance = stockBalanceRepository.findByIdAndIsDeletedFalseForUpdate(source.getId())
            .orElseThrow(() -> new BusinessException("Quarantined stock position is no longer available"));
        if (entityManager != null) entityManager.refresh(balance, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        validateOutboundBalanceScope(balance, movement, movement.getWarehouseId());
        if (!Objects.equals(balance.getStorageLocationId(), movement.getStorageLocationId())
            || !Objects.equals(balance.getStorageBinId(), movement.getStorageBinId())
            || !Objects.equals(balance.getStockBatchId(), movement.getStockBatchId())
            || !"OWNED".equals(balance.getStockCategory()) || balance.getOwnerPartyId() != null
            || nvl(balance.getQuarantineQuantity()).compareTo(movement.getQuantity()) < 0)
            throw new BusinessException("Quarantined stock position changed or has insufficient quantity");
        var mutation = stockBalanceMutationService.applyExactDelta(balance, movement.getQuantity().negate(),
            BigDecimal.ZERO, BigDecimal.ZERO, movement.getQuantity().negate(), movement.getTotalCost().negate());
        stockBalanceRepository.save(balance);
        BatchMutation batch = reduceBatchQuantity(movement, movement.getWarehouseId(), balance.getStockBatchId(), movement.getQuantity());
        UomSnapshot uom = resolveUomSnapshot(movement);
        movement.setUomIdSnapshot(uom.id()); movement.setUomCodeSnapshot(uom.code());
        movement.setBatchNumberSnapshot(batch.batchNumber()); movement.setSourceDispositionSnapshot("QUARANTINE");
        var sourceKey = new StockMovementStockKeySnapshot(balance.getWarehouseId(), balance.getStorageLocationId(),
            balance.getStorageBinId(), balance.getStockBatchId(), batch.batchNumber(), "QUARANTINE");
        var allocation = new StockMovementAllocationDraft("SOURCE", balance.getId(), sourceKey, StockMovementStockKeySnapshot.empty(),
            uom.id(), uom.code(), mutation.onHandDelta(), mutation.availableDelta(), mutation.reservedDelta(), mutation.quarantineDelta(),
            mutation.totalValueDelta(), batch.delta(), movement.getUnitCost(), commonCurrency(outbound.consumptions()),
            commonExchangeRate(outbound.consumptions()), outbound.consumptions().stream().map(this::consumedLineage).toList());
        allocationWriter.write(movement, new StockMovementAllocationEvidence(movement.getTenantId(), movementCompanyId(movement),
            movement.getId(), movement.getMaterialId(), null, List.of(allocation)));
        finalizePostedMovement(movement);
        auditService.log("StockMovement", movement.getId(), "POST", "Damaged stock write-off", null, null);
        return movement.getId();
    }

    /** Package-private entry point; public stock APIs continue to accept TMB only. */
    @Transactional
    StockMovementResponse postFixedAssetStockFact(StockMovement movement) {
        if (!WarehouseStockFactService.REFERENCE_TYPE.equals(movement.getReferenceType())
            || movement.getDocumentId() == null || !movement.getDocumentId().equals(movement.getReferenceId())
            || (movement.getMovementType() != MovementType.RECEIPT && movement.getMovementType() != MovementType.ISSUE))
            throw new BusinessException("Fixed asset stock facts require their linked receipt or issue record");
        Material material = materialCatalogGuard.requireActiveFixedAssetStockMaterial(movement.getMaterialId(), "materialId");
        if (material.getMaterialType() != com.enterprise.mm.enums.MaterialType.FIXED_ASSET)
            throw new BusinessException("Select a fixed-asset material", "FIXED_ASSET_MATERIAL_REQUIRED");
        if (movement.getQuantity() == null || movement.getQuantity().stripTrailingZeros().scale() > 0)
            throw new BusinessException("Fixed asset quantity must be whole", "FIXED_ASSET_QUANTITY_WHOLE");
        initializeMovement(movement, material);
        StockMovement saved = repository.save(movement);
        postMovement(saved, false, true, material, true);
        auditService.log("StockMovement", saved.getId(), "POST", "Manual fixed asset quantity operation", null, null);
        return mapper.toResponse(saved);
    }

    @Transactional
    public StockMovementResponse createAndPostManagedInventoryAdjustment(StockMovement movement) {
        beforeCreate(movement, new StockMovementCreateRequest());
        StockMovement saved = repository.save(movement);
        if (!"InventoryDocument".equals(saved.getReferenceType())
            || !(saved.getMovementType() == MovementType.WRITE_OFF || saved.getMovementType() == MovementType.ADJUSTMENT)) {
            throw new BusinessException("Managed inventory adjustment requires InventoryDocument stock reference");
        }
        postMovement(saved, true);
        auditService.log("StockMovement", saved.getId(), "POST", saved.getMovementType().name(), null, null);
        return mapper.toResponse(saved);
    }
    /** Only TransferPostingService may call this after locking and validating persisted transfer facts. */
    StockMovement postManagedTransfer(TransferPostingContext context, StockMovement movement,
        com.enterprise.mm.entity.TransferShipmentAllocation origin, BigDecimal exactValue) {
        if (materialCatalogGuard.requireActiveTmbMaterial(movement.getMaterialId(), "materialId").getMaterialSubtype()
            == com.enterprise.mm.enums.MaterialSubtype.RETURNABLE_PACKAGING)
            throw new BusinessException("Returnable packaging requires a linked warehouse operation");
        beforeCreate(movement, new StockMovementCreateRequest());
        movement = repository.save(movement);
        ensurePostingPeriodOpen(movement);
        ensureNotFrozen(movement);
        List<StockMovementAllocationDraft> drafts;
        if (context.kind() == TransferPostingContext.Kind.DISPATCH) {
            var outbound = prepareOutboundCost(movement, movement.getQuantity());
            drafts = subtractStock(movement, movement.getWarehouseId(), movement.getStorageLocationId(), movement.getStorageBinId(), movement.getQuantity(), outbound);
        } else if (context.kind() == TransferPostingContext.Kind.QUARANTINE_RELEASE || context.kind() == TransferPostingContext.Kind.QUARANTINE_WRITE_OFF || context.kind() == TransferPostingContext.Kind.QUARANTINE_RETURN_DISPATCH) {
            drafts = List.of(disposeTransferredQuarantine(context, movement, origin));
        } else {
            movement.setTotalCost(exactValue);
            movement.setUnitCost(exactValue.divide(movement.getQuantity(), 6, RoundingMode.HALF_UP));
            drafts = List.of(addTransferredStock(context, movement, origin, exactValue));
        }
        allocationWriter.write(movement, new StockMovementAllocationEvidence(movement.getTenantId(), movementCompanyId(movement),
            movement.getId(), movement.getMaterialId(), null, drafts));
        finalizePostedMovement(movement);
        auditService.log("StockMovement", movement.getId(), "TRANSFER_" + context.kind(), context.transferId().toString(), null, null);
        return movement;
    }

    private StockMovementAllocationDraft disposeTransferredQuarantine(TransferPostingContext context, StockMovement movement,
        com.enterprise.mm.entity.TransferShipmentAllocation origin) {
        BigDecimal quantity = movement.getQuantity();
        boolean writeOff = context.kind() == TransferPostingContext.Kind.QUARANTINE_WRITE_OFF || context.kind() == TransferPostingContext.Kind.QUARANTINE_RETURN_DISPATCH;
        OutboundCostResult outbound = null;
        if (writeOff) { movement.setUnitCost(BigDecimal.ZERO); outbound = prepareOutboundCost(movement, quantity); }
        var balance = stockBalanceRepository.findCompanyBalanceForUpdate(movement.getTenantId(), movement.getCompanyId(),
            movement.getMaterialId(), movement.getWarehouseId(), movement.getStorageLocationId() == null, movement.getStorageLocationId(),
            movement.getStorageBinId() == null, movement.getStorageBinId(), false, movement.getStockBatchId())
            .orElseThrow(() -> new BusinessException("Transfer quarantine balance is missing"));
        if (nvl(balance.getQuarantineQuantity()).compareTo(quantity) < 0)
            throw new BusinessException("Selected quantity exceeds remaining quarantine stock");
        var batch = requireInboundBatchForUpdate(movement.getStockBatchId(), movement, movement.getWarehouseId());
        BigDecimal value = BigDecimal.ZERO;
        List<StockMovementCostLineageDraft> lineages = List.of();
        if (writeOff) {
            value = movement.getTotalCost();
            lineages = outbound.consumptions().stream().map(c -> new StockMovementCostLineageDraft("CONSUMED", c.costLayerId(),
                c.parentCostLayerId(), c.costMovementId(), c.quantity().negate(), c.totalCost().negate(), c.unitCost(), c.currencyCode(), c.exchangeRate())).toList();
            batch.setQuantity(batch.getQuantity().subtract(quantity)); stockBatchRepository.save(batch);
        } else {
            movement.setTotalCost(BigDecimal.ZERO); movement.setUnitCost(BigDecimal.ZERO);
            movement.setTargetWarehouseId(movement.getWarehouseId()); movement.setTargetStorageLocationId(movement.getStorageLocationId());
            movement.setTargetStorageBinId(movement.getStorageBinId()); movement.setTargetStockBatchId(batch.getId());
        }
        var mutation = stockBalanceMutationService.applyExactDelta(balance, writeOff ? quantity.negate() : BigDecimal.ZERO,
            writeOff ? BigDecimal.ZERO : quantity, BigDecimal.ZERO, quantity.negate(), value.negate());
        stockBalanceRepository.save(balance);
        movement.setUomIdSnapshot(origin.getUnitOfMeasureId()); movement.setUomCodeSnapshot(origin.getUnitCode());
        movement.setBatchNumberSnapshot(batch.getBatchNumber()); movement.setSourceDispositionSnapshot("QUARANTINE");
        movement.setTargetDispositionSnapshot(writeOff ? null : "AVAILABLE");
        var source = new StockMovementStockKeySnapshot(movement.getWarehouseId(), movement.getStorageLocationId(), movement.getStorageBinId(), batch.getId(), batch.getBatchNumber(), "QUARANTINE");
        var target = writeOff ? StockMovementStockKeySnapshot.empty() : new StockMovementStockKeySnapshot(movement.getWarehouseId(), movement.getStorageLocationId(), movement.getStorageBinId(), batch.getId(), batch.getBatchNumber(), "AVAILABLE");
        return new StockMovementAllocationDraft(writeOff ? "SOURCE" : "TARGET", balance.getId(), source, target,
            origin.getUnitOfMeasureId(), origin.getUnitCode(), mutation.onHandDelta(), mutation.availableDelta(), mutation.reservedDelta(),
            mutation.quarantineDelta(), mutation.totalValueDelta(), writeOff ? quantity.negate() : BigDecimal.ZERO,
            movement.getUnitCost(), origin.getCurrencyCode(), origin.getExchangeRate(), lineages);
    }

    private StockMovementAllocationDraft addTransferredStock(TransferPostingContext context, StockMovement movement,
        com.enterprise.mm.entity.TransferShipmentAllocation origin, BigDecimal value) {
        boolean returned = context.kind() == TransferPostingContext.Kind.TRANSIT_RETURN;
        boolean quarantine = context.kind() == TransferPostingContext.Kind.QUARANTINE_RECEIPT;
        StockBatch batch;
        StockBalance balance = null;
        if (returned) {
            balance = findOrCreateBalanceForUpdate(movement, movementCompanyId(movement), movement.getWarehouseId(),
                movement.getStorageLocationId(), movement.getStorageBinId(), origin.getSourceStockBatchId());
            batch = requireInboundBatchForUpdate(origin.getSourceStockBatchId(), movement, movement.getWarehouseId());
        } else {
            batch = new StockBatch(); batch.setName("Received transfer batch");
            batch.setCode("XFER-BATCH-" + UUID.randomUUID().toString().substring(0, 8)); batch.setStatus("ACTIVE");
            batch.setMaterialId(movement.getMaterialId()); batch.setWarehouseId(movement.getWarehouseId());
            batch.setBatchNumber(origin.getBatchNumber()); batch.setSerialNumber(origin.getSerialNumber());
            batch.setExpirationDate(origin.getExpiryDate()); batch.setReceivedDate(movement.getMovementDate());
            batch.setQuantity(BigDecimal.ZERO); batch.setUnitCost(movement.getUnitCost());
            batch.setPurchaseUnitCost(movement.getUnitCost()); batch.setFinalUnitCost(movement.getUnitCost());
        }
        batch.setQuantity(nvl(batch.getQuantity()).add(movement.getQuantity()));
        batch = stockBatchRepository.saveAndFlush(batch); movement.setStockBatchId(batch.getId());
        if (balance == null) balance = findOrCreateBalanceForUpdate(movement, movementCompanyId(movement), movement.getWarehouseId(),
            movement.getStorageLocationId(), movement.getStorageBinId(), batch.getId());
        var quantity = movement.getQuantity();
        var mutation = stockBalanceMutationService.applyExactDelta(balance, quantity, quarantine ? BigDecimal.ZERO : quantity,
            BigDecimal.ZERO, quarantine ? quantity : BigDecimal.ZERO, value);
        balance = stockBalanceRepository.saveAndFlush(balance);
        var cost = inventoryCostingService.recordTransferInboundLayer(movement, origin, value);
        var disposition = quarantine ? "QUARANTINE" : "AVAILABLE";
        movement.setBatchNumberSnapshot(batch.getBatchNumber()); movement.setUomIdSnapshot(origin.getUnitOfMeasureId());
        movement.setUomCodeSnapshot(origin.getUnitCode()); movement.setTargetDispositionSnapshot(disposition);
        movement.setSourceCurrencyCodeSnapshot(origin.getCurrencyCode()); movement.setSourceExchangeRateSnapshot(origin.getExchangeRate());
        return new StockMovementAllocationDraft("TARGET", balance.getId(), StockMovementStockKeySnapshot.empty(),
            new StockMovementStockKeySnapshot(movement.getWarehouseId(), movement.getStorageLocationId(), movement.getStorageBinId(), batch.getId(), batch.getBatchNumber(), disposition),
            origin.getUnitOfMeasureId(), origin.getUnitCode(), mutation.onHandDelta(), mutation.availableDelta(), mutation.reservedDelta(),
            mutation.quarantineDelta(), mutation.totalValueDelta(), quantity, cost.unitCost(), cost.currencyCode(), cost.exchangeRate(),
            List.of(new StockMovementCostLineageDraft("CREATED", cost.costLayerId(), cost.parentCostLayerId(), cost.costMovementId(),
                quantity, value, cost.unitCost(), cost.currencyCode(), cost.exchangeRate())));
    }

    private void rejectManagedWorkflowReference(String referenceType) {
        if ("ONE_C_API".equalsIgnoreCase(referenceType))
            throw new BusinessException("Native 1C facts must use the authenticated import API");
        if ("KIMYOSANOAT".equalsIgnoreCase(referenceType))
            throw new BusinessException("Kimyosanoat stock must use its scoped approval workflow");
        if ("DAMAGED_STOCK".equalsIgnoreCase(referenceType))
            throw new BusinessException("Damaged stock must use its approved workflow");
        if ("PRODUCTION_OPERATION".equalsIgnoreCase(referenceType))
            throw new BusinessException("Production stock must use its linked document");
        if (referenceType != null && referenceType.toUpperCase(Locale.ROOT).startsWith("RETURNABLE_PACKAGING_"))
            throw new BusinessException("Returnable packaging stock must use its linked register workflow");
        if ("MANUAL_WAREHOUSE".equalsIgnoreCase(referenceType))
            throw new BusinessException("Manual warehouse stock must use its scoped command");
        if (WarehouseStockFactService.REFERENCE_TYPE.equalsIgnoreCase(referenceType))
            throw new BusinessException("Physical warehouse facts must use their scoped command");
        if ("OPENING_STOCK_EXACT".equalsIgnoreCase(referenceType))
            throw new BusinessException("Exact opening stock must be posted through its managed import workflow");
        if (referenceType != null && referenceType.toUpperCase(Locale.ROOT).startsWith("TRANSFER_"))
            throw new BusinessException("Transfer stock must be posted through the transfer workflow");
        if (referenceType != null && java.util.Set.of("INVENTORYDOCUMENT", "INVENTORY_DOCUMENT").contains(referenceType.toUpperCase(Locale.ROOT)))
            throw new BusinessException("Physical inventory adjustments must be posted through the approved inventory workflow");
    }

    @Transactional
    public StockMovementResponse cancelPosted(UUID id) {
        return cancelPosted(id, null, "Stock movement cancellation");
    }

    StockMovementResponse cancelPosted(UUID id, UUID reversalDocumentId) {
        return cancelPosted(id, reversalDocumentId, "Stock movement document cancellation");
    }

    StockMovementResponse cancelPosted(UUID id, UUID reversalDocumentId, String reason) {
        return cancelPosted(id, reversalDocumentId, reason, false);
    }

    private StockMovementResponse cancelPosted(UUID id, UUID reversalDocumentId, String reason, boolean production) {
        StockMovement original = findEntity(id);
        if (original.getReversalOfId() != null) {
            StockMovement reversed = repository.findByIdAndIsDeletedFalse(original.getReversalOfId()).orElse(null);
            if (reversed != null && "PRODUCTION_OPERATION".equals(reversed.getReferenceType()))
                throw new BusinessException("A production reversal cannot be cancelled independently of its document");
        }
        // Restoring a reversal would restore stock while its cancelled receipt remains excluded from contract usage.
        if (original.getContractSpecificationId() != null && original.getReversalOfId() != null) {
            throw new BusinessException("Contract-linked reversal cannot be cancelled; use a new scoped warehouse operation");
        }
        if (!(production && "PRODUCTION_OPERATION".equals(original.getReferenceType()))
            && !"MANUAL_WAREHOUSE".equals(original.getReferenceType())) rejectManagedWorkflowReference(original.getReferenceType());
        materialCatalogGuard.requireActiveTmbMaterial(original.getMaterialId(), "materialId");
        if (!(production && "PRODUCTION_OPERATION".equals(original.getReferenceType()))) guardSerializedCancellation(original);
        if (!StockMovementStatus.isEffective(original.getStatus())) {
            throw new BusinessException("Only POSTED stock movement can be cancelled");
        }
        if (repository.existsByTenantIdAndCompanyIdAndReversalOfIdAndIsDeletedFalse(
            original.getTenantId(), movementCompanyId(original), original.getId())) {
            throw new BusinessException("An active reversal already exists for this stock movement");
        }
        StockMovement reversal = new StockMovement();
        reversal.setTenantId(original.getTenantId());
        reversal.setCompanyId(movementCompanyId(original));
        reversal.setCode(original.getCode() + "-REV");
        reversal.setName("Reversal of " + original.getName());
        reversal.setStatus(StockMovementStatus.DRAFT.name());
        reversal.setDocumentId(reversalDocumentId);
        reversal.setMaterialId(original.getMaterialId());
        if (MovementType.TRANSFER.equals(original.getMovementType())) {
            reversal.setWarehouseId(original.getTargetWarehouseId());
            reversal.setTargetWarehouseId(original.getWarehouseId());
            reversal.setStorageLocationId(original.getTargetStorageLocationId());
            reversal.setTargetStorageLocationId(original.getStorageLocationId());
            reversal.setStorageBinId(original.getTargetStorageBinId());
            reversal.setTargetStorageBinId(original.getStorageBinId());
        } else {
            reversal.setWarehouseId(original.getWarehouseId());
            reversal.setTargetWarehouseId(original.getTargetWarehouseId());
            reversal.setStorageLocationId(original.getStorageLocationId());
            reversal.setTargetStorageLocationId(original.getTargetStorageLocationId());
            reversal.setStorageBinId(original.getStorageBinId());
            reversal.setTargetStorageBinId(original.getTargetStorageBinId());
        }
        reversal.setStockBatchId(original.getStockBatchId());
        reversal.setSourceInventoryItemId(original.getSourceInventoryItemId());
        reversal.setSourceCurrencyCodeSnapshot(original.getSourceCurrencyCodeSnapshot());
        reversal.setSourceExchangeRateSnapshot(original.getSourceExchangeRateSnapshot());
        reversal.setAccountCodeSnapshot(original.getAccountCodeSnapshot());
        reversal.setMovementType(reverseType(original.getMovementType()));
        reversal.setQuantity(original.getQuantity());
        reversal.setUnitCost(original.getUnitCost());
        reversal.setTotalCost(original.getTotalCost());
        reversal.setMovementDate(LocalDate.now());
        reversal.setRequestDate(LocalDateTime.now());
        reversal.setReferenceType("REVERSAL");
        reversal.setReferenceId(original.getId());
        reversal.setReversalOfId(original.getId());
        reversal.setContractSpecificationId(original.getContractSpecificationId());
        StockMovement postedReversal = repository.save(reversal);
        ensurePostingPeriodOpen(postedReversal);
        ensureNotFrozen(postedReversal);
        reversePersistedAllocations(original, postedReversal, reason);
        finalizePostedMovement(postedReversal);
        original.setStatus(StockMovementStatus.CANCELLED.name());
        repository.save(original);
        auditService.log("StockMovement", original.getId(), "CANCEL_POSTED", postedReversal.getId().toString(), null, null);
        return mapper.toResponse(postedReversal);
    }

    private void reversePersistedAllocations(
        StockMovement original,
        StockMovement reversal,
        String reason
    ) {
        List<StockMovementAllocation> originals = allocationRepository
            .findByTenantIdAndCompanyIdAndMovementId(
                original.getTenantId(), movementCompanyId(original), original.getId());
        if (originals.isEmpty()) {
            throw new BusinessException(
                "Posted stock movement has no immutable allocation evidence and cannot be reversed exactly");
        }
        Map<UUID, List<StockMovementAllocationCostLayer>> lineagesByAllocation =
            allocationCostLayerRepository.findByTenantIdAndCompanyIdAndMovementId(
                    original.getTenantId(), movementCompanyId(original), original.getId())
                .stream()
                .collect(java.util.stream.Collectors.groupingBy(
                    StockMovementAllocationCostLayer::getAllocationId,
                    LinkedHashMap::new,
                    java.util.stream.Collectors.toList()
                ));

        List<StockMovementAllocationDraft> reversalDrafts = new ArrayList<>();
        for (StockMovementAllocation allocation : originals) {
            validateOriginalAllocationScope(original, allocation);
            StockMovementStockKeySnapshot currentKey = currentStockKey(allocation);
            StockBalance balance = stockBalanceRepository
                .findByIdAndIsDeletedFalseForUpdate(allocation.getBalanceId())
                .orElseThrow(() -> new BusinessException(
                    "Exact reversal stock balance is missing: " + allocation.getBalanceId()));
            validateExactReversalBalance(balance, original, currentKey);

            BigDecimal onHandDelta = nvl(allocation.getOnHandDelta()).negate();
            BigDecimal availableDelta = nvl(allocation.getAvailableDelta()).negate();
            BigDecimal reservedDelta = nvl(allocation.getReservedDelta()).negate();
            BigDecimal quarantineDelta = nvl(allocation.getQuarantineDelta()).negate();
            BigDecimal totalValueDelta = nvl(allocation.getTotalValueDelta()).negate();
            StockBalanceMutationService.BalanceMutation balanceMutation =
                stockBalanceMutationService.applyExactDelta(
                    balance,
                    onHandDelta,
                    availableDelta,
                    reservedDelta,
                    quarantineDelta,
                    totalValueDelta
                );
            stockBalanceRepository.save(balance);
            validateExactMutation(
                balanceMutation,
                onHandDelta,
                availableDelta,
                reservedDelta,
                quarantineDelta,
                totalValueDelta
            );
            BigDecimal batchDelta = nvl(allocation.getBatchQuantityDelta()).negate();
            applyExactBatchDelta(original, currentKey, batchDelta);

            List<StockMovementAllocationCostLayer> originalLineages =
                lineagesByAllocation.getOrDefault(allocation.getId(), List.of());
            List<InventoryCostingService.ExactReversalResult> costResults =
                inventoryCostingService.reverseExactLineages(
                    reversal,
                    currentKey.warehouseId(),
                    originalLineages.stream().map(this::exactReversalLineage).toList(),
                    reason
                );
            List<StockMovementCostLineageDraft> reversalLineages = costResults.stream()
                .map(this::reversalLineageDraft)
                .toList();

            boolean sourceMutation = onHandDelta.signum() < 0
                || (onHandDelta.signum() == 0 && totalValueDelta.signum() < 0);
            reversalDrafts.add(new StockMovementAllocationDraft(
                sourceMutation ? "SOURCE" : "TARGET",
                balance.getId(),
                sourceMutation ? currentKey : StockMovementStockKeySnapshot.empty(),
                sourceMutation ? StockMovementStockKeySnapshot.empty() : currentKey,
                allocation.getUomIdSnapshot(),
                allocation.getUomCodeSnapshot(),
                onHandDelta,
                availableDelta,
                reservedDelta,
                quarantineDelta,
                totalValueDelta,
                batchDelta,
                allocation.getUnitCostSnapshot(),
                allocation.getCurrencyCodeSnapshot(),
                allocation.getExchangeRateSnapshot(),
                reversalLineages
            ));
        }
        StockMovementAllocation first = originals.get(0);
        reversal.setUomIdSnapshot(first.getUomIdSnapshot());
        reversal.setUomCodeSnapshot(first.getUomCodeSnapshot());
        allocationWriter.write(reversal, new StockMovementAllocationEvidence(
            reversal.getTenantId(),
            movementCompanyId(reversal),
            reversal.getId(),
            reversal.getMaterialId(),
            reversal.getSourceInventoryItemId(),
            reversalDrafts
        ));
    }

    private void validateOriginalAllocationScope(
        StockMovement original,
        StockMovementAllocation allocation
    ) {
        if (!Objects.equals(original.getTenantId(), allocation.getTenantId())
            || !Objects.equals(movementCompanyId(original), allocation.getCompanyId())
            || !Objects.equals(original.getId(), allocation.getMovementId())
            || !Objects.equals(original.getMaterialId(), allocation.getMaterialId())
            || allocation.getBalanceId() == null) {
            throw new BusinessException("Original stock allocation evidence is outside movement scope");
        }
    }

    private StockMovementStockKeySnapshot currentStockKey(StockMovementAllocation allocation) {
        if ("TARGET".equals(allocation.getAllocationRole())) {
            return new StockMovementStockKeySnapshot(
                allocation.getTargetWarehouseIdSnapshot(),
                allocation.getTargetStorageLocationIdSnapshot(),
                allocation.getTargetStorageBinIdSnapshot(),
                allocation.getTargetStockBatchIdSnapshot(),
                allocation.getTargetBatchNumberSnapshot(),
                allocation.getTargetDispositionSnapshot()
            );
        }
        return new StockMovementStockKeySnapshot(
            allocation.getSourceWarehouseIdSnapshot(),
            allocation.getSourceStorageLocationIdSnapshot(),
            allocation.getSourceStorageBinIdSnapshot(),
            allocation.getSourceStockBatchIdSnapshot(),
            allocation.getSourceBatchNumberSnapshot(),
            allocation.getSourceDispositionSnapshot()
        );
    }

    private void validateExactReversalBalance(
        StockBalance balance,
        StockMovement original,
        StockMovementStockKeySnapshot key
    ) {
        if (!Objects.equals(balance.getTenantId(), original.getTenantId())
            || !Objects.equals(balance.getCompanyId(), movementCompanyId(original))
            || !Objects.equals(balance.getMaterialId(), original.getMaterialId())
            || !Objects.equals(balance.getWarehouseId(), key.warehouseId())
            || !Objects.equals(balance.getStorageLocationId(), key.storageLocationId())
            || !Objects.equals(balance.getStorageBinId(), key.storageBinId())
            || !Objects.equals(balance.getStockBatchId(), key.stockBatchId())) {
            throw new BusinessException("Exact reversal stock balance key no longer matches evidence");
        }
    }

    private void validateExactMutation(
        StockBalanceMutationService.BalanceMutation mutation,
        BigDecimal onHand,
        BigDecimal available,
        BigDecimal reserved,
        BigDecimal quarantine,
        BigDecimal value
    ) {
        if (mutation.onHandDelta().compareTo(onHand) != 0
            || mutation.availableDelta().compareTo(available) != 0
            || mutation.reservedDelta().compareTo(reserved) != 0
            || mutation.quarantineDelta().compareTo(quarantine) != 0
            || mutation.totalValueDelta().compareTo(value) != 0) {
            throw new BusinessException("Exact reversal balance mutation does not match evidence");
        }
    }

    private void applyExactBatchDelta(
        StockMovement original,
        StockMovementStockKeySnapshot key,
        BigDecimal delta
    ) {
        if (delta.signum() == 0) {
            return;
        }
        if (key.stockBatchId() == null) {
            throw new BusinessException("Exact reversal batch delta has no persisted batch key");
        }
        StockBatch batch = stockBatchRepository.findByIdAndIsDeletedFalseForUpdate(key.stockBatchId())
            .orElseThrow(() -> new BusinessException(
                "Exact reversal stock batch is missing: " + key.stockBatchId()));
        validateOutboundBatchScope(batch, original, key.warehouseId());
        BigDecimal next = nvl(batch.getQuantity()).add(delta);
        if (next.signum() < 0) {
            throw new BusinessException("Exact reversal would produce a negative batch quantity");
        }
        batch.setQuantity(next);
        stockBatchRepository.save(batch);
    }

    private InventoryCostingService.ExactReversalLineage exactReversalLineage(
        StockMovementAllocationCostLayer lineage
    ) {
        return new InventoryCostingService.ExactReversalLineage(
            lineage.getCostLayerId(),
            lineage.getParentCostLayerIdSnapshot(),
            lineage.getSourceCostMovementId(),
            lineage.getQuantityDelta(),
            lineage.getValueDelta(),
            lineage.getUnitCostSnapshot(),
            lineage.getCurrencyCodeSnapshot(),
            lineage.getExchangeRateSnapshot()
        );
    }

    private StockMovementCostLineageDraft reversalLineageDraft(
        InventoryCostingService.ExactReversalResult result
    ) {
        return new StockMovementCostLineageDraft(
            result.quantityDelta().signum() < 0 ? "CONSUMED" : "RESTORED",
            result.costLayerId(),
            result.parentCostLayerIdSnapshot(),
            result.costMovementId(),
            result.quantityDelta(),
            result.valueDelta(),
            result.unitCostSnapshot(),
            result.currencyCodeSnapshot(),
            result.exchangeRateSnapshot()
        );
    }

    private void applyMovement(StockMovement movement) {
        MovementType type = movement.getMovementType();
        BigDecimal quantity = nvl(movement.getQuantity());
        List<StockMovementAllocationDraft> allocationDrafts = new ArrayList<>();
        if (quantity.signum() <= 0) {
            throw new BusinessException("Movement quantity must be positive");
        }
        switch (type) {
            case RECEIPT, RETURN -> allocationDrafts.add(addStock(movement, movement.getWarehouseId(), movement.getStorageLocationId(), movement.getStorageBinId(), quantity, true, null, InventoryCostLayerType.RECEIPT));
            case ADJUSTMENT -> allocationDrafts.add(addStock(movement, movement.getWarehouseId(), movement.getStorageLocationId(), movement.getStorageBinId(), quantity, true, null, InventoryCostLayerType.ADJUSTMENT_IN));
            case ISSUE, WRITE_OFF -> {
                boolean kimyosanoatIssue = movement.getMovementType() == MovementType.ISSUE
                    && "KIMYOSANOAT".equals(movement.getReferenceType());
                OutboundCostResult outbound = prepareOutboundCost(movement, quantity, kimyosanoatIssue);
                if (kimyosanoatIssue && outbound.consumptions().stream()
                    .anyMatch(layer -> !Objects.equals(layer.stockBatchId(), movement.getStockBatchId())))
                    throw new BusinessException("Kimyosanoat issue cost layers must match the selected stock batch");
                allocationDrafts.addAll(subtractStock(
                    movement,
                    movement.getWarehouseId(),
                    movement.getStorageLocationId(),
                    movement.getStorageBinId(),
                    quantity,
                    outbound
                ));
            }
            case TRANSFER -> {
                OutboundCostResult outbound = prepareOutboundCost(movement, quantity);
                allocationDrafts.addAll(subtractStock(
                    movement,
                    movement.getWarehouseId(),
                    movement.getStorageLocationId(),
                    movement.getStorageBinId(),
                    quantity,
                    outbound
                ));
                if (movement.getTargetWarehouseId() == null) {
                    throw new BusinessException("Target warehouse is required for transfer");
                }
                UUID targetBatchId = Objects.equals(movement.getWarehouseId(), movement.getTargetWarehouseId())
                    ? movement.getStockBatchId()
                    : createTransferTargetBatch(movement, quantity);
                allocationDrafts.add(addStock(
                    movement,
                    movement.getTargetWarehouseId(),
                    movement.getTargetStorageLocationId(),
                    movement.getTargetStorageBinId(),
                    quantity,
                    true,
                    targetBatchId,
                    InventoryCostLayerType.TRANSFER_IN
                ));
            }
            case REVERSAL -> addStock(movement, movement.getWarehouseId(), movement.getStorageLocationId(), movement.getStorageBinId(), quantity, false, null, InventoryCostLayerType.ADJUSTMENT_IN);
        }
        if (!allocationDrafts.isEmpty()) {
            allocationWriter.write(movement, new StockMovementAllocationEvidence(
                movement.getTenantId(),
                movementCompanyId(movement),
                movement.getId(),
                movement.getMaterialId(),
                movement.getSourceInventoryItemId(),
                allocationDrafts
            ));
        }
    }

    private void ensureNotFrozen(StockMovement movement) {
        inventoryFreezeGuardService.ensureMovementAllowed(movement);
    }

    private OutboundCostResult prepareOutboundCost(StockMovement movement, BigDecimal quantity) {
        return prepareOutboundCost(movement, quantity, false);
    }

    private OutboundCostResult prepareOutboundCost(StockMovement movement, BigDecimal quantity, boolean exactBatch) {
        ValuationMethod method = valuationPolicyService.resolveMethod(movement.getMaterialId(), movement.getWarehouseId());
        if (method == ValuationMethod.SPECIFIC_BATCH) {
            validateSpecificBatchIssue(movement, quantity);
            if (nvl(movement.getUnitCost()).signum() > 0) {
                throw new BusinessException("Manual unit cost cannot bypass SPECIFIC_BATCH valuation");
            }
        }

        if ("REVERSAL".equals(movement.getReferenceType()) && nvl(movement.getUnitCost()).signum() > 0) {
            movement.setTotalCost(nvl(movement.getUnitCost()).multiply(quantity));
            inventoryCostingService.recordManualCostMovement(movement, movement.getWarehouseId(), quantity, movement.getUnitCost());
            return new OutboundCostResult(method, List.of());
        }

        BigDecimal totalCost;
        List<InventoryCostingService.LayerConsumption> consumptions;
        try {
            InventoryCostingService.CostResult result = exactBatch
                ? inventoryCostingService.consumeOutboundLayersForStockBatch(movement, movement.getWarehouseId(), quantity, method)
                : inventoryCostingService.consumeOutboundLayers(movement, movement.getWarehouseId(), quantity, method);
            totalCost = result.totalCost();
            consumptions = result.consumptions();
        } catch (BusinessException ex) {
            if (negativeStockAllowed() && nvl(movement.getUnitCost()).signum() > 0) {
                if (method == ValuationMethod.SPECIFIC_BATCH) {
                    throw new BusinessException("Manual unit cost cannot bypass SPECIFIC_BATCH valuation");
                }
                movement.setTotalCost(nvl(movement.getUnitCost()).multiply(quantity));
                inventoryCostingService.recordManualCostMovement(movement, movement.getWarehouseId(), quantity, movement.getUnitCost());
                return new OutboundCostResult(method, List.of());
            }
            throw ex;
        }
        movement.setTotalCost(totalCost);
        movement.setUnitCost(totalCost.divide(quantity, valuationPolicyService.roundingScale(), valuationPolicyService.roundingMode()));
        return new OutboundCostResult(method, consumptions);
    }

    private void validateSpecificBatchIssue(StockMovement movement, BigDecimal quantity) {
        if (movement.getStockBatchId() == null) {
            throw new BusinessException("SPECIFIC_BATCH requires stockBatchId");
        }
        StockBatch batch = stockBatchRepository.findByIdAndIsDeletedFalse(movement.getStockBatchId())
            .orElseThrow(() -> new BusinessException("SPECIFIC_BATCH requires stockBatchId"));
        if (!TenantContext.currentTenant().equals(batch.getTenantId()) || !movementCompanyId(movement).equals(batch.getCompanyId())) {
            throw new BusinessException("SPECIFIC_BATCH requires stockBatchId");
        }
        if (!movement.getMaterialId().equals(batch.getMaterialId())) {
            throw new BusinessException("Selected batch does not belong to this material");
        }
        if (!movement.getWarehouseId().equals(batch.getWarehouseId())) {
            throw new BusinessException("Selected batch does not belong to this warehouse");
        }
        if (nvl(batch.getQuantity()).compareTo(nvl(quantity)) < 0) {
            throw insufficientStock(movement, movement.getWarehouseId(), quantity, nvl(batch.getQuantity()));
        }
    }

    private void ensurePostingPeriodOpen(StockMovement movement) {
        LocalDate movementDate = movement.getMovementDate() == null ? LocalDate.now() : movement.getMovementDate();
        periodGuardService.ensureOpen(movement.getWarehouseId(), movementDate, "stock movement");
        if (movement.getTargetWarehouseId() != null) {
            periodGuardService.ensureOpen(movement.getTargetWarehouseId(), movementDate, "stock movement target warehouse");
        }
    }

    private StockMovementAllocationDraft addStock(
        StockMovement movement,
        UUID warehouseId,
        UUID storageLocationId,
        UUID storageBinId,
        BigDecimal quantity,
        boolean createBatch,
        UUID batchIdOverride,
        InventoryCostLayerType layerType
    ) {
        validateInboundCost(movement);
        UUID batchId = batchIdOverride == null ? movement.getStockBatchId() : batchIdOverride;
        StockBatch postingBatch = null;
        BatchMutation batchMutation = new BatchMutation(null, null, BigDecimal.ZERO, BigDecimal.ZERO);

        if (createBatch && batchId == null) {
            StockBatch batch = new StockBatch();
            batch.setCode("BATCH-" + UUID.randomUUID().toString().substring(0, 8));
            batch.setName("Received stock batch");
            batch.setStatus("ACTIVE");
            batch.setCompanyId(movementCompanyId(movement));
            batch.setMaterialId(movement.getMaterialId());
            batch.setWarehouseId(warehouseId);
            batch.setBatchNumber(nonBlank(movement.getBatchNumber()) ? movement.getBatchNumber().trim() : batch.getCode());
            batch.setReceivedDate(LocalDate.now());
            batch.setQuantity(quantity);
            batch.setUnitCost(nvl(movement.getUnitCost()));
            batch.setPurchaseUnitCost(nvl(movement.getUnitCost()));
            batch.setFinalUnitCost(nvl(movement.getUnitCost()));
            postingBatch = stockBatchRepository.save(batch);
            if (postingBatch == null || postingBatch.getId() == null) {
                throw new BusinessException("Inbound stock batch id was not assigned");
            }
            batchId = postingBatch.getId();
            movement.setStockBatchId(batchId);
            batchMutation = new BatchMutation(
                batchId,
                postingBatch.getBatchNumber(),
                BigDecimal.ZERO,
                nvl(postingBatch.getQuantity())
            );
        } else if (batchId != null) {
            postingBatch = requireInboundBatchForUpdate(batchId, movement, warehouseId);
            BigDecimal beforeBatchQuantity = nvl(postingBatch.getQuantity());
            if (createBatch) {
                postingBatch.setQuantity(beforeBatchQuantity.add(quantity));
                refreshExistingBatchCosts(postingBatch, movement.getUnitCost());
                postingBatch = stockBatchRepository.save(postingBatch);
            }
            batchMutation = new BatchMutation(
                postingBatch.getId(),
                postingBatch.getBatchNumber(),
                beforeBatchQuantity,
                nvl(postingBatch.getQuantity())
            );
        }

        UUID lookupBatchId = batchId;
        UUID companyId = movementCompanyId(movement);

        StockBalance balance = findOrCreateBalanceForUpdate(
            movement,
            companyId,
            warehouseId,
            storageLocationId,
            storageBinId,
            lookupBatchId
        );

        boolean productionValue = "PRODUCTION_OPERATION".equals(movement.getReferenceType());
        StockBalanceMutationService.BalanceMutation balanceMutation =
            stockBalanceMutationService.increaseOnHand(balance, quantity, movement.getUnitCost(),
                productionValue ? movement.getTotalCost() : null);
        StockBalance savedBalance = stockBalanceRepository.save(balance);
        if (savedBalance == null || savedBalance.getId() == null) {
            throw new BusinessException("Inbound stock balance id was not assigned");
        }
        InventoryCostingService.InboundLayerResult costing = productionValue
            ? inventoryCostingService.recordProductionLayer(movement, warehouseId, lookupBatchId)
            : inventoryCostingService.recordInboundLayer(
                movement, warehouseId, lookupBatchId, quantity, movement.getUnitCost(), layerType);
        validateInboundEvidence(balanceMutation, costing);

        UomSnapshot uom = resolveUomSnapshot(movement);
        String batchNumber = postingBatch == null ? null : postingBatch.getBatchNumber();
        if (createBatch) {
            movement.setBatchNumberSnapshot(batchNumber);
            movement.setUomIdSnapshot(uom.id());
            movement.setUomCodeSnapshot(uom.code());
            movement.setSourceDispositionSnapshot(null);
            movement.setTargetDispositionSnapshot("AVAILABLE");
        }
        StockMovementStockKeySnapshot targetKey = new StockMovementStockKeySnapshot(
            savedBalance.getWarehouseId(),
            savedBalance.getStorageLocationId(),
            savedBalance.getStorageBinId(),
            savedBalance.getStockBatchId(),
            batchNumber,
            "AVAILABLE"
        );
        StockMovementCostLineageDraft lineage = new StockMovementCostLineageDraft(
            "CREATED",
            costing.costLayerId(),
            costing.parentCostLayerId(),
            costing.costMovementId(),
            costing.quantityDelta(),
            costing.valueDelta(),
            costing.unitCost(),
            costing.currencyCode(),
            costing.exchangeRate()
        );
        return new StockMovementAllocationDraft(
            "TARGET",
            savedBalance.getId(),
            StockMovementStockKeySnapshot.empty(),
            targetKey,
            uom.id(),
            uom.code(),
            balanceMutation.onHandDelta(),
            balanceMutation.availableDelta(),
            balanceMutation.reservedDelta(),
            balanceMutation.quarantineDelta(),
            balanceMutation.totalValueDelta(),
            batchMutation.delta(),
            costing.unitCost(),
            costing.currencyCode(),
            costing.exchangeRate(),
            List.of(lineage)
        );
    }

    private void validateInboundEvidence(
        StockBalanceMutationService.BalanceMutation balanceMutation,
        InventoryCostingService.InboundLayerResult costing
    ) {
        if (costing == null) {
            throw new BusinessException("Inbound cost evidence is required");
        }
        if (balanceMutation.onHandDelta().compareTo(nvl(costing.quantityDelta())) != 0) {
            throw new BusinessException("Inbound balance quantity does not match cost evidence quantity");
        }
        if (balanceMutation.totalValueDelta().compareTo(nvl(costing.valueDelta())) != 0) {
            throw new BusinessException("Inbound balance value does not match cost evidence value");
        }
    }

    private StockBatch requireInboundBatchForUpdate(UUID batchId, StockMovement movement, UUID warehouseId) {
        StockBatch batch = stockBatchRepository.findByIdAndIsDeletedFalseForUpdate(batchId)
            .orElseThrow(() -> new BusinessException("Inbound stock batch not found: " + batchId));
        if (!TenantContext.currentTenant().equals(batch.getTenantId())) {
            throw new BusinessException("Inbound stock batch tenant does not match movement tenant");
        }
        if (!movementCompanyId(movement).equals(batch.getCompanyId())) {
            throw new BusinessException("Inbound stock batch company does not match movement company");
        }
        if (!movement.getMaterialId().equals(batch.getMaterialId())) {
            throw new BusinessException("Inbound stock batch material does not match movement material");
        }
        if (!warehouseId.equals(batch.getWarehouseId())) {
            throw new BusinessException("Inbound stock batch warehouse does not match movement warehouse");
        }
        return batch;
    }

    private UomSnapshot resolveUomSnapshot(StockMovement movement) {
        var material = materialRepository.findByIdAndIsDeletedFalse(movement.getMaterialId())
            .orElseThrow(() -> new BusinessException("Inbound movement material not found"));
        if (!TenantContext.currentTenant().equals(material.getTenantId())
            || !movementCompanyId(movement).equals(material.getCompanyId())) {
            throw new BusinessException("Inbound movement material is outside tenant/company scope");
        }
        var uom = material.getUnitOfMeasure();
        String code = uom == null ? null : firstNonBlank(
            uom.getCode(),
            uom.getMeasurementNameShort(),
            uom.getMeasurementNameShortRu(),
            uom.getName()
        );
        return new UomSnapshot(material.getUnitOfMeasureId(), code);
    }

    private void validateInboundCost(StockMovement movement) {
        if (nvl(movement.getUnitCost()).signum() > 0) {
            return;
        }
        if (!valuationPolicyService.allowZeroCostReceipt()) {
            throw new BusinessException("Zero-cost receipt is not allowed by inventory accounting settings");
        }
        if (movement.getDescription() == null || movement.getDescription().isBlank()) {
            throw new BusinessException("Zero-cost receipt requires a reason in description");
        }
    }

    private UUID createTransferTargetBatch(StockMovement movement, BigDecimal quantity) {
        StockBatch target = new StockBatch();
        target.setCode("XFER-BATCH-" + UUID.randomUUID().toString().substring(0, 8));
        target.setName("Transfer stock batch");
        target.setStatus("ACTIVE");
        target.setCompanyId(movementCompanyId(movement));
        target.setMaterialId(movement.getMaterialId());
        target.setWarehouseId(movement.getTargetWarehouseId());
        target.setReceivedDate(LocalDate.now());
        // Quantity is applied by addStock under the same locked mutation path so
        // the transfer target allocation captures an exact before/after delta.
        target.setQuantity(BigDecimal.ZERO);
        target.setUnitCost(nvl(movement.getUnitCost()));
        target.setPurchaseUnitCost(nvl(movement.getUnitCost()));
        target.setFinalUnitCost(nvl(movement.getUnitCost()));
        if (movement.getStockBatchId() != null) {
            stockBatchRepository.findByIdAndIsDeletedFalse(movement.getStockBatchId()).ifPresent(source -> {
                target.setBatchNumber(nonBlank(source.getBatchNumber()) ? source.getBatchNumber() : source.getCode());
                target.setSerialNumber(source.getSerialNumber());
                target.setExpirationDate(source.getExpirationDate());
            });
        }
        if (!nonBlank(target.getBatchNumber())) {
            target.setBatchNumber(target.getCode());
        }
        return stockBatchRepository.save(target).getId();
    }

    private List<StockMovementAllocationDraft> subtractStock(
        StockMovement movement,
        UUID warehouseId,
        UUID storageLocationId,
        UUID storageBinId,
        BigDecimal quantity,
        OutboundCostResult outbound
    ) {
        UUID companyId = movementCompanyId(movement);
        if (hasLayerConsumptions(outbound)) {
            return subtractStockByLayerConsumptions(
                movement, warehouseId, storageLocationId, storageBinId, quantity, outbound.consumptions());
        }

        List<StockMovementAllocationDraft> allocations = new ArrayList<>();

        if (movement.getStockBatchId() != null) {
            StockBalance balance = "KIMYOSANOAT".equals(movement.getReferenceType())
                ? stockBalanceRepository.findPartitionedBalanceForUpdate(
                    TenantContext.currentTenant(), companyId, movement.getMaterialId(), warehouseId,
                    storageLocationId, storageBinId, movement.getStockBatchId(), null, "OWNED")
                    .orElseThrow(() -> new BusinessException("Owned source balance not found for selected batch"))
                : stockBalanceRepository.findCompanyBalanceForUpdate(
                            TenantContext.currentTenant(),
                            companyId,
                            movement.getMaterialId(),
                            warehouseId,
                            storageLocationId == null,
                            storageLocationId,
                            storageBinId == null,
                            storageBinId,
                            false,
                            movement.getStockBatchId()
                    )
                    .orElseThrow(() -> new BusinessException("Stock balance not found for material, warehouse and stock batch"));

            allocations.add(subtractFromBalance(
                balance, movement, warehouseId, quantity, nvl(movement.getTotalCost()), List.of()));
            return allocations;
        }

        List<StockBalance> candidates = stockBalanceRepository.findCompanyBalancesForUpdate(
                        TenantContext.currentTenant(),
                        companyId,
                        movement.getMaterialId(),
                        warehouseId,
                        storageLocationId == null,
                        storageLocationId,
                        storageBinId == null,
                        storageBinId
                ).stream()
                .filter(balance -> nvl(balance.getAvailableQuantity()).signum() > 0)
                .sorted(balanceComparator(outbound.method()))
                .toList();

        BigDecimal remaining = quantity;
        BigDecimal remainingCost = nvl(movement.getTotalCost());

        for (StockBalance balance : candidates) {
            if (remaining.signum() <= 0) {
                break;
            }

            BigDecimal available = nvl(balance.getAvailableQuantity());
            if (available.signum() <= 0) {
                continue;
            }

            BigDecimal consume = available.min(remaining);
            BigDecimal consumedCost = remaining.subtract(consume).signum() == 0
                ? remainingCost
                : nvl(movement.getTotalCost()).multiply(consume).divide(quantity, 4, RoundingMode.HALF_UP).min(remainingCost);
            allocations.add(subtractFromBalance(
                balance, movement, warehouseId, consume, consumedCost, List.of()));
            remaining = remaining.subtract(consume);
            remainingCost = remainingCost.subtract(consumedCost);
        }

        if (remaining.signum() <= 0) {
            return allocations;
        }

        if (!negativeStockAllowed()) {
            throw insufficientStock(movement, warehouseId, quantity, quantity.subtract(remaining));
        }

        allocations.add(recordNegativeStock(
            movement, warehouseId, storageLocationId, storageBinId, remaining));
        return allocations;
    }

    private boolean hasLayerConsumptions(OutboundCostResult outbound) {
        return outbound != null && outbound.consumptions() != null
            && !outbound.consumptions().isEmpty();
    }

    private List<StockMovementAllocationDraft> subtractStockByLayerConsumptions(
        StockMovement movement,
        UUID warehouseId,
        UUID storageLocationId,
        UUID storageBinId,
        BigDecimal requestedQuantity,
        List<InventoryCostingService.LayerConsumption> consumptions
    ) {
        Map<UUID, List<InventoryCostingService.LayerConsumption>> byBatch = new LinkedHashMap<>();
        List<InventoryCostingService.LayerConsumption> unbatched = new ArrayList<>();
        for (InventoryCostingService.LayerConsumption consumption : consumptions) {
            if (consumption.stockBatchId() == null) {
                unbatched.add(consumption);
                continue;
            }
            byBatch.computeIfAbsent(consumption.stockBatchId(), ignored -> new ArrayList<>())
                .add(consumption);
        }

        List<StockMovementAllocationDraft> allocations = new ArrayList<>();
        BigDecimal reducedQuantity = BigDecimal.ZERO;
        for (Map.Entry<UUID, List<InventoryCostingService.LayerConsumption>> entry : byBatch.entrySet()) {
            BigDecimal batchQuantity = totalConsumptionQuantity(entry.getValue());
            BigDecimal batchCost = totalConsumptionCost(entry.getValue());
            StockBalance balance;
            if ("TRANSFER_DISPATCH".equals(movement.getReferenceType()) || "PRODUCTION_OPERATION".equals(movement.getReferenceType())
                || "KIMYOSANOAT".equals(movement.getReferenceType())) {
                balance = stockBalanceRepository.findPartitionedBalanceForUpdate(
                    TenantContext.currentTenant(), movementCompanyId(movement), movement.getMaterialId(), warehouseId,
                    storageLocationId, storageBinId, entry.getKey(), null, "OWNED")
                    .orElseThrow(() -> new BusinessException("Owned source balance not found for transfer batch"));
            } else {
                balance = stockBalanceRepository.findCompanyBalanceForUpdate(
                    TenantContext.currentTenant(), movementCompanyId(movement), movement.getMaterialId(), warehouseId,
                    storageLocationId == null, storageLocationId, storageBinId == null, storageBinId, false, entry.getKey())
                    .orElseThrow(() -> new BusinessException("Stock balance not found for consumed cost layer batch"));
            }
            allocations.add(subtractFromBalance(
                balance, movement, warehouseId, batchQuantity, batchCost, entry.getValue()));
            reducedQuantity = reducedQuantity.add(batchQuantity);
        }

        BigDecimal unbatchedQuantity = unbatched.stream()
            .map(InventoryCostingService.LayerConsumption::quantity)
            .map(this::nvl)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (unbatchedQuantity.signum() > 0) {
            BigDecimal unbatchedCost = unbatched.stream()
                .map(InventoryCostingService.LayerConsumption::totalCost)
                .map(this::nvl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            allocations.add(subtractUnbatchedBalance(
                movement,
                warehouseId,
                storageLocationId,
                storageBinId,
                unbatchedQuantity,
                unbatchedCost,
                unbatched
            ));
            reducedQuantity = reducedQuantity.add(unbatchedQuantity);
        }

        if (reducedQuantity.compareTo(requestedQuantity) != 0) {
            throw new BusinessException("Consumed layer quantity does not match stock issue quantity");
        }
        return allocations;
    }

    private StockMovementAllocationDraft subtractUnbatchedBalance(
        StockMovement movement,
        UUID warehouseId,
        UUID storageLocationId,
        UUID storageBinId,
        BigDecimal quantity,
        BigDecimal cost,
        List<InventoryCostingService.LayerConsumption> consumptions
    ) {
        StockBalance balance = stockBalanceRepository.findCompanyBalanceForUpdate(
                TenantContext.currentTenant(),
                movementCompanyId(movement),
                movement.getMaterialId(),
                warehouseId,
                storageLocationId == null,
                storageLocationId,
                storageBinId == null,
                storageBinId,
                true,
                null
            )
            .orElseThrow(() -> new BusinessException("Stock balance not found for consumed unbatched cost layer"));
        return subtractFromBalance(balance, movement, warehouseId, quantity, cost, consumptions);
    }

    private StockMovementAllocationDraft recordNegativeStock(
            StockMovement movement,
            UUID warehouseId,
            UUID storageLocationId,
            UUID storageBinId,
            BigDecimal quantity
    ) {
        StockBalance balance = findOrCreateBalanceForUpdate(
            movement,
            movementCompanyId(movement),
            warehouseId,
            storageLocationId,
            storageBinId,
            null
        );

        BigDecimal fallbackCost = nvl(movement.getUnitCost()).multiply(quantity).setScale(4, RoundingMode.HALF_UP);
        return subtractFromBalance(
            balance, movement, warehouseId, quantity, fallbackCost, List.of());
    }

    private StockMovementAllocationDraft subtractFromBalance(
        StockBalance balance,
        StockMovement movement,
        UUID warehouseId,
        BigDecimal quantity,
        BigDecimal consumedCost,
        List<InventoryCostingService.LayerConsumption> consumptions
    ) {
        validateOutboundBalanceScope(balance, movement, warehouseId);
        StockBalanceMutationService.BalanceMutation balanceMutation =
            stockBalanceMutationService.decreaseOnHand(
                balance, quantity, consumedCost, !"TRANSFER_DISPATCH".equals(movement.getReferenceType()) && negativeStockAllowed());
        StockBalance savedBalance = stockBalanceRepository.save(balance);
        if (savedBalance == null || savedBalance.getId() == null) {
            throw new BusinessException("Outbound stock balance id was not assigned");
        }
        BatchMutation batchMutation = reduceBatchQuantity(
            movement, warehouseId, savedBalance.getStockBatchId(), quantity);
        validateOutboundEvidence(balanceMutation, quantity, consumedCost);

        UomSnapshot uom = resolveUomSnapshot(movement);
        movement.setUomIdSnapshot(uom.id());
        movement.setUomCodeSnapshot(uom.code());
        movement.setSourceDispositionSnapshot("AVAILABLE");
        movement.setTargetDispositionSnapshot(null);

        List<StockMovementCostLineageDraft> lineages = consumptions.stream()
            .map(this::consumedLineage)
            .toList();
        BigDecimal unitCost = quantity.signum() == 0
            ? BigDecimal.ZERO
            : nvl(consumedCost).divide(quantity, 6, RoundingMode.HALF_UP);
        String currency = commonCurrency(consumptions);
        BigDecimal exchangeRate = commonExchangeRate(consumptions);
        StockMovementStockKeySnapshot sourceKey = new StockMovementStockKeySnapshot(
            savedBalance.getWarehouseId(),
            savedBalance.getStorageLocationId(),
            savedBalance.getStorageBinId(),
            savedBalance.getStockBatchId(),
            batchMutation.batchNumber(),
            "AVAILABLE"
        );
        return new StockMovementAllocationDraft(
            "SOURCE",
            savedBalance.getId(),
            sourceKey,
            StockMovementStockKeySnapshot.empty(),
            uom.id(),
            uom.code(),
            balanceMutation.onHandDelta(),
            balanceMutation.availableDelta(),
            balanceMutation.reservedDelta(),
            balanceMutation.quarantineDelta(),
            balanceMutation.totalValueDelta(),
            batchMutation.delta(),
            unitCost,
            currency,
            exchangeRate,
            lineages
        );
    }

    private BatchMutation reduceBatchQuantity(
        StockMovement movement,
        UUID warehouseId,
        UUID stockBatchId,
        BigDecimal quantity
    ) {
        if (stockBatchId == null) {
            return new BatchMutation(null, null, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        var lockedBatch = ("TRANSFER_DISPATCH".equals(movement.getReferenceType()) || "PRODUCTION_OPERATION".equals(movement.getReferenceType()))
            ? stockBatchRepository.findForTransferUpdate(stockBatchId)
            : stockBatchRepository.findByIdAndIsDeletedFalseForUpdate(stockBatchId);
        StockBatch batch = lockedBatch.orElseThrow(() -> new BusinessException("Outbound stock batch not found: " + stockBatchId));
        if ("TRANSFER_DISPATCH".equals(movement.getReferenceType()) || "PRODUCTION_OPERATION".equals(movement.getReferenceType())) {
            if (entityManager != null) entityManager.refresh(batch);
            var expiry = batch.getExpiresOn() == null ? batch.getExpirationDate() : batch.getExpiresOn();
            if (List.of("INACTIVE", "CANCELLED", "BLOCKED").contains(String.valueOf(batch.getStatus()))
                || (expiry != null && expiry.isBefore(LocalDate.now())))
                throw new BusinessException("Source batch became unavailable during dispatch", "stockPicking.unavailable");
        }
        validateOutboundBatchScope(batch, movement, warehouseId);
        BigDecimal before = nvl(batch.getQuantity());
        if (before.compareTo(quantity) < 0) {
            throw new BusinessException("Outbound stock batch quantity is lower than allocation quantity");
        }
        batch.setQuantity(before.subtract(quantity));
        StockBatch saved = stockBatchRepository.save(batch);
        if (saved == null || saved.getId() == null) {
            throw new BusinessException("Outbound stock batch id was not assigned");
        }
        return new BatchMutation(
            saved.getId(),
            firstNonBlank(saved.getBatchNumber(), saved.getCode()),
            before,
            nvl(saved.getQuantity())
        );
    }

    private void validateOutboundBalanceScope(
        StockBalance balance,
        StockMovement movement,
        UUID warehouseId
    ) {
        if (balance == null
            || !TenantContext.currentTenant().equals(balance.getTenantId())
            || !movementCompanyId(movement).equals(balance.getCompanyId())
            || !movement.getMaterialId().equals(balance.getMaterialId())
            || !warehouseId.equals(balance.getWarehouseId())) {
            throw new BusinessException("Outbound stock balance is outside movement scope");
        }
    }

    private void validateOutboundBatchScope(
        StockBatch batch,
        StockMovement movement,
        UUID warehouseId
    ) {
        if (!TenantContext.currentTenant().equals(batch.getTenantId())
            || !movementCompanyId(movement).equals(batch.getCompanyId())
            || !movement.getMaterialId().equals(batch.getMaterialId())
            || !warehouseId.equals(batch.getWarehouseId())) {
            throw new BusinessException("Outbound stock batch is outside movement scope");
        }
    }

    private void validateOutboundEvidence(
        StockBalanceMutationService.BalanceMutation mutation,
        BigDecimal quantity,
        BigDecimal consumedCost
    ) {
        if (mutation.onHandDelta().compareTo(nvl(quantity).negate()) != 0
            || mutation.availableDelta().compareTo(nvl(quantity).negate()) != 0) {
            throw new BusinessException("Outbound balance quantity does not match allocation quantity");
        }
        if (mutation.totalValueDelta().compareTo(nvl(consumedCost).negate()) != 0) {
            throw new BusinessException("Outbound balance value does not match cost evidence value");
        }
    }

    private StockMovementCostLineageDraft consumedLineage(
        InventoryCostingService.LayerConsumption consumption
    ) {
        return new StockMovementCostLineageDraft(
            "CONSUMED",
            consumption.costLayerId(),
            consumption.parentCostLayerId(),
            consumption.costMovementId(),
            nvl(consumption.quantity()).negate(),
            nvl(consumption.totalCost()).negate(),
            consumption.unitCost(),
            consumption.currencyCode(),
            consumption.exchangeRate()
        );
    }

    private BigDecimal totalConsumptionQuantity(
        List<InventoryCostingService.LayerConsumption> consumptions
    ) {
        return consumptions.stream()
            .map(InventoryCostingService.LayerConsumption::quantity)
            .map(this::nvl)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal totalConsumptionCost(
        List<InventoryCostingService.LayerConsumption> consumptions
    ) {
        return consumptions.stream()
            .map(InventoryCostingService.LayerConsumption::totalCost)
            .map(this::nvl)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String commonCurrency(List<InventoryCostingService.LayerConsumption> consumptions) {
        List<String> currencies = consumptions.stream()
            .map(InventoryCostingService.LayerConsumption::currencyCode)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (currencies.isEmpty()) {
            return "UZS";
        }
        return currencies.size() == 1 ? currencies.get(0) : null;
    }

    private BigDecimal commonExchangeRate(List<InventoryCostingService.LayerConsumption> consumptions) {
        List<BigDecimal> rates = consumptions.stream()
            .map(InventoryCostingService.LayerConsumption::exchangeRate)
            .filter(Objects::nonNull)
            .map(BigDecimal::stripTrailingZeros)
            .distinct()
            .toList();
        if (rates.isEmpty()) {
            return BigDecimal.ONE;
        }
        return rates.size() == 1 ? rates.get(0) : null;
    }

    private void refreshExistingBatchCosts(StockBatch batch, BigDecimal receiptUnitCost) {
        if (nvl(batch.getPurchaseUnitCost()).signum() == 0) {
            batch.setPurchaseUnitCost(nvl(receiptUnitCost));
        }
        if (nvl(batch.getFinalUnitCost()).signum() == 0) {
            batch.setFinalUnitCost(nvl(receiptUnitCost).add(nvl(batch.getAdditionalCostPerUnit())));
        }
    }

    private Comparator<StockBalance> balanceComparator(ValuationMethod method) {
        return StockPickingOrder.balances(method == ValuationMethod.LIFO
            ? com.enterprise.mm.enums.PickingMethod.LIFO : com.enterprise.mm.enums.PickingMethod.FIFO);
    }

    private boolean negativeStockAllowed() {
        return allowNegativeStock || valuationPolicyService.allowNegativeStock();
    }

    private UUID movementCompanyId(StockMovement movement) {
        return movement.getCompanyId() == null ? CompanyContext.currentCompany() : movement.getCompanyId();
    }

    private StockBalance findOrCreateBalanceForUpdate(
        StockMovement movement,
        UUID companyId,
        UUID warehouseId,
        UUID storageLocationId,
        UUID storageBinId,
        UUID stockBatchId
    ) {
        String tenantId = TenantContext.currentTenant();
        var existing = findBalanceForUpdate(
            tenantId,
            companyId,
            movement.getMaterialId(),
            warehouseId,
            storageLocationId,
            storageBinId,
            stockBatchId
        );
        if (existing.isPresent()) {
            return existing.get();
        }

        // A SELECT ... FOR UPDATE cannot lock a balance row that does not exist.
        // Lock the canonical material row, then repeat the exact-key lookup. This
        // serializes first creation across application instances while keeping the
        // whole posting, valuation and ledger mutation in the caller transaction.
        materialRepository.lockForStockBalanceCreation(tenantId, companyId, movement.getMaterialId())
            .orElseThrow(() -> new NotFoundException("Material not found: " + movement.getMaterialId()));

        return findBalanceForUpdate(
            tenantId,
            companyId,
            movement.getMaterialId(),
            warehouseId,
            storageLocationId,
            storageBinId,
            stockBatchId
        ).orElseGet(() -> newBalance(movement, warehouseId, storageLocationId, storageBinId, stockBatchId));
    }

    private java.util.Optional<StockBalance> findBalanceForUpdate(
        String tenantId,
        UUID companyId,
        UUID materialId,
        UUID warehouseId,
        UUID storageLocationId,
        UUID storageBinId,
        UUID stockBatchId
    ) {
        return stockBalanceRepository.findCompanyBalanceForUpdate(
            tenantId,
            companyId,
            materialId,
            warehouseId,
            storageLocationId == null,
            storageLocationId,
            storageBinId == null,
            storageBinId,
            stockBatchId == null,
            stockBatchId
        );
    }

    private StockBalance newBalance(StockMovement movement, UUID warehouseId, UUID storageLocationId, UUID storageBinId, UUID batchId) {
        StockBalance balance = new StockBalance();
        balance.setCode("BAL-" + UUID.randomUUID().toString().substring(0, 8));
        balance.setName(balanceName(movement, warehouseId));
        balance.setStatus("ACTIVE");
        balance.setCompanyId(movement.getCompanyId() != null ? movement.getCompanyId() : CompanyContext.currentCompany());
        balance.setMaterialId(movement.getMaterialId());
        balance.setWarehouseId(warehouseId);
        balance.setStorageLocationId(storageLocationId);
        balance.setStorageBinId(storageBinId);
        balance.setStockBatchId(batchId);
        return balance;
    }

    private String balanceName(StockMovement movement, UUID warehouseId) {
        String materialName = materialRepository.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), movement.getMaterialId())
            .map(material -> material.getName() == null || material.getName().isBlank() ? material.getCode() : material.getName())
            .orElse(String.valueOf(movement.getMaterialId()));
        String warehouseName = warehouseRepository.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), warehouseId)
            .map(warehouse -> warehouse.getName() == null || warehouse.getName().isBlank() ? warehouse.getCode() : warehouse.getName())
            .orElse(String.valueOf(warehouseId));
        return materialName + " В· " + warehouseName;
    }

    String generateMovementName(StockMovement movement) {
        String documentType = movementTypeTitle(movement.getMovementType());
        String materialName = movement.getMaterialId() == null
            ? null
            : materialRepository.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), movement.getMaterialId())
                .map(Material::getName)
                .filter(name -> name != null && !name.isBlank())
                .orElse(null);
        return materialName == null ? documentType : documentType + " - " + materialName;
    }

    private String movementTypeTitle(MovementType movementType) {
        if (movementType == null) {
            return "РЎРєР»Р°РґСЃРєРѕРµ РґРІРёР¶РµРЅРёРµ";
        }
        return switch (movementType) {
            case RECEIPT -> "РџСЂРёС…РѕРґ С‚РѕРІР°СЂР°";
            case ISSUE -> "Р Р°СЃС…РѕРґ С‚РѕРІР°СЂР°";
            case TRANSFER -> "Р—Р°РєР°Р· РЅР° РїРµСЂРµРјРµС‰РµРЅРёРµ";
            case RETURN -> "Р’РѕР·РІСЂР°С‚ С‚РѕРІР°СЂР°";
            case WRITE_OFF -> "РЎРїРёСЃР°РЅРёРµ С‚РѕРІР°СЂР°";
            case ADJUSTMENT -> "РљРѕСЂСЂРµРєС‚РёСЂРѕРІРєР° РѕСЃС‚Р°С‚РєРѕРІ";
            case REVERSAL -> "РЎС‚РѕСЂРЅРѕ РґРІРёР¶РµРЅРёСЏ";
        };
    }

    private MovementType reverseType(MovementType movementType) {
        MovementType type = movementType;
        return switch (type) {
            case RECEIPT, RETURN, ADJUSTMENT, REVERSAL -> MovementType.WRITE_OFF;
            case ISSUE, WRITE_OFF -> MovementType.REVERSAL;
            case TRANSFER -> MovementType.TRANSFER;
        };
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private void recordReceiptCostObservation(StockMovement movement) {
        if (materialCostObservationService == null || movement.getMovementType() != MovementType.RECEIPT) {
            return;
        }
        materialCostObservationService.recordReceipt(
            movement.getMaterialId(),
            movement.getQuantity(),
            movement.getUnitCost(),
            movement.getSourceCurrencyCodeSnapshot(),
            movement.getId(),
            movement.getCode(),
            movement.getPostedAt()
        );
    }

    private boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private InsufficientStockException insufficientStock(
        StockMovement movement,
        UUID warehouseId,
        BigDecimal requested,
        BigDecimal available
    ) {
        var material = materialRepository.findByIdAndIsDeletedFalse(movement.getMaterialId()).orElse(null);
        var warehouse = warehouseRepository.findByIdAndIsDeletedFalse(warehouseId).orElse(null);
        String unit = material == null || material.getUnitOfMeasure() == null
            ? null
            : firstNonBlank(
                material.getUnitOfMeasure().getMeasurementNameShort(),
                material.getUnitOfMeasure().getMeasurementNameShortRu(),
                material.getUnitOfMeasure().getName()
            );
        return new InsufficientStockException(
            movement.getMaterialId(),
            material == null ? null : material.getName(),
            warehouseId,
            warehouse == null ? null : warehouse.getName(),
            requested,
            available,
            unit
        );
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    @Transactional(readOnly = true)
    public Page<StockMovementResponse> search(StockMovementSearchRequest request, Pageable pageable, String language) {
        var previous = org.springframework.context.i18n.LocaleContextHolder.getLocaleContext();
        String resolved = language(language);
        try {
            org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.forLanguageTag(resolved));
            MovementQuerySupport.validate(request);
            return importedMovements.available() ? importedMovements.searchBatch(request, pageable, this::getQueryRows)
                : search(request, pageable);
        } finally {
            org.springframework.context.i18n.LocaleContextHolder.setLocaleContext(previous);
        }
    }

    /** One database snapshot prevents page shifts while users post new movements. */
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public byte[] exportMovements(StockMovementSearchRequest filter, Sort sort, String language) {
        MovementQuerySupport.validate(filter);
        Page<StockMovementResponse> page = search(filter, PageRequest.of(0, BATCH, sort),language);
        int limit = Math.max(1, Math.min(maxRows, 1_048_000));
        if (page.getTotalElements() > limit)
            throw new BadRequestException(labelLimit(language, limit));
        SXSSFWorkbook book = new SXSSFWorkbook(100);
        book.setCompressTempFiles(true);
        try (book; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = book.createSheet(word("title", language));
            CellStyle heading = book.createCellStyle();
            Font bold = book.createFont(); bold.setBold(true); heading.setFont(bold);
            heading.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
            heading.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            heading.setWrapText(true);
            heading.setVerticalAlignment(VerticalAlignment.CENTER);
            CellStyle date = book.createCellStyle(); date.setDataFormat(book.createDataFormat().getFormat("dd.mm.yyyy"));
            CellStyle number = book.createCellStyle(); number.setDataFormat(book.createDataFormat().getFormat("#,##0.############"));
            int headerRow = 0;
            Row header = sheet.createRow(headerRow);
            header.setHeightInPoints(30);
            var headers = headers(language);
            for(int c=0;c<headers.size();c++) { text(header,c,headers.get(c)); header.getCell(c).setCellStyle(heading); }
            int rowIndex = headerRow+1;
            Map<String,String> units = new HashMap<>();
            while(true) {
                loadUnits(page.getContent(), language, units);
                for(StockMovementResponse movement : page.getContent()) {
                    Row row = sheet.createRow(rowIndex++);
                    LocalDate movementDate = movement.getMovementDate() != null ? movement.getMovementDate()
                        : movement.getCreatedAt() == null ? null : movement.getCreatedAt().toLocalDate();
                    if(movementDate != null) { row.createCell(0).setCellValue(movementDate); row.getCell(0).setCellStyle(date); }
                    text(row,1,movement.getCode());
                    text(row,2,movement.getMovementType() == null ? "" : word(movement.getMovementType().name(),language));
                    text(row,3,label(movement.getCounterparty(),language));
                    text(row,4,label(first(movement.getSourceLocation(),movement.getSourceWarehouseName(),movement.getSupplierName()),language));
                    text(row,5,label(first(movement.getDestinationLocation(),movement.getDestinationWarehouseName(),movement.getDepartmentName()),language));
                    text(row,6,movement.getMaterialCode());
                    text(row,7,label(movement.getMaterialName(),language));
                    if(movement.getQuantity()!=null) { row.createCell(8).setCellValue(movement.getQuantity().doubleValue()); row.getCell(8).setCellStyle(number); }
                    text(row,9,units.getOrDefault(movement.getUnitOfMeasureCode(),label(movement.getUnitOfMeasureCode(),language)));
                    text(row,10,word(first(movement.getStatus(), movement.getStockMovementStatus() == null ? null : movement.getStockMovementStatus().name()),language));
                    text(row,11,movement.getBatchNumber());
                    text(row,12,movement.getActorName());
                }
                // Streaming XLSX alone does not bound Hibernate's first-level cache.
                if(entityManager != null) entityManager.clear();
                if(!page.hasNext()) break;
                page = search(filter,page.nextPageable(),language);
            }
            if(rowIndex == headerRow+1) text(sheet.createRow(rowIndex),0,word("none",language));
            sheet.createFreezePane(0,headerRow+1);
            sheet.setAutoFilter(new CellRangeAddress(headerRow,Math.max(headerRow,rowIndex-1),0,headers.size()-1));
            int[] widths = {14,25,20,30,30,30,20,48,18,20,18,22,28};
            for(int c=0;c<widths.length;c++) sheet.setColumnWidth(c,widths[c]*256);
            book.write(out);
            return out.toByteArray();
        } catch(IOException ex) {
            throw new IllegalStateException("Movement workbook could not be written",ex);
        } finally { book.dispose(); }
    }

    private String labelLimit(String language, int maxRows) {
        return switch(language) {
            case "uz" -> "Eksport chegarasi " + maxRows + " ta yozuv. Filtr yoki sana oralig‘ini toraytiring.";
            case "ru" -> "Лимит экспорта: " + maxRows + " записей. Уточните фильтры или период.";
            default -> "Export limit is " + maxRows + " rows. Narrow the filters or date range.";
        };
    }
    private void loadUnits(List<StockMovementResponse> rows, String language, Map<String,String> cache) {
        var codes = rows.stream().map(StockMovementResponse::getUnitOfMeasureCode).filter(Objects::nonNull)
            .filter(c -> !cache.containsKey(c)).distinct().toList();
        if(codes.isEmpty()) return;
        for(String code : codes) cache.put(code,label(code,language));
        var params = new MapSqlParameterSource("tenant",TenantContext.currentTenant())
            .addValue("company",CompanyContext.currentCompany()).addValue("codes",codes);
        jdbcTemplate.query("""
            select code,measurement_name_short,measurement_name_short_ru,measurement_name_short_uz
            from unit_of_measures where tenant_id=:tenant and company_id=:company and is_delete=false and code in (:codes)
            order by id
            """, params, rs -> {
                String code = rs.getString("code");
                String localized = switch(language) {
                    case "uz" -> rs.getString("measurement_name_short_uz");
                    case "ru" -> rs.getString("measurement_name_short_ru");
                    default -> rs.getString("measurement_name_short");
                };
                cache.put(code,first(localized,rs.getString("measurement_name_short"),rs.getString("measurement_name_short_ru"),rs.getString("measurement_name_short_uz"),code));
            });
    }
    private static void text(Row row,int column,String value) {
        // Always STRING: user-controlled names beginning with '=' cannot become formulas.
        row.createCell(column,CellType.STRING).setCellValue(value == null ? "" : value.length()>32767 ? value.substring(0,32767) : value);
    }
    private static String first(String... values) {
        for(String value:values) if(value!=null && !value.isBlank()) return value;
        return "";
    }

    @Transactional(readOnly = true)
    public Page<StockMovementResponse> search(StockMovementSearchRequest request, Pageable pageable) {
        MovementQuerySupport.validate(request);
        PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                MovementQuerySupport.jpaSort(pageable.getSort()));
        SearchCriteria filter = roleDataScopeService.applyWarehouseScope(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        boolean hasExtendedFilters = filter.movementDate() != null
            || filter.hasWarehouseIds()
            || filter.materialId() != null
            || (filter.materialCode() != null && !filter.materialCode().isBlank())
            || (filter.materialName() != null && !filter.materialName().isBlank())
            || filter.hasMovementTypes();

        Page<StockMovementResponse> result;
        if (hasExtendedFilters) {
            result = repository.search(
                TenantContext.currentTenant(),
                scoped ? CompanyContext.currentCompany() : null,
                filter.includeDeleted(),
                filter.normalizedSearch(),
                filter.fromDateTime(),
                filter.toDateTime(),
                filter.status(),
                !filter.hasStatus(),
                filter.hasWarehouseIds() ? null : filter.warehouseId(),
                filter.materialIds(),
                !filter.hasMaterialIds(),
                filter.inventoryId(),
                filter.referenceId(),
                filter.normalizedReferenceType(),
                filter.movementType(),
                filter.movementDate(),
                filter.warehouseIds(),
                !filter.hasWarehouseIds(),
                filter.materialId(),
                filter.materialCode(),
                filter.materialName(),
                filter.movementTypes(),
                !filter.hasMovementTypes(),
                pageable
            ).map(mapper::toResponse);
        } else {
            result = repository.search(
                TenantContext.currentTenant(),
                scoped ? CompanyContext.currentCompany() : null,
                filter.includeDeleted(),
                filter.normalizedSearch(),
                filter.fromDateTime(),
                filter.toDateTime(),
                filter.status(),
                !filter.hasStatus(),
                filter.hasWarehouseIds() ? null : filter.warehouseId(),
                filter.materialIds(),
                !filter.hasMaterialIds(),
                filter.inventoryId(),
                filter.referenceId(),
                filter.normalizedReferenceType(),
                filter.movementType(),
                pageable
            ).map(mapper::toResponse);
        }

        enrichMovements(result.getContent());
        return result;
    }

    /** IDs have already been authorized by the scoped movement query. */
    @Transactional(readOnly = true)
    public List<StockMovementResponse> getQueryRows(List<UUID> ids) {
        var rows = repository.findQueryRows(TenantContext.currentTenant(), CompanyContext.currentCompany(), ids).stream()
            .map(mapper::toResponse).toList();
        enrichMovements(rows);
        return rows;
    }

    @Transactional(readOnly = true)
    public StockMovementResponse get(UUID id) {
        StockMovementResponse response = mapper.toResponse(findEntity(id));
        if (response != null) {
            enrichMovements(java.util.Collections.singletonList(response));
        }
        return response;
    }

    public void enrichMovements(List<StockMovementResponse> movements) {
        if (movements == null || movements.isEmpty()) {
            return;
        }

        Set<UUID> poIds = new HashSet<>();
        Set<UUID> grIds = new HashSet<>();
        Set<UUID> contractIds = new HashSet<>();
        Set<UUID> irIds = new HashSet<>();
        Set<UUID> transferIds = new HashSet<>();
        Set<UUID> manualFactIds = new HashSet<>();

        for (StockMovementResponse m : movements) {
            if (m == null) continue;

            if (m.getMovementDate() == null && m.getCreatedAt() != null) {
                m.setMovementDate(m.getCreatedAt().toLocalDate());
            }

            MovementType type = m.getMovementType();
            String refType = m.getReferenceType();
            UUID refId = m.getReferenceId();

            if (type == MovementType.TRANSFER || m.getTargetWarehouseId() != null) {
                if ("TRANSFER_RECEIVE".equalsIgnoreCase(refType)
                    || (m.getQuantity() != null && m.getQuantity().signum() >= 0 && type == MovementType.TRANSFER)) {
                    if (m.getSourceWarehouseId() == null) {
                        m.setSourceWarehouseId(m.getTargetWarehouseId());
                        m.setSourceWarehouseCode(m.getTargetWarehouseCode());
                        m.setSourceWarehouseName(m.getTargetWarehouseName());
                        m.setSourceLocation(m.getTargetWarehouseName());
                        m.setSourceType("WAREHOUSE");
                    }
                    if (m.getDestinationWarehouseId() == null) {
                        m.setDestinationWarehouseId(m.getWarehouseId());
                        m.setDestinationWarehouseCode(m.getWarehouseCode());
                        m.setDestinationWarehouseName(m.getWarehouseName());
                        m.setDestinationLocation(m.getWarehouseName());
                        m.setDestinationType("WAREHOUSE");
                    }
                    if (m.getCounterparty() == null) {
                        m.setCounterparty(m.getTargetWarehouseName() != null ? m.getTargetWarehouseName() : m.getSourceWarehouseName());
                    }
                } else {
                    if (m.getSourceWarehouseId() == null) {
                        m.setSourceWarehouseId(m.getWarehouseId());
                        m.setSourceWarehouseCode(m.getWarehouseCode());
                        m.setSourceWarehouseName(m.getWarehouseName());
                        m.setSourceLocation(m.getWarehouseName());
                        m.setSourceType("WAREHOUSE");
                    }
                    if (m.getDestinationWarehouseId() == null) {
                        m.setDestinationWarehouseId(m.getTargetWarehouseId());
                        m.setDestinationWarehouseCode(m.getTargetWarehouseCode());
                        m.setDestinationWarehouseName(m.getTargetWarehouseName());
                        m.setDestinationLocation(m.getTargetWarehouseName());
                        m.setDestinationType("WAREHOUSE");
                    }
                    if (m.getCounterparty() == null) {
                        m.setCounterparty(m.getTargetWarehouseName() != null ? m.getTargetWarehouseName() : m.getDestinationWarehouseName());
                    }
                }
            } else if (type == MovementType.RECEIPT) {
                if (m.getDestinationWarehouseId() == null) {
                    m.setDestinationWarehouseId(m.getWarehouseId());
                    m.setDestinationWarehouseCode(m.getWarehouseCode());
                    m.setDestinationWarehouseName(m.getWarehouseName());
                    m.setDestinationLocation(m.getWarehouseName());
                    m.setDestinationType("WAREHOUSE");
                }
            } else if (type == MovementType.ISSUE || type == MovementType.WRITE_OFF) {
                if (m.getSourceWarehouseId() == null) {
                    m.setSourceWarehouseId(m.getWarehouseId());
                    m.setSourceWarehouseCode(m.getWarehouseCode());
                    m.setSourceWarehouseName(m.getWarehouseName());
                    m.setSourceLocation(m.getWarehouseName());
                    m.setSourceType("WAREHOUSE");
                }
            }

            if ("OPENING_STOCK_EXACT".equalsIgnoreCase(refType) || "OPENING_BALANCE".equalsIgnoreCase(refType)) {
                m.setCounterparty("Opening Stock Balance");
                m.setSourceLocation("Opening Stock");
                m.setSourceType("SYSTEM");
                m.setDestinationLocation(m.getWarehouseName());
                m.setDestinationType("WAREHOUSE");
            }

            if (refId != null && refType != null) {
                if ("PurchaseOrder".equalsIgnoreCase(refType) || "PURCHASE_ORDER".equalsIgnoreCase(refType)) {
                    poIds.add(refId);
                } else if ("GOODS_RECEIPT".equalsIgnoreCase(refType)) {
                    grIds.add(refId);
                } else if ("CONTRACT".equalsIgnoreCase(refType)) {
                    contractIds.add(refId);
                } else if ("ISSUE_REQUEST".equalsIgnoreCase(refType)) {
                    irIds.add(refId);
                } else if (WarehouseStockFactService.REFERENCE_TYPE.equalsIgnoreCase(refType)) {
                    manualFactIds.add(refId);
                } else if (Set.of("TRANSFER_DISPATCH", "TRANSFER_GOOD_RECEIPT", "TRANSFER_QUARANTINE_RECEIPT")
                    .contains(refType.toUpperCase(java.util.Locale.ROOT))) {
                    transferIds.add(refId);
                }
            }
        }

        if (jdbcTemplate != null) {
            record SupplierRef(UUID id, String code, String name) {}
            record DepartmentRef(UUID id, String code, String name, String receiverName) {}
            record TransferRef(UUID sourceId, String sourceCode, String sourceName,
                UUID destinationId, String destinationCode, String destinationName) {}

            Map<UUID, SupplierRef> suppliersByDocId = new HashMap<>();
            Map<UUID, String> manualSources = new HashMap<>();
            Map<UUID, DepartmentRef> deptByIrId = new HashMap<>();
            Map<UUID, TransferRef> transfersById = new HashMap<>();

            try {
                if (!manualFactIds.isEmpty()) {
                    jdbcTemplate.query("""
                        select id doc_id, manual_operation_payload, description
                        from stock_movement_documents
                        where id in (:ids) and tenant_id=:tenant and company_id=:company
                          and reference_type='MANUAL_WAREHOUSE_FACT' and is_delete=false
                        """, new MapSqlParameterSource("ids", manualFactIds)
                            .addValue("tenant", TenantContext.currentTenant()).addValue("company", CompanyContext.currentCompany()),
                        rs -> {
                            String source = WarehouseStockFactMetadata.source(rs.getString("manual_operation_payload"), rs.getString("description"));
                            if (source != null) manualSources.put(rs.getObject("doc_id", UUID.class), source);
                        });
                }
                if (!transferIds.isEmpty()) {
                    jdbcTemplate.query("""
                        select t.id doc_id, s.id source_id, s.code source_code, s.name source_name,
                               d.id destination_id, d.code destination_code, d.name destination_name
                        from transfer_documents t
                        join warehouses s on s.id = t.source_warehouse_id and s.tenant_id = t.tenant_id and s.company_id = t.company_id
                        join warehouses d on d.id = t.destination_warehouse_id and d.tenant_id = t.tenant_id and d.company_id = t.company_id
                        where t.id in (:ids) and t.tenant_id = :tenant and t.company_id = :company
                        """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("ids", transferIds)
                            .addValue("tenant", TenantContext.currentTenant()).addValue("company", CompanyContext.currentCompany()),
                        rs -> { transfersById.put(rs.getObject("doc_id", UUID.class), new TransferRef(
                            rs.getObject("source_id", UUID.class), rs.getString("source_code"), rs.getString("source_name"),
                            rs.getObject("destination_id", UUID.class), rs.getString("destination_code"), rs.getString("destination_name"))); });
                }
                if (!poIds.isEmpty()) {
                    jdbcTemplate.query(
                        """
                        select po.id doc_id, s.id supplier_id, s.code supplier_code, s.name supplier_name
                        from purchase_orders po
                        join suppliers s on s.id = po.supplier_id
                        where po.id in (:ids)
                        """,
                        new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("ids", poIds),
                        rs -> {
                            suppliersByDocId.put(
                                rs.getObject("doc_id", UUID.class),
                                new SupplierRef(rs.getObject("supplier_id", UUID.class), rs.getString("supplier_code"), rs.getString("supplier_name"))
                            );
                        }
                    );
                }
                if (!grIds.isEmpty()) {
                    jdbcTemplate.query(
                        """
                        select gr.id doc_id, s.id supplier_id, s.code supplier_code, s.name supplier_name
                        from goods_receipts gr
                        join suppliers s on s.id = gr.supplier_id
                        where gr.id in (:ids)
                        """,
                        new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("ids", grIds),
                        rs -> {
                            suppliersByDocId.put(
                                rs.getObject("doc_id", UUID.class),
                                new SupplierRef(rs.getObject("supplier_id", UUID.class), rs.getString("supplier_code"), rs.getString("supplier_name"))
                            );
                        }
                    );
                }
                if (!contractIds.isEmpty()) {
                    jdbcTemplate.query(
                        """
                        select c.id doc_id, s.id supplier_id, s.code supplier_code, s.name supplier_name
                        from contracts c
                        join suppliers s on s.id = c.supplier_id
                        where c.id in (:ids)
                        """,
                        new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("ids", contractIds),
                        rs -> {
                            suppliersByDocId.put(
                                rs.getObject("doc_id", UUID.class),
                                new SupplierRef(rs.getObject("supplier_id", UUID.class), rs.getString("supplier_code"), rs.getString("supplier_name"))
                            );
                        }
                    );
                }
                if (!irIds.isEmpty()) {
                    jdbcTemplate.query(
                        """
                        select ir.id doc_id, d.id dept_id, d.code dept_code, d.name dept_name, u.full_name receiver_name
                        from issue_requests ir
                        left join departments d on d.id = ir.cost_object_id and ir.cost_object_type = 'DEPARTMENT'
                        left join users u on u.id = ir.receiver_user_id
                        where ir.id in (:ids)
                        """,
                        new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("ids", irIds),
                        rs -> {
                            deptByIrId.put(
                                rs.getObject("doc_id", UUID.class),
                                new DepartmentRef(
                                    rs.getObject("dept_id", UUID.class),
                                    rs.getString("dept_code"),
                                    rs.getString("dept_name"),
                                    rs.getString("receiver_name")
                                )
                            );
                        }
                    );
                }
            } catch (Exception ignored) {
                // Ignore auxiliary lookup failures (e.g., partial schemas in some unit tests)
            }

            for (StockMovementResponse m : movements) {
                if (m == null || m.getReferenceId() == null) continue;

                String manualSource = WarehouseStockFactService.REFERENCE_TYPE.equalsIgnoreCase(m.getReferenceType())
                    ? manualSources.get(m.getReferenceId()) : null;
                if (manualSource != null) {
                    m.setCounterparty(manualSource);
                    if (m.getMovementType() == MovementType.RECEIPT) {
                        m.setSourceLocation(manualSource);
                        m.setSourceType("MANUAL");
                    } else if (m.getMovementType() == MovementType.ISSUE) {
                        m.setDestinationLocation(manualSource);
                        m.setDestinationType("MANUAL");
                    }
                }

                TransferRef transfer = transfersById.get(m.getReferenceId());
                if (transfer != null) {
                    m.setSourceWarehouseId(transfer.sourceId());
                    m.setSourceWarehouseCode(transfer.sourceCode());
                    m.setSourceWarehouseName(transfer.sourceName());
                    m.setSourceLocation(transfer.sourceName());
                    m.setSourceType("WAREHOUSE");
                    m.setDestinationWarehouseId(transfer.destinationId());
                    m.setDestinationWarehouseCode(transfer.destinationCode());
                    m.setDestinationWarehouseName(transfer.destinationName());
                    m.setDestinationLocation(transfer.destinationName());
                    m.setDestinationType("WAREHOUSE");
                    m.setCounterparty("TRANSFER_DISPATCH".equalsIgnoreCase(m.getReferenceType())
                        ? transfer.destinationName() : transfer.sourceName());
                }

                SupplierRef sup = suppliersByDocId.get(m.getReferenceId());
                if (sup != null) {
                    m.setSupplierId(sup.id());
                    m.setSupplierCode(sup.code());
                    m.setSupplierName(sup.name());
                    m.setSourceLocation(sup.name());
                    m.setSourceType("SUPPLIER");
                    m.setCounterparty(sup.name());
                }

                DepartmentRef dept = deptByIrId.get(m.getReferenceId());
                if (dept != null) {
                    if (dept.id() != null) {
                        m.setDepartmentId(dept.id());
                        m.setDepartmentCode(dept.code());
                        m.setDepartmentName(dept.name());
                        m.setDestinationLocation(dept.name());
                        m.setDestinationType("DEPARTMENT");
                        m.setCounterparty(dept.name());
                    } else if (dept.receiverName() != null && !dept.receiverName().isBlank()) {
                        m.setDestinationLocation(dept.receiverName());
                        m.setDestinationType("USER");
                        m.setCounterparty(dept.receiverName());
                    }
                }
            }
        }

        for (StockMovementResponse m : movements) {
            if (m == null) continue;
            if (m.getCounterparty() == null || m.getCounterparty().isBlank()) {
                if (m.getTargetWarehouseName() != null && !m.getTargetWarehouseName().isBlank()) {
                    m.setCounterparty(m.getTargetWarehouseName());
                } else if (m.getSupplierName() != null && !m.getSupplierName().isBlank()) {
                    m.setCounterparty(m.getSupplierName());
                } else if (m.getDepartmentName() != null && !m.getDepartmentName().isBlank()) {
                    m.setCounterparty(m.getDepartmentName());
                } else if (m.getMovementType() == MovementType.RECEIPT) {
                    if (m.getSourceLocation() == null || m.getSourceLocation().isBlank()) {
                        m.setCounterparty(word("Inbound Receipt", org.springframework.context.i18n.LocaleContextHolder.getLocale().getLanguage()));
                        m.setSourceType("SYSTEM");
                    } else m.setCounterparty(m.getSourceLocation());
                } else if (m.getMovementType() == MovementType.ISSUE) {
                    if (m.getDestinationLocation() == null || m.getDestinationLocation().isBlank()) {
                        m.setCounterparty(word("Outbound Issue", org.springframework.context.i18n.LocaleContextHolder.getLocale().getLanguage()));
                        m.setDestinationType("SYSTEM");
                    } else m.setCounterparty(m.getDestinationLocation());
                } else {
                    m.setCounterparty(m.getMovementType() != null ? m.getMovementType().name() : "Stock Movement");
                }
            }
            if (m.getMovementType() == MovementType.RECEIPT) {
                if (m.getSourceLocation() == null || m.getSourceLocation().isBlank()) {
                    m.setSourceLocation(m.getCounterparty());
                }
                if (m.getDestinationLocation() == null || m.getDestinationLocation().isBlank()) {
                    m.setDestinationLocation(m.getWarehouseName());
                }
            } else if (m.getMovementType() == MovementType.ISSUE || m.getMovementType() == MovementType.WRITE_OFF) {
                if (m.getSourceLocation() == null || m.getSourceLocation().isBlank()) {
                    m.setSourceLocation(m.getWarehouseName());
                }
                if (m.getDestinationLocation() == null || m.getDestinationLocation().isBlank()) {
                    m.setDestinationLocation(m.getCounterparty());
                }
            }
        }
    }

    @Transactional
    public StockMovementResponse create(StockMovementCreateRequest dto) {
        rejectManagedWorkflowReference(dto.getReferenceType());
        StockMovement entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        StockMovement saved = repository.save(entity);
        // A draft is independent of approval configuration. start() binds the current
        // active route when the user explicitly submits this movement for approval.
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }

    @Transactional
    public StockMovementResponse submitForApproval(UUID id) {
        StockMovement movement = findEntity(id);
        if (StockMovementStatus.RETURNED.name().equals(movement.getStatus())) {
            approvalEngineService.resetForResubmission(
                StockMovementApprovalAdapter.MODULE_KEY,
                id,
                "Stock movement resubmitted"
            );
        }
        approvalEngineService.start(StockMovementApprovalAdapter.MODULE_KEY, id);
        return mapper.toResponse(findEntity(id));
    }

    @Transactional
    public void softDelete(UUID id) {
        StockMovement entity = findEntity(id);
        if ("PRODUCTION_OPERATION".equals(entity.getReferenceType()))
            throw new BusinessException("Production movement history cannot be deleted independently of its document");
        String oldSnapshot = auditService.snapshot(entity);
        requireSoftDeletable(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }

    private void requireSoftDeletable(StockMovement entity) {
        if (StockMovementStatus.isEffective(entity.getStatus())) {
            throw new BusinessException("Approved or posted stock movement cannot be deleted. Cancel it through reversal flow instead.");
        }
    }
    @Transactional
    public StockMovementResponse restore(UUID id) {
        StockMovement entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (openingImportGuard != null) openingImportGuard.assertOperationsAllowed(entity.getCompanyId());
        rejectManagedWorkflowReference(entity.getReferenceType());
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        StockMovement saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }

    protected StockMovement findEntity(UUID id) {
        StockMovement entity = repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (entity.isDeleted()) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        return entity;
    }

    protected boolean companyScoped() {
        return true;
    }

    private record OutboundCostResult(ValuationMethod method, List<InventoryCostingService.LayerConsumption> consumptions) {
    }

    private record BatchMutation(
        UUID batchId,
        String batchNumber,
        BigDecimal beforeQuantity,
        BigDecimal afterQuantity
    ) {
        private BigDecimal delta() {
            return afterQuantity.subtract(beforeQuantity);
        }
    }

    private record UomSnapshot(UUID id, String code) {
    }

    private static final class ConsumptionTotal {
        private BigDecimal quantity = BigDecimal.ZERO;
        private BigDecimal cost = BigDecimal.ZERO;

        private void add(BigDecimal nextQuantity, BigDecimal nextCost) {
            quantity = quantity.add(nextQuantity == null ? BigDecimal.ZERO : nextQuantity);
            cost = cost.add(nextCost == null ? BigDecimal.ZERO : nextCost);
        }
    }
}
