package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AssetEvent;
import com.enterprise.mm.entity.AssetManagementSettings;
import com.enterprise.mm.entity.AssetNumberRegistry;
import com.enterprise.mm.entity.Department;
import com.enterprise.mm.entity.Employee;
import com.enterprise.mm.entity.Equipment;
import com.enterprise.mm.entity.FixedAsset;
import com.enterprise.mm.enums.AssetNumberRegistrySource;
import com.enterprise.mm.enums.AssetNumberRegistryStatus;
import com.enterprise.mm.enums.EmployeeStatus;
import com.enterprise.mm.enums.FixedAssetCustodyKind;
import com.enterprise.mm.enums.FixedAssetLifecycleStatus;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.AssetFoundationMapper;
import com.enterprise.mm.payload.AssetEventResponse;
import com.enterprise.mm.payload.AssetInventoryNumberAllocateRequest;
import com.enterprise.mm.payload.AssetInventoryNumberAssignRequest;
import com.enterprise.mm.payload.AssetInventoryNumberCorrectionRequest;
import com.enterprise.mm.payload.FixedAssetBulkCommissionRequest;
import com.enterprise.mm.payload.FixedAssetBulkCommissionResponse;
import com.enterprise.mm.payload.FixedAssetCommissionRequest;
import com.enterprise.mm.payload.FixedAssetEquipmentLinkRequest;
import com.enterprise.mm.payload.FixedAssetResponse;
import com.enterprise.mm.payload.FixedAssetSearchRequest;
import com.enterprise.mm.payload.VersionedAssetCommand;
import com.enterprise.mm.repository.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.enterprise.mm.repository.specification.FixedAssetSpecification;
import org.springframework.data.jpa.domain.Specification;

@Service
@RequiredArgsConstructor
@com.enterprise.mm.security.pilot.PilotCapabilityBoundary(value = com.enterprise.mm.security.pilot.PilotCapability.FIXED_ASSET_EXECUTION, reads = {"search", "fixedAssetMaterials", "get", "events", "findForApproval"})
public class FixedAssetService {
    public static final String DISPOSAL_APPROVAL_MODULE = "ASSET_DISPOSAL";

    private final FixedAssetRepository repository;
    private final AssetEventRepository eventRepository;
    private final AssetFoundationMapper mapper;
    private final AssetNumberRegistryRepository numberRegistryRepository;
    private final AssetManagementSettingsRepository settingsRepository;
    private final EquipmentRepository equipmentRepository;
    private final EmployeeRepository employeeRepository;
    private final DepartmentRepository departmentRepository;
    private final AttachmentRepository attachmentRepository;
    private final IdempotencyService idempotencyService;
    private final AssetAcceptanceRepository assetAcceptanceRepository;
    private final AssetHandoverRepository assetHandoverRepository;
    private final AssetOpeningImportRepository assetOpeningImportRepository;
    private final RoleDataScopeService roleDataScopeService;

    @Autowired(required = false)
    private AssetIntegrationEventPublisher assetIntegrationEventPublisher;

    @Transactional(readOnly = true)
    public Page<FixedAssetResponse> search(FixedAssetSearchRequest request, Pageable pageable) {
        Specification<FixedAsset> spec = FixedAssetSpecification.fromRequest(request).and(departmentScope());
        return repository.findAll(spec, pageable)
            .map(mapper::toResponse);
    }
    public Page<FixedAssetResponse> fixedAssetMaterials(String tenantId, UUID companyId, UUID materialId, Pageable pageable) {
        if (roleDataScopeService.isDepartmentAssetScoped()) {
            Specification<FixedAsset> scope = (root, query, cb) -> cb.and(
                cb.equal(root.get("tenantId"), tenantId), cb.equal(root.get("companyId"), companyId),
                cb.equal(root.get("materialId"), materialId), cb.isFalse(root.get("isDeleted")));
            return repository.findAll(scope.and(departmentScope()), pageable).map(mapper::toResponse);
        }
        return repository.findByTenantIdAndCompanyIdAndMaterialIdAndIsDeletedFalse(tenantId, companyId, materialId, pageable)
                .map(mapper::toResponse);
    }

    @Transactional(readOnly = true)
    public FixedAssetResponse get(UUID id) {
        return mapper.toResponse(find(id));
    }

    @Transactional(readOnly = true)
    public List<AssetEventResponse> events(UUID fixedAssetId) {
        find(fixedAssetId);
        List<AssetEvent> events = eventRepository.findByTenantIdAndCompanyIdAndFixedAssetIdAndIsDeletedFalseOrderByRecordedAtDesc(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), fixedAssetId);

        List<AssetEventResponse> responses = events.stream()
            .map(mapper::toResponse)
            .toList();

        Set<UUID> acceptanceIds = new java.util.HashSet<>();
        Set<UUID> handoverIds = new java.util.HashSet<>();
        Set<UUID> openingImportIds = new java.util.HashSet<>();
        Set<UUID> fixedAssetIds = new java.util.HashSet<>();

        for (AssetEvent event : events) {
            if (event.getSourceDocumentId() == null) continue;

            if ("ASSET_ACCEPTANCE".equals(event.getSourceDocumentType())) {
                acceptanceIds.add(event.getSourceDocumentId());
            } else if ("ASSET_HANDOVER".equals(event.getSourceDocumentType())) {
                handoverIds.add(event.getSourceDocumentId());
            } else if ("ASSET_OPENING_IMPORT".equals(event.getSourceDocumentType())) {
                openingImportIds.add(event.getSourceDocumentId());
            } else if ("FIXED_ASSET".equals(event.getSourceDocumentType())) {
                fixedAssetIds.add(event.getSourceDocumentId());
            }
        }

        java.util.Map<UUID, String> names = new java.util.HashMap<>();

        if (!acceptanceIds.isEmpty()) {
            assetAcceptanceRepository.findAllById(acceptanceIds)
                .forEach(doc -> putSourceDocumentName(names, doc.getId(), doc.getName()));
        }
        if (!handoverIds.isEmpty()) {
            assetHandoverRepository.findAllById(handoverIds)
                .forEach(doc -> putSourceDocumentName(names, doc.getId(), doc.getName()));
        }
        if (!openingImportIds.isEmpty()) {
            assetOpeningImportRepository.findAllById(openingImportIds)
                .forEach(doc -> putSourceDocumentName(names, doc.getId(), doc.getName()));
        }
        if (!fixedAssetIds.isEmpty()) {
            repository.findAllById(fixedAssetIds)
                .forEach(doc -> putSourceDocumentName(names, doc.getId(), fixedAssetSourceDocumentName(doc)));
        }

