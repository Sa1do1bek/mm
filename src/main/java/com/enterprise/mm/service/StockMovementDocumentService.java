package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.entity.StockMovementDocument;
import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.enums.NonMovingStockExecutionOutputStatus;
import com.enterprise.mm.enums.StockMovementStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.StockMovementMapper;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.payload.StockMovementDocumentCreateRequest;
import com.enterprise.mm.payload.StockMovementDocumentItemRequest;
import com.enterprise.mm.payload.StockMovementDocumentResponse;
import com.enterprise.mm.payload.StockMovementDocumentUpdateRequest;
import com.enterprise.mm.payload.StockMovementStatusChangeRequest;
import com.enterprise.mm.payload.StockMovementReversalPreviewResponse;
import com.enterprise.mm.payload.StockMovementPostRequest;
import com.enterprise.mm.payload.StockMovementCancelRequest;
import com.enterprise.mm.payload.StockMovementDocumentSearchRequest;
import com.enterprise.mm.repository.IssueRequestRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.StockMovementDocumentRepository;
import com.enterprise.mm.repository.StockMovementRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StockMovementDocumentService {
    private static final String ENTITY_NAME = "StockMovementDocument";

    private final StockMovementDocumentRepository documentRepository;
    private final StockMovementRepository movementRepository;
    private final StockMovementMapper movementMapper;
    private final MaterialCatalogGuard materialCatalogGuard;
    private final StockMovementService stockMovementService;
    private final AuditService auditService;
    private final AuditSnapshotSerializer snapshotSerializer;
    private final RoleDataScopeService roleDataScopeService;
    private final NonMovingStockExecutionService nonMovingStockExecutionService;
    private final IdempotencyService idempotencyService;
    private final StockBalanceRepository stockBalanceRepository;
    private final IssueRequestRepository issueRequestRepository;
    private final InventoryPeriodGuardService inventoryPeriodGuardService;
    private final WarehouseRepository warehouseRepository;
    private final ApprovalEngineService approvalEngineService;

    public StockMovementDocumentService(
        StockMovementDocumentRepository documentRepository,
        StockMovementRepository movementRepository,
        StockMovementMapper movementMapper,
        MaterialCatalogGuard materialCatalogGuard,
        StockMovementService stockMovementService,
        AuditService auditService,
        AuditSnapshotSerializer snapshotSerializer,
        RoleDataScopeService roleDataScopeService,
        NonMovingStockExecutionService nonMovingStockExecutionService,
        IdempotencyService idempotencyService,
        StockBalanceRepository stockBalanceRepository,
        IssueRequestRepository issueRequestRepository,
        InventoryPeriodGuardService inventoryPeriodGuardService,
        WarehouseRepository warehouseRepository,
        ApprovalEngineService approvalEngineService
    ) {
        this.documentRepository = documentRepository;
        this.movementRepository = movementRepository;
        this.movementMapper = movementMapper;
        this.materialCatalogGuard = materialCatalogGuard;
        this.stockMovementService = stockMovementService;
        this.auditService = auditService;
        this.snapshotSerializer = snapshotSerializer;
        this.roleDataScopeService = roleDataScopeService;
        this.nonMovingStockExecutionService = nonMovingStockExecutionService;
        this.idempotencyService = idempotencyService;
        this.stockBalanceRepository = stockBalanceRepository;
        this.issueRequestRepository = issueRequestRepository;
        this.inventoryPeriodGuardService = inventoryPeriodGuardService;
        this.warehouseRepository = warehouseRepository;
        this.approvalEngineService = approvalEngineService;
    }

    @Transactional(readOnly = true)
    public Page<StockMovementDocumentResponse> search(StockMovementDocumentSearchRequest request, Pageable pageable) {
        SearchCriteria filter = roleDataScopeService.applyWarehouseScope(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(true);
        return documentRepository.search(
            TenantContext.currentTenant(),
            scoped ? CompanyContext.currentCompany() : null,
            filter.includeDeleted(),
            filter.normalizedSearch(),
            filter.fromDateTime(),
            filter.toDateTime(),
            filter.status(),
            !filter.hasStatus(),
            filter.hasWarehouseIds() ? null : filter.warehouseId(),
            filter.warehouseIds(), !filter.hasWarehouseIds(),
            filter.referenceId(),
            filter.movementType(),
            pageable
        ).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public StockMovementDocumentResponse get(UUID id) {
        return toResponse(findDocument(id));
    }

    @Transactional(readOnly = true)
    public StockMovementReversalPreviewResponse reversalPreview(UUID id) {
        StockMovementDocument document = findDocument(id);
        List<StockMovement> lines = lines(document.getId());
        BigDecimal quantity = lines.stream().map(StockMovement::getQuantity).map(this::nvl).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCost = lines.stream().map(StockMovement::getTotalCost).map(this::nvl).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal unitCost = quantity.signum() == 0 ? BigDecimal.ZERO : totalCost.divide(quantity, 6, java.math.RoundingMode.HALF_UP);
        BigDecimal quantityDelta = reversalQuantityDelta(document.getMovementType(), quantity);
        BigDecimal valueDelta = reversalValueDelta(document.getMovementType(), totalCost);
        BigDecimal balanceAfter = BigDecimal.ZERO;
        if (!lines.isEmpty()) {
            StockMovement first = lines.get(0);
            BigDecimal firstLineDelta = reversalQuantityDelta(document.getMovementType(), nvl(first.getQuantity()));
            balanceAfter = stockBalanceRepository.findByTenantIdAndMaterial_IdAndWarehouse_IdAndIsDeletedFalse(
                TenantContext.currentTenant(), first.getMaterialId(), document.getWarehouseId()
            ).stream()
                .filter(balance -> CompanyContext.currentCompany().equals(balance.getCompanyId()))
                .map(com.enterprise.mm.entity.StockBalance::getQuantity)
                .map(this::nvl)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .add(firstLineDelta);
        }
        LocalDate movementDate = document.getMovementDate() == null ? LocalDate.now() : document.getMovementDate();
        boolean periodClosed = inventoryPeriodGuardService.isClosed(document.getWarehouseId(), movementDate);
        List<String> warnings = new java.util.ArrayList<>();
        if (!StockMovementStatus.isEffective(document.getStatus())) {
            warnings.add("Movement is not posted and cannot be reversed");
        }
        if (periodClosed) {
            warnings.add("Inventory period is closed");
        }
        if (documentRepository.existsByTenantIdAndCompanyIdAndReversalOfIdAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), document.getId()
        )) {
            warnings.add("A reversal document already exists");
        }
        List<StockMovementReversalPreviewResponse.LinkedDocument> linkedDocuments = linkedDocuments(document);
        return new StockMovementReversalPreviewResponse(
            document.getId(),
            document.getMovementType(),
            quantity,
            unitCost,
            totalCost,
            signed(quantityDelta),
            signed(valueDelta),
            balanceAfter,
            linkedDocuments,
            movementDate.toString().substring(0, 7),
            periodClosed,
            List.copyOf(warnings)
        );
    }

    private void rejectManagedTransferReference(String type) {
        if ("KIMYOSANOAT".equalsIgnoreCase(type))
            throw new BusinessException("Kimyosanoat stock must use its scoped approval workflow");
        if (type != null && type.toUpperCase(java.util.Locale.ROOT).startsWith("TRANSFER_"))
            throw new BusinessException("Transfer stock must be posted through the transfer workflow");
        if (type != null && type.toUpperCase(java.util.Locale.ROOT).startsWith("RETURNABLE_PACKAGING_"))
            throw new BusinessException("Returnable packaging stock must use its linked register workflow");
    }

    @Transactional
    public StockMovementDocumentResponse create(StockMovementDocumentCreateRequest request) {
        rejectManagedTransferReference(request.getReferenceType());
        return createInternal(request);
    }

    @Transactional
    public StockMovementDocumentResponse createKimyosanoatIssue(StockMovementDocumentCreateRequest request) {
        if (request == null || !"KIMYOSANOAT".equals(request.getReferenceType())
            || request.getMovementType() != MovementType.ISSUE || request.getReferenceId() == null)
            throw new BusinessException("Kimyosanoat issue requires a linked source document");
        return createInternal(request);
    }

    private StockMovementDocumentResponse createInternal(StockMovementDocumentCreateRequest request) {
        StockMovementDocument document = new StockMovementDocument();
        applyRequest(document, request);
        prepareNewDocument(document);
        StockMovementDocument saved = documentRepository.save(document);
        createLines(saved, request.getItems());
        StockMovementDocumentResponse response = toResponse(saved);
        auditService.log(ENTITY_NAME, saved.getId(), "CREATE", saved.getCode(), null, response);
        return response;
    }

    @Transactional
    public StockMovementDocumentResponse submitForApproval(UUID id) {
        throw new BusinessException("Stock movement approval is submitted on StockMovement, not StockMovementDocument");
    }

    @Transactional
    public StockMovementDocumentResponse update(UUID id, StockMovementDocumentUpdateRequest request) {
        rejectManagedTransferReference(request.getReferenceType());
        StockMovementDocument document = findDocument(id);
        rejectManagedTransferReference(document.getReferenceType());
        requireEditable(document);
        String oldSnapshot = snapshotSerializer.toJson(toResponse(document));
        applyRequest(document, request);
        prepareDraftDocument(document);
        movementRepository.deleteByTenantIdAndDocumentId(TenantContext.currentTenant(), document.getId());
        StockMovementDocument saved = documentRepository.save(document);
        createLines(saved, request.getItems());
        StockMovementDocumentResponse response = toResponse(saved);
        auditService.log(ENTITY_NAME, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, response);
        return response;
    }

    @Transactional
    public StockMovementDocumentResponse post(UUID id) {
        return postInternal(id, null);
    }

    @Transactional
    public StockMovementDocumentResponse postManagedIssue(
        UUID id,
        StockMovementPostRequest request,
        String idempotencyKey
    ) {
        if (request == null || request.expectedVersion() == null) {
            throw new BusinessException("expectedVersion is required for stock movement posting");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException("Idempotency-Key is required for stock movement posting");
        }
        return idempotencyService.execute(
            "STOCK_MOVEMENT_POST",
            id,
            idempotencyKey,
            request,
            StockMovementDocumentResponse.class,
            () -> postInternal(id, request.expectedVersion(), true)
        );
    }

    @Transactional
    public StockMovementDocumentResponse postManagedReturn(
        UUID id,
        StockMovementPostRequest request,
        String idempotencyKey
    ) {
        if (request == null || request.expectedVersion() == null) {
            throw new BusinessException("expectedVersion is required for stock movement posting");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException("Idempotency-Key is required for stock movement posting");
        }
        return idempotencyService.execute(
            "STOCK_MOVEMENT_POST",
            id,
            idempotencyKey,
            request,
            StockMovementDocumentResponse.class,
            () -> postInternal(id, request.expectedVersion(), true)
        );
    }

    @Transactional
    public StockMovementDocumentResponse post(
        UUID id,
        StockMovementPostRequest request,
        String idempotencyKey
    ) {
        if (request == null || request.expectedVersion() == null) {
            throw new BusinessException("expectedVersion is required for stock movement posting");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException("Idempotency-Key is required for stock movement posting");
        }
        return idempotencyService.execute(
            "STOCK_MOVEMENT_POST",
            id,
            idempotencyKey,
            request,
            StockMovementDocumentResponse.class,
            () -> postInternal(id, request.expectedVersion())
        );
    }

    @Transactional
    public StockMovementDocumentResponse postKimyosanoatIssue(UUID id, StockMovementPostRequest request, String idempotencyKey) {
        if (request == null || request.expectedVersion() == null || idempotencyKey == null || idempotencyKey.isBlank())
            throw new BusinessException("Expected version and Idempotency-Key are required for Kimyosanoat posting");
        return idempotencyService.execute("STOCK_MOVEMENT_POST", id, idempotencyKey, request,
            StockMovementDocumentResponse.class, () -> postInternal(id, request.expectedVersion(), false, true));
    }

    private StockMovementDocumentResponse postInternal(UUID id, Long expectedVersion) {
        return postInternal(id, expectedVersion, false);
    }

    private StockMovementDocumentResponse postInternal(UUID id, Long expectedVersion, boolean managedIssue) {
        return postInternal(id, expectedVersion, managedIssue, false);
    }

    private StockMovementDocumentResponse postInternal(UUID id, Long expectedVersion, boolean managedIssue, boolean kimyosanoat) {
        StockMovementDocument document = findDocument(id);
        if (kimyosanoat) {
            if (!"KIMYOSANOAT".equals(document.getReferenceType()) || document.getMovementType() != MovementType.ISSUE
                || document.getReferenceId() == null) throw new BusinessException("Kimyosanoat issue document is invalid");
        } else {
            rejectManagedTransferReference(document.getReferenceType());
        }
        if (managedIssue
            && !(("ISSUE_REQUEST".equals(document.getReferenceType()) && document.getMovementType() == MovementType.ISSUE)
                || ("MATERIAL_RETURN".equals(document.getReferenceType()) && document.getMovementType() == MovementType.RETURN))) {
            throw new BusinessException("Managed serialized posting requires an approved managed workflow document");
        }
        if (expectedVersion != null && !Objects.equals(document.getVersion(), expectedVersion)) {
            throw new ConflictException(
                "Stock movement document was changed by another user; refresh and retry");
        }
        if (StockMovementStatus.POSTED.name().equals(document.getStatus())) {
            return toResponse(document);
        }
        requireApproved(document);
        List<StockMovement> lines = lines(document.getId());
        if (lines.isEmpty()) {
            throw new BusinessException("Stock movement document must contain at least one item");
        }
        String oldSnapshot = snapshotSerializer.toJson(toResponse(document));
        if (managedIssue) {
            if (document.getMovementType() == MovementType.RETURN) {
                lines.forEach(stockMovementService::postManagedReturnDocumentLine);
            } else {
                lines.forEach(stockMovementService::postManagedIssueDocumentLine);
            }
        } else {
            lines.forEach(stockMovementService::postApprovedDocumentLine);
        }
        document.setStatus(StockMovementStatus.POSTED.name());
        if (document.getPostedAt() == null) {
            document.setPostedAt(LocalDateTime.now());
        }
        StockMovementDocument saved = documentRepository.save(document);
        StockMovementDocumentResponse response = toResponse(saved);
        auditService.log(ENTITY_NAME, saved.getId(), "POST", saved.getCode(), oldSnapshot, response);
        nonMovingStockExecutionService.reconcileDocumentOutput(
            "STOCK_MOVEMENT_DOCUMENT",
            saved.getId(),
            NonMovingStockExecutionOutputStatus.POSTED,
            null,
            null
        );
        return response;
    }

    @Transactional
    public StockMovementDocumentResponse cancelPosted(UUID id) {
        return cancelPosted(id, null);
    }

    @Transactional
    public StockMovementDocumentResponse cancelPosted(UUID id, String idempotencyKey) {
        StockMovementDocument document = findDocument(id);
        return cancelPosted(
            id,
            new StockMovementCancelRequest(document.getVersion(), "Stock movement document cancellation"),
            idempotencyKey
        );
    }

    @Transactional
    public StockMovementDocumentResponse cancelPosted(
        UUID id,
        StockMovementCancelRequest request,
        String idempotencyKey
    ) {
        if (request == null || request.expectedVersion() == null) {
            throw new BusinessException("expectedVersion is required for stock movement reversal");
        }
        if (request.reason() == null || request.reason().trim().length() < 5) {
            throw new BusinessException("A reversal reason of at least 5 characters is required");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException("Idempotency-Key is required for stock movement reversal");
        }
        return idempotencyService.execute(
            "STOCK_MOVEMENT_CANCEL",
            id,
            idempotencyKey,
            request,
            StockMovementDocumentResponse.class,
            () -> cancelPostedInternal(id, request.expectedVersion(), request.reason().trim())
        );
    }

    private StockMovementDocumentResponse cancelPostedInternal(UUID id, Long expectedVersion, String reason) {
        StockMovementDocument original = findDocument(id);
        if (!Objects.equals(original.getVersion(), expectedVersion)) {
            throw new ConflictException("Stock movement document was changed by another user; refresh and retry");
        }
        if (!StockMovementStatus.isEffective(original.getStatus())) {
            throw new BusinessException("Only POSTED stock movement document can be reversed");
        }

        // P2 AT06: Check dependent downstream movements. If stock was already consumed downstream, block direct reversal.
        if (MovementType.RECEIPT.equals(original.getMovementType()) || MovementType.TRANSFER.equals(original.getMovementType())) {
            for (StockMovement line : lines(original.getId())) {
                UUID checkWh = MovementType.TRANSFER.equals(original.getMovementType()) ? original.getTargetWarehouseId() : original.getWarehouseId();
                if (checkWh != null) {
                    var agg = stockBalanceRepository.aggregateMaterial360(
                        TenantContext.currentTenant(),
                        CompanyContext.currentCompany(),
                        line.getMaterialId(),
                        checkWh
                    );
                    if (agg != null && agg.getAvailable() != null && agg.getAvailable().compareTo(line.getQuantity()) < 0) {
                        throw new BusinessException(
                            "Direct reversal is blocked because dependent downstream movements exist; require an approved correction with separate effects",
                            "reversal.dependent.movements.exist"
                        );
                    }
                }
            }
        }

        String oldSnapshot = snapshotSerializer.toJson(toResponse(original));

        StockMovementDocument reversal = new StockMovementDocument();
        reversal.setCode(original.getCode() + "-REV");
        reversal.setName("Reversal of " + original.getName());
        reversal.setDescription(original.getDescription());
        reversal.setWarehouseId(MovementType.TRANSFER.equals(original.getMovementType()) ? original.getTargetWarehouseId() : original.getWarehouseId());
        reversal.setTargetWarehouseId(MovementType.TRANSFER.equals(original.getMovementType()) ? original.getWarehouseId() : original.getTargetWarehouseId());
        reversal.setMovementType(original.getMovementType());
        reversal.setMovementDate(LocalDate.now());
        reversal.setReferenceType("REVERSAL");
        reversal.setReferenceId(original.getId());
        reversal.setReversalOfId(original.getId());
        prepareNewDocument(reversal);
        StockMovementDocument savedReversal = documentRepository.save(reversal);

        for (StockMovement line : lines(original.getId())) {
            stockMovementService.cancelPosted(line.getId(), savedReversal.getId(), reason);
        }
        savedReversal.setStatus(StockMovementStatus.POSTED.name());
        savedReversal.setPostedAt(LocalDateTime.now());
        original.setStatus(StockMovementStatus.CANCELLED.name());
        StockMovementDocument savedOriginal = documentRepository.save(original);
        documentRepository.save(savedReversal);
        auditService.log(ENTITY_NAME, original.getId(), "CANCEL_POSTED", reason + " · " + savedReversal.getId(), oldSnapshot, toResponse(savedOriginal));
        nonMovingStockExecutionService.reconcileDocumentOutput(
            "STOCK_MOVEMENT_DOCUMENT",
            original.getId(),
            NonMovingStockExecutionOutputStatus.CANCELLED,
            "STOCK_MOVEMENT_DOCUMENT_CANCELLED",
            "Stock movement document was cancelled"
        );
        return toResponse(savedReversal);
    }

    @Transactional
    public StockMovementDocumentResponse changeStatus(UUID id, StockMovementStatusChangeRequest request) {
        StockMovementDocument document = findDocument(id);
        StockMovementStatus current = parseStatus(document.getStatus());
        StockMovementStatus target = request.status();
        if (!isAllowedTransition(current, target)) {
            throw new BusinessException("Stock movement status cannot change from " + current + " to " + target);
        }
        String oldSnapshot = snapshotSerializer.toJson(toResponse(document));
        document.setStatus(target.name());
        List<StockMovement> lines = lines(document.getId());
        if (target == StockMovementStatus.APPROVED) {
            if (lines.isEmpty()) {
                throw new BusinessException("Stock movement document must contain at least one item");
            }
            lines.forEach(stockMovementService::approveDocumentLine);
            document.setPostedAt(null);
        } else {
            lines.forEach(line -> {
                line.setStatus(target.name());
                movementRepository.save(line);
            });
            if (target == StockMovementStatus.DRAFT || target == StockMovementStatus.PENDING || target == StockMovementStatus.RETURNED) {
                document.setPostedAt(null);
            }
        }
        StockMovementDocument saved = documentRepository.save(document);
        StockMovementDocumentResponse response = toResponse(saved);
        auditService.log(ENTITY_NAME, saved.getId(), "STATUS_CHANGED", target.name(), oldSnapshot, response);
        return response;
    }

    @Transactional
    public void softDelete(UUID id) {
        StockMovementDocument document = findDocument(id);
        requireEditable(document);
        String oldSnapshot = snapshotSerializer.toJson(toResponse(document));
        document.softDelete();
        StockMovementDocument saved = documentRepository.save(document);
        auditService.log(ENTITY_NAME, id, "SOFT_DELETE", document.getCode(), oldSnapshot, toResponse(saved));
    }

    @Transactional
    public StockMovementDocumentResponse restore(UUID id) {
        StockMovementDocument document = documentRepository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException("Stock movement document not found: " + id));
        if (!TenantContext.currentTenant().equals(document.getTenantId())
            || (com.enterprise.mm.security.AccessScope.companyScoped(true)
                && !com.enterprise.mm.security.AccessScope.canAccessCompany(document.getCompanyId()))) {
            throw new NotFoundException("Stock movement document not found: " + id);
        }
        String oldSnapshot = snapshotSerializer.toJson(toResponse(document));
        document.restore();
        StockMovementDocument saved = documentRepository.save(document);
        StockMovementDocumentResponse response = toResponse(saved);
        auditService.log(ENTITY_NAME, id, "RESTORE", saved.getCode(), oldSnapshot, response);
        return response;
    }

    private void applyRequest(StockMovementDocument document, StockMovementDocumentCreateRequest request) {
        document.setCode(request.getCode());
        document.setName(request.getName());
        document.setDescription(request.getDescription());
        document.setWarehouseId(request.getWarehouseId());
        document.setTargetWarehouseId(request.getTargetWarehouseId());
        document.setMovementType(request.getMovementType());
        document.setMovementDate(request.getMovementDate());
        document.setReferenceType(request.getReferenceType());
        document.setReferenceId(request.getReferenceId());
    }

    private void prepareNewDocument(StockMovementDocument document) {
        document.setTenantId(TenantContext.currentTenant());
        document.setCompanyId(CompanyContext.currentCompany());
        if (blank(document.getCode())) {
            document.setCode("STOCK-MOV-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        }
        document.setName(BusinessDisplayNamePolicy.requireHumanName(
            "Stock movement", document.getName(), document.getCode()
        ));
        prepareDraftDocument(document);
    }

    private void prepareDraftDocument(StockMovementDocument document) {
        if (document.getMovementType() == null) {
            throw new BusinessException("Movement type is required");
        }
        if (document.getWarehouseId() == null) {
            throw new BusinessException("Warehouse is required");
        }
        if (MovementType.TRANSFER.equals(document.getMovementType()) && document.getTargetWarehouseId() == null) {
            throw new BusinessException("Target warehouse is required for transfer");
        }
        document.setStatus(StockMovementStatus.DRAFT.name());
        document.setPostedAt(null);
        if (document.getMovementDate() == null) {
            document.setMovementDate(LocalDate.now());
        }
    }

    private void createLines(StockMovementDocument document, List<StockMovementDocumentItemRequest> items) {
        for (int index = 0; index < items.size(); index++) {
            StockMovementDocumentItemRequest item = items.get(index);
            materialCatalogGuard.requireActiveTmbMaterial(item.getMaterialId(), "items[" + index + "].materialId");
            StockMovement line = new StockMovement();
            line.setDocumentId(document.getId());
            line.setTenantId(document.getTenantId());
            line.setCompanyId(document.getCompanyId());
            line.setCode(document.getCode() + "-" + (index + 1));
            line.setStatus(StockMovementStatus.DRAFT.name());
            line.setDescription(document.getDescription());
            line.setMaterialId(item.getMaterialId());
            line.setWarehouseId(document.getWarehouseId());
            line.setTargetWarehouseId(document.getTargetWarehouseId());
            line.setStorageLocationId(item.getStorageLocationId());
            line.setStorageBinId(item.getStorageBinId());
            line.setTargetStorageLocationId(item.getTargetStorageLocationId());
            line.setStockBatchId(item.getStockBatchId());
            line.setMovementType(document.getMovementType());
            line.setQuantity(item.getQuantity());
            line.setUnitCost(item.getUnitCost() == null ? BigDecimal.ZERO : item.getUnitCost());
            line.setTotalCost(line.getUnitCost().multiply(line.getQuantity()));
            line.setMovementDate(document.getMovementDate());
            line.setRequestDate(document.getCreatedAt() == null ? LocalDateTime.now() : document.getCreatedAt());
            line.setReferenceType(document.getReferenceType());
            line.setReferenceId(document.getReferenceId());
            line.setName(stockMovementService.generateMovementName(line));
            movementRepository.save(line);
        }
    }

    private StockMovementDocument findDocument(UUID id) {
        StockMovementDocument document = documentRepository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException("Stock movement document not found: " + id));
        if (document.isDeleted() || !TenantContext.currentTenant().equals(document.getTenantId())
            || (com.enterprise.mm.security.AccessScope.companyScoped(true)
                && !com.enterprise.mm.security.AccessScope.canAccessCompany(document.getCompanyId()))) {
            throw new NotFoundException("Stock movement document not found: " + id);
        }
        return document;
    }

    private void requireEditable(StockMovementDocument document) {
        if (!StockMovementStatus.DRAFT.name().equals(document.getStatus())
            && !StockMovementStatus.RETURNED.name().equals(document.getStatus())) {
            throw new BusinessException("Only DRAFT or RETURNED stock movement document can be edited");
        }
    }

    private void requireApproved(StockMovementDocument document) {
        if (!StockMovementStatus.APPROVED.name().equals(document.getStatus())) {
            throw new BusinessException("Only APPROVED stock movement document can be posted");
        }
        LocalDate movementDate = document.getMovementDate() == null ? LocalDate.now() : document.getMovementDate();
        if (inventoryPeriodGuardService.isClosed(document.getWarehouseId(), movementDate)) {
            throw new BusinessException("Inventory period is closed", "inventory.period.closed");
        }
    }

    private StockMovementStatus parseStatus(String status) {
        try {
            return StockMovementStatus.valueOf(status);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new BusinessException("Unknown stock movement status: " + status);
        }
    }

    private boolean isAllowedTransition(StockMovementStatus current, StockMovementStatus target) {
        return switch (current) {
            case DRAFT -> target == StockMovementStatus.PENDING;
            case PENDING -> target == StockMovementStatus.APPROVED || target == StockMovementStatus.RETURNED;
            case RETURNED -> target == StockMovementStatus.DRAFT || target == StockMovementStatus.PENDING;
            default -> false;
        };
    }

    private List<StockMovement> lines(UUID documentId) {
        return movementRepository.findByTenantIdAndDocumentIdAndIsDeletedFalseOrderByCreatedAtAsc(TenantContext.currentTenant(), documentId);
    }

    private StockMovementDocumentResponse toResponse(StockMovementDocument document) {
        StockMovementDocumentResponse response = new StockMovementDocumentResponse();
        response.setId(document.getId());
        response.setTenantId(document.getTenantId());
        response.setCompanyId(document.getCompanyId());
        response.setCode(document.getCode());
        response.setName(document.getName());
        response.setStatus(document.getStatus());
        response.setDescription(document.getDescription());
        response.setDeleted(document.isDeleted());
        response.setCreatedAt(document.getCreatedAt());
        response.setUpdatedAt(document.getUpdatedAt());
        response.setCreatedBy(document.getCreatedBy());
        response.setUpdatedBy(document.getUpdatedBy());
        response.setVersion(document.getVersion());
        response.setWarehouseId(document.getWarehouseId());
        response.setWarehouseName(resolveWarehouseName(document.getWarehouseId()));
        response.setTargetWarehouseId(document.getTargetWarehouseId());
        response.setTargetWarehouseName(resolveWarehouseName(document.getTargetWarehouseId()));
        response.setMovementType(document.getMovementType());
        response.setMovementDate(document.getMovementDate());
        response.setPostedAt(document.getPostedAt());
        response.setActorName(preferActor(document.getUpdatedBy(), document.getCreatedBy()));
        response.setReferenceType(document.getReferenceType());
        response.setReferenceId(document.getReferenceId());
        response.setReversalOfId(document.getReversalOfId());
        response.setItems(lines(document.getId()).stream().map(movementMapper::toResponse).toList());
        response.setAllowedActions(
            StockMovementStatus.DRAFT.name().equals(document.getStatus())
                || StockMovementStatus.RETURNED.name().equals(document.getStatus())
                ? List.of("submit-approval")
                : StockMovementStatus.APPROVED.name().equals(document.getStatus())
                ? List.of("post")
                : StockMovementStatus.POSTED.name().equals(document.getStatus())
                    ? List.of("cancel")
                    : List.of()
        );
        return response;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String resolveWarehouseName(UUID warehouseId) {
        if (warehouseId == null) {
            return null;
        }
        return warehouseRepository.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), warehouseId)
            .map(com.enterprise.mm.entity.Warehouse::getName)
            .orElse(null);
    }

    private String preferActor(String updatedBy, String createdBy) {
        if (updatedBy != null && !updatedBy.isBlank()) {
            return updatedBy;
        }
        return createdBy;
    }

    private List<StockMovementReversalPreviewResponse.LinkedDocument> linkedDocuments(StockMovementDocument document) {
        if (document.getReferenceId() == null) {
            return List.of();
        }
        String code = document.getCode();
        if ("ISSUE_REQUEST".equals(document.getReferenceType())) {
            code = issueRequestRepository.findByIdAndIsDeletedFalse(document.getReferenceId())
                .map(com.enterprise.mm.entity.IssueRequest::getCode)
                .orElse(code);
        }
        return List.of(new StockMovementReversalPreviewResponse.LinkedDocument(
            document.getReferenceType(), document.getReferenceId(), code
        ));
    }

    private BigDecimal reversalQuantityDelta(MovementType type, BigDecimal quantity) {
        return switch (type) {
            case ISSUE, WRITE_OFF -> quantity;
            case RECEIPT, RETURN, ADJUSTMENT, REVERSAL -> quantity.negate();
            case TRANSFER -> BigDecimal.ZERO;
        };
    }

    private BigDecimal reversalValueDelta(MovementType type, BigDecimal value) {
        return switch (type) {
            case ISSUE, WRITE_OFF -> value;
            case RECEIPT, RETURN, ADJUSTMENT, REVERSAL -> value.negate();
            case TRANSFER -> BigDecimal.ZERO;
        };
    }

    private String signed(BigDecimal value) {
        return value.signum() > 0 ? "+" + value.toPlainString() : value.toPlainString();
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
