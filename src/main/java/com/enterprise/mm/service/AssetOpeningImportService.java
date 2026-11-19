package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AssetEvent;
import com.enterprise.mm.entity.AssetNumberRegistry;
import com.enterprise.mm.entity.AssetOpeningImport;
import com.enterprise.mm.entity.AssetOpeningImportLine;
import com.enterprise.mm.entity.Department;
import com.enterprise.mm.entity.Equipment;
import com.enterprise.mm.entity.FixedAsset;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.enums.AssetNumberRegistrySource;
import com.enterprise.mm.enums.AssetNumberRegistryStatus;
import com.enterprise.mm.enums.AssetOpeningImportStatus;
import com.enterprise.mm.enums.FixedAssetCustodyKind;
import com.enterprise.mm.enums.FixedAssetLifecycleStatus;
import com.enterprise.mm.enums.MaterialStatus;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.AssetFoundationMapper;
import com.enterprise.mm.payload.AssetOpeningImportCreateRequest;
import com.enterprise.mm.payload.AssetOpeningImportLineRequest;
import com.enterprise.mm.payload.AssetOpeningImportPostRequest;
import com.enterprise.mm.payload.AssetOpeningImportResponse;
import com.enterprise.mm.payload.AssetOpeningImportValidateRequest;
import com.enterprise.mm.payload.VersionedAssetCommand;
import com.enterprise.mm.repository.AssetEventRepository;
import com.enterprise.mm.repository.AssetNumberRegistryRepository;
import com.enterprise.mm.repository.AssetOpeningImportLineRepository;
import com.enterprise.mm.repository.AssetOpeningImportRepository;
import com.enterprise.mm.repository.DepartmentRepository;
import com.enterprise.mm.repository.EquipmentRepository;
import com.enterprise.mm.repository.FixedAssetRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@com.enterprise.mm.security.pilot.PilotCapabilityBoundary(value = com.enterprise.mm.security.pilot.PilotCapability.FIXED_ASSET_EXECUTION, reads = {"get", "findForApproval"})
public class AssetOpeningImportService {
    public static final String APPROVAL_MODULE = "ASSET_OPENING_IMPORT";

    private final AssetOpeningImportRepository repository;
    private final AssetOpeningImportLineRepository lineRepository;
    private final FixedAssetRepository fixedAssetRepository;
    private final AssetNumberRegistryRepository numberRegistryRepository;
    private final AssetEventRepository eventRepository;
    private final MaterialRepository materialRepository;
    private final EquipmentRepository equipmentRepository;
    private final DepartmentRepository departmentRepository;
    private final WarehouseRepository warehouseRepository;
    private final AssetFoundationMapper mapper;
    private final IdempotencyService idempotencyService;
    private final ApprovalEngineService approvalEngineService;

    @Transactional
    public AssetOpeningImportResponse validate(AssetOpeningImportValidateRequest request) {
        validateLines(request == null ? List.of() : request.lines());
        AssetOpeningImport preview = new AssetOpeningImport();
        preview.setCode("AOP-VALIDATION");
        preview.setName("Asset opening import validation");
        preview.setOpeningImportStatus(AssetOpeningImportStatus.VALIDATED);
        preview.setValidatedAt(LocalDateTime.now());
        return mapper.toResponse(preview);
    }

    @Transactional
    public AssetOpeningImportResponse create(AssetOpeningImportCreateRequest request) {
        return create(request, null);
    }

    @Transactional
    public AssetOpeningImportResponse create(AssetOpeningImportCreateRequest request, String idempotencyKey) {
        if (hasText(idempotencyKey)) {
            return idempotencyService.execute(
                "ASSET_OPENING_IMPORT_CREATE", null, idempotencyKey, request, AssetOpeningImportResponse.class,
                () -> createNow(request)
            );
        }
        return createNow(request);
    }

