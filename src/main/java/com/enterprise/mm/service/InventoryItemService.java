package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import com.enterprise.mm.inventory.policy.InventoryLineMutationPolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.payload.InventoryItemResponse;
import com.enterprise.mm.payload.InventoryLineHistoryResponse;
import com.enterprise.mm.payload.InventoryItemUpdateRequest;
import com.enterprise.mm.payload.InventoryItemCreateRequest;
import com.enterprise.mm.payload.InventoryItemSearchRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.enums.InventoryDiscrepancyDecisionType;
import com.enterprise.mm.enums.InventoryDiscrepancyType;
import com.enterprise.mm.enums.InventoryExplanationStatus;
import com.enterprise.mm.enums.InventoryLineStatus;
import com.enterprise.mm.enums.InventoryStatus;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.InventoryCountRequest;
import com.enterprise.mm.payload.InventoryDiscrepancyDecisionRequest;
import com.enterprise.mm.payload.InventoryExplanationRequest;
import com.enterprise.mm.payload.InventoryRecountRequest;
import com.enterprise.mm.payload.InventoryScanCountRequest;
import com.enterprise.mm.mapper.InventoryItemMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.InventoryDocument;
import com.enterprise.mm.entity.InventoryItem;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.repository.CommissionMemberRepository;
import com.enterprise.mm.repository.InventoryDocumentRepository;
import com.enterprise.mm.repository.InventoryItemRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.UserRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InventoryItemService {
    private final com.enterprise.mm.service.inventory.InventoryAccessService inventoryAccess;
    private final com.enterprise.mm.service.inventory.InventoryScanResolutionService scans;
    private final RoleDataScopeService roleScopes;
    private final com.enterprise.mm.service.inventory.InventoryCountBatchService countBatches;
    private final com.enterprise.mm.service.inventory.InventoryReviewService reviews;
    private final InventoryItemRepository repository;
    private final InventoryDocumentRepository documentRepository;
    private final InventoryItemMapper mapper;
    private final MaterialCatalogGuard materialCatalogGuard;
    private final MaterialRepository materialRepository;
    private final CommissionMemberRepository commissionMemberRepository;
    private final InventoryCommissionService inventoryCommissionService;
    private final UserRepository userRepository;
    private final InventoryLineHistoryService historyService;
    private final String entityName = "InventoryItem";
    private final AuditService auditService;
    private final InventoryLineMutationPolicy inventoryLineMutationPolicy;
    private final InventoryScopeService inventoryScope;
    @Transactional(readOnly = true)
    public Page<InventoryItemResponse> search(InventoryItemSearchRequest request, Pageable pageable) {
        SearchCriteria filter = roleScopes.applyWarehouseScope(request);
        if (request.inventoryDocumentId() != null) inventoryAccess.document(request.inventoryDocumentId());
        if (filter.hasStatus() && !inventoryAccess.readAll()) throw new BadRequestException("Use the authorized count workspace to filter count state");
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
            filter.materialIds(),
            !filter.hasMaterialIds(),
            request.inventoryDocumentId(),
            filter.hasWarehouseIds() ? null : filter.warehouseId(),
            filter.warehouseIds(), !filter.hasWarehouseIds(),
            inventoryAccess.readAll(), inventoryAccess.hasWarehouseReadAccess(), inventoryAccess.currentUserId(),
            pageable
        ).map(this::response);
    }
    @Transactional(readOnly = true)
    public InventoryItemResponse get(UUID id) {
        return response(findEntity(id));
    }

    @Transactional(readOnly = true)
    public List<InventoryLineHistoryResponse> history(UUID id) {
        var line = findEntity(id);
        boolean visible = inventoryAccess.canSeeSystemQuantity(findDocument(line.getInventoryDocumentId()));
        return historyService.list(id).stream().map(row -> com.enterprise.mm.service.inventory.InventoryReadPolicy.project(row, visible)).toList();
    }
    @Transactional
    public InventoryItemResponse create(InventoryItemCreateRequest dto) {
        InventoryItem entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        InventoryItem saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return response(saved);
    }
    @Transactional
    public InventoryItemResponse update(UUID id, InventoryItemUpdateRequest dto) {
        InventoryItem entity = findEntity(id);
        InventoryDocument document = findDocument(entity.getInventoryDocumentId());
        inventoryAccess.requireCount(document);
        InventoryStatus documentStatus = InventoryStatus.from(document.getStatus());
        inventoryLineMutationPolicy.checkUpdate(documentStatus);
        String oldSnapshot = auditService.snapshot(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        InventoryItem saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return response(saved);
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public InventoryItemResponse count(UUID id, InventoryCountRequest request) {
        return count(id, request, "legacy-count-" + UUID.randomUUID());
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public InventoryItemResponse count(UUID id, InventoryCountRequest request, String key) {
        var item = inventoryAccess.line(id);
        var row = new com.enterprise.mm.payload.InventoryCountBatchDtos.RowCommand(id, request.getExpectedLineVersion(), request.getCountedQuantity(), request.isClearCount(), request.getComment());
        var batch = new com.enterprise.mm.payload.InventoryCountBatchDtos.BatchCommand(request.getExpectedDocumentVersion(), List.of(row));
        var outcome = countBatches.save(item.getInventoryDocumentId(), batch, key).rows().getFirst();
        if ("SAVED".equals(outcome.outcome())) return outcome.saved();
        if ("CONFLICT".equals(outcome.outcome())) throw new com.enterprise.mm.error.InventoryCountConflictException(outcome);
        throw new BadRequestException(outcome.message(), outcome.errorCode());
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public InventoryItemResponse scanAndCount(UUID inventoryDocumentId, InventoryScanCountRequest request, String key) {
        inventoryAccess.document(inventoryDocumentId);
        var candidates = scans.resolve(inventoryDocumentId, request.getScannedCode());
        if (request.getLineId() == null || candidates.stream().noneMatch(c -> c.lineId().equals(request.getLineId())))
            throw new com.enterprise.mm.error.ConflictException("Choose a matching stock position before counting this scan", "STOCK_KEY_SELECTION_REQUIRED");
        var count = new InventoryCountRequest(); count.setExpectedDocumentVersion(request.getExpectedDocumentVersion());
        count.setExpectedLineVersion(request.getExpectedLineVersion()); count.setCountedQuantity(request.getCountedQuantity()); count.setComment(request.getComment());
        return count(request.getLineId(), count, key);
    }

    @Transactional(readOnly = true)
    public List<InventoryItemResponse> mobileLines(UUID inventoryDocumentId, String filter) {
        InventoryDocument document = findDocument(inventoryDocumentId);
        String normalized = filter == null ? "ALL" : filter.trim().toUpperCase(Locale.ROOT);
        boolean visible = inventoryAccess.canSeeSystemQuantity(document);
        if (!visible && !List.of("ALL", "NOT_COUNTED", "COUNTED").contains(normalized)) throw new BadRequestException("Blind count filters support only counted or uncounted observations");
        return repository.findByTenantIdAndInventoryDocumentIdAndIsDeletedFalse(TenantContext.currentTenant(), inventoryDocumentId).stream()
            .map(item -> mapper.toCountingResponse(item, visible))
            .filter(item -> switch (normalized) {
                case "NOT_COUNTED" -> item.getCountedQuantity() == null;
                case "COUNTED" -> item.getCountedQuantity() != null;
                case "DISCREPANCY" -> item.getLineStatus() == InventoryLineStatus.DISCREPANCY || item.getLineStatus() == InventoryLineStatus.EXPLANATION_REQUIRED;
                case "RESOLVED" -> item.getLineStatus() == InventoryLineStatus.RESOLVED || item.getLineStatus() == InventoryLineStatus.MATCHED;
                default -> true;
            }).toList();
    }

    @Transactional
    public InventoryItemResponse requestExplanation(UUID id, InventoryExplanationRequest c) { return reviews.explanation(id, c, "REQUEST_EXPLANATION"); }
    @Transactional
    public InventoryItemResponse submitExplanation(UUID id, InventoryExplanationRequest c) { return reviews.explanation(id, c, "SUBMIT_EXPLANATION"); }
    @Transactional
    public InventoryItemResponse acceptExplanation(UUID id, InventoryExplanationRequest c) { return reviews.explanation(id, c, "ACCEPT_EXPLANATION"); }
    @Transactional
    public InventoryItemResponse rejectExplanation(UUID id, InventoryExplanationRequest c) { return reviews.explanation(id, c, "REJECT_EXPLANATION"); }
    @Transactional
    public InventoryItemResponse requestRecount(UUID id, InventoryRecountRequest c) {
        return reviews.requestRecount(id, c == null ? null : new com.enterprise.mm.payload.InventoryReviewDtos.Recount(c.getExpectedLineVersion(), c.getReason()));
    }
    @Transactional
    public InventoryItemResponse resolveDiscrepancy(UUID id, InventoryDiscrepancyDecisionRequest c) {
        return reviews.decide(id, c == null ? null : new com.enterprise.mm.payload.InventoryReviewDtos.Decision(c.getExpectedLineVersion(), c.getCountRevision(), c.getDecisionType() == null ? null : c.getDecisionType().name(), firstNonBlank(c.getDecisionComment(), c.getReason(), c.getComment()), c.getEvidenceIds(), c.getSourceCorrectionMovementId()));
    }
    @Transactional
    public void softDelete(UUID id) {
        InventoryItem entity = findEntity(id);
        InventoryDocument document = findDocument(entity.getInventoryDocumentId());
        inventoryAccess.requireCount(document);
        InventoryStatus documentStatus = InventoryStatus.from(document.getStatus());
        inventoryLineMutationPolicy.checkDelete(documentStatus);

        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public InventoryItemResponse restore(UUID id) {
        InventoryItem entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        InventoryDocument document = findDocument(entity.getInventoryDocumentId());
        inventoryAccess.requireCount(document);
        InventoryStatus documentStatus = InventoryStatus.from(document.getStatus());
        inventoryLineMutationPolicy.checkRestore(documentStatus);

        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        InventoryItem saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return response(saved);
    }
    protected InventoryItem findEntity(UUID id) {
        InventoryItem entity = repository.findByIdAndIsDeletedFalse(id)
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
        inventoryAccess.line(id);
        return entity;
    }
    protected void beforeCreate(InventoryItem entity, InventoryItemCreateRequest dto) {
        materialCatalogGuard.requireActiveTmbMaterial(entity.getMaterialId(), "materialId");
        InventoryDocument document = findDocument(entity.getInventoryDocumentId());
        inventoryAccess.requireCount(document);
        InventoryStatus documentStatus = InventoryStatus.from(document.getStatus());
        inventoryLineMutationPolicy.checkCreate(documentStatus);
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus(InventoryLineStatus.NOT_COUNTED.name());
        }
        if (entity.getCompanyId() == null) {
            entity.setCompanyId(document.getCompanyId());
        }
        if (entity.getWarehouseId() == null) {
            entity.setWarehouseId(document.getWarehouseId());
        }
        requireStockScope(document, entity);
        if (entity.getCode() == null || entity.getCode().isBlank()) {
            entity.setCode(entityName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_") + "-" + UUID.randomUUID().toString().substring(0, 8));
        }
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
        if (entity.getLineStatus() == null) {
            entity.setLineStatus(InventoryLineStatus.NOT_COUNTED);
        }
        recalculateLine(entity);
    }
    protected void beforeUpdate(InventoryItem entity, InventoryItemUpdateRequest dto) {
        materialCatalogGuard.requireActiveTmbMaterial(entity.getMaterialId(), "materialId");
        InventoryDocument document = findDocument(entity.getInventoryDocumentId());
        inventoryAccess.requireCount(document);
        requireStockScope(document, entity);
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
        recalculateLine(entity);
    }
    protected boolean companyScoped() {
        return true;
    }

    private InventoryDocument findDocument(UUID id) {
        return inventoryAccess.document(id);
    }

    private void requireStockScope(InventoryDocument document, InventoryItem line) {
        if (document.getScopeMode() != null && line.getWarehouseId() == null)
            throw new BadRequestException("warehouseId is required for a multi-warehouse count line");
        if (!inventoryScope.contains(document, line.getWarehouseId(), line.getStorageLocationId(),
            line.getStorageBinId(), line.getMaterialId()))
            throw new BadRequestException("Inventory line is outside the count scope");
    }

    private InventoryItemResponse response(InventoryItem item) {
        var document = inventoryAccess.document(item.getInventoryDocumentId());
        return mapper.toCountingResponse(item, inventoryAccess.canSeeSystemQuantity(document));
    }

    private void refreshDocumentTotals(InventoryDocument document) {
        document.setTotalItems((int) repository.countByTenantIdAndInventoryDocumentIdAndIsDeletedFalse(TenantContext.currentTenant(), document.getId()));
        document.setCountedItems((int) repository.countByTenantIdAndInventoryDocumentIdAndCountedQuantityIsNotNullAndIsDeletedFalse(TenantContext.currentTenant(), document.getId()));
        document.setDiscrepancyItems((int) repository.countByTenantIdAndInventoryDocumentIdAndDifferenceQuantityNotAndIsDeletedFalse(TenantContext.currentTenant(), document.getId(), BigDecimal.ZERO));
        documentRepository.save(document);
    }

    private void recalculateLine(InventoryItem entity) {
        BigDecimal systemQuantity = nvl(entity.getSystemQuantity());
        BigDecimal countedQuantity = entity.getCountedQuantity();
        entity.setSystemQuantity(systemQuantity);
        entity.setUnitCost(nvl(entity.getUnitCost()));
        if (countedQuantity == null) {
            entity.setDifferenceQuantity(BigDecimal.ZERO);
            entity.setDifferenceAmount(BigDecimal.ZERO);
            entity.setLineStatus(InventoryLineStatus.NOT_COUNTED);
            entity.setStatus(InventoryLineStatus.NOT_COUNTED.name());
            return;
        }
        BigDecimal difference = countedQuantity.subtract(systemQuantity);
        entity.setDifferenceQuantity(difference);
        entity.setDifferenceAmount(difference.abs().multiply(nvl(entity.getUnitCost())));
        if (difference.signum() == 0) {
            entity.setLineStatus(InventoryLineStatus.MATCHED);
            entity.setDiscrepancyType(null);
        } else {
            entity.setLineStatus(InventoryLineStatus.DISCREPANCY);
            if (entity.getDiscrepancyType() == null) {
                entity.setDiscrepancyType(difference.signum() < 0 ? InventoryDiscrepancyType.SHORTAGE : InventoryDiscrepancyType.SURPLUS);
            }
        }
        entity.setStatus(entity.getLineStatus().name());
    }

    private InventoryItem snapshot(InventoryItem source) {
        InventoryItem copy = new InventoryItem();
        copy.setId(source.getId());
        copy.setCompanyId(source.getCompanyId());
        copy.setInventoryDocumentId(source.getInventoryDocumentId());
        copy.setCountedQuantity(source.getCountedQuantity());
        copy.setLineStatus(source.getLineStatus());
        copy.setDifferenceQuantity(source.getDifferenceQuantity());
        return copy;
    }

    private void validateDiscrepancyDecision(InventoryDiscrepancyType discrepancyType, InventoryDiscrepancyDecisionType decisionType) {
        if (discrepancyType == null) {
            throw new BadRequestException("discrepancyType is required");
        }
        if (decisionType == null) {
            throw new BadRequestException("decisionType is required");
        }
        boolean allowed = switch (discrepancyType) {
            case SHORTAGE -> decisionType == InventoryDiscrepancyDecisionType.WRITE_OFF_SHORTAGE
                || decisionType == InventoryDiscrepancyDecisionType.RECOVER_FROM_RESPONSIBLE
                || decisionType == InventoryDiscrepancyDecisionType.START_INVESTIGATION
                || decisionType == InventoryDiscrepancyDecisionType.CORRECT_SOURCE_OPERATION
                || decisionType == InventoryDiscrepancyDecisionType.POSTPONE;
            case SURPLUS -> decisionType == InventoryDiscrepancyDecisionType.POST_SURPLUS
                || decisionType == InventoryDiscrepancyDecisionType.LINK_TO_EXISTING_MATERIAL
                || decisionType == InventoryDiscrepancyDecisionType.CREATE_MATERIAL_MASTER_REQUEST
                || decisionType == InventoryDiscrepancyDecisionType.CORRECT_PREVIOUS_DOCUMENT
                || decisionType == InventoryDiscrepancyDecisionType.POSTPONE;
            case UNIDENTIFIED_MATERIAL -> decisionType == InventoryDiscrepancyDecisionType.LINK_TO_EXISTING_MATERIAL
                || decisionType == InventoryDiscrepancyDecisionType.CREATE_MATERIAL_MASTER_REQUEST
                || decisionType == InventoryDiscrepancyDecisionType.START_INVESTIGATION
                || decisionType == InventoryDiscrepancyDecisionType.POSTPONE;
            case MIX_UP, UOM_ERROR, BATCH_ERROR, LOCATION_ERROR, DAMAGED, EXPIRED -> decisionType == InventoryDiscrepancyDecisionType.START_INVESTIGATION
                || decisionType == InventoryDiscrepancyDecisionType.CORRECT_SOURCE_OPERATION
                || decisionType == InventoryDiscrepancyDecisionType.CORRECT_PREVIOUS_DOCUMENT
                || decisionType == InventoryDiscrepancyDecisionType.POSTPONE;
        };
        if (!allowed) {
            throw new BadRequestException("decisionType " + decisionType + " is not allowed for " + discrepancyType);
        }
    }

    private String decisionHistoryReason(InventoryDiscrepancyDecisionRequest request) {
        String text = firstNonBlank(request.getDecisionComment(), request.getReason(), request.getComment());
        return request.getDecisionType() == null
            ? text
            : request.getDecisionType().name() + (text == null ? "" : ": " + text);
    }

    private UUID findMaterialIdByScannedCode(String scannedCode) {
        String code = trimToNull(scannedCode);
        if (code == null) {
            throw new BadRequestException("scannedCode is required");
        }
        return Optional.ofNullable(materialRepository.findByTenantIdAndCodeAndIsDeletedFalse(TenantContext.currentTenant(), code).orElse(null))
            .or(() -> materialRepository.findFirstByTenantIdAndBarcodeAndIsDeletedFalse(TenantContext.currentTenant(), code))
            .or(() -> materialRepository.findFirstByTenantIdAndQrCodeAndIsDeletedFalse(TenantContext.currentTenant(), code))
            .or(() -> materialRepository.findFirstByTenantIdAndRfidTagAndIsDeletedFalse(TenantContext.currentTenant(), code))
            .filter(material -> com.enterprise.mm.security.AccessScope.canAccessCompany(material.getCompanyId()))
            .map(material -> material.getId())
            .orElseThrow(() -> new NotFoundException("Material not found for scanned code"));
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String currentUser() {
        var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? "System" : authentication.getName();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String trimmed = trimToNull(value);
            if (trimmed != null) {
                return trimmed;
            }
        }
        return null;
    }

    private boolean canCurrentUserSeeSystemQuantity(InventoryDocument document) {
        return inventoryAccess.canSeeSystemQuantity(document);
    }

    private Optional<User> currentUserEntity() {
        String username = currentUser();
        if (username == null || username.isBlank() || "system".equals(username)) {
            return Optional.empty();
        }
        return userRepository.findByTenantIdAndUsernameAndIsDeletedFalse(TenantContext.currentTenant(), username);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

