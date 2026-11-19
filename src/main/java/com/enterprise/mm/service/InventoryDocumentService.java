package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.enterprise.mm.payload.InventoryDocumentResponse;
import com.enterprise.mm.payload.InventoryDocumentUpdateRequest;
import com.enterprise.mm.payload.InventoryDocumentCreateRequest;
import com.enterprise.mm.payload.InventoryDocumentSearchRequest;
import com.enterprise.mm.payload.InventoryItemResponse;
import com.enterprise.mm.payload.InventoryCommissionMemberResponse;
import com.enterprise.mm.payload.AttachmentDto;
import com.enterprise.mm.payload.WarehouseResponse;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.entity.InventoryItem;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.enums.InventoryLineStatus;
import com.enterprise.mm.enums.InventoryStatus;
import com.enterprise.mm.enums.InventoryType;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.InventoryDocumentMapper;
import com.enterprise.mm.mapper.InventoryItemMapper;
import com.enterprise.mm.mapper.WarehouseMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.InventoryDocument;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.MaterialGroup;
import com.enterprise.mm.entity.Commission;
import com.enterprise.mm.enums.CommissionType;
import com.enterprise.mm.repository.CommissionMemberRepository;
import com.enterprise.mm.repository.CommissionRepository;
import com.enterprise.mm.repository.InventoryDocumentRepository;
import com.enterprise.mm.repository.InventoryDocumentMaterialGroupRepository;
import com.enterprise.mm.repository.InventoryDocumentMaterialRepository;
import com.enterprise.mm.repository.InventoryItemRepository;
import com.enterprise.mm.repository.MaterialGroupRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import com.enterprise.mm.service.approval.InventoryDocumentApprovalAdapter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.enterprise.mm.entity.InventoryDocumentMaterial;
import com.enterprise.mm.entity.InventoryDocumentMaterialGroup;