    private AssetOpeningImportResponse createNow(AssetOpeningImportCreateRequest request) {
        List<AssetOpeningImportLineRequest> requestedLines = request == null || request.lines() == null ? List.of() : request.lines();
        validateLines(requestedLines);
        AssetOpeningImport openingImport = new AssetOpeningImport();
        openingImport.setCode("AOI-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        openingImport.setName("Asset opening import");
        openingImport.setSourceFileId(request == null ? null : request.sourceFileId());
        openingImport.setOpeningImportStatus(AssetOpeningImportStatus.VALIDATED);
        openingImport.setValidatedAt(LocalDateTime.now());
        openingImport = repository.save(openingImport);
        int sequence = 1;
        for (AssetOpeningImportLineRequest lineRequest : requestedLines) {
            AssetOpeningImportLine line = new AssetOpeningImportLine();
            int lineNumber = sequence++;
            line.setCode("AOIL-" + lineNumber);
            line.setName("Asset opening import line " + lineNumber);
            line.setStatus("VALID");
            line.setAssetOpeningImportId(openingImport.getId());
            line.setMaterialId(lineRequest.materialId());
            line.setEquipmentId(lineRequest.equipmentId());
            line.setInventoryNumber(trimToNull(lineRequest.inventoryNumber()));
            line.setSerialNumber(trimToNull(lineRequest.serialNumber()));
            line.setLifecycleStatus(parseLifecycle(lineRequest.lifecycleStatus(), FixedAssetLifecycleStatus.IN_WAREHOUSE));
            line.setCustodyKind(parseCustody(lineRequest.custodyKind(), FixedAssetCustodyKind.WAREHOUSE));
            line.setWarehouseId(lineRequest.warehouseId());
            line.setDepartmentId(lineRequest.departmentId());
            line.setAcquisitionValue(lineRequest.acquisitionValue());
            line.setValidationStatus("VALID");
            lineRepository.save(line);
        }
        return mapper.toResponse(openingImport);
    }

    @Transactional(readOnly = true)
    public AssetOpeningImportResponse get(UUID id) {
        return mapper.toResponse(find(id));
    }

    @Transactional
    public AssetOpeningImportResponse submit(UUID id, VersionedAssetCommand request) {
        AssetOpeningImport openingImport = find(id);
        requireVersion(openingImport, request == null ? null : request.expectedVersion());
        if (openingImport.getOpeningImportStatus() == AssetOpeningImportStatus.POSTED) {
            return mapper.toResponse(openingImport);
        }
        if (openingImport.getOpeningImportStatus() != AssetOpeningImportStatus.VALIDATED) {
            throw business("Only validated opening imports can be submitted", "ASSET_INVALID_TRANSITION");
        }
        openingImport.setOpeningImportStatus(AssetOpeningImportStatus.SUBMITTED);
        AssetOpeningImport saved = repository.save(openingImport);
        approvalEngineService.start(APPROVAL_MODULE, id);
        return mapper.toResponse(saved);
    }

    @Transactional
    public AssetOpeningImportResponse post(UUID id, AssetOpeningImportPostRequest request) {
        return post(id, request, null);
    }

    @Transactional
    public AssetOpeningImportResponse post(UUID id, AssetOpeningImportPostRequest request, String idempotencyKey) {
        if (hasText(idempotencyKey)) {
            return idempotencyService.execute(
                "ASSET_OPENING_IMPORT_POST", id, idempotencyKey, request, AssetOpeningImportResponse.class,
                () -> find(id),
                () -> postNow(id, request, idempotencyKey)
            );
        }
        return postNow(id, request, null);
    }

    private AssetOpeningImportResponse postNow(UUID id, AssetOpeningImportPostRequest request, String idempotencyKey) {
        AssetOpeningImport openingImport = find(id);
        requireVersion(openingImport, request == null ? null : request.expectedVersion());
        if (openingImport.getOpeningImportStatus() == AssetOpeningImportStatus.POSTED) {
            return mapper.toResponse(openingImport);
        }
        if (openingImport.getOpeningImportStatus() != AssetOpeningImportStatus.APPROVED) {
            throw business("Opening import is not ready to post", "ASSET_INVALID_TRANSITION");
        }
        List<AssetOpeningImportLine> importLines = lines(openingImport.getId());
        validateStoredLines(importLines);
        for (AssetOpeningImportLine line : importLines) {
            validateRegistryAvailable(line.getInventoryNumber());
            FixedAsset asset = createAsset(openingImport, line);
            registerNumber(line.getInventoryNumber(), asset.getId());
            appendPostedEvent(asset, openingImport, request == null ? null : request.reason(), idempotencyKey);
        }
        openingImport.setOpeningImportStatus(AssetOpeningImportStatus.POSTED);
        openingImport.setPostedAt(LocalDateTime.now());
        openingImport.setPostedBy("system");
        return mapper.toResponse(repository.save(openingImport));
    }

    private FixedAsset createAsset(AssetOpeningImport openingImport, AssetOpeningImportLine line) {
        FixedAsset asset = new FixedAsset();
        asset.setCode("FA-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        String inventoryNumber = trimToNull(line.getInventoryNumber());
        asset.setName(inventoryNumber == null ? "Opening fixed asset" : "Opening fixed asset " + inventoryNumber);
        asset.setStatus("ACTIVE");
        asset.setMaterialId(line.getMaterialId());
        asset.setEquipmentId(line.getEquipmentId());
        asset.setOpeningSourceType("ASSET_OPENING_IMPORT");
        asset.setOpeningSourceId(line.getId());
        asset.setAssetCode(asset.getCode());
        asset.setInventoryNumber(trimToNull(line.getInventoryNumber()));
        asset.setSerialNumber(trimToNull(line.getSerialNumber()));
        asset.setLifecycleStatus(line.getLifecycleStatus());
        asset.setCustodyKind(line.getCustodyKind());
        asset.setWarehouseId(line.getWarehouseId());
        asset.setDepartmentId(line.getDepartmentId());
        asset.setAcquisitionValue(line.getAcquisitionValue());
        asset.setAcquisitionEvidenceType("ASSET_OPENING_IMPORT");
        asset.setAcquisitionEvidenceId(openingImport.getId());
        return fixedAssetRepository.save(asset);
    }

    private void registerNumber(String inventoryNumber, UUID fixedAssetId) {
        String normalized = normalized(inventoryNumber);
        AssetNumberRegistry row = new AssetNumberRegistry();
        row.setCode("ANR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        row.setName("Asset number " + inventoryNumber);
        row.setInventoryNumber(inventoryNumber.trim());
        row.setNormalizedNumber(normalized);
        row.setFixedAssetId(fixedAssetId);
        row.setSource(AssetNumberRegistrySource.OPENING_IMPORT);
        row.setReservedAt(LocalDateTime.now());
        row.setAssignedAt(LocalDateTime.now());
        row.setRegistryStatus(AssetNumberRegistryStatus.ASSIGNED);
        numberRegistryRepository.save(row);
    }

    private void appendPostedEvent(FixedAsset asset, AssetOpeningImport openingImport, String reason, String idempotencyKey) {
        AssetEvent event = new AssetEvent();
        event.setCode("AE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        event.setName("ASSET_OPENING_IMPORT_POSTED");
        event.setStatus("POSTED");
        event.setFixedAssetId(asset.getId());
        event.setEventType("ASSET_OPENING_IMPORT_POSTED");
        event.setIdempotencyKey(trimToNull(idempotencyKey));
        event.setAfterLifecycleStatus(asset.getLifecycleStatus());
        event.setAfterCustodyKind(asset.getCustodyKind());
        event.setEffectiveDate(LocalDate.now());
        event.setRecordedAt(LocalDateTime.now());
        event.setActorUsername("system");
        event.setReason(trimToNull(reason));
        event.setSourceDocumentType("ASSET_OPENING_IMPORT");
        event.setSourceDocumentId(openingImport.getId());
        event.setAggregateVersion(asset.getVersion());
        eventRepository.save(event);
    }

    private void validateLines(List<AssetOpeningImportLineRequest> requestedLines) {
        if (requestedLines == null || requestedLines.isEmpty()) {
            throw bad("Opening import requires at least one line", "ASSET_EVIDENCE_REQUIRED");
        }
        Set<UUID> equipmentIds = new HashSet<>();
        Set<String> inventoryNumbers = new HashSet<>();
        for (AssetOpeningImportLineRequest line : requestedLines) {
            if (line == null || line.materialId() == null) {
                throw bad("materialId is required", "ASSET_EVIDENCE_REQUIRED");
            }
            requireFixedAssetMaterial(line.materialId());
            FixedAssetLifecycleStatus lifecycle = parseLifecycle(line.lifecycleStatus(), FixedAssetLifecycleStatus.IN_WAREHOUSE);
            FixedAssetCustodyKind custody = parseCustody(line.custodyKind(), FixedAssetCustodyKind.WAREHOUSE);
            if (!hasText(line.inventoryNumber())) {
                throw bad("inventoryNumber is required", "ASSET_INVENTORY_NUMBER_REQUIRED");
            }
            String normalizedInventoryNumber = normalized(line.inventoryNumber());
            if (!inventoryNumbers.add(normalizedInventoryNumber)) {
                throw new ConflictException("Duplicate inventory number in opening import", "ASSET_INVENTORY_NUMBER_DUPLICATE");
            }
            validateRegistryAvailable(line.inventoryNumber());
            if (line.equipmentId() != null) {
                requireEligibleEquipment(line.equipmentId());
                if (!equipmentIds.add(line.equipmentId())) {
                    throw business("Equipment is already linked to another fixed asset", "ASSET_EQUIPMENT_ALREADY_LINKED");
                }
            }
            validateCustodyReferences(custody, lifecycle, line.warehouseId(), null, line.departmentId());
        }
    }

    private void validateStoredLines(List<AssetOpeningImportLine> importLines) {
        if (importLines == null || importLines.isEmpty()) {
            throw bad("Opening import requires at least one line", "ASSET_EVIDENCE_REQUIRED");
        }
        Set<UUID> equipmentIds = new HashSet<>();
        Set<String> inventoryNumbers = new HashSet<>();
        for (AssetOpeningImportLine line : importLines) {
            requireFixedAssetMaterial(line.getMaterialId());
            if (!hasText(line.getInventoryNumber())) {
                throw bad("inventoryNumber is required", "ASSET_INVENTORY_NUMBER_REQUIRED");
            }
            String normalizedInventoryNumber = normalized(line.getInventoryNumber());
            if (!inventoryNumbers.add(normalizedInventoryNumber)) {
                throw new ConflictException("Duplicate inventory number in opening import", "ASSET_INVENTORY_NUMBER_DUPLICATE");
            }
            validateRegistryAvailable(line.getInventoryNumber());
            if (line.getEquipmentId() != null) {
                requireEligibleEquipment(line.getEquipmentId());
                if (!equipmentIds.add(line.getEquipmentId())) {
                    throw business("Equipment is already linked to another fixed asset", "ASSET_EQUIPMENT_ALREADY_LINKED");
                }
            }
            validateCustodyReferences(
                line.getCustodyKind(),
                line.getLifecycleStatus(),
                line.getWarehouseId(),
                null,
                line.getDepartmentId()
            );
        }
    }

    private Material requireFixedAssetMaterial(UUID materialId) {
        Material material = materialRepository.findByIdAndIsDeletedFalse(materialId)
            .orElseThrow(() -> new NotFoundException("Material not found: " + materialId));
        if (!Objects.equals(material.getTenantId(), TenantContext.currentTenant())
            || !Objects.equals(material.getCompanyId(), CompanyContext.currentCompany())) {
            throw business("Material does not belong to current company", "ASSET_REFERENCE_SCOPE_VIOLATION");
        }
        if (!MaterialStatus.ACTIVE.name().equals(material.getStatus())) {
            throw business("Material is not active", "ASSET_REFERENCE_INACTIVE");
        }
        if (material.getMaterialType() != MaterialType.FIXED_ASSET) {
            throw business("Opening import requires FIXED_ASSET material", "ASSET_MATERIAL_TYPE_REQUIRED");
        }
        return material;
    }

    private Equipment requireEligibleEquipment(UUID equipmentId) {
        Equipment equipment = equipmentRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), equipmentId)
            .orElseThrow(() -> new NotFoundException("Equipment not found: " + equipmentId));
        if (!"ACTIVE".equalsIgnoreCase(nullToFallback(equipment.getStatus(), "ACTIVE"))) {
            throw business("Equipment is not active", "ASSET_REFERENCE_INACTIVE");
        }
        if (fixedAssetRepository.existsByTenantIdAndCompanyIdAndEquipmentIdAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), equipmentId)) {
            throw business("Equipment is already linked to another fixed asset", "ASSET_EQUIPMENT_ALREADY_LINKED");
        }
        return equipment;
    }