        for (AssetEventResponse res : responses) {
            if (res.getSourceDocumentId() != null && names.containsKey(res.getSourceDocumentId())) {
                res.setSourceDocumentName(names.get(res.getSourceDocumentId()));
            }
        }

        return responses;
    }

    @Transactional
    public FixedAssetResponse assignInventoryNumber(UUID id, AssetInventoryNumberAssignRequest request) {
        return assignInventoryNumber(id, request, null);
    }

    @Transactional
    public FixedAssetResponse assignInventoryNumber(UUID id, AssetInventoryNumberAssignRequest request, String idempotencyKey) {
        if (hasText(idempotencyKey)) {
            return idempotencyService.execute(
                "ASSET_NUMBER_ASSIGN", id, idempotencyKey, request, FixedAssetResponse.class,
                () -> find(id),
                () -> assignInventoryNumberNow(id, request)
            );
        }
        return assignInventoryNumberNow(id, request);
    }

    private FixedAssetResponse assignInventoryNumberNow(UUID id, AssetInventoryNumberAssignRequest request) {
        FixedAsset asset = find(id);
        requireVersion(asset, request == null ? null : request.expectedVersion());
        String inventoryNumber = trimToNull(request == null ? null : request.inventoryNumber());
        if (inventoryNumber == null) {
            asset.setInventoryNumber(null);
            return mapper.toResponse(repository.save(asset));
        }
        assignNumber(asset, inventoryNumber, AssetNumberRegistrySource.MANUAL, "ASSET_INVENTORY_NUMBER_ASSIGNED",
            request == null ? null : request.reason());
        return mapper.toResponse(repository.save(asset));
    }

    @Transactional
    public List<String> allocateInventoryNumbers(AssetInventoryNumberAllocateRequest request) {
        return allocateInventoryNumbers(request, null);
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public List<String> allocateInventoryNumbers(AssetInventoryNumberAllocateRequest request, String idempotencyKey) {
        if (hasText(idempotencyKey)) {
            return idempotencyService.execute(
                "ASSET_NUMBER_ALLOCATE", null, idempotencyKey, request, List.class,
                () -> allocateInventoryNumbersNow(request)
            );
        }
        return allocateInventoryNumbersNow(request);
    }

    private List<String> allocateInventoryNumbersNow(AssetInventoryNumberAllocateRequest request) {
        int count = request == null ? 0 : request.count();
        if (count < 1) {
            throw new BadRequestException("count must be greater than zero", "ASSET_INVENTORY_NUMBER_REQUIRED");
        }
        AssetManagementSettings settings = settingsRepository.findByTenantIdAndCompanyIdForUpdate(
                TenantContext.currentTenant(), CompanyContext.currentCompany())
            .orElseThrow(() -> new BusinessException(
                "Central warehouse is not configured for fixed assets",
                "ASSET_CENTRAL_WAREHOUSE_NOT_CONFIGURED"
            ));
        List<String> allocated = new ArrayList<>();
        long next = settings.getInventoryNumberNextValue() == null ? 1L : settings.getInventoryNumberNextValue();
        int padding = settings.getInventoryNumberPadding() == null ? 6 : settings.getInventoryNumberPadding();
        String prefix = trimToNull(settings.getInventoryNumberPrefix()) == null ? "FA" : settings.getInventoryNumberPrefix().trim();
        for (int index = 0; index < count; index++) {
            String number = prefix + "-" + String.format(Locale.ROOT, "%0" + padding + "d", next++);
            reserveRegistry(number, null, AssetNumberRegistrySource.GENERATED, AssetNumberRegistryStatus.RESERVED);
            allocated.add(number);
        }
        settings.setInventoryNumberNextValue(next);
        settingsRepository.save(settings);
        return allocated;
    }

    @Transactional
    public FixedAsset assignGeneratedInventoryNumber(FixedAsset asset, String reason) {
        if (asset == null) {
            throw new BadRequestException("fixedAsset is required", "ASSET_EVIDENCE_REQUIRED");
        }
        if (hasText(asset.getInventoryNumber())) {
            return asset;
        }
        String inventoryNumber = allocateInventoryNumbersNow(new AssetInventoryNumberAllocateRequest(1, "ASSET_ACCEPTANCE")).get(0);
        assignNumber(asset, inventoryNumber, AssetNumberRegistrySource.GENERATED, "ASSET_INVENTORY_NUMBER_ASSIGNED", reason);
        return repository.save(asset);
    }

    @Transactional
    public FixedAssetResponse correctInventoryNumber(UUID id, AssetInventoryNumberCorrectionRequest request) {
        return correctInventoryNumber(id, request, null);
    }

    @Transactional
    public FixedAssetResponse correctInventoryNumber(UUID id, AssetInventoryNumberCorrectionRequest request, String idempotencyKey) {
        if (hasText(idempotencyKey)) {
            return idempotencyService.execute(
                "ASSET_NUMBER_CORRECT", id, idempotencyKey, request, FixedAssetResponse.class,
                () -> find(id),
                () -> correctInventoryNumberNow(id, request)
            );
        }
        return correctInventoryNumberNow(id, request);
    }

    private FixedAssetResponse correctInventoryNumberNow(UUID id, AssetInventoryNumberCorrectionRequest request) {
        FixedAsset asset = find(id);
        requireVersion(asset, request == null ? null : request.expectedVersion());
        if (!hasText(request == null ? null : request.reason())) {
            throw new BusinessException("Inventory number correction requires a reason", "ASSET_EVIDENCE_REQUIRED");
        }
        String newNumber = trimToNull(request.inventoryNumber());
        if (newNumber == null) {
            throw new BusinessException("Inventory number is required", "ASSET_INVENTORY_NUMBER_REQUIRED");
        }
        String oldNumber = trimToNull(asset.getInventoryNumber());
        if (oldNumber != null && normalized(oldNumber).equals(normalized(newNumber))) {
            return mapper.toResponse(asset);
        }
        if (oldNumber != null) {
            numberRegistryRepository.findByTenantIdAndCompanyIdAndNormalizedNumberAndIsDeletedFalse(
                    TenantContext.currentTenant(), CompanyContext.currentCompany(), normalized(oldNumber))
                .ifPresent(row -> {
                    row.setRegistryStatus(AssetNumberRegistryStatus.CORRECTED);
                    row.setCorrectedAt(LocalDateTime.now());
                    numberRegistryRepository.save(row);
                });
        }
        assignNumber(asset, newNumber, AssetNumberRegistrySource.CORRECTION, "ASSET_INVENTORY_NUMBER_CORRECTED", request.reason());
        return mapper.toResponse(repository.save(asset));
    }

    public FixedAssetResponse commission(UUID id, FixedAssetCommissionRequest request) {
        return commission(id, request, null);
    }

    @Transactional
    public FixedAssetResponse commission(UUID id, FixedAssetCommissionRequest request, String idempotencyKey) {
        if (!hasText(idempotencyKey)) {
            throw new BadRequestException("Idempotency-Key is required", "ASSET_IDEMPOTENCY_KEY_REQUIRED");
        }
        return idempotencyService.execute(
            "ASSET_COMMISSION", id, idempotencyKey, request, FixedAssetResponse.class,
            () -> find(id),
            () -> commissionNow(id, request, idempotencyKey)
        );
    }

    private FixedAssetResponse commissionNow(UUID id, FixedAssetCommissionRequest request, String idempotencyKey) {
        FixedAssetBulkCommissionRequest.Row row = new FixedAssetBulkCommissionRequest.Row(
            id,
            request == null ? null : request.expectedVersion(),
            request == null ? null : request.inventoryNumber(),
            request == null ? null : request.responsibleEmployeeId(),
            request == null ? null : request.departmentId(),
            request == null ? null : request.operatingLocation(),
            request == null ? null : request.commissioningDate(),
            request == null ? null : request.evidenceIds(),
            request == null ? null : request.reason()
        );
        return applyCommissionPlan(validateBulkCommission(List.of(row)).getFirst(), idempotencyKey);
    }

    public FixedAssetBulkCommissionResponse bulkCommission(FixedAssetBulkCommissionRequest request) {
        return bulkCommission(request, null);
    }

    @Transactional
    public FixedAssetBulkCommissionResponse bulkCommission(FixedAssetBulkCommissionRequest request, String idempotencyKey) {
        if (hasText(idempotencyKey)) {
            return idempotencyService.execute(
                "ASSET_BULK_COMMISSION", null, idempotencyKey, request, FixedAssetBulkCommissionResponse.class,
                () -> guardBulkCommissionReplay(request),
                () -> bulkCommissionNow(request, idempotencyKey)
            );
        }
        return bulkCommissionNow(request, null);
    }

    private FixedAssetBulkCommissionResponse bulkCommissionNow(FixedAssetBulkCommissionRequest request, String idempotencyKey) {
        List<FixedAssetBulkCommissionRequest.Row> rows = request == null || request.rows() == null ? List.of() : request.rows();
        if (rows.isEmpty()) {
            throw new BadRequestException("Bulk commissioning requires at least one row", "ASSET_EVIDENCE_REQUIRED");
        }
        List<BulkCommissionPlan> plans = validateBulkCommission(rows);
        List<FixedAssetResponse> responses = new ArrayList<>();
        for (BulkCommissionPlan plan : plans) {
            responses.add(applyCommissionPlan(plan, idempotencyKey));
        }
        return new FixedAssetBulkCommissionResponse(responses);
    }

    private FixedAssetResponse applyCommissionPlan(BulkCommissionPlan plan, String idempotencyKey) {
        FixedAsset asset = plan.asset();
        FixedAssetLifecycleStatus beforeLifecycle = asset.getLifecycleStatus();
        FixedAssetCustodyKind beforeCustody = asset.getCustodyKind();
        if (hasText(plan.row().inventoryNumber()) && !Objects.equals(normalized(asset.getInventoryNumber()), normalized(plan.row().inventoryNumber()))) {
            assignNumber(asset, plan.row().inventoryNumber(), AssetNumberRegistrySource.MANUAL, "ASSET_INVENTORY_NUMBER_ASSIGNED", plan.row().reason());
        }
        asset.setLifecycleStatus(FixedAssetLifecycleStatus.IN_USE);
        asset.setCustodianEmployeeId(plan.employee().getId());
        asset.setDepartmentId(plan.department().getId());
        asset.setWarehouseId(null);
        asset.setStorageLocationId(null);
        asset.setOperatingLocation(trimToNull(plan.row().operatingLocation()));
        if (asset.getFirstCommissionedOn() == null) {
            asset.setFirstCommissionedOn(plan.row().commissioningDate());
        }
        if (asset.getFirstCommissionedAt() == null) {
            asset.setFirstCommissionedAt(LocalDateTime.now());
        }
        if (!hasText(asset.getFirstCommissionedBy())) {
            asset.setFirstCommissionedBy(currentActor());
        }
        appendAssetEvent(asset, "ASSET_COMMISSIONED", beforeLifecycle, beforeCustody, plan.row().reason(), idempotencyKey);
        return mapper.toResponse(repository.save(asset));
    }

    private void guardBulkCommissionReplay(FixedAssetBulkCommissionRequest request) {
        List<FixedAssetBulkCommissionRequest.Row> rows = request == null || request.rows() == null ? List.of() : request.rows();
        for (FixedAssetBulkCommissionRequest.Row row : rows) {
            if (row != null && row.assetId() != null) {
                find(row.assetId());
            }
        }
    }

    public FixedAssetResponse markIdle(UUID id, VersionedAssetCommand request) {
        return markIdle(id, request, null);
    }

    @Transactional
    public FixedAssetResponse markIdle(UUID id, VersionedAssetCommand request, String idempotencyKey) {
        if (hasText(idempotencyKey)) {
            return idempotencyService.execute(
                "ASSET_MARK_IDLE", id, idempotencyKey, request, FixedAssetResponse.class,
                () -> find(id),
                () -> transitionLifecycle(
                    id,
                    request,
                    idempotencyKey,
                    "ASSET_MARKED_IDLE",
                    FixedAssetLifecycleStatus.IDLE,
                    FixedAssetLifecycleStatus.IN_USE
                )
            );
        }
        return transitionLifecycle(id, request, null, "ASSET_MARKED_IDLE", FixedAssetLifecycleStatus.IDLE, FixedAssetLifecycleStatus.IN_USE);
    }

    public FixedAssetResponse reactivate(UUID id, VersionedAssetCommand request) {
        return reactivate(id, request, null);
    }

    @Transactional
    public FixedAssetResponse reactivate(UUID id, VersionedAssetCommand request, String idempotencyKey) {
        if (hasText(idempotencyKey)) {
            return idempotencyService.execute(
                "ASSET_REACTIVATE", id, idempotencyKey, request, FixedAssetResponse.class,
                () -> find(id),
                () -> transitionLifecycle(
                    id,
                    request,
                    idempotencyKey,
                    "ASSET_REACTIVATED",
                    FixedAssetLifecycleStatus.IN_USE,
                    FixedAssetLifecycleStatus.IDLE
                )
            );
        }
        return transitionLifecycle(id, request, null, "ASSET_REACTIVATED", FixedAssetLifecycleStatus.IN_USE, FixedAssetLifecycleStatus.IDLE);
    }

    public FixedAssetResponse sendToRepair(UUID id, VersionedAssetCommand request) {
        return sendToRepair(id, request, null);
    }

    @Transactional
    public FixedAssetResponse sendToRepair(UUID id, VersionedAssetCommand request, String idempotencyKey) {
        if (hasText(idempotencyKey)) {
            return idempotencyService.execute(
                "ASSET_SEND_TO_REPAIR", id, idempotencyKey, request, FixedAssetResponse.class,
                () -> find(id),
                () -> sendToRepairNow(id, request, idempotencyKey)
            );
        }
        return sendToRepairNow(id, request, null);
    }

    public FixedAssetResponse completeRepair(UUID id, VersionedAssetCommand request) {
        return completeRepair(id, request, null);
    }

    @Transactional
    public FixedAssetResponse completeRepair(UUID id, VersionedAssetCommand request, String idempotencyKey) {
        if (hasText(idempotencyKey)) {
            return idempotencyService.execute(
                "ASSET_COMPLETE_REPAIR", id, idempotencyKey, request, FixedAssetResponse.class,
                () -> find(id),
                () -> completeRepairNow(id, request, idempotencyKey)
            );
        }
        return completeRepairNow(id, request, null);
    }

    public FixedAssetResponse returnToSupplier(UUID id, VersionedAssetCommand request) {
        return returnToSupplier(id, request, null);
    }

    @Transactional
    public FixedAssetResponse returnToSupplier(UUID id, VersionedAssetCommand request, String idempotencyKey) {
        if (hasText(idempotencyKey)) {
            return idempotencyService.execute(
                "ASSET_RETURN_TO_SUPPLIER", id, idempotencyKey, request, FixedAssetResponse.class,
                () -> find(id),
                () -> returnToSupplierNow(id, request, idempotencyKey)
            );
        }
        return returnToSupplierNow(id, request, null);
    }

    public FixedAssetResponse dispose(UUID id, VersionedAssetCommand request) {
        return dispose(id, request, null);
    }

    @Transactional
    public FixedAssetResponse dispose(UUID id, VersionedAssetCommand request, String idempotencyKey) {
        if (hasText(idempotencyKey)) {
            return idempotencyService.execute(
                "ASSET_DISPOSE", id, idempotencyKey, request, FixedAssetResponse.class,
                () -> find(id),
                () -> disposeNow(id, request, idempotencyKey)
            );
        }
        return disposeNow(id, request, null);
    }

    public FixedAssetResponse linkEquipment(UUID id, FixedAssetEquipmentLinkRequest request) {
        return linkEquipment(id, request, null);
    }

    @Transactional
    public FixedAssetResponse linkEquipment(UUID id, FixedAssetEquipmentLinkRequest request, String idempotencyKey) {
        if (hasText(idempotencyKey)) {
            return idempotencyService.execute(
                "ASSET_EQUIPMENT_LINK", id, idempotencyKey, request, FixedAssetResponse.class,
                () -> find(id),
                () -> linkEquipmentNow(id, request, idempotencyKey)
            );
        }
        return linkEquipmentNow(id, request, null);
    }

    private FixedAssetResponse linkEquipmentNow(UUID id, FixedAssetEquipmentLinkRequest request, String idempotencyKey) {
        FixedAsset asset = find(id);
        requireVersion(asset, request == null ? null : request.expectedVersion());
        UUID equipmentId = request == null ? null : request.equipmentId();
        if (equipmentId == null) {
            throw new BadRequestException("equipmentId is required", "ASSET_EVIDENCE_REQUIRED");
        }
        Equipment equipment = equipmentRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), equipmentId)
            .orElseThrow(() -> new NotFoundException("Equipment not found: " + equipmentId));
        if (repository.existsByTenantIdAndCompanyIdAndEquipmentIdAndIdNotAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), equipment.getId(), asset.getId())) {
            throw new BusinessException("Equipment is already linked to another fixed asset", "ASSET_EQUIPMENT_ALREADY_LINKED");
        }
        if (Objects.equals(asset.getEquipmentId(), equipment.getId())) {
            return mapper.toResponse(asset);
        }
        FixedAssetLifecycleStatus beforeLifecycle = asset.getLifecycleStatus();
        FixedAssetCustodyKind beforeCustody = asset.getCustodyKind();
        asset.setEquipmentId(equipment.getId());
        appendAssetEvent(asset, "ASSET_EQUIPMENT_LINKED", beforeLifecycle, beforeCustody, request.reason(), idempotencyKey);
        return mapper.toResponse(repository.save(asset));
    }

    public FixedAssetResponse unlinkEquipment(UUID id, VersionedAssetCommand request) {
        return unlinkEquipment(id, request, null);
    }

    @Transactional
    public FixedAssetResponse unlinkEquipment(UUID id, VersionedAssetCommand request, String idempotencyKey) {
        if (hasText(idempotencyKey)) {
            return idempotencyService.execute(
                "ASSET_EQUIPMENT_UNLINK", id, idempotencyKey, request, FixedAssetResponse.class,
                () -> find(id),
                () -> unlinkEquipmentNow(id, request, idempotencyKey)
            );
        }
        return unlinkEquipmentNow(id, request, null);
    }

    private FixedAssetResponse unlinkEquipmentNow(UUID id, VersionedAssetCommand request, String idempotencyKey) {
        FixedAsset asset = find(id);
        requireVersion(asset, request == null ? null : request.expectedVersion());
        if (asset.getEquipmentId() == null) {
            return mapper.toResponse(asset);
        }
        FixedAssetLifecycleStatus beforeLifecycle = asset.getLifecycleStatus();
        FixedAssetCustodyKind beforeCustody = asset.getCustodyKind();
        asset.setEquipmentId(null);
        appendAssetEvent(asset, "ASSET_EQUIPMENT_UNLINKED", beforeLifecycle, beforeCustody, request == null ? null : request.reason(), idempotencyKey);
        return mapper.toResponse(repository.save(asset));
    }

    private List<BulkCommissionPlan> validateBulkCommission(List<FixedAssetBulkCommissionRequest.Row> rows) {
        List<Map<String, Object>> blockers = new ArrayList<>();
        List<BulkCommissionPlan> plans = new ArrayList<>();
        Set<UUID> seenAssets = new HashSet<>();
        Set<String> seenNumbers = new HashSet<>();
        for (int index = 0; index < rows.size(); index++) {
            FixedAssetBulkCommissionRequest.Row row = rows.get(index);
            if (row == null) {
                blockers.add(blocker(index, null, "row", "Row is required", "ASSET_EVIDENCE_REQUIRED"));
                continue;
            }
            FixedAsset asset = resolveBulkAsset(index, row, blockers);
            Employee employee = resolveBulkEmployee(index, row, blockers);
            Department department = resolveBulkDepartment(index, row, blockers);
            if (row.assetId() != null && !seenAssets.add(row.assetId())) {
                blockers.add(blocker(index, row.assetId(), "assetId", "Asset appears more than once", "ASSET_ALREADY_ALLOCATED"));
            }
            String inventoryNumber = trimToNull(row.inventoryNumber());
            if (asset != null && inventoryNumber == null) {
                inventoryNumber = trimToNull(asset.getInventoryNumber());
            }
            if (!hasText(inventoryNumber)) {
                blockers.add(blocker(index, row.assetId(), "inventoryNumber", "Inventory number is required", "ASSET_INVENTORY_NUMBER_REQUIRED"));
            } else {
                String normalized = normalized(inventoryNumber);
                if (!seenNumbers.add(normalized)) {
                    blockers.add(blocker(index, row.assetId(), "inventoryNumber", "Duplicate inventory number in request", "ASSET_INVENTORY_NUMBER_DUPLICATE"));
                }
                validateBulkInventoryNumber(index, row.assetId(), asset, inventoryNumber, blockers);
            }
            if (asset != null) {
                validateBulkAssetState(index, row, asset, blockers);
            }
            validateBulkReadiness(index, row, asset, blockers);
            if (employee != null
                && department != null
                && employee.getDepartment() != null
                && !Objects.equals(employee.getDepartment().getId(), department.getId())) {
                blockers.add(blocker(index, row.assetId(), "responsibleEmployeeId", "Responsible employee is not valid for selected department", "ASSET_EMPLOYEE_DEPARTMENT_MISMATCH"));
            }
            if (asset != null && employee != null && department != null) {
                plans.add(new BulkCommissionPlan(asset, row, employee, department));
            }
        }
        if (!blockers.isEmpty()) {
            throw new BusinessException(
                "Bulk commissioning is blocked by one or more invalid rows",
                "ASSET_BULK_COMMISSION_BLOCKED",
                Map.of("blockers", blockers)
            );
        }
        return plans;
    }

    private FixedAsset resolveBulkAsset(int index, FixedAssetBulkCommissionRequest.Row row, List<Map<String, Object>> blockers) {
        if (row.assetId() == null) {
            blockers.add(blocker(index, null, "assetId", "assetId is required", "ASSET_EVIDENCE_REQUIRED"));
            return null;
        }
        return repository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), row.assetId())
            .filter(asset -> !roleDataScopeService.isDepartmentAssetScoped()
                || roleDataScopeService.canAccessDepartmentAsset(asset.getDepartmentId(), asset.getWarehouseId()))
            .orElseGet(() -> {
                blockers.add(blocker(index, row.assetId(), "assetId", "Fixed asset was not found", "ASSET_SCOPE_DENIED"));
                return null;
            });
    }

    private Employee resolveBulkEmployee(int index, FixedAssetBulkCommissionRequest.Row row, List<Map<String, Object>> blockers) {
        if (row.responsibleEmployeeId() == null) {
            blockers.add(blocker(index, row.assetId(), "responsibleEmployeeId", "responsibleEmployeeId is required", "ASSET_EVIDENCE_REQUIRED"));
            return null;
        }
        return employeeRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), row.responsibleEmployeeId())
            .map(employee -> {
                if (employee.getEmploymentStatus() == EmployeeStatus.DISMISSED
                    || employee.getEmploymentStatus() == EmployeeStatus.SUSPENDED
                    || !"ACTIVE".equalsIgnoreCase(employee.getStatus())) {
                    blockers.add(blocker(index, row.assetId(), "responsibleEmployeeId", "Responsible employee is not active", "ASSET_EMPLOYEE_NOT_ACTIVE"));
                }
                return employee;
            })
            .orElseGet(() -> {
                blockers.add(blocker(index, row.assetId(), "responsibleEmployeeId", "Responsible employee was not found", "ASSET_SCOPE_DENIED"));
                return null;
            });
    }

    private Department resolveBulkDepartment(int index, FixedAssetBulkCommissionRequest.Row row, List<Map<String, Object>> blockers) {
        if (roleDataScopeService.isDepartmentAssetScoped() && !roleDataScopeService.canReadIssueDepartment(row.departmentId())) {
            blockers.add(blocker(index, row.assetId(), "departmentId", "Department is outside your permitted scope", "ASSET_SCOPE_DENIED"));
            return null;
        }
        if (row.departmentId() == null) {
            blockers.add(blocker(index, row.assetId(), "departmentId", "departmentId is required", "ASSET_EVIDENCE_REQUIRED"));
            return null;
        }
        return departmentRepository.findByIdAndTenantIdAndCompanyIdAndIsDeletedFalse(
                row.departmentId(), TenantContext.currentTenant(), CompanyContext.currentCompany())
            .map(department -> {
                if (department.getStatus() != null && !"ACTIVE".equalsIgnoreCase(department.getStatus())) {
                    blockers.add(blocker(index, row.assetId(), "departmentId", "Department is not active", "ASSET_SCOPE_DENIED"));
                }
                return department;
            })
            .orElseGet(() -> {
                blockers.add(blocker(index, row.assetId(), "departmentId", "Department was not found", "ASSET_SCOPE_DENIED"));
                return null;
            });
    }

    private void validateBulkAssetState(
        int index,
        FixedAssetBulkCommissionRequest.Row row,
        FixedAsset asset,
        List<Map<String, Object>> blockers
    ) {
        if (!Objects.equals(asset.getVersion(), row.expectedVersion())) {
            blockers.add(blocker(index, row.assetId(), "expectedVersion", "Fixed asset version conflict", "ASSET_VERSION_CONFLICT"));
        }
        if (asset.getLifecycleStatus() != FixedAssetLifecycleStatus.ISSUED_PENDING_COMMISSIONING) {
            blockers.add(blocker(index, row.assetId(), "lifecycleStatus", "Asset is not ready for commissioning", "ASSET_INVALID_TRANSITION"));
        }
        if (asset.getActiveTransferId() != null || asset.getCustodyKind() == FixedAssetCustodyKind.IN_TRANSIT) {
            blockers.add(blocker(index, row.assetId(), "activeTransferId", "Asset has an active handover", "ASSET_ALREADY_ALLOCATED"));
        }
    }

    private void validateBulkReadiness(
        int index,
        FixedAssetBulkCommissionRequest.Row row,
        FixedAsset asset,
        List<Map<String, Object>> blockers
    ) {
        if (row.commissioningDate() == null) {
            blockers.add(blocker(index, row.assetId(), "commissioningDate", "Commissioning date is required", "ASSET_EVIDENCE_REQUIRED"));
        } else if (row.commissioningDate().isAfter(LocalDate.now())) {
            blockers.add(blocker(index, row.assetId(), "commissioningDate", "Commissioning date cannot be in the future", "ASSET_INVALID_TRANSITION"));
        }
        if (!hasText(row.operatingLocation())) {
            blockers.add(blocker(index, row.assetId(), "operatingLocation", "Operating location is required", "ASSET_EVIDENCE_REQUIRED"));
        }
        List<String> evidenceIds = row.evidenceIds() == null
            ? List.of()
            : row.evidenceIds().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(this::hasText)
                .toList();
        if (evidenceIds.isEmpty()) {
            blockers.add(blocker(index, row.assetId(), "evidenceIds", "Commissioning evidence is required", "ASSET_EVIDENCE_REQUIRED"));
        } else {
            for (String evidenceId : evidenceIds) {
                UUID attachmentId;
                try {
                    attachmentId = UUID.fromString(evidenceId);
                } catch (IllegalArgumentException exception) {
                    blockers.add(blocker(index, row.assetId(), "evidenceIds", "Expected a UUID or omit the field", "ASSET_EVIDENCE_INVALID"));
                    break;
                }
                if (asset == null) {
                    continue;
                }
                var attachment = attachmentRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
                    TenantContext.currentTenant(),
                    CompanyContext.currentCompany(),
                    attachmentId
                );
                if (attachment.isEmpty()
                    || !"FIXED_ASSET".equals(attachment.get().getEntityType())
                    || !asset.getId().equals(attachment.get().getEntityId())
                    || !"COMMISSIONING_EVIDENCE".equals(attachment.get().getAttachmentType())) {
                    blockers.add(blocker(index, row.assetId(), "evidenceIds", "Commissioning evidence must be uploaded on this fixed asset", "ASSET_EVIDENCE_INVALID"));
                    break;
                }
            }
        }
    }

    private void validateBulkInventoryNumber(
        int index,
        UUID assetId,
        FixedAsset asset,
        String inventoryNumber,
        List<Map<String, Object>> blockers
    ) {
        numberRegistryRepository.findByTenantIdAndCompanyIdAndNormalizedNumberAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), normalized(inventoryNumber))
            .ifPresent(registry -> {
                AssetNumberRegistryStatus status = registry.getRegistryStatus();
                if (status == AssetNumberRegistryStatus.CORRECTED || status == AssetNumberRegistryStatus.RETIRED) {
                    blockers.add(blocker(index, assetId, "inventoryNumber", "Inventory number cannot be reused", "ASSET_INVENTORY_NUMBER_RETIRED"));
                    return;
                }
                if (registry.getFixedAssetId() != null && (asset == null || !registry.getFixedAssetId().equals(asset.getId()))) {
                    blockers.add(blocker(index, assetId, "inventoryNumber", "Inventory number belongs to another asset", "ASSET_INVENTORY_NUMBER_DUPLICATE"));
                }
            });
    }

    private Map<String, Object> blocker(int rowIndex, UUID assetId, String field, String message, String code) {
        return Map.of(
            "rowIndex", rowIndex,
            "assetId", assetId == null ? "" : assetId.toString(),
            "field", field,
            "message", message,
            "code", code
        );
    }

    private FixedAssetResponse transitionLifecycle(
        UUID id,
        VersionedAssetCommand request,
        String idempotencyKey,
        String eventType,
        FixedAssetLifecycleStatus target,
        FixedAssetLifecycleStatus... allowedSources
    ) {
        FixedAsset asset = find(id);
        requireVersion(asset, request == null ? null : request.expectedVersion());
        requireInventoryNumber(asset);
        requireNoActiveTransfer(asset);
        if (asset.getLifecycleStatus() == target) {
            return mapper.toResponse(asset);
        }
        requireLifecycle(asset, allowedSources);
        FixedAssetLifecycleStatus beforeLifecycle = asset.getLifecycleStatus();
        FixedAssetCustodyKind beforeCustody = asset.getCustodyKind();
        asset.setLifecycleStatus(target);
        appendAssetEvent(asset, eventType, beforeLifecycle, beforeCustody, request == null ? null : request.reason(), idempotencyKey);
        return mapper.toResponse(repository.save(asset));
    }

    private record BulkCommissionPlan(
        FixedAsset asset,
        FixedAssetBulkCommissionRequest.Row row,
        Employee employee,
        Department department
    ) {
    }

    private FixedAssetResponse sendToRepairNow(UUID id, VersionedAssetCommand request, String idempotencyKey) {
        FixedAsset asset = find(id);
        requireVersion(asset, request == null ? null : request.expectedVersion());
        requireInventoryNumber(asset);
        requireNoActiveTransfer(asset);
        requireLifecycle(asset, FixedAssetLifecycleStatus.IN_USE, FixedAssetLifecycleStatus.IDLE);
        FixedAssetLifecycleStatus beforeLifecycle = asset.getLifecycleStatus();
        FixedAssetCustodyKind beforeCustody = asset.getCustodyKind();
        asset.setLifecycleStatus(FixedAssetLifecycleStatus.UNDER_REPAIR);
        asset.setCustodyKind(FixedAssetCustodyKind.EXTERNAL);
        asset.setCustodianEmployeeId(null);
        asset.setDepartmentId(null);
        asset.setWarehouseId(null);
        asset.setStorageLocationId(null);
        appendAssetEvent(asset, "ASSET_SENT_TO_REPAIR", beforeLifecycle, beforeCustody, request == null ? null : request.reason(), idempotencyKey);
        return mapper.toResponse(repository.save(asset));
    }

    private FixedAssetResponse completeRepairNow(UUID id, VersionedAssetCommand request, String idempotencyKey) {
        FixedAsset asset = find(id);
        requireVersion(asset, request == null ? null : request.expectedVersion());
        requireInventoryNumber(asset);
        requireNoActiveTransfer(asset);
        requireLifecycle(asset, FixedAssetLifecycleStatus.UNDER_REPAIR);
        FixedAssetLifecycleStatus beforeLifecycle = asset.getLifecycleStatus();
        FixedAssetCustodyKind beforeCustody = asset.getCustodyKind();
        asset.setLifecycleStatus(FixedAssetLifecycleStatus.IDLE);
        appendAssetEvent(asset, "ASSET_REPAIR_COMPLETED", beforeLifecycle, beforeCustody, request == null ? null : request.reason(), idempotencyKey);
        return mapper.toResponse(repository.save(asset));
    }

    private FixedAssetResponse returnToSupplierNow(UUID id, VersionedAssetCommand request, String idempotencyKey) {
        FixedAsset asset = find(id);
        requireVersion(asset, request == null ? null : request.expectedVersion());
        requireNoActiveTransfer(asset);
        requireLifecycle(
            asset,
            FixedAssetLifecycleStatus.IN_WAREHOUSE,
            FixedAssetLifecycleStatus.ISSUED_PENDING_COMMISSIONING,
            FixedAssetLifecycleStatus.IDLE,
            FixedAssetLifecycleStatus.IN_USE,
            FixedAssetLifecycleStatus.UNDER_REPAIR
        );
        FixedAssetLifecycleStatus beforeLifecycle = asset.getLifecycleStatus();
        FixedAssetCustodyKind beforeCustody = asset.getCustodyKind();
        asset.setLifecycleStatus(FixedAssetLifecycleStatus.RETURNED_TO_SUPPLIER);
        asset.setCustodyKind(FixedAssetCustodyKind.EXTERNAL);
        asset.setCustodianEmployeeId(null);
        asset.setDepartmentId(null);
        asset.setWarehouseId(null);
        asset.setStorageLocationId(null);
        appendAssetEvent(asset, "ASSET_RETURNED_TO_SUPPLIER", beforeLifecycle, beforeCustody, request == null ? null : request.reason(), idempotencyKey);
        return mapper.toResponse(repository.save(asset));
    }

    private FixedAssetResponse disposeNow(UUID id, VersionedAssetCommand request, String idempotencyKey) {
        FixedAsset asset = find(id);
        requireVersion(asset, request == null ? null : request.expectedVersion());
        requireNoActiveTransfer(asset);
        requireLifecycle(
            asset,
            FixedAssetLifecycleStatus.IN_WAREHOUSE,
            FixedAssetLifecycleStatus.ISSUED_PENDING_COMMISSIONING,
            FixedAssetLifecycleStatus.IDLE,
            FixedAssetLifecycleStatus.IN_USE,
            FixedAssetLifecycleStatus.UNDER_REPAIR,
            FixedAssetLifecycleStatus.RETURNED_TO_SUPPLIER
        );
        FixedAssetLifecycleStatus beforeLifecycle = asset.getLifecycleStatus();
        FixedAssetCustodyKind beforeCustody = asset.getCustodyKind();
        asset.setLifecycleStatus(FixedAssetLifecycleStatus.DISPOSED);
        asset.setCustodyKind(FixedAssetCustodyKind.NONE);
        asset.setCustodianEmployeeId(null);
        asset.setDepartmentId(null);
        asset.setWarehouseId(null);
        asset.setStorageLocationId(null);
        asset.setDisposedAt(LocalDateTime.now());
        appendAssetEvent(asset, "ASSET_DISPOSED", beforeLifecycle, beforeCustody, request == null ? null : request.reason(), idempotencyKey);
        return mapper.toResponse(repository.save(asset));
    }

    @Transactional(readOnly = true)
    public FixedAsset findForApproval(UUID id) {
        return find(id);
    }

    @Transactional
    public void disposeFromApproval(UUID id, String comment) {
        FixedAsset asset = find(id);
        disposeNow(id, new VersionedAssetCommand(asset.getVersion(), comment), "APPROVAL:" + id);
    }

    @Transactional
    public void rejectDisposalFromApproval(UUID id, String comment) {
        find(id);
    }

    private void assignNumber(
        FixedAsset asset,
        String inventoryNumber,
        AssetNumberRegistrySource source,
        String eventType,
        String reason
    ) {
        String normalized = normalized(inventoryNumber);
        AssetNumberRegistry registry = numberRegistryRepository
            .findByTenantIdAndCompanyIdAndNormalizedNumberAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), normalized)
            .orElse(null);
        if (registry != null) {
            AssetNumberRegistryStatus status = registry.getRegistryStatus();
            if (status == AssetNumberRegistryStatus.CORRECTED || status == AssetNumberRegistryStatus.RETIRED) {
                throw new BusinessException("Inventory number cannot be reused", "ASSET_INVENTORY_NUMBER_RETIRED");
            }
            if (registry.getFixedAssetId() != null && !registry.getFixedAssetId().equals(asset.getId())) {
                throw new ConflictException("Inventory number already belongs to another fixed asset", "ASSET_INVENTORY_NUMBER_DUPLICATE");
            }
            registry.setFixedAssetId(asset.getId());
            registry.setRegistryStatus(AssetNumberRegistryStatus.ASSIGNED);
            registry.setAssignedAt(registry.getAssignedAt() == null ? LocalDateTime.now() : registry.getAssignedAt());
            registry.setSource(registry.getSource() == null ? source : registry.getSource());
            numberRegistryRepository.save(registry);
        } else {
            reserveRegistry(inventoryNumber, asset.getId(), source, AssetNumberRegistryStatus.ASSIGNED);
        }
        String before = asset.getInventoryNumber();
        asset.setInventoryNumber(inventoryNumber.trim());
        appendNumberEvent(asset, eventType, before, asset.getInventoryNumber(), reason);
    }

    private AssetNumberRegistry reserveRegistry(
        String inventoryNumber,
        UUID assetId,
        AssetNumberRegistrySource source,
        AssetNumberRegistryStatus status
    ) {
        String normalized = normalized(inventoryNumber);
        numberRegistryRepository.findByTenantIdAndCompanyIdAndNormalizedNumberAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), normalized)
            .ifPresent(existing -> {
                throw new ConflictException("Inventory number already exists", "ASSET_INVENTORY_NUMBER_DUPLICATE");
            });
        AssetNumberRegistry registry = new AssetNumberRegistry();
        registry.setCode("ANR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        registry.setName("Asset number " + inventoryNumber);
        registry.setInventoryNumber(inventoryNumber.trim());
        registry.setNormalizedNumber(normalized);
        registry.setFixedAssetId(assetId);
        registry.setSource(source);
        registry.setReservedAt(LocalDateTime.now());
        registry.setRegistryStatus(status);
        if (status == AssetNumberRegistryStatus.ASSIGNED) {
            registry.setAssignedAt(LocalDateTime.now());
        }
        return numberRegistryRepository.save(registry);
    }

    private void appendNumberEvent(FixedAsset asset, String eventType, String before, String after, String reason) {
        AssetEvent event = new AssetEvent();
        event.setCode("AE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        event.setName(eventType);
        event.setStatus("POSTED");
        event.setFixedAssetId(asset.getId());
        event.setEventType(eventType);
        event.setBeforeLifecycleStatus(asset.getLifecycleStatus());
        event.setAfterLifecycleStatus(asset.getLifecycleStatus());
        event.setBeforeCustodyKind(asset.getCustodyKind());
        event.setAfterCustodyKind(asset.getCustodyKind());
        event.setBeforeCustodyJson(before == null ? null : "{\"inventoryNumber\":\"" + escape(before) + "\"}");
        event.setAfterCustodyJson(after == null ? null : "{\"inventoryNumber\":\"" + escape(after) + "\"}");
        event.setEffectiveDate(LocalDate.now());
        event.setRecordedAt(LocalDateTime.now());
        event.setActorUsername("system");
        event.setReason(trimToNull(reason));
        event.setSourceDocumentType("FIXED_ASSET");
        event.setSourceDocumentId(asset.getId());
        event.setAggregateVersion(asset.getVersion());
        eventRepository.save(event);
        publishIntegrationEvent(
            asset,
            eventType,
            asset.getLifecycleStatus(),
            asset.getCustodyKind(),
            "FIXED_ASSET",
            asset.getId(),
            LocalDate.now(),
            "system",
            null
        );
    }

    private void appendAssetEvent(
        FixedAsset asset,
        String eventType,
        FixedAssetLifecycleStatus beforeLifecycle,
        FixedAssetCustodyKind beforeCustody,
        String reason,
        String idempotencyKey
    ) {
        AssetEvent event = new AssetEvent();
        event.setCode("AE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        event.setName(eventType);
        event.setStatus("POSTED");
        event.setFixedAssetId(asset.getId());
        event.setEventType(eventType);
        event.setIdempotencyKey(trimToNull(idempotencyKey));
        event.setBeforeLifecycleStatus(beforeLifecycle);
        event.setAfterLifecycleStatus(asset.getLifecycleStatus());
        event.setBeforeCustodyKind(beforeCustody);
        event.setAfterCustodyKind(asset.getCustodyKind());
        event.setEffectiveDate(LocalDate.now());
        event.setRecordedAt(LocalDateTime.now());
        event.setActorUsername(currentActor());
        event.setReason(trimToNull(reason));
        event.setSourceDocumentType("FIXED_ASSET");
        event.setSourceDocumentId(asset.getId());
        event.setAggregateVersion(asset.getVersion());
        eventRepository.save(event);
        publishIntegrationEvent(
            asset,
            eventType,
            beforeLifecycle,
            beforeCustody,
            "FIXED_ASSET",
            asset.getId(),
            LocalDate.now(),
            currentActor(),
            idempotencyKey
        );
    }

    private void publishIntegrationEvent(
        FixedAsset asset,
        String eventType,
        FixedAssetLifecycleStatus beforeLifecycle,
        FixedAssetCustodyKind beforeCustody,
        String sourceDocumentType,
        UUID sourceDocumentId,
        LocalDate effectiveDate,
        String actor,
        String idempotencyKey
    ) {
        if (assetIntegrationEventPublisher != null) {
            assetIntegrationEventPublisher.publish(
                asset,
                eventType,
                beforeLifecycle,
                beforeCustody,
                sourceDocumentType,
                sourceDocumentId,
                effectiveDate,
                actor,
                idempotencyKey
            );
        }
    }

    private FixedAsset find(UUID id) {
        return repository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), id)
            .filter(asset -> !roleDataScopeService.isDepartmentAssetScoped()
                || roleDataScopeService.canAccessDepartmentAsset(asset.getDepartmentId(), asset.getWarehouseId()))
            .orElseThrow(() -> new NotFoundException("Fixed asset not found: " + id));
    }

    private Specification<FixedAsset> departmentScope() {
        return (root, query, cb) -> {
            if (!roleDataScopeService.isDepartmentAssetScoped()) return cb.conjunction();
            UUID departmentId = roleDataScopeService.issueDepartmentId();
            var department = departmentId == null ? cb.disjunction() : cb.equal(root.get("departmentId"), departmentId);
            var scope = roleDataScopeService.currentScope();
            return !scope.warehouseScoped() || scope.warehouseIds().isEmpty() ? department
                : cb.or(department, root.get("warehouseId").in(scope.warehouseIds()));
        };
    }

    private void requireVersion(FixedAsset asset, Long expectedVersion) {
        if (expectedVersion == null) {
            throw new BadRequestException("expectedVersion is required", "ASSET_VERSION_CONFLICT");
        }
        if (!Objects.equals(asset.getVersion(), expectedVersion)) {
            throw new ConflictException("Fixed asset version conflict", "ASSET_VERSION_CONFLICT");
        }
    }

    private void requireInventoryNumber(FixedAsset asset) {
        if (!hasText(asset.getInventoryNumber())) {
            throw new BusinessException("Inventory number is required before lifecycle transition", "ASSET_INVENTORY_NUMBER_REQUIRED");
        }
    }

    private void requireNoActiveTransfer(FixedAsset asset) {
        if (asset.getActiveTransferId() != null || asset.getCustodyKind() == FixedAssetCustodyKind.IN_TRANSIT) {
            throw new BusinessException("Fixed asset has an active handover", "ASSET_ALREADY_ALLOCATED");
        }
    }

    private void requireLifecycle(FixedAsset asset, FixedAssetLifecycleStatus... allowedSources) {
        for (FixedAssetLifecycleStatus status : allowedSources) {
            if (asset.getLifecycleStatus() == status) {
                return;
            }
        }
        throw new BusinessException("Fixed asset lifecycle transition is not allowed", "ASSET_INVALID_TRANSITION");
    }

    private String normalized(String inventoryNumber) {
        return inventoryNumber == null ? "" : inventoryNumber.trim().toUpperCase(Locale.ROOT);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void putSourceDocumentName(Map<UUID, String> names, UUID id, String value) {
        String name = trimToNull(value);
        if (id == null || name == null || isDocumentCode(name)) {
            return;
        }
        names.put(id, name);
    }

    private String fixedAssetSourceDocumentName(FixedAsset asset) {
        if (asset == null) {
            return null;
        }
        if (asset.getMaterial() != null) {
            return trimToNull(asset.getMaterial().getName());
        }
        return asset.getName();
    }

    private boolean isDocumentCode(String value) {
        return value.matches("(?i)^[A-Z]{1,8}-[A-Z0-9-]+$");
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String currentActor() {
        return "system";
    }

    private BusinessException waveOneBoundary() {
        return new BusinessException(
            "Fixed asset lifecycle commands are not implemented in Wave 1 foundation",
            "ASSET_INVALID_TRANSITION"
        );
    }
}
