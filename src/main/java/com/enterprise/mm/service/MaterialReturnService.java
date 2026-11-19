package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.IssueRequest;
import com.enterprise.mm.entity.IssueRequestLine;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.MaterialReturnDocument;
import com.enterprise.mm.entity.MaterialReturnLine;
import com.enterprise.mm.entity.ReturnOriginLink;
import com.enterprise.mm.entity.MaterialUnit;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.enums.IssueRequestStatus;
import com.enterprise.mm.enums.MaterialReturnCondition;
import com.enterprise.mm.enums.MaterialReturnStatus;
import com.enterprise.mm.enums.MaterialUnitOperationType;
import com.enterprise.mm.enums.MaterialUnitStatus;
import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.enums.ReturnStockStatus;
import com.enterprise.mm.enums.SerialTrackingPolicy;
import com.enterprise.mm.enums.StockMovementStatus;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.MaterialReturnMapper;
import com.enterprise.mm.payload.MaterialReturnAcceptRequest;
import com.enterprise.mm.payload.MaterialReturnCreateRequest;
import com.enterprise.mm.payload.MaterialReturnResponse;
import com.enterprise.mm.payload.MaterialReturnSearchRequest;
import com.enterprise.mm.payload.ServiceActionRequest;
import com.enterprise.mm.payload.StockMovementDocumentCreateRequest;
import com.enterprise.mm.payload.StockMovementDocumentItemRequest;
import com.enterprise.mm.payload.StockMovementDocumentResponse;
import com.enterprise.mm.payload.StockMovementPostRequest;
import com.enterprise.mm.payload.StockMovementStatusChangeRequest;
import com.enterprise.mm.repository.IssueRequestLineRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.MaterialReturnDocumentRepository;
import com.enterprise.mm.repository.MaterialReturnLineRepository;
import com.enterprise.mm.repository.ReturnOriginLinkRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.service.InventoryPeriodGuardService;
import com.enterprise.mm.util.BusinessNameGenerator;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MaterialReturnService {
    private final InventoryFreezeGuardService inventoryFreezeGuard;
    private static final String ENTITY_NAME = "MaterialReturnDocument";

    private final MaterialReturnDocumentRepository repository;
    private final MaterialReturnLineRepository lineRepository;
    private final IssueRequestLineRepository issueLineRepository;
    private final IssueRequestService issueRequestService;
    private final WarehouseRepository warehouseRepository;
    private final MaterialReturnMapper mapper;
    private final StockMovementDocumentService stockMovementDocumentService;
    private final StockBalanceRepository stockBalanceRepository;
    private final StockBalanceMutationService stockBalanceMutationService;
    private final MaterialCatalogGuard materialCatalogGuard;
    private final MaterialRepository materialRepository;
    private final IdempotencyService idempotencyService;
    private final AuditService auditService;
    private final AuditSnapshotSerializer snapshotSerializer;
    private final RoleDataScopeService roleDataScopeService;
    private final MaterialUnitEligibilityService materialUnitEligibilityService;
    private final InventoryPeriodGuardService periodGuard;
    private final ReturnOriginLinkRepository returnOriginLinkRepository;

    @org.springframework.beans.factory.annotation.Autowired
    public MaterialReturnService(
        InventoryFreezeGuardService inventoryFreezeGuard,
        MaterialReturnDocumentRepository repository,
        MaterialReturnLineRepository lineRepository,
        IssueRequestLineRepository issueLineRepository,
        IssueRequestService issueRequestService,
        WarehouseRepository warehouseRepository,
        MaterialReturnMapper mapper,
        StockMovementDocumentService stockMovementDocumentService,
        StockBalanceRepository stockBalanceRepository,
        StockBalanceMutationService stockBalanceMutationService,
        MaterialCatalogGuard materialCatalogGuard,
        MaterialRepository materialRepository,
        IdempotencyService idempotencyService,
        AuditService auditService,
        AuditSnapshotSerializer snapshotSerializer,
        RoleDataScopeService roleDataScopeService,
        MaterialUnitEligibilityService materialUnitEligibilityService,
        InventoryPeriodGuardService periodGuard,
        ReturnOriginLinkRepository returnOriginLinkRepository
    ) {
        this.inventoryFreezeGuard = inventoryFreezeGuard;
        this.repository = repository;
        this.lineRepository = lineRepository;
        this.issueLineRepository = issueLineRepository;
        this.issueRequestService = issueRequestService;
        this.warehouseRepository = warehouseRepository;
        this.mapper = mapper;
        this.stockMovementDocumentService = stockMovementDocumentService;
        this.stockBalanceRepository = stockBalanceRepository;
        this.stockBalanceMutationService = stockBalanceMutationService;
        this.materialCatalogGuard = materialCatalogGuard;
        this.materialRepository = materialRepository;
        this.idempotencyService = idempotencyService;
        this.auditService = auditService;
        this.snapshotSerializer = snapshotSerializer;
        this.roleDataScopeService = roleDataScopeService;
        this.materialUnitEligibilityService = materialUnitEligibilityService;
        this.periodGuard = periodGuard;
        this.returnOriginLinkRepository = returnOriginLinkRepository;
    }

    public MaterialReturnService(
        InventoryFreezeGuardService inventoryFreezeGuard,
        MaterialReturnDocumentRepository repository,
        MaterialReturnLineRepository lineRepository,
        IssueRequestLineRepository issueLineRepository,
        IssueRequestService issueRequestService,
        WarehouseRepository warehouseRepository,
        MaterialReturnMapper mapper,
        StockMovementDocumentService stockMovementDocumentService,
        StockBalanceRepository stockBalanceRepository,
        StockBalanceMutationService stockBalanceMutationService,
        MaterialCatalogGuard materialCatalogGuard,
        MaterialRepository materialRepository,
        IdempotencyService idempotencyService,
        AuditService auditService,
        AuditSnapshotSerializer snapshotSerializer,
        RoleDataScopeService roleDataScopeService,
        MaterialUnitEligibilityService materialUnitEligibilityService
    ) {
        this(
            inventoryFreezeGuard, repository, lineRepository, issueLineRepository,
            issueRequestService, warehouseRepository, mapper, stockMovementDocumentService,
            stockBalanceRepository, stockBalanceMutationService, materialCatalogGuard,
            materialRepository, idempotencyService, auditService, snapshotSerializer,
            roleDataScopeService, materialUnitEligibilityService, null, null
        );
    }

    @Transactional(readOnly = true)
    public Page<MaterialReturnResponse> search(MaterialReturnSearchRequest request, Pageable pageable) {
        MaterialReturnSearchRequest effective = request == null
            ? new MaterialReturnSearchRequest(null, List.of(), null, null, null, null)
            : request;
        effective = new MaterialReturnSearchRequest(
            effective.search(),
            effective.status(),
            effective.issueRequestId(),
            effective.warehouseId(),
            effective.dateFrom(),
            effective.dateTo()
        );
        return repository.findAll(spec(effective), pageable).map(this::response);
    }

    @Transactional(readOnly = true)
    public MaterialReturnResponse get(UUID id) {
        return response(findEntity(id));
    }

    @Transactional
    public MaterialReturnResponse create(MaterialReturnCreateRequest request) {
        if (request == null) {
            throw new BadRequestException("Request body is required");
        }
        Warehouse warehouse = requireWarehouse(request.getWarehouseId());
        if (!roleDataScopeService.canAccessWarehouse(warehouse.getId())) {
            throw new BusinessException("User does not have access to return warehouse " + warehouse.getId());
        }

        boolean isHistorical = request.getOriginType() != null && "HISTORICAL_1C".equalsIgnoreCase(request.getOriginType());
        if (isHistorical && roleDataScopeService.isDepartmentWarehouseScoped()) {
            throw new org.springframework.security.access.AccessDeniedException("Department returns require an issued department request");
        }

        IssueRequest issue = null;
        if (!isHistorical) {
            if (request.getIssueRequestId() == null) {
                throw new BadRequestException("issueRequestId is required for native return");
            }
            issue = issueRequestService.findEntity(request.getIssueRequestId());
            if (!List.of(
                IssueRequestStatus.PARTIALLY_ISSUED.name(),
                IssueRequestStatus.ISSUED.name(),
                IssueRequestStatus.CLOSED.name()
            ).contains(issue.getStatus())) {
                throw new ConflictException("Only issued material can be returned");
            }
            if (!Objects.equals(issue.getWarehouseId(), warehouse.getId())) {
                throw new BadRequestException("Return warehouse must match the issue warehouse");
            }
        } else {
            if (request.getHistoricalDocumentNumber() == null || request.getHistoricalDocumentNumber().isBlank()) {
                throw new BadRequestException("historicalDocumentNumber is required for historical return");
            }
        }

        MaterialReturnDocument entity = new MaterialReturnDocument();
        entity.setCode("RT-" + shortId());
        entity.setName(BusinessNameGenerator.workflowDocumentName("MaterialReturnDocument", isHistorical ? request.getHistoricalDocumentNumber() : issue.getName()));
        entity.setStatus(MaterialReturnStatus.DRAFT.name());
        entity.setIssueRequestId(isHistorical ? null : issue.getId());
        entity.setWarehouseId(warehouse.getId());
        entity.setReason(request.getReason());
        entity.setOriginType(isHistorical ? "HISTORICAL_1C" : "NATIVE_ISSUE");
        entity.setHistoricalDocumentNumber(request.getHistoricalDocumentNumber());
        entity.setHistoricalDocumentDate(request.getHistoricalDocumentDate());
        entity.setHistoricalSourceSystem(request.getHistoricalSourceSystem() == null ? "1C_ENTERPRISE" : request.getHistoricalSourceSystem());
        entity.setOwnerId(request.getOwnerId());
        MaterialReturnDocument saved = repository.save(entity);

        int index = 0;
        for (MaterialReturnCreateRequest.Line input : request.getLines()) {
            if (isHistorical) {
                if (input.getMaterialId() == null) {
                    throw new BadRequestException("materialId is required for historical return line");
                }
                Material material = requireMaterial(input.getMaterialId());
                if (material.getSerialTrackingPolicy() == SerialTrackingPolicy.REQUIRED) {
                    if (input.getSerialNumber() == null || input.getSerialNumber().isBlank()) {
                        throw new BadRequestException("serialNumber is required for serial-tracked material: " + material.getId());
                    }
                }
                BigDecimal issuedQuantity = input.getIssuedQuantity() != null && input.getIssuedQuantity().signum() > 0
                    ? input.getIssuedQuantity()
                    : input.getReturnQuantity();
                if (returnOriginLinkRepository != null) {
                    returnOriginLinkRepository.findAndLockByOriginReference(
                        TenantContext.currentTenant(),
                        CompanyContext.currentCompany(),
                        request.getHistoricalDocumentNumber(),
                        material.getId()
                    );
                }
                BigDecimal previouslyReturned = returnOriginLinkRepository != null
                    ? nvl(returnOriginLinkRepository.sumReturnedQuantity(
                        TenantContext.currentTenant(),
                        CompanyContext.currentCompany(),
                        request.getHistoricalDocumentNumber(),
                        material.getId()
                    ))
                    : BigDecimal.ZERO;
                BigDecimal availableToReturn = issuedQuantity.subtract(previouslyReturned);
                if (nvl(input.getReturnQuantity()).signum() <= 0 || input.getReturnQuantity().compareTo(availableToReturn) > 0) {
                    throw new BadRequestException("returnQuantity exceeds returnableQuantity for historical document line");
                }

                MaterialReturnLine line = new MaterialReturnLine();
                line.setCode(saved.getCode() + "-" + (++index));
                line.setName(material.getName());
                line.setStatus("ACTIVE");
                line.setReturnDocumentId(saved.getId());
                line.setMaterialId(material.getId());
                line.setIssuedQuantity(issuedQuantity);
                line.setPreviouslyReturnedQuantity(previouslyReturned);
                line.setReturnQuantity(input.getReturnQuantity());
                line.setAcceptedQuantity(BigDecimal.ZERO);
                line.setRejectedQuantity(BigDecimal.ZERO);
                line.setCondition(input.getCondition());
                line.setOriginalUnitCost(input.getOriginalUnitCost() != null ? input.getOriginalUnitCost() : BigDecimal.ZERO);
                line.setSerialNumber(input.getSerialNumber());
                line.setOwnerId(input.getOwnerId() != null ? input.getOwnerId() : request.getOwnerId());
                line.setStorageLocationId(input.getStorageLocationId());
                line.setStockBatchId(input.getStockBatchId());
                line.setLineComment(input.getComment());
                lineRepository.save(line);
            } else {
                IssueRequestLine issueLine = issueLineRepository.findByIdForUpdate(input.getIssueRequestLineId())
                    .orElseThrow(() -> new NotFoundException("Issue request line not found: " + input.getIssueRequestLineId()));
                if (!Objects.equals(issueLine.getIssueRequestId(), issue.getId())) {
                    throw new BadRequestException("issueRequestLineId does not belong to issueRequestId");
                }
                BigDecimal committed = nvl(lineRepository.sumCommittedQuantity(
                    TenantContext.currentTenant(), CompanyContext.currentCompany(), issueLine.getId()
                ));
                BigDecimal availableToReturn = nvl(issueLine.getIssuedQuantity()).subtract(committed);
                if (nvl(input.getReturnQuantity()).signum() <= 0
                    || input.getReturnQuantity().compareTo(availableToReturn) > 0) {
                    throw new BadRequestException(
                        "returnQuantity exceeds returnableQuantity for issue line " + issueLine.getId()
                    );
                }
                BigDecimal previouslyReturned = nvl(lineRepository.sumAcceptedQuantity(
                    TenantContext.currentTenant(), CompanyContext.currentCompany(), issueLine.getId()
                ));
                MaterialReturnLine line = new MaterialReturnLine();
                line.setCode(saved.getCode() + "-" + (++index));
                line.setName(issueLine.getMaterial() == null ? "Returned material" : issueLine.getMaterial().getName());
                line.setStatus("ACTIVE");
                line.setReturnDocumentId(saved.getId());
                line.setIssueRequestLineId(issueLine.getId());
                line.setMaterialId(issueLine.getMaterialId());
                line.setOriginalStockMovementId(issueLine.getStockMovementId());
                line.setStockBatchId(issueLine.getStockBatchId());
                line.setStorageLocationId(issueLine.getStorageLocationId());
                line.setIssuedQuantity(nvl(issueLine.getIssuedQuantity()));
                line.setPreviouslyReturnedQuantity(previouslyReturned);
                line.setReturnQuantity(input.getReturnQuantity());
                line.setAcceptedQuantity(BigDecimal.ZERO);
                line.setRejectedQuantity(BigDecimal.ZERO);
                line.setCondition(input.getCondition());
                line.setOriginalUnitCost(nvl(issueLine.getUnitCost()));
                line.setLineComment(input.getComment());
                lineRepository.save(line);
            }
        }
        MaterialReturnResponse response = response(saved);
        auditService.log(ENTITY_NAME, saved.getId(), "CREATE", saved.getCode(), null, response);
        return response;
    }

    @Transactional
    public MaterialReturnResponse submit(UUID id) {
        MaterialReturnDocument entity = findEntity(id);
        requireStatus(entity, MaterialReturnStatus.DRAFT);
        if (lines(id).isEmpty()) {
            throw new BadRequestException("Return document must contain at least one line");
        }
        String oldSnapshot = snapshotSerializer.toJson(response(entity));
        entity.setStatus(MaterialReturnStatus.SUBMITTED.name());
        MaterialReturnDocument saved = repository.save(entity);
        MaterialReturnResponse response = response(saved);
        auditService.log(ENTITY_NAME, id, "SUBMIT", saved.getCode(), oldSnapshot, response);
        return response;
    }

    @Transactional
    public MaterialReturnResponse accept(UUID id, MaterialReturnAcceptRequest request, String idempotencyKey) {
        return idempotencyService.execute(
            "MATERIAL_RETURN_ACCEPT",
            id,
            idempotencyKey,
            request,
            MaterialReturnResponse.class,
            () -> acceptInternal(id, request)
        );
    }

    private MaterialReturnResponse acceptInternal(UUID id, MaterialReturnAcceptRequest request) {
        MaterialReturnDocument entity = findEntity(id);
        if (!List.of(MaterialReturnStatus.SUBMITTED.name(), MaterialReturnStatus.INSPECTION.name()).contains(entity.getStatus())) {
            throw new ConflictException("Return must be SUBMITTED or INSPECTION, current status is " + entity.getStatus());
        }
        requireExpectedVersion(entity, request == null ? null : request.getExpectedVersion());
        if (request == null || request.getLines() == null || request.getLines().isEmpty()) {
            throw new BadRequestException("Acceptance lines are required");
        }
        String oldSnapshot = snapshotSerializer.toJson(response(entity));
        entity.setStatus(MaterialReturnStatus.INSPECTION.name());
        Map<UUID, MaterialReturnLine> existing = byId(lines(id));
        if (request.getLines().size() != existing.size()) {
            throw new BadRequestException("All return document lines must be inspected");
        }
        if (new HashSet<>(request.getLines().stream().map(MaterialReturnAcceptRequest.Line::getReturnLineId).toList()).size()
            != request.getLines().size()) {
            throw new BadRequestException("Duplicate returnLineId is not allowed");
        }
        List<StockMovementDocumentItemRequest> items = new ArrayList<>();
        List<ReturnPostingBinding> acceptedLines = new ArrayList<>();
        BigDecimal totalAccepted = BigDecimal.ZERO;
        BigDecimal totalRejected = BigDecimal.ZERO;

        for (MaterialReturnAcceptRequest.Line input : request.getLines()) {
            MaterialReturnLine line = existing.get(input.getReturnLineId());
            if (line == null) {
                throw new BadRequestException("returnLineId does not belong to return document");
            }
            materialCatalogGuard.requireActiveTmbMaterial(line.getMaterialId(), "materialId");
            BigDecimal accepted = nvl(input.getAcceptedQuantity());
            BigDecimal rejected = nvl(input.getRejectedQuantity());
            if (accepted.signum() < 0 || rejected.signum() < 0
                || accepted.add(rejected).compareTo(nvl(line.getReturnQuantity())) != 0) {
                throw new BadRequestException("acceptedQuantity + rejectedQuantity must equal returnQuantity");
            }
            ReturnStockStatus targetStatus = input.getTargetStockStatus() == null
                ? defaultTargetStatus(line.getCondition())
                : input.getTargetStockStatus();
            line.setAcceptedQuantity(accepted);
            line.setRejectedQuantity(rejected);
            line.setTargetStockStatus(targetStatus);
            line.setInspectionNote(input.getInspectionNote());
            lineRepository.save(line);
            totalAccepted = totalAccepted.add(accepted);
            totalRejected = totalRejected.add(rejected);

            if (accepted.signum() > 0) {
                Material material = requireMaterial(line.getMaterialId());
                if (material.getSerialTrackingPolicy() == SerialTrackingPolicy.REQUIRED) {
                    List<MaterialUnit> selectedUnits = materialUnitEligibilityService.lockAndValidate(
                        line.getMaterialId(),
                        entity.getWarehouseId(),
                        null,
                        null,
                        accepted,
                        input.getMaterialUnitIds(),
                        MaterialUnitStatus.ISSUED
                    );
                    validateReturnedUnitsBelongToIssueLine(line, selectedUnits);
                    Map<ReturnStockKey, List<MaterialUnit>> groupedUnits = selectedUnits.stream()
                        .collect(java.util.stream.Collectors.groupingBy(
                            unit -> new ReturnStockKey(
                                unit.getStorageLocationId() == null ? line.getStorageLocationId() : unit.getStorageLocationId(),
                                unit.getStockBatchId()
                            ),
                            LinkedHashMap::new,
                            java.util.stream.Collectors.toList()
                        ));
                    for (Map.Entry<ReturnStockKey, List<MaterialUnit>> entry : groupedUnits.entrySet()) {
                        StockMovementDocumentItemRequest item = new StockMovementDocumentItemRequest();
                        item.setMaterialId(line.getMaterialId());
                        item.setStorageLocationId(entry.getKey().storageLocationId());
                        item.setStockBatchId(entry.getKey().stockBatchId());
                        item.setQuantity(BigDecimal.valueOf(entry.getValue().size()));
                        item.setUnitCost(line.getOriginalUnitCost());
                        items.add(item);
                        acceptedLines.add(new ReturnPostingBinding(line, entry.getValue(), true));
                    }
                } else {
                    StockMovementDocumentItemRequest item = new StockMovementDocumentItemRequest();
                    item.setMaterialId(line.getMaterialId());
                    item.setStorageLocationId(line.getStorageLocationId());
                    item.setStockBatchId(line.getStockBatchId());
                    item.setQuantity(accepted);
                    item.setUnitCost(line.getOriginalUnitCost());
                    items.add(item);
                    acceptedLines.add(new ReturnPostingBinding(line, List.of(), false));
                }
            }
        }

        StockMovementDocumentResponse movement = null;
        if (!items.isEmpty()) {
            if (periodGuard != null && periodGuard.isClosed(entity.getWarehouseId(), LocalDate.now())) {
                throw new BusinessException("Inventory period is closed", "inventory.period.closed");
            }
            movement = postReturnMovement(entity, items);
            for (int index = 0; index < acceptedLines.size(); index++) {
                ReturnPostingBinding binding = acceptedLines.get(index);
                MaterialReturnLine line = binding.line();
                var movementLine = movement.getItems().get(index);
                if (!binding.serialized() || binding.units().stream().map(MaterialUnit::getStockBatchId).distinct().count() <= 1) {
                    line.setStockBatchId(movementLine.getStockBatchId());
                }
                line.setStorageLocationId(movementLine.getStorageLocationId());
                lineRepository.save(line);
                if (binding.serialized()) {
                    materialUnitEligibilityService.markReturned(
                        binding.units(),
                        ENTITY_NAME,
                        entity.getId(),
                        line.getId(),
                        entity.getWarehouseId(),
                        movementLine.getStorageLocationId(),
                        null
                    );
                }
                if (line.getTargetStockStatus() != ReturnStockStatus.AVAILABLE) {
                    quarantine(movementLine.getMaterialId(), entity.getWarehouseId(), movementLine.getStorageLocationId(), movementLine.getStockBatchId(), line.getAcceptedQuantity());
                }
            }
            entity.setStockMovementDocumentId(movement.getId());

            if (returnOriginLinkRepository != null && totalAccepted.signum() > 0) {
                for (ReturnPostingBinding binding : acceptedLines) {
                    MaterialReturnLine line = binding.line();
                    if (line.getAcceptedQuantity().signum() > 0) {
                        ReturnOriginLink link = new ReturnOriginLink();
                        link.setTenantId(TenantContext.currentTenant());
                        link.setCompanyId(CompanyContext.currentCompany());
                        link.setReturnDocumentId(entity.getId());
                        link.setReturnLineId(line.getId());
                        link.setOriginType(entity.getOriginType() != null ? entity.getOriginType() : "NATIVE_ISSUE");
                        link.setOriginReference(entity.getOriginType() != null && "HISTORICAL_1C".equalsIgnoreCase(entity.getOriginType())
                            ? entity.getHistoricalDocumentNumber()
                            : (entity.getIssueRequest() != null ? entity.getIssueRequest().getCode() : entity.getId().toString()));
                        link.setMaterialId(line.getMaterialId());
                        link.setReturnedQuantity(line.getAcceptedQuantity());
                        link.setReturnedUnitCost(line.getOriginalUnitCost());
                        link.setReturnedTotalValue(line.getAcceptedQuantity().multiply(line.getOriginalUnitCost()));
                        link.setOwnerId(line.getOwnerId());
                        link.setSerialNumber(line.getSerialNumber());
                        returnOriginLinkRepository.save(link);
                    }
                }
            }
        }

        String acceptanceStatus = totalAccepted.signum() == 0
            ? MaterialReturnStatus.REJECTED.name()
            : totalRejected.signum() > 0
                ? MaterialReturnStatus.PARTIALLY_ACCEPTED.name()
                : MaterialReturnStatus.ACCEPTED.name();
        entity.setAcceptanceStatus(acceptanceStatus);
        entity.setStatus(totalAccepted.signum() == 0 ? MaterialReturnStatus.REJECTED.name() : MaterialReturnStatus.POSTED.name());
        MaterialReturnDocument saved = repository.save(entity);
        MaterialReturnResponse response = response(saved);
        auditService.log(ENTITY_NAME, id, "ACCEPT", movement == null ? acceptanceStatus : movement.getId().toString(), oldSnapshot, response);
        return response;
    }

    @Transactional
    public MaterialReturnResponse cancel(UUID id, ServiceActionRequest request) {
        MaterialReturnDocument entity = findEntity(id);
        if (!List.of(MaterialReturnStatus.DRAFT.name(), MaterialReturnStatus.SUBMITTED.name(), MaterialReturnStatus.INSPECTION.name()).contains(entity.getStatus())) {
            throw new ConflictException("Posted or rejected return cannot be cancelled");
        }
        String oldSnapshot = snapshotSerializer.toJson(response(entity));
        entity.setStatus(MaterialReturnStatus.CANCELLED.name());
        MaterialReturnDocument saved = repository.save(entity);
        MaterialReturnResponse response = response(saved);
        auditService.log(ENTITY_NAME, id, "CANCEL", request == null ? saved.getCode() : request.comment(), oldSnapshot, response);
        return response;
    }

    private StockMovementDocumentResponse postReturnMovement(
        MaterialReturnDocument entity,
        List<StockMovementDocumentItemRequest> items
    ) {
        StockMovementDocumentCreateRequest request = new StockMovementDocumentCreateRequest();
        String returnCode = entity.getCode() != null ? entity.getCode() : "RET-" + entity.getId();
        request.setCode(returnCode + "-POST");
        request.setName(entity.getName() != null ? entity.getName() : "Material Return " + returnCode);
        request.setDescription("Material return: " + (entity.getReason() != null ? entity.getReason() : returnCode));
        request.setWarehouseId(entity.getWarehouseId());
        request.setMovementType(MovementType.RETURN);
        request.setMovementDate(LocalDate.now());
        request.setReferenceType("MATERIAL_RETURN");
        request.setReferenceId(entity.getId());
        request.setItems(items);
        var created = stockMovementDocumentService.create(request);
        stockMovementDocumentService.changeStatus(created.getId(), new StockMovementStatusChangeRequest(StockMovementStatus.PENDING));
        var approved = stockMovementDocumentService.changeStatus(created.getId(), new StockMovementStatusChangeRequest(StockMovementStatus.APPROVED));
        return stockMovementDocumentService.postManagedReturn(
            created.getId(),
            new StockMovementPostRequest(approved.getVersion()),
            "material-return-post-" + entity.getId() + "-" + created.getId()
        );
    }

    private void quarantine(UUID materialId, UUID warehouseId, UUID locationId, UUID batchId, BigDecimal quantity) {
        inventoryFreezeGuard.ensureStockKeyAllowed(CompanyContext.currentCompany(),warehouseId,locationId,null,materialId);
        StockBalance balance = stockBalanceRepository.findCompanyBalanceForUpdate(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            materialId,
            warehouseId,
            locationId == null,
            locationId,
            batchId == null,
            batchId
        ).orElseThrow(() -> new NotFoundException("Returned stock balance not found"));
        stockBalanceMutationService.moveAvailableToQuarantine(balance, quantity);
        stockBalanceRepository.save(balance);
    }

    private ReturnStockStatus defaultTargetStatus(MaterialReturnCondition condition) {
        return condition == MaterialReturnCondition.USABLE
            ? ReturnStockStatus.AVAILABLE
            : ReturnStockStatus.QUALITY_INSPECTION;
    }

    private MaterialReturnDocument findEntity(UUID id) {
        MaterialReturnDocument entity = repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException("Return document not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())
            || !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())
            || !roleDataScopeService.canAccessWarehouse(entity.getWarehouseId())) {
            throw new NotFoundException("Return document not found: " + id);
        }
        if (roleDataScopeService.isDepartmentWarehouseScoped()) {
            if (entity.getIssueRequestId() == null) {
                throw new NotFoundException("Return document not found: " + id);
            }
            issueRequestService.findEntity(entity.getIssueRequestId());
        }
        return entity;
    }

    private List<MaterialReturnLine> lines(UUID id) {
        return lineRepository.findByTenantIdAndReturnDocumentIdAndIsDeletedFalseOrderByCreatedAtAsc(
            TenantContext.currentTenant(), id
        );
    }

    private Map<UUID, MaterialReturnLine> byId(List<MaterialReturnLine> lines) {
        Map<UUID, MaterialReturnLine> result = new HashMap<>();
        lines.forEach(line -> result.put(line.getId(), line));
        return result;
    }

    private MaterialReturnResponse response(MaterialReturnDocument entity) {
        return mapper.toResponse(entity, lines(entity.getId()));
    }

    private Warehouse requireWarehouse(UUID id) {
        if (id == null) {
            throw new BadRequestException("warehouseId is required");
        }
        return warehouseRepository.findByIdAndIsDeletedFalse(id)
            .filter(entity -> TenantContext.currentTenant().equals(entity.getTenantId())
                && com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("Warehouse not found: " + id));
    }

    private Material requireMaterial(UUID id) {
        return materialRepository.findByIdAndIsDeletedFalse(id)
            .filter(entity -> TenantContext.currentTenant().equals(entity.getTenantId())
                && com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("Material not found: " + id));
    }

    private void validateReturnedUnitsBelongToIssueLine(MaterialReturnLine line, List<MaterialUnit> selectedUnits) {
        if (line.getIssueRequestLineId() == null) {
            // Historical return: validate serial numbers if specified
            if (line.getSerialNumber() != null && !line.getSerialNumber().isBlank()) {
                boolean anyMismatch = selectedUnits.stream()
                    .anyMatch(u -> {
                        String sn = u.getManufacturerSerialNumber() != null ? u.getManufacturerSerialNumber() : u.getInternalUnitNumber();
                        return sn != null && !line.getSerialNumber().equalsIgnoreCase(sn);
                    });
                if (anyMismatch) {
                    throw new BadRequestException("Serial number mismatch for historical return: " + line.getSerialNumber());
                }
            }
            return;
        }
        IssueRequestLine issueLine = issueLineRepository.findByIdForUpdate(line.getIssueRequestLineId())
            .orElseThrow(() -> new NotFoundException("Issue request line not found: " + line.getIssueRequestLineId()));
        List<UUID> issuedUnitIds = materialUnitEligibilityService.operationUnitIds(
            "IssueRequest",
            issueLine.getIssueRequestId(),
            MaterialUnitOperationType.ISSUE
        );
        if (!new LinkedHashSet<>(issuedUnitIds).containsAll(
            selectedUnits.stream().map(MaterialUnit::getId).toList()
        )) {
            throw new BadRequestException("MATERIAL_UNIT_NOT_RETURNABLE");
        }
    }

    private void requireStatus(MaterialReturnDocument entity, MaterialReturnStatus status) {
        if (!status.name().equals(entity.getStatus())) {
            throw new ConflictException("Return must be " + status + ", current status is " + entity.getStatus());
        }
    }

    private void requireExpectedVersion(MaterialReturnDocument entity, Long expectedVersion) {
        if (expectedVersion != null && !Objects.equals(expectedVersion, entity.getVersion())) {
            throw new ConflictException(
                "Material return version conflict: expected=" + expectedVersion + ", actual=" + entity.getVersion()
            );
        }
    }

    private Specification<MaterialReturnDocument> spec(MaterialReturnSearchRequest request) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("tenantId"), TenantContext.currentTenant()));
            predicates.add(cb.equal(root.get("companyId"), CompanyContext.currentCompany()));
            predicates.add(cb.isFalse(root.get("isDeleted")));
            var scope = roleDataScopeService.currentScope();
            if (scope.warehouseScoped()) {
                predicates.add(scope.warehouseIds().isEmpty() ? cb.disjunction() : root.get("warehouseId").in(scope.warehouseIds()));
            }
            if (roleDataScopeService.isDepartmentWarehouseScoped()) {
                predicates.add(issueRequestService.departmentScopePredicate(
                    root.join("issueRequest", jakarta.persistence.criteria.JoinType.INNER), cb));
            }
            if (request != null) {
                if (request.search() != null && !request.search().isBlank()) {
                    String pattern = "%" + request.search().trim().toLowerCase(Locale.ROOT) + "%";
                    predicates.add(cb.or(
                        cb.like(cb.lower(root.get("code")), pattern),
                        cb.like(cb.lower(root.get("name")), pattern),
                        cb.like(cb.lower(root.get("reason")), pattern)
                    ));
                }
                if (request.status() != null && !request.status().isEmpty()) {
                    predicates.add(root.get("status").in(request.status()));
                }
                if (request.issueRequestId() != null) {
                    predicates.add(cb.equal(root.get("issueRequestId"), request.issueRequestId()));
                }
                if (request.warehouseId() != null) {
                    predicates.add(cb.equal(root.get("warehouseId"), request.warehouseId()));
                }
                if (request.dateFrom() != null) {
                    predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), request.dateFrom().atStartOfDay()));
                }
                if (request.dateTo() != null) {
                    predicates.add(cb.lessThan(root.get("createdAt"), request.dateTo().plusDays(1).atStartOfDay()));
                }
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private record ReturnStockKey(UUID storageLocationId, UUID stockBatchId) {}

    private record ReturnPostingBinding(MaterialReturnLine line, List<MaterialUnit> units, boolean serialized) {}
}