    private void validateCustodyReferences(
        FixedAssetCustodyKind custody,
        FixedAssetLifecycleStatus lifecycle,
        UUID warehouseId,
        UUID storageLocationId,
        UUID departmentId
    ) {
        if (lifecycle == FixedAssetLifecycleStatus.IN_USE) {
            throw business("Historical in-use opening import requires commissioning details not present in this API", "ASSET_OPENING_IMPORT_IN_USE_UNSUPPORTED");
        }
        if (custody == FixedAssetCustodyKind.WAREHOUSE) {
            if (warehouseId == null) {
                throw bad("warehouseId is required for warehouse custody", "ASSET_REFERENCE_REQUIRED");
            }
            requireWarehouse(warehouseId);
            if (departmentId != null) {
                throw business("Department cannot be set for warehouse custody", "ASSET_INVALID_CUSTODY_REFERENCE");
            }
            return;
        }
        if (custody == FixedAssetCustodyKind.DEPARTMENT) {
            if (departmentId == null) {
                throw bad("departmentId is required for department custody", "ASSET_REFERENCE_REQUIRED");
            }
            requireDepartment(departmentId);
            if (warehouseId != null || storageLocationId != null) {
                throw business("Warehouse references cannot be set for department custody", "ASSET_INVALID_CUSTODY_REFERENCE");
            }
            return;
        }
        if (warehouseId != null || storageLocationId != null || departmentId != null) {
            throw business("Custody references are incompatible with selected custody", "ASSET_INVALID_CUSTODY_REFERENCE");
        }
    }