@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryDocumentService {
    private final InventoryDocumentRepository repository;
    private final InventoryDocumentMapper mapper;
    private final InventoryItemMapper itemMapper;
    private final WarehouseMapper warehouseMapper;
    private final String entityName = "InventoryDocument";
    private final AuditService auditService;
    private final RoleDataScopeService roleDataScopeService;
    private final StockBalanceRepository stockBalanceRepository;
    private final InventoryItemRepository itemRepository;
    private final CommissionRepository commissionRepository;
    private final CommissionMemberRepository commissionMemberRepository;
    private final InventoryCommissionService inventoryCommissionService;
    private final InventoryDocumentMaterialRepository documentMaterialRepository;
    private final InventoryDocumentMaterialGroupRepository documentMaterialGroupRepository;
    private final MaterialRepository materialRepository;
    private final MaterialGroupRepository materialGroupRepository;
    private final WarehouseRepository warehouseRepository;
    private final AttachmentService attachmentService;
    private final ApprovalEngineService approvalEngineService;
    private final MaterialCatalogGuard materialCatalogGuard;
    private final com.enterprise.mm.service.inventory.InventoryAccessService inventoryAccess;
    private final InventoryScopeService inventoryScope;
    private final jakarta.persistence.EntityManager entityManager;
    private final InventoryAiDraftService inventoryAiDraftService;
    private final IdempotencyService idempotencyService;
    private final InventoryStockAsOfQuery stockAsOfQuery;

    @Transactional
    public InventoryDocumentResponse create(InventoryDocumentCreateRequest dto, String idempotencyKey) {
        if (dto.getSourceAiDraftId() != null) {
            inventoryAiDraftService.requireOwner(dto.getSourceAiDraftId(), inventoryAccess.username());
            if (idempotencyKey == null || idempotencyKey.isBlank()) {
                throw new BadRequestException("Idempotency-Key is required when creating inventory from uploaded documents");
            }
        }
        return idempotencyService.execute("INVENTORY_CREATE", dto.getSourceAiDraftId(), idempotencyKey,
            dto, InventoryDocumentResponse.class, () -> create(dto));
    }
    @Transactional(readOnly = true)
    public Page<InventoryDocumentResponse> search(InventoryDocumentSearchRequest request, Pageable pageable) {
        SearchCriteria filter = roleDataScopeService.applyWarehouseScope(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        return repository.search(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            filter.includeDeleted(),
            filter.normalizedSearch(),
            filter.fromDateTime(),
            filter.toDateTime(),
            filter.status(),
            !filter.hasStatus(),
            filter.hasWarehouseIds() ? null : filter.warehouseId(),
            filter.warehouseIds(), !filter.hasWarehouseIds(),
            inventoryAccess.readAll(), inventoryAccess.hasWarehouseReadAccess(), inventoryAccess.currentUserId(),
            pageable
        ).map(document -> toResponse(document, false));
    }
    @Transactional(readOnly = true)
    public InventoryDocumentResponse get(UUID id) {
        return toResponse(findEntity(id));
    }

    @Transactional(readOnly = true)
    public List<InventoryItemResponse> items(UUID id) {
        return items(id, false);
    }

    @Transactional(readOnly = true)
    public List<InventoryItemResponse> items(UUID id, boolean blindCount) {
        InventoryDocument document = findEntity(id);
        List<InventoryItemResponse> items = inventoryItems(document.getId());
        boolean visible = !blindCount && inventoryAccess.canSeeSystemQuantity(document);
        return items.stream().map(row -> com.enterprise.mm.service.inventory.InventoryReadPolicy.project(row, visible)).toList();
    }
    @Transactional
    public InventoryDocumentResponse create(InventoryDocumentCreateRequest dto) {
        InventoryDocument entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        InventoryDocument saved = repository.save(entity);
        syncSelectedMaterials(saved, dto.getMaterialIds());
        syncSelectedMaterialGroups(saved, dto.getMaterialGroupIds());
        if (dto.getSourceAiDraftId() != null) inventoryAiDraftService.bindToInventory(dto.getSourceAiDraftId(), saved.getId());
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return toResponse(saved);
    }
    @Transactional
    public InventoryDocumentResponse update(UUID id, InventoryDocumentUpdateRequest dto) {
        InventoryDocument entity = findEntity(id);
        inventoryAccess.requirePlan(entity);
        String oldSnapshot = auditService.snapshot(entity);
        requireEditable(entity);
        InventoryStatus previousStatus = parseStatus(entity.getStatus());
        mapper.updateEntity(entity, dto);
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus(previousStatus.name());
        }
        validateDirectStatusChange(previousStatus, entity.getStatus());
        beforeUpdate(entity, dto);
        InventoryDocument saved = repository.save(entity);
        if (dto.getMaterialIds() != null) {
            syncSelectedMaterials(saved, dto.getMaterialIds());
        }
        if (dto.getMaterialGroupIds() != null) {
            syncSelectedMaterialGroups(saved, dto.getMaterialGroupIds());
        }
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return toResponse(saved);
    }

    @Transactional
    public InventoryDocumentResponse start(UUID id) {
        long startedAt = System.nanoTime();
        log.info("Inventory start {}: requested", id);
        InventoryDocument document = lockDocument(id);
        inventoryAccess.requirePlan(document);
        inventoryScope.validate(document);inventoryScope.lock(document);
        if(document.getSnapshotAt()!=null && "IN_PROGRESS".equals(document.getStatus()))return toResponse(document);
        InventoryStatus status = InventoryStatus.from(document.getStatus());
        if (status != InventoryStatus.DRAFT && status != InventoryStatus.PLANNED) {
            throw new BusinessException("Inventory can be started only from DRAFT or PLANNED status");
        }
        if (!document.isFreezeOperations()) {
            throw new BusinessException("Enable the stock freeze before starting inventory so approved adjustments can be posted safely");
        }
        requireCommissionMembers(document);
        ensureNoActiveInventoryConflict(document);
        log.info("Inventory start {}: scope validated in {} ms", id, elapsedMillis(startedAt));
        if (document.getSnapshotAt() == null) {
            createSnapshot(document);
        }
        log.info("Inventory start {}: snapshot prepared in {} ms", id, elapsedMillis(startedAt));
        document.setStatus(InventoryStatus.IN_PROGRESS.name());
        document.setActualStartDate(LocalDate.now());
        refreshTotals(document);
        InventoryDocument saved = repository.save(document);
        auditService.log(entityName, saved.getId(), "START", saved.getCode(), null, null);
        log.info("Inventory start {}: completed in {} ms", id, elapsedMillis(startedAt));
        return toResponse(saved);
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    @Transactional
    public InventoryDocumentResponse completeCounting(UUID id) {
        InventoryDocument document = lockDocument(id);
        inventoryAccess.requireRead(document);
        if (!inventoryAccess.canCount(document) && !inventoryAccess.canCommissionReview(document)) throw new org.springframework.security.access.AccessDeniedException("Counting or commission review authority is required");
        if (document.getUnidentifiedStock().stream().anyMatch(o -> o.resolvedLineId() == null)) throw new BusinessException("Identify found stock through its material-master request before finishing counting");
        InventoryStatus status = InventoryStatus.from(document.getStatus());
        if (status != InventoryStatus.IN_PROGRESS && status != InventoryStatus.DISCREPANCY_REVIEW) {
            throw new BusinessException("Inventory counting can be completed only while inventory is in progress");}
        refreshTotals(document);
        if (document.getCountedItems() < document.getTotalItems()) {
            throw new BusinessException("Inventory counting is incomplete. "
                            + (document.getTotalItems() - document.getCountedItems())
                            + " line(s) are not counted");}
        document.setStatus(
                document.getDiscrepancyItems() > 0
                        && unresolvedDiscrepancyItems(document.getId()) > 0
                        ? InventoryStatus.DISCREPANCY_REVIEW.name()
                        : InventoryStatus.COUNTING_COMPLETED.name()
        );

        InventoryDocument saved = repository.save(document);
        auditService.log(
                entityName,
                saved.getId(),
                "COMPLETE_COUNTING",
                saved.getCode(),
                null,
                null);
        return toResponse(saved);
    }

    @Transactional
    public InventoryDocumentResponse submitForApproval(UUID id) {
        InventoryDocument document = findEntity(id);
        inventoryAccess.requireReview(document);
        approvalEngineService.start(InventoryDocumentApprovalAdapter.MODULE_KEY, id);
        return toResponse(findEntity(document.getId()));
    }

    @Transactional
    public InventoryDocumentResponse close(UUID id) {
        InventoryDocument document = findEntity(id);
        inventoryAccess.requirePlan(document);
        InventoryStatus status = InventoryStatus.from(document.getStatus());
        if (status != InventoryStatus.ADJUSTMENT_POSTED) {
            throw new BusinessException("Inventory can be closed only after adjustment posting");
        }
        document.setStatus(InventoryStatus.CLOSED.name());
        document.setActualEndDate(LocalDate.now());
        InventoryDocument saved = repository.save(document);
        auditService.log(entityName, saved.getId(), "CLOSE", saved.getCode(), null, null);
        return toResponse(saved);
    }

    @Transactional
    public InventoryDocumentResponse cancel(UUID id) {
        InventoryDocument document = lockDocument(id);
        inventoryAccess.requirePlan(document);
        inventoryScope.lock(document);
        InventoryStatus status = InventoryStatus.from(document.getStatus());
        if (!List.of(
            InventoryStatus.DRAFT,
            InventoryStatus.PLANNED,
            InventoryStatus.IN_PROGRESS,
            InventoryStatus.COUNTING_COMPLETED,
            InventoryStatus.DISCREPANCY_REVIEW,
            InventoryStatus.REJECTED
        ).contains(status)) {
            throw new BusinessException("Inventory cannot be cancelled after approval submission, approval, adjustment posting or closing");
        }
        document.setStatus(InventoryStatus.CANCELLED.name());
        InventoryDocument saved = repository.save(document);
        auditService.log(entityName, saved.getId(), "CANCEL", saved.getCode(), null, null);
        return toResponse(saved);
    }

    @Transactional
    public AttachmentDto generateDocument(UUID id, String documentType) {
        InventoryDocument document = findEntity(id);
        inventoryAccess.requireVisibleExport(document);
        refreshTotals(document);
        String normalizedType = normalizeDocumentType(documentType);
        String fileName = document.getCode() + "-" + normalizedType.toLowerCase(Locale.ROOT) + ".txt";
        byte[] content = buildDocumentText(document, normalizedType).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        AttachmentDto attachment = attachmentService.saveBytes(
            "INVENTORY_DOCUMENT",
            document.getId(),
            normalizedType,
            fileName,
            "text/plain",
            content
        );
        auditService.log(entityName, document.getId(), "GENERATE_DOCUMENT", normalizedType, null, null);
        return attachment;
    }
    @Transactional
    public void softDelete(UUID id) {
        InventoryDocument entity = findEntity(id);
        inventoryAccess.requirePlan(entity);
        requireDeletable(entity);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public InventoryDocumentResponse restore(UUID id) {
        InventoryDocument entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        inventoryAccess.requirePlan(entity);
        requireDeletable(entity);
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        InventoryDocument saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return toResponse(saved);
    }
    protected InventoryDocument findEntity(UUID id) {
        InventoryDocument entity = repository.findByIdAndIsDeletedFalse(id)
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
        inventoryAccess.requireRead(entity);
        return entity;
    }
    protected void beforeCreate(InventoryDocument entity, InventoryDocumentCreateRequest dto) {
        validatePlannedDates(entity);
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus(InventoryStatus.DRAFT.name());
        } else {
            InventoryStatus status = parseStatus(entity.getStatus());
            if (status != InventoryStatus.DRAFT && status != InventoryStatus.PLANNED) {
                throw new BadRequestException("Inventory can be created only in DRAFT or PLANNED status");
            }
            entity.setStatus(status.name());
        }
        entity.setInventoryType(normalizeType(entity.getInventoryType()));
        if (dto.getFreezeOperations() == null) {
            entity.setFreezeOperations(true);
        }
        applyCreateCompany(entity);
        normalizeWarehouseScope(entity);
        inventoryScope.validate(entity);
        if (entity.getCommissionId() != null) {
            requireInventoryCommission(entity.getCommissionId());
        }
        if (entity.getCode() == null || entity.getCode().isBlank()) {
            entity.setCode(entityName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_") + "-" + UUID.randomUUID().toString().substring(0, 8));
        }
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }
    protected void beforeUpdate(InventoryDocument entity, InventoryDocumentUpdateRequest dto) {
        validatePlannedDates(entity);
        entity.setStatus(parseStatus(entity.getStatus()).name());
        entity.setInventoryType(normalizeType(entity.getInventoryType()));
        normalizeWarehouseScope(entity);
        inventoryScope.validate(entity);
        if (entity.getCommissionId() != null) {
            requireInventoryCommission(entity.getCommissionId());
        }
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }
    protected boolean companyScoped() {
        return true;
    }

    private void validatePlannedDates(InventoryDocument document) {
        if (document.getStockAsOfDate() != null && document.getStockAsOfDate().isAfter(LocalDate.now()))
            throw new BadRequestException("Stock as-of date cannot be in the future");
        if (document.getPlannedStartDate() != null && document.getPlannedEndDate() != null
            && document.getPlannedEndDate().isBefore(document.getPlannedStartDate())) {
            throw new BadRequestException("Planned inventory end date must be on or after the start date");
        }
    }

    private void applyCreateCompany(InventoryDocument entity) {
        if (entity.getCompanyId() == null) {
            entity.setCompanyId(CompanyContext.currentCompany());
            return;
        }
        if (!com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new BadRequestException("Company is not accessible: " + entity.getCompanyId());
        }
    }

    private void normalizeWarehouseScope(InventoryDocument entity) {
        if (entity.getScopeMode() != null) {
            inventoryScope.prepareScope(entity);
            return;
        }
        inventoryScope.prepareScope(entity);
        if (entity.isGlobalWarehouse()) {
            entity.setWarehouseId(null);
            return;
        }
        if (entity.getWarehouseId() == null) {
            throw new BadRequestException("warehouseId is required when globalWarehouse is false");
        }
    }

    private void createSnapshot(InventoryDocument document) {
        long startedAt = System.nanoTime();
        var historical = document.getStockAsOfDate() != null && document.getStockAsOfDate().isBefore(LocalDate.now())
            ? stockAsOfQuery.historical(TenantContext.currentTenant(), document.getCompanyId(),
                document.isGlobalWarehouse() ? List.of() : inventoryScope.warehouseIds(document), document.getStockAsOfDate())
            : java.util.Map.<UUID, InventoryStockAsOfQuery.HistoricalBalance>of();
        Set<UUID> selectedMaterialIds = selectedMaterialIds(document.getId());
        Set<UUID> selectedMaterialGroupIds = selectedMaterialGroupIds(document.getId());
        Set<UUID> materialIdsWithBalanceLines = new LinkedHashSet<>();
        Set<String> materialWarehouseKeys = new LinkedHashSet<>();
        List<StockBalance> candidates = stockBalancesForScope(document, selectedMaterialIds, selectedMaterialGroupIds, !historical.isEmpty());
        log.info("Inventory snapshot {}: loaded {} scoped balances in {} ms", document.getId(), candidates.size(), elapsedMillis(startedAt));
        Map<UUID, Boolean> eligibleMaterials = new HashMap<>();
        List<StockBalance> balances = candidates
            .stream()
            .filter(balance -> com.enterprise.mm.security.AccessScope.canAccessCompany(balance.getCompanyId()))
            .filter(balance -> balance.getQuantity() != null && balance.getQuantity().signum() > 0
                || historical.containsKey(balance.getId()) && historical.get(balance.getId()).quantity().signum() > 0)
            .filter(balance -> eligibleMaterials.computeIfAbsent(balance.getMaterialId(), this::isTmbSnapshotMaterial))
            .toList();
        for (StockBalance balance : balances) {
            InventoryItem line = snapshotLine(document, balance);
            if (document.getStockAsOfDate() != null) {
                var basis = historical.get(balance.getId());
                line.setAsOfQuantity(basis == null ? defaultDecimal(balance.getQuantity()) : basis.quantity());
                line.setAsOfValue(basis == null ? defaultDecimal(balance.getTotalValue()) : basis.value());
                if (line.getUnitCost().signum() == 0 && line.getAsOfQuantity().signum() > 0)
                    line.setUnitCost(line.getAsOfValue().divide(line.getAsOfQuantity(), 4, RoundingMode.HALF_UP));
            }
            itemRepository.save(line);
            materialIdsWithBalanceLines.add(balance.getMaterialId());
            materialWarehouseKeys.add(balance.getMaterialId() + ":" + balance.getWarehouseId());
        }
        for (UUID materialId : selectedMaterialIds) {
            if (document.getScopeMode() != null) {
                for (UUID warehouseId : inventoryScope.warehouseIds(document)) {
                    if (!materialWarehouseKeys.contains(materialId + ":" + warehouseId))
                        itemRepository.save(zeroSnapshotLine(document, materialId, warehouseId));
                }
            } else if (!materialIdsWithBalanceLines.contains(materialId)) {
                itemRepository.save(zeroSnapshotLine(document, materialId));
            }
        }
        document.setSnapshotAt(LocalDateTime.now());
        log.info("Inventory snapshot {}: saved {} balance lines in {} ms", document.getId(), balances.size(), elapsedMillis(startedAt));
    }

    private List<StockBalance> stockBalancesForScope(InventoryDocument document, Set<UUID> materialIds,
        Set<UUID> groupIds, boolean includeZeroBalances) {
        boolean allWarehouses = document.isGlobalWarehouse();
        List<UUID> warehouseIds = allWarehouses ? List.of(new UUID(0, 0)) : inventoryScope.warehouseIds(document);
        // Hibernate needs a nonempty collection for IN even when that clause is disabled.
        List<UUID> selectedIds = materialIds.isEmpty() ? List.of(new UUID(0, 0)) : List.copyOf(materialIds);
        List<UUID> selectedGroups = groupIds.isEmpty() ? List.of(new UUID(0, 0)) : List.copyOf(groupIds);
        return stockBalanceRepository.findInventorySnapshotBalances(TenantContext.currentTenant(), document.getCompanyId(),
            allWarehouses, warehouseIds, document.getStorageLocationId(), document.getStorageBinId(),
            materialIds.isEmpty() && groupIds.isEmpty(), selectedIds, selectedGroups, includeZeroBalances);
    }

    private void refreshTotals(InventoryDocument document) {
        document.setTotalItems(
                (int) itemRepository.countByTenantIdAndInventoryDocumentIdAndIsDeletedFalse(
                        TenantContext.currentTenant(),
                        document.getId()));
        document.setCountedItems(
                (int) itemRepository.countByTenantIdAndInventoryDocumentIdAndCountedQuantityIsNotNullAndIsDeletedFalse(
                        TenantContext.currentTenant(),
                        document.getId()));
        document.setDiscrepancyItems(
                (int) itemRepository.countByTenantIdAndInventoryDocumentIdAndDifferenceQuantityNotAndIsDeletedFalse(
                        TenantContext.currentTenant(),
                        document.getId(),
                        BigDecimal.ZERO));
    }

    private long unresolvedDiscrepancyItems(UUID inventoryDocumentId) {
        return itemRepository.countByTenantIdAndInventoryDocumentIdAndLineStatusInAndIsDeletedFalse(
            TenantContext.currentTenant(),
            inventoryDocumentId,
            List.of(
                InventoryLineStatus.DISCREPANCY,
                InventoryLineStatus.EXPLANATION_REQUIRED,
                InventoryLineStatus.NEEDS_RECOUNT
            )
        );
    }

    private InventoryDocument lockDocument(UUID id) {
        var document = findEntity(id);
        if (entityManager.getLockMode(document) != jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
            entityManager.refresh(document, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        inventoryAccess.requireRead(document);
        return document;
    }
    private void ensureNoActiveInventoryConflict(InventoryDocument document) {inventoryScope.requireNoOverlap(document);}

    private void requireEditable(InventoryDocument document) {
        if (!InventoryStatus.from(document.getStatus()).editable()) {
            throw new BusinessException("Inventory can be edited only in DRAFT or PLANNED status");
        }
    }

    private void requireDeletable(InventoryDocument document) {
        if (!InventoryStatus.from(document.getStatus()).deletable()) {
            throw new BusinessException("Inventory can be deleted only before counting starts or after controlled cancellation");
        }
    }

    private InventoryDocumentResponse toResponse(InventoryDocument document) {
        return toResponse(document, true);
    }

    private InventoryDocumentResponse toResponse(InventoryDocument document, boolean includeItems) {
        InventoryDocumentResponse response = mapper.toResponse(document);
        response.setMaterialIds(List.copyOf(selectedMaterialIds(document.getId())));
        response.setMaterialGroupIds(List.copyOf(selectedMaterialGroupIds(document.getId())));
        response.setCommissionMemberIds(commissionMemberIds(document.getCommissionId()));
        response.setCommissionMembers(commissionMembers(document.getCommissionId()));
        response.setWarehouses(warehouses(document));
        response.setWarehouseIds(document.getScopeMode() == null
            ? response.getWarehouses().stream().map(WarehouseResponse::getId).toList()
            : inventoryScope.warehouseIds(document));
        if (includeItems) {
            response.setItems(inventoryItems(document.getId()));
        }
        inventoryAccess.requireRead(document);
        boolean visible = inventoryAccess.canSeeSystemQuantity(document);
        if (response.getItems() != null) response.setItems(response.getItems().stream().map(row -> com.enterprise.mm.service.inventory.InventoryReadPolicy.project(row, visible)).toList());
        if (!visible) {
            response.setCorrectionReason(null); response.setDiscrepancyItems(null); response.setSubmittedCountRevision(null); response.setApprovedCountRevision(null);
            response.setCommissionApprovedRevision(null); response.setCommissionApprovedBy(null); response.setCommissionApprovedAt(null);
            response.setFinanceApprovedRevision(null); response.setFinanceApprovedBy(null); response.setFinanceApprovedAt(null); response.setApprovalInstanceId(null);
        }
        return response;
    }

    private List<WarehouseResponse> warehouses(InventoryDocument document) {
        if (document.getScopeMode() != null) {
            return warehouseRepository.findByTenantIdAndCompanyIdAndIdIn(
                    TenantContext.currentTenant(), document.getCompanyId(), document.getResolvedWarehouseIds())
                .stream().map(warehouseMapper::toResponse).toList();
        }
        if (document.isGlobalWarehouse()) {
            return warehouseRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(
                    TenantContext.currentTenant(),
                    document.getCompanyId()
                )
                .stream()
                .filter(warehouse -> com.enterprise.mm.security.AccessScope.canAccessCompany(warehouse.getCompanyId()))
                .map(warehouseMapper::toResponse)
                .toList();
        }
        if (document.getWarehouseId() == null) {
            return List.of();
        }
        return warehouseRepository.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), document.getWarehouseId())
            .filter(warehouse -> com.enterprise.mm.security.AccessScope.canAccessCompany(warehouse.getCompanyId()))
            .map(warehouse -> List.of(warehouseMapper.toResponse(warehouse)))
            .orElseGet(List::of);
    }

    private List<InventoryItemResponse> inventoryItems(UUID inventoryDocumentId) {
        return itemRepository.findByTenantIdAndInventoryDocumentIdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                inventoryDocumentId
            )
            .stream()
            .map(itemMapper::toResponse)
            .toList();
    }

    private Set<UUID> selectedMaterialIds(UUID inventoryDocumentId) {
        return documentMaterialRepository.findByTenantIdAndInventoryDocumentIdAndIsDeletedFalseOrderByCreatedAtAsc(
                TenantContext.currentTenant(),
                inventoryDocumentId
            )
            .stream()
            .map(InventoryDocumentMaterial::getMaterialId)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<UUID> selectedMaterialGroupIds(UUID inventoryDocumentId) {
        return documentMaterialGroupRepository.findByTenantIdAndInventoryDocumentIdAndIsDeletedFalseOrderByCreatedAtAsc(
                TenantContext.currentTenant(),
                inventoryDocumentId
            )
            .stream()
            .map(InventoryDocumentMaterialGroup::getMaterialGroupId)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private List<UUID> commissionMemberIds(UUID commissionId) {
        return commissionMembers(commissionId).stream()
            .map(InventoryCommissionMemberResponse::getUserId)
            .toList();
    }

    private List<InventoryCommissionMemberResponse> commissionMembers(UUID commissionId) {
        if (commissionId == null) {
            return List.of();
        }
        return inventoryCommissionService.get(commissionId);
    }

    private void requireCommissionMembers(InventoryDocument document) {
        Commission commission = requireInventoryCommission(document.getCommissionId());
        long count = commissionMemberRepository.countByTenantIdAndCommissionIdAndActiveTrueAndIsDeletedFalse(
            TenantContext.currentTenant(),
            commission.getId()
        );
        if (count == 0) {
            throw new BusinessException("Inventory commission members are required before inventory start");
        }
    }

    private Commission requireInventoryCommission(UUID commissionId) {
        if (commissionId == null) {
            throw new BadRequestException("commissionId is required before inventory start");
        }
        Commission commission = commissionRepository.findByIdAndIsDeletedFalse(commissionId)
            .filter(item -> TenantContext.currentTenant().equals(item.getTenantId()))
            .filter(item -> com.enterprise.mm.security.AccessScope.canAccessCompany(item.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("Commission not found: " + commissionId));
        if (commission.getType() != CommissionType.INVENTORY) {
            throw new BadRequestException("Only INVENTORY commission can be assigned to inventory");
        }
        if (!commission.isActive()) {
            throw new BadRequestException("Commission is not active: " + commissionId);
        }
        return commission;
    }

    private void syncSelectedMaterials(InventoryDocument document, List<UUID> materialIds) {
        List<InventoryDocumentMaterial> existing = documentMaterialRepository.findByTenantIdAndInventoryDocumentIdAndIsDeletedFalseOrderByCreatedAtAsc(
            TenantContext.currentTenant(),
            document.getId()
        );
        existing.forEach(InventoryDocumentMaterial::softDelete);
        documentMaterialRepository.saveAll(existing);

        if (materialIds == null || materialIds.isEmpty()) {
            return;
        }

        List<InventoryDocumentMaterial> selections = new LinkedHashSet<>(materialIds).stream()
            .map(materialId -> createMaterialSelection(document, resolveSelectedMaterial(materialId)))
            .toList();
        documentMaterialRepository.saveAll(selections);
    }

    private void syncSelectedMaterialGroups(InventoryDocument document, List<UUID> materialGroupIds) {
        List<InventoryDocumentMaterialGroup> existing = documentMaterialGroupRepository.findByTenantIdAndInventoryDocumentIdAndIsDeletedFalseOrderByCreatedAtAsc(
            TenantContext.currentTenant(),
            document.getId()
        );
        existing.forEach(InventoryDocumentMaterialGroup::softDelete);
        documentMaterialGroupRepository.saveAll(existing);

        if (materialGroupIds == null || materialGroupIds.isEmpty()) {
            return;
        }

        List<InventoryDocumentMaterialGroup> selections = new LinkedHashSet<>(materialGroupIds).stream()
            .map(materialGroupId -> createMaterialGroupSelection(document, resolveSelectedMaterialGroup(materialGroupId)))
            .toList();
        documentMaterialGroupRepository.saveAll(selections);
    }

    private InventoryDocumentMaterial createMaterialSelection(InventoryDocument document, Material material) {
        InventoryDocumentMaterial selection = new InventoryDocumentMaterial();
        selection.setCompanyId(document.getCompanyId());
        selection.setInventoryDocumentId(document.getId());
        selection.setMaterialId(material.getId());
        selection.setStatus("ACTIVE");
        selection.setCode("INV-MAT-" + UUID.randomUUID().toString().substring(0, 8));
        selection.setName(material.getName());
        return selection;
    }

    private InventoryDocumentMaterialGroup createMaterialGroupSelection(InventoryDocument document, MaterialGroup materialGroup) {
        InventoryDocumentMaterialGroup selection = new InventoryDocumentMaterialGroup();
        selection.setCompanyId(document.getCompanyId());
        selection.setInventoryDocumentId(document.getId());
        selection.setMaterialGroupId(materialGroup.getId());
        selection.setStatus("ACTIVE");
        selection.setCode("INV-MG-" + UUID.randomUUID().toString().substring(0, 8));
        selection.setName(materialGroup.getName());
        return selection;
    }

    private Material resolveSelectedMaterial(UUID materialId) {
        if (materialId == null) {
            throw new BadRequestException("materialIds cannot contain null values");
        }
        Material material = materialRepository.findByIdAndIsDeletedFalse(materialId)
            .filter(candidate -> TenantContext.currentTenant().equals(candidate.getTenantId()))
            .filter(candidate -> com.enterprise.mm.security.AccessScope.canAccessCompany(candidate.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("Material not found: " + materialId));
        materialCatalogGuard.requireActiveTmbMaterial(material.getId(), "materialIds");
        return material;
    }

    private MaterialGroup resolveSelectedMaterialGroup(UUID materialGroupId) {
        if (materialGroupId == null) {
            throw new BadRequestException("materialGroupIds cannot contain null values");
        }
        return materialGroupRepository.findByIdAndIsDeletedFalse(materialGroupId)
            .filter(materialGroup -> TenantContext.currentTenant().equals(materialGroup.getTenantId()))
            .filter(materialGroup -> com.enterprise.mm.security.AccessScope.canAccessCompany(materialGroup.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("MaterialGroup not found: " + materialGroupId));
    }

    private InventoryItem snapshotLine(InventoryDocument document, StockBalance balance) {
        InventoryItem item = baseSnapshotLine(document, balance.getMaterialId());
        item.setWarehouseId(balance.getWarehouseId());
        item.setStorageLocationId(balance.getStorageLocationId());
        item.setStorageBinId(balance.getStorageBinId());
        item.setStockBatchId(balance.getStockBatchId());
        item.setSystemQuantity(defaultDecimal(balance.getQuantity()));
        item.setUnitCost(unitCost(balance));
        item.setName(balance.getMaterial() == null ? "Inventory count line" : balance.getMaterial().getName());
        return item;
    }

    private InventoryItem zeroSnapshotLine(InventoryDocument document, UUID materialId) {
        return zeroSnapshotLine(document, materialId, document.getWarehouseId());
    }

    private InventoryItem zeroSnapshotLine(InventoryDocument document, UUID materialId, UUID warehouseId) {
        Material material = resolveSelectedMaterial(materialId);
        InventoryItem item = baseSnapshotLine(document, materialId);
        item.setWarehouseId(warehouseId);
        item.setStorageLocationId(document.getStorageLocationId());item.setStorageBinId(document.getStorageBinId());
        item.setSystemQuantity(BigDecimal.ZERO);
        if (document.getStockAsOfDate() != null) {
            item.setAsOfQuantity(BigDecimal.ZERO);
            item.setAsOfValue(BigDecimal.ZERO);
        }
        item.setUnitCost(BigDecimal.ZERO);
        item.setName(material.getName());
        return item;
    }

    private InventoryItem baseSnapshotLine(InventoryDocument document, UUID materialId) {
        InventoryItem item = new InventoryItem();
        item.setCompanyId(document.getCompanyId());
        item.setInventoryDocumentId(document.getId());
        item.setMaterialId(materialId);
        item.setWarehouseId(document.getWarehouseId());
        item.setCountedQuantity(null);
        item.setDifferenceQuantity(BigDecimal.ZERO);
        item.setDifferenceAmount(BigDecimal.ZERO);
        item.setLineStatus(InventoryLineStatus.NOT_COUNTED);
        item.setStatus(InventoryLineStatus.NOT_COUNTED.name());
        item.setCode("INV-LINE-" + UUID.randomUUID().toString().substring(0, 8));
        item.setName("Inventory count line");
        return item;
    }

    private boolean isTmbSnapshotMaterial(UUID materialId) {
        try {
            materialCatalogGuard.requireActiveTmbMaterial(materialId, "materialId");
            return true;
        } catch (BusinessException | NotFoundException ex) {
            return false;
        }
    }

    private String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            return InventoryType.PLANNED.name();
        }
        try {
            return InventoryType.valueOf(type.trim().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Invalid inventory type: " + type);
        }
    }

    private InventoryStatus parseStatus(String status) {
        try {
            return InventoryStatus.from(status);
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Invalid inventory status: " + status);
        }
    }

    private void validateDirectStatusChange(InventoryStatus previousStatus, String requestedStatus) {
        InventoryStatus nextStatus = parseStatus(requestedStatus);
        if (nextStatus == previousStatus || nextStatus == InventoryStatus.DRAFT || nextStatus == InventoryStatus.PLANNED) {
            return;
        }
        throw new BadRequestException("Inventory lifecycle status must be changed through action endpoints");
    }

    private BigDecimal unitCost(StockBalance balance) {
        BigDecimal quantity = defaultDecimal(balance.getQuantity());
        if (quantity.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return defaultDecimal(balance.getTotalValue()).divide(quantity, 4, RoundingMode.HALF_UP);
    }

    private BigDecimal defaultDecimal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String normalizeDocumentType(String documentType) {
        if (documentType == null || documentType.isBlank()) {
            return "SUMMARY";
        }
        String normalized = documentType.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return switch (normalized) {
            case "ORDER", "COUNT_SHEET", "SHORTAGE_ACT", "SURPLUS_ACT", "ADJUSTMENT_ACT", "SUMMARY" -> normalized;
            default -> throw new BadRequestException("Unsupported inventory document type: " + documentType);
        };
    }

    private String buildDocumentText(InventoryDocument document, String documentType) {
        List<InventoryItem> items = itemRepository.findByTenantIdAndInventoryDocumentIdAndIsDeletedFalse(TenantContext.currentTenant(), document.getId());
        StringBuilder builder = new StringBuilder();
        builder.append("Inventory document: ").append(document.getCode()).append('\n');
        builder.append("Document type: ").append(documentType).append('\n');
        builder.append("Name: ").append(document.getName()).append('\n');
        builder.append("Status: ").append(document.getStatus()).append('\n');
        if (document.getScopeMode() == null) builder.append("Warehouse ID: ").append(document.getWarehouseId()).append('\n');
        else {
            builder.append("Scope mode: ").append(document.getScopeMode()).append('\n');
            builder.append("Selected department IDs: ").append(document.getDepartmentIds()).append('\n');
            builder.append("Effective warehouse IDs: ").append(inventoryScope.warehouseIds(document)).append('\n');
        }
        builder.append("Stock as-of date: ").append(document.getStockAsOfDate()).append('\n');
        builder.append("Snapshot captured at: ").append(document.getSnapshotAt()).append('\n');
        builder.append("Planned start date: ").append(document.getPlannedStartDate()).append('\n');
        builder.append("Planned end date: ").append(document.getPlannedEndDate()).append('\n');
        builder.append("Total items: ").append(document.getTotalItems()).append('\n');
        builder.append("Counted items: ").append(document.getCountedItems()).append('\n');
        builder.append("Discrepancy items: ").append(document.getDiscrepancyItems()).append("\n\n");
        builder.append("Lines\n");
        for (InventoryItem item : items) {
            builder.append("- ")
                .append(item.getCode()).append(" | material=").append(item.getMaterialId())
                .append(" | system=").append(defaultDecimal(item.getSystemQuantity()))
                .append(" | as-of=").append(item.getAsOfQuantity())
                .append(" | counted=").append(item.getCountedQuantity())
                .append(" | difference=").append(defaultDecimal(item.getDifferenceQuantity()))
                .append(" | status=").append(item.getLineStatus())
                .append(" | discrepancy=").append(item.getDiscrepancyType())
                .append('\n');
        }
        return builder.toString();
    }
}