    private Warehouse requireWarehouse(UUID warehouseId) {
        return warehouseRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), warehouseId)
            .orElseThrow(() -> new NotFoundException("Warehouse not found: " + warehouseId));
    }

    private Department requireDepartment(UUID departmentId) {
        return departmentRepository.findByIdAndTenantIdAndCompanyIdAndIsDeletedFalse(
                departmentId, TenantContext.currentTenant(), CompanyContext.currentCompany())
            .orElseThrow(() -> new NotFoundException("Department not found: " + departmentId));
    }

    private void validateRegistryAvailable(String inventoryNumber) {
        numberRegistryRepository.findByTenantIdAndCompanyIdAndNormalizedNumberAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), normalized(inventoryNumber))
            .ifPresent(row -> {
                throw new ConflictException("Inventory number already exists", "ASSET_INVENTORY_NUMBER_DUPLICATE");
            });
    }

    @Transactional(readOnly = true)
    public AssetOpeningImport findForApproval(UUID id) {
        return find(id);
    }

    @Transactional
    public void markSubmittedFromApproval(UUID id) {
        AssetOpeningImport openingImport = find(id);
        if (openingImport.getOpeningImportStatus() != AssetOpeningImportStatus.SUBMITTED) {
            openingImport.setOpeningImportStatus(AssetOpeningImportStatus.SUBMITTED);
            repository.save(openingImport);
        }
    }

    @Transactional
    public void markApprovedFromApproval(UUID id) {
        AssetOpeningImport openingImport = find(id);
        if (openingImport.getOpeningImportStatus() != AssetOpeningImportStatus.APPROVED) {
            openingImport.setOpeningImportStatus(AssetOpeningImportStatus.APPROVED);
            repository.save(openingImport);
        }
    }

    @Transactional
    public void markRejectedFromApproval(UUID id) {
        AssetOpeningImport openingImport = find(id);
        if (openingImport.getOpeningImportStatus() != AssetOpeningImportStatus.REJECTED) {
            openingImport.setOpeningImportStatus(AssetOpeningImportStatus.REJECTED);
            repository.save(openingImport);
        }
    }

    private AssetOpeningImport find(UUID id) {
        return repository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), id)
            .orElseThrow(() -> new NotFoundException("Asset opening import not found: " + id));
    }

    private List<AssetOpeningImportLine> lines(UUID id) {
        return lineRepository.findByTenantIdAndCompanyIdAndAssetOpeningImportIdAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), id);
    }

    private void requireVersion(AssetOpeningImport openingImport, Long expectedVersion) {
        if (expectedVersion == null) {
            throw bad("expectedVersion is required", "ASSET_VERSION_CONFLICT");
        }
        if (!Objects.equals(openingImport.getVersion(), expectedVersion)) {
            throw new ConflictException("Opening import version conflict", "ASSET_VERSION_CONFLICT");
        }
    }

    private FixedAssetLifecycleStatus parseLifecycle(String value, FixedAssetLifecycleStatus fallback) {
        if (!hasText(value)) {
            return fallback;
        }
        try {
            return FixedAssetLifecycleStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw bad("Unknown fixed asset lifecycle status: " + value, "ASSET_INVALID_TRANSITION");
        }
    }

    private FixedAssetCustodyKind parseCustody(String value, FixedAssetCustodyKind fallback) {
        if (!hasText(value)) {
            return fallback;
        }
        try {
            return FixedAssetCustodyKind.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw bad("Unknown fixed asset custody kind: " + value, "ASSET_INVALID_TRANSITION");
        }
    }

    private String normalized(String inventoryNumber) {
        return inventoryNumber == null ? "" : inventoryNumber.trim().toUpperCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String nullToFallback(String value, String fallback) {
        return hasText(value) ? value : fallback;
    }

    private BadRequestException bad(String message, String code) {
        return new BadRequestException(message, code);
    }

    private BusinessException business(String message, String code) {
        return new BusinessException(message, code);
    }
}
