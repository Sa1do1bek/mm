package com.enterprise.mm.service;

import com.enterprise.mm.payload.*;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.MaterialMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.MaterialCostObservation;
import com.enterprise.mm.entity.MaterialGroup;
import com.enterprise.mm.entity.Manufacturer;
import com.enterprise.mm.entity.MaterialValuationMethodHistory;
import com.enterprise.mm.entity.MXIK;
import com.enterprise.mm.entity.TnvedCode;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.enums.HazardClass;
import com.enterprise.mm.enums.BatchTrackingPolicy;
import com.enterprise.mm.enums.MaterialGroupStatus;
import com.enterprise.mm.enums.MaterialStatus;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.enums.MaterialSubtype;
import com.enterprise.mm.enums.SerialTrackingPolicy;
import com.enterprise.mm.enums.TrackingMode;
import com.enterprise.mm.enums.ValuationMethod;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.ManufacturerRepository;
import com.enterprise.mm.repository.MaterialGroupRepository;
import com.enterprise.mm.repository.MaterialValuationMethodHistoryRepository;
import com.enterprise.mm.repository.MXIKRepository;
import com.enterprise.mm.repository.TnvedCodeRepository;
import com.enterprise.mm.repository.UnitOfMeasureRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor(onConstructor_ = @org.springframework.beans.factory.annotation.Autowired)
public class MaterialService {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.enterprise.mm.warehouse.packaging.ReturnablePackagingLotRepository returnablePackagingLots;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ExactOpeningImportGuard openingImportGuard;

    private void guardOpeningImport(UUID companyId) {
        if (openingImportGuard != null) openingImportGuard.assertOperationsAllowed(companyId);
    }

    private final MaterialRepository repository;
    private final MaterialMapper mapper;
    private final String entityName = "Material";
    private final MXIKRepository mxikRepository;
    private final TnvedCodeRepository tnvedCodeRepository;
    private final MaterialGroupRepository materialGroupRepository;
    private final UnitOfMeasureRepository unitOfMeasureRepository;
    private final MaterialValuationMethodHistoryRepository valuationMethodHistoryRepository;
    private final MaterialUsageService materialUsageService;
    private final AuditService auditService;
    private final MaterialAttributeService materialAttributeService;
    private final ManufacturerRepository manufacturerRepository;
    private final MaterialAttributeValidationService materialAttributeValidationService;
    private final MaterialAdviceService materialAdviceService;
    private final MaterialCostObservationService materialCostObservationService;
    private final MaterialWmsIntegrationPublisher materialWmsIntegrationPublisher;
    @org.springframework.beans.factory.annotation.Autowired
    private MaterialBusinessTypePolicy materialBusinessTypePolicy = new MaterialBusinessTypePolicy();
    @org.springframework.beans.factory.annotation.Value("${app.features.fixed-assets.enabled:${FIXED_ASSETS_ENABLED:false}}")
    private boolean fixedAssetsEnabled;

    public MaterialService(
        MaterialRepository repository,
        MaterialMapper mapper,
        MXIKRepository mxikRepository,
        TnvedCodeRepository tnvedCodeRepository,
        MaterialGroupRepository materialGroupRepository,
        UnitOfMeasureRepository unitOfMeasureRepository,
        MaterialValuationMethodHistoryRepository valuationMethodHistoryRepository,
        MaterialUsageService materialUsageService,
        AuditService auditService,
        MaterialAttributeService materialAttributeService,
        ManufacturerRepository manufacturerRepository,
        MaterialAttributeValidationService materialAttributeValidationService,
        MaterialAdviceService materialAdviceService
    ) {
        this(
            repository,
            mapper,
            mxikRepository,
            tnvedCodeRepository,
            materialGroupRepository,
            unitOfMeasureRepository,
            valuationMethodHistoryRepository,
            materialUsageService,
            auditService,
            materialAttributeService,
            manufacturerRepository,
            materialAttributeValidationService,
            materialAdviceService,
            null,
            null
        );
    }

    public MaterialService(
        MaterialRepository repository,
        MaterialMapper mapper,
        MXIKRepository mxikRepository,
        TnvedCodeRepository tnvedCodeRepository,
        MaterialGroupRepository materialGroupRepository,
        UnitOfMeasureRepository unitOfMeasureRepository,
        MaterialValuationMethodHistoryRepository valuationMethodHistoryRepository,
        MaterialUsageService materialUsageService,
        AuditService auditService,
        MaterialAttributeService materialAttributeService,
        ManufacturerRepository manufacturerRepository,
        MaterialAttributeValidationService materialAttributeValidationService,
        MaterialAdviceService materialAdviceService,
        MaterialWmsIntegrationPublisher materialWmsIntegrationPublisher
    ) {
        this(
            repository,
            mapper,
            mxikRepository,
            tnvedCodeRepository,
            materialGroupRepository,
            unitOfMeasureRepository,
            valuationMethodHistoryRepository,
            materialUsageService,
            auditService,
            materialAttributeService,
            manufacturerRepository,
            materialAttributeValidationService,
            materialAdviceService,
            null,
            materialWmsIntegrationPublisher
        );
    }
    protected void beforeCreate(Material entity, MaterialCreateRequest request) {
        requireRequest(entity);
        if (entity.getCompanyId() == null) {
            entity.setCompanyId(CompanyContext.currentCompany());
        }
        normalize(entity);
        if (blank(entity.getCode())) {
            String prefix = entityName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_") + "-";
            for (int attempt = 0; attempt < 5; attempt++) {
                String candidate = prefix + UUID.randomUUID().toString().replace("-", "")
                    .substring(0, 12).toUpperCase(Locale.ROOT);
                if (!existsCode(TenantContext.currentTenant(), CompanyContext.currentCompany(), candidate, null)) {
                    entity.setCode(candidate);
                    break;
                }
            }
            if (blank(entity.getCode())) {
                throw new BusinessException("Unable to generate a unique material code", "material.code.generation_failed");
            }
        }
        entity.setStatus(normalizeStatus(entity.getStatus(), MaterialStatus.ACTIVE));
        entity.setValuationMethod(normalizeValuationMethod(entity.getValuationMethod()));
        entity.setHazardClass(normalizeHazardClass(entity.getHazardClass()));
        applyNumericDefaults(entity);
        validateMaterial(entity, null);
        validateManufacturer(entity);
        materialAttributeValidationService.validate(entity.getMaterialGroupId(), request.getAttributes());
        resolveProductCodes(entity);
        entity.setMaterialType(materialBusinessTypePolicy.normalizeMaterialType(entity.getMaterialType(), entity.getMxik()));
        entity.setMaterialSubtype(materialBusinessTypePolicy.normalizeMaterialSubtype(
            entity.getMaterialType(), request.getMaterialSubtype()));
        normalizeTrackingMode(entity);
        requireFixedAssetsEnabledForMaterialCreate(entity.getMaterialType());
        applyAverageCostSelection(entity, request);
    }
    @Transactional
    public MaterialResponse markObsolete(UUID id, MaterialLifecycleRequest request) {
        Material material = findEntity(id);
        guardOpeningImport(material.getCompanyId());
        requireStatus(material, MaterialStatus.ACTIVE, MaterialStatus.INACTIVE);
        String reason = requireReason(request, "mark material obsolete");
        UUID replacementId = request.replacementMaterialId();
        if (replacementId != null) {
            Material replacement = loadReplacement(replacementId);
            if (replacement.getId().equals(material.getId())) {
                throw new BusinessException("Replacement material cannot be the same material", "material.replacement.same");
            }
            if (!MaterialStatus.ACTIVE.name().equals(replacement.getStatus())) {
                throw new BusinessException("Replacement material must be ACTIVE", "material.replacement.active");
            }
            material.setReplacementMaterialId(replacement.getId());
        }
        material.setStatus(MaterialStatus.OBSOLETE.name());
        material.setLifecycleReason(reason);
        material.setLifecycleChangedAt(LocalDateTime.now());
        Material saved = repository.saveAndFlush(material);
        materialWmsIntegrationPublisher.publish(saved);
        auditService.log("Material", id, "MARK_OBSOLETE", material.getLifecycleReason(), null, null);
        return mapper.toResponse(saved);
    }
    /**
     * TODO item 4 ("Назначить замену"): unlike markObsolete, this keeps the material's
     * current lifecycle status untouched. It exists for the shortage/below-safety
     * workflow in the inventory-availability drawer, where an operator wants to point
     * an ACTIVE material at a substitute without deprecating it.
     */
    @Transactional
    public MaterialResponse assignReplacement(UUID id, MaterialLifecycleRequest request) {
        Material material = findEntity(id);
        guardOpeningImport(material.getCompanyId());
        requireStatus(material, MaterialStatus.ACTIVE, MaterialStatus.INACTIVE);
        String reason = requireReason(request, "assign replacement material");
        UUID replacementId = request.replacementMaterialId();
        if (replacementId == null) {
            throw new BusinessException("replacementMaterialId is required", "material.replacement.required");
        }
        Material replacement = loadReplacement(replacementId);
        if (replacement.getId().equals(material.getId())) {
            throw new BusinessException("Replacement material cannot be the same material", "material.replacement.same");
        }
        if (!MaterialStatus.ACTIVE.name().equals(replacement.getStatus())) {
            throw new BusinessException("Replacement material must be ACTIVE", "material.replacement.active");
        }
        material.setReplacementMaterialId(replacement.getId());
        material.setLifecycleReason(reason);
        material.setLifecycleChangedAt(LocalDateTime.now());
        Material saved = repository.saveAndFlush(material);
        materialWmsIntegrationPublisher.publish(saved);
        auditService.log("Material", id, "ASSIGN_REPLACEMENT", reason, null, null);
        return mapper.toResponse(saved);
    }
    @Transactional
    public MaterialResponse prohibit(UUID id, MaterialLifecycleRequest request) {
        Material material = findEntity(id);
        guardOpeningImport(material.getCompanyId());
        requireStatus(material, MaterialStatus.ACTIVE, MaterialStatus.INACTIVE, MaterialStatus.OBSOLETE);
        String reason = requireReason(request, "prohibit material");
        material.setStatus(MaterialStatus.PROHIBITED.name());
        material.setReplacementMaterialId(null);
        material.setLifecycleReason(reason);
        material.setLifecycleChangedAt(LocalDateTime.now());
        Material saved = repository.saveAndFlush(material);
        materialWmsIntegrationPublisher.publish(saved);
        auditService.log("Material", id, "PROHIBIT", reason, null, null);
        return mapper.toResponse(saved);
    }
    @Transactional
    public MaterialResponse reactivate(UUID id, MaterialLifecycleRequest request) {
        Material material = findEntity(id);
        guardOpeningImport(material.getCompanyId());
        requireStatus(material, MaterialStatus.INACTIVE, MaterialStatus.OBSOLETE);
        String reason = requireReason(request, "reactivate material");
        material.setStatus(MaterialStatus.ACTIVE.name());
        material.setReplacementMaterialId(null);
        material.setLifecycleReason(reason);
        material.setLifecycleChangedAt(LocalDateTime.now());
        Material saved = repository.saveAndFlush(material);
        materialWmsIntegrationPublisher.publish(saved);
        auditService.log("Material", id, "REACTIVATE", material.getLifecycleReason(), null, null);
        return mapper.toResponse(saved);
    }
    protected void beforeUpdate(Material entity, MaterialUpdateRequest request) {
        requireRequest(entity);
        entity.setMaterialSubtype(materialBusinessTypePolicy.normalizeMaterialSubtype(entity.getMaterialType(),
            entity.getMaterialType() == MaterialType.TMB ? entity.getMaterialSubtype() : request.getMaterialSubtype()));
        normalize(entity);
        entity.setStatus(normalizeStatus(entity.getStatus(), MaterialStatus.ACTIVE));
        entity.setValuationMethod(normalizeValuationMethod(entity.getValuationMethod()));
        entity.setHazardClass(normalizeHazardClass(entity.getHazardClass()));
        applyNumericDefaults(entity);
        normalizeTrackingMode(entity);
        validateMaterial(entity, entity.getId());
        validateManufacturer(entity);
        materialAttributeValidationService.validate(
            entity.getMaterialGroupId(),
            attributesForValidation(entity.getId(), request.getAttributes())
        );
        resolveProductCodes(entity);
        entity.setMaterialType(materialBusinessTypePolicy.normalizeMaterialType(entity.getMaterialType(), entity.getMxik()));
        applyAverageCostSelection(entity, request);
    }

    private void requireRequest(Material entity) {
        if (entity == null) {
            throw new BadRequestException("Material request is required", "material.request.required");
        }
    }

    private void normalize(Material material) {
        material.setCode(normalize(material.getCode()));
        material.setName(trim(material.getName()));
        material.setStatus(normalize(material.getStatus()));
        material.setBarcode(trim(material.getBarcode()));
        material.setQrCode(trim(material.getQrCode()));
        material.setRfidTag(trim(material.getRfidTag()));
        material.setMxikCode(trim(material.getMxikCode()));
        material.setTnvedCode(normalize(material.getTnvedCode()));
        material.setArticle(trim(material.getArticle()));
        material.setManufacturer(trim(material.getManufacturer()));
        material.setCountryOfOrigin(trim(material.getCountryOfOrigin()));
        material.setHazardClass(trim(material.getHazardClass()));
        material.setDescription(trim(material.getDescription()));
    }

    private void applyNumericDefaults(Material material) {
        if (material.getMinStock() == null) {
            material.setMinStock(java.math.BigDecimal.ZERO);
        }
        if (material.getSafetyStock() == null) {
            material.setSafetyStock(java.math.BigDecimal.ZERO);
        }
        if (material.getReorderPoint() == null) {
            material.setReorderPoint(java.math.BigDecimal.ZERO);
        }
    }

    private void normalizeTrackingMode(Material material) {
        TrackingMode trackingMode = material.getTrackingMode() == null ? TrackingMode.QUANTITY : material.getTrackingMode();
        BatchTrackingPolicy batchPolicy = material.getBatchTrackingPolicy() == null
            ? TrackingMode.batchTrackingPolicy(trackingMode)
            : material.getBatchTrackingPolicy();
        SerialTrackingPolicy serialPolicy = material.getSerialTrackingPolicy() == null
            ? TrackingMode.serialTrackingPolicy(trackingMode)
            : material.getSerialTrackingPolicy();
        boolean tracked = trackingMode != TrackingMode.QUANTITY
            || batchPolicy != BatchTrackingPolicy.NONE
            || serialPolicy != SerialTrackingPolicy.NONE;
        if (material.getMaterialType() == MaterialType.SERVICES && tracked) {
            throw new BadRequestException(
                "SERIAL_TRACKING_NOT_ALLOWED: tracking mode is only configurable for goods materials",
                "material.tracking.not_allowed",
                material.getMaterialType(),
                trackingMode
            );
        }
        if (material.getMaterialType() == MaterialType.SERVICES) {
            material.setTrackingMode(TrackingMode.QUANTITY);
            material.setBatchTrackingPolicy(BatchTrackingPolicy.NONE);
            material.setSerialTrackingPolicy(SerialTrackingPolicy.NONE);
            return;
        }
        TrackingMode expectedMode = TrackingMode.fromPolicies(batchPolicy, serialPolicy);
        if (trackingMode != expectedMode) {
            throw new BadRequestException(
                "Invalid tracking combination: trackingMode=" + trackingMode
                    + ", batchTrackingPolicy=" + batchPolicy
                    + ", serialTrackingPolicy=" + serialPolicy
                    + ". These policies require trackingMode=" + expectedMode
            );
        }
        material.setBatchTrackingPolicy(batchPolicy);
        material.setSerialTrackingPolicy(serialPolicy);
        material.setTrackingMode(trackingMode);
    }

    private void validateMaterial(Material material, UUID materialId) {
        if (blank(material.getName())) {
            throw new BadRequestException("Material name is required", "material.name.required");
        }
        if (material.getMaterialGroupId() == null) {
            throw new BadRequestException("Material group is required", "material.group.required");
        }
        if (material.getUnitOfMeasureId() == null) {
            throw new BadRequestException("Unit of measure is required", "material.unit.required");
        }

        validateMaterialGroup(material.getMaterialGroupId());
        validateUnitOfMeasure(material.getUnitOfMeasureId());
        validateNonNegative(material.getMinStock(), "Minimum stock");
        validateNonNegative(material.getSafetyStock(), "Safety stock");
        validateNonNegative(material.getReorderPoint(), "Reorder point");
        validateNonNegative(material.getMaxStock(), "Maximum stock");
        if (material.getReplenishmentLeadTimeDays() != null && material.getReplenishmentLeadTimeDays() < 0) {
            throw new BadRequestException("Replenishment lead time days must not be negative", "validation.non_negative", "Replenishment lead time days");
        }
        if (material.getShelfLifeDays() != null && material.getShelfLifeDays() < 0) {
            throw new BadRequestException("Shelf life days must not be negative", "validation.non_negative", "Shelf life days");
        }
        validateMaximumStock(material);
        validateUniqueIdentifiers(material, materialId);
    }

    private void validateMaterialGroup(UUID materialGroupId) {
        MaterialGroup group = materialGroupRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), materialGroupId)
            .orElseThrow(() -> new BadRequestException("Unknown material group id: " + materialGroupId, "material.group.unknown", materialGroupId));
        if (group.isDeleted()) {
            throw new BadRequestException("Unknown material group id: " + materialGroupId, "material.group.unknown", materialGroupId);
        }
        if (!MaterialGroupStatus.ACTIVE.name().equals(group.getStatus())) {
            throw new BadRequestException("Material group must be ACTIVE", "material.group.active");
        }
    }

    private void validateUnitOfMeasure(UUID unitOfMeasureId) {
        UnitOfMeasure unit = unitOfMeasureRepository.findByIdAndIsDeletedFalse(unitOfMeasureId)
            .orElseThrow(() -> new BadRequestException("Unknown unit of measure id: " + unitOfMeasureId, "material.unit.unknown", unitOfMeasureId));
        if (!TenantContext.currentTenant().equals(unit.getTenantId()) || unit.isDeleted()) {
            throw new BadRequestException("Unknown unit of measure id: " + unitOfMeasureId, "material.unit.unknown", unitOfMeasureId);
        }
    }

    private void validateNonNegative(java.math.BigDecimal value, String fieldName) {
        if (value != null && value.signum() < 0) {
            throw new BadRequestException(fieldName + " must not be negative", "validation.non_negative", fieldName);
        }
    }

    private void applyAverageCostSelection(Material material, MaterialCreateRequest request) {
        if (request.getAverageCostObservationId() != null) {
            if (materialCostObservationService == null) {
                throw new BadRequestException("Average cost observation selection is not available", "material.average_cost.selection_unavailable");
            }
            MaterialCostObservation observation = materialCostObservationService
                .requireSelectable(request.getAverageCostObservationId(), material.getMxikCode());
            material.setAverageCost(observation.getUnitCost());
            material.setAverageCostCurrencyCode(observation.getCurrencyCode());
            material.setAverageCostObservationId(observation.getId());
            material.setAverageCostSourceType(observation.getSourceType());
            material.setAverageCostSourceEntityId(observation.getSourceEntityId());
            material.setAverageCostSourceLineId(observation.getSourceLineId());
            material.setAverageCostDocumentCode(observation.getDocumentCode());
            material.setAverageCostObservedAt(observation.getObservedAt());
            material.setAverageCostSelectedAt(LocalDateTime.now());
            return;
        }
        if (request.getAverageCost() != null) {
            validateNonNegative(request.getAverageCost(), "Average cost");
            material.setAverageCost(request.getAverageCost());
            material.setAverageCostCurrencyCode(normalizeCurrency(request.getAverageCostCurrencyCode()));
            material.setAverageCostObservationId(null);
            material.setAverageCostSourceType("MANUAL");
            material.setAverageCostSourceEntityId(null);
            material.setAverageCostSourceLineId(null);
            material.setAverageCostDocumentCode(null);
            material.setAverageCostObservedAt(LocalDateTime.now());
            material.setAverageCostSelectedAt(LocalDateTime.now());
            return;
        }
        clearAverageCost(material);
    }

    private void clearAverageCost(Material material) {
        material.setAverageCost(null);
        material.setAverageCostCurrencyCode(null);
        material.setAverageCostObservationId(null);
        material.setAverageCostSourceType(null);
        material.setAverageCostSourceEntityId(null);
        material.setAverageCostSourceLineId(null);
        material.setAverageCostDocumentCode(null);
        material.setAverageCostObservedAt(null);
        material.setAverageCostSelectedAt(null);
    }

    private String normalizeCurrency(String value) {
        String normalized = normalize(value);
        if (blank(normalized)) {
            return "UZS";
        }
        if (normalized.length() != 3) {
            throw new BadRequestException("averageCostCurrencyCode must be a 3-letter ISO code", "material.average_cost.currency.invalid");
        }
        return normalized;
    }

    private void validateMaximumStock(Material material) {
        if (material.getMaxStock() == null) {
            return;
        }
        if (material.getMaxStock().compareTo(material.getMinStock()) < 0
            || material.getMaxStock().compareTo(material.getSafetyStock()) < 0
            || material.getMaxStock().compareTo(material.getReorderPoint()) < 0) {
            throw new BadRequestException("Maximum stock cannot be less than minimum stock, safety stock, or reorder point", "material.max_stock.invalid");
        }
    }

    private void validateUniqueIdentifiers(Material material, UUID materialId) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        if (existsCode(tenantId, companyId, material.getCode(), materialId)) {
            throw new BusinessException("Material code already exists in the current company", "material.code.duplicate");
        }
        if (!blank(material.getBarcode()) && existsBarcode(tenantId, companyId, material.getBarcode(), materialId)) {
            throw new BusinessException("Material barcode already exists in the current company", "material.barcode.duplicate");
        }
        if (!blank(material.getQrCode()) && existsQrCode(tenantId, companyId, material.getQrCode(), materialId)) {
            throw new BusinessException("Material QR code already exists in the current company", "material.qr.duplicate");
        }
        if (!blank(material.getRfidTag()) && existsRfidTag(tenantId, companyId, material.getRfidTag(), materialId)) {
            throw new BusinessException("Material RFID tag already exists in the current company", "material.rfid.duplicate");
        }
    }

    private boolean existsCode(String tenantId, UUID companyId, String value, UUID materialId) {
        return repository.existsActiveNormalizedCode(tenantId, companyId, normalize(value), materialId);
    }

    private boolean existsBarcode(String tenantId, UUID companyId, String value, UUID materialId) {
        return materialId == null
            ? repository.existsByTenantIdAndCompanyIdAndBarcodeAndIsDeletedFalse(tenantId, companyId, value)
            : repository.existsByTenantIdAndCompanyIdAndBarcodeAndIdNotAndIsDeletedFalse(tenantId, companyId, value, materialId);
    }

    private boolean existsQrCode(String tenantId, UUID companyId, String value, UUID materialId) {
        return materialId == null
            ? repository.existsByTenantIdAndCompanyIdAndQrCodeAndIsDeletedFalse(tenantId, companyId, value)
            : repository.existsByTenantIdAndCompanyIdAndQrCodeAndIdNotAndIsDeletedFalse(tenantId, companyId, value, materialId);
    }

    private boolean existsRfidTag(String tenantId, UUID companyId, String value, UUID materialId) {
        return materialId == null
            ? repository.existsByTenantIdAndCompanyIdAndRfidTagAndIsDeletedFalse(tenantId, companyId, value)
            : repository.existsByTenantIdAndCompanyIdAndRfidTagAndIdNotAndIsDeletedFalse(tenantId, companyId, value, materialId);
    }

    private void enforceUpdateStatus(String currentStatus, String requestedStatus) {
        if (blank(requestedStatus)) {
            return;
        }
        String normalizedCurrent = normalizeStatus(currentStatus, MaterialStatus.ACTIVE);
        String normalizedRequested = normalizeStatus(requestedStatus, null);
        if (normalizedCurrent.equals(normalizedRequested)) {
            return;
        }
        if (isLifecycleStatus(normalizedCurrent) || isLifecycleStatus(normalizedRequested)) {
            throw new BusinessException("Use a material lifecycle action to change OBSOLETE or PROHIBITED status", "material.lifecycle.action_required");
        }
    }

    private boolean isLifecycleStatus(String status) {
        return MaterialStatus.OBSOLETE.name().equals(status) || MaterialStatus.PROHIBITED.name().equals(status);
    }

    private void enforceMaterialTypeChangeAllowed(Material material, MaterialType previousMaterialType) {
        if (previousMaterialType == null || material.getMaterialType() == previousMaterialType) {
            return;
        }
        if (materialUsageService.isUsed(material.getId())) {
            throw new BusinessException(
                "MATERIAL_TYPE_CHANGE_BLOCKED: material is already used in durable documents or stock",
                "material.type.change_blocked",
                previousMaterialType,
                material.getMaterialType()
            );
        }
    }

    private void requireFixedAssetsEnabledForMaterialCreate(MaterialType materialType) {
        if (materialType == MaterialType.FIXED_ASSET) {
            requireFixedAssetsEnabled();
        }
    }

    private void requireFixedAssetsEnabledForMaterialTypeTransition(
        MaterialType previousMaterialType,
        MaterialType requestedMaterialType
    ) {
        if (requestedMaterialType == MaterialType.FIXED_ASSET && previousMaterialType != MaterialType.FIXED_ASSET) {
            requireFixedAssetsEnabled();
        }
    }

    private void requireFixedAssetsEnabled() {
        if (!fixedAssetsEnabled) {
            throw new BusinessException(
                "FIXED_ASSETS_DISABLED: fixed asset material commands are disabled",
                "fixed_assets.disabled"
            );
        }
    }

    private void validateManufacturer(Material material) {
        if (material.getManufacturerId() == null) {
            material.setManufacturerRef(null);
            return;
        }
        Manufacturer manufacturer = manufacturerRepository
            .findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), material.getManufacturerId())
            .filter(candidate -> "ACTIVE".equalsIgnoreCase(candidate.getStatus()))
            .orElseThrow(() -> new BadRequestException(
                "Manufacturer must be active in the current company",
                "material.manufacturer.invalid",
                material.getManufacturerId()
            ));
        material.setManufacturerRef(manufacturer);
        material.setManufacturer(manufacturer.getName());
    }

    private List<com.enterprise.mm.payload.MaterialAttributeRequest> attributesForValidation(
        UUID materialId,
        List<com.enterprise.mm.payload.MaterialAttributeRequest> requested
    ) {
        if (requested != null) {
            return requested;
        }
        if (materialId == null) {
            return List.of();
        }
        return materialAttributeService.getByMaterialId(materialId).stream()
            .map(attribute -> new com.enterprise.mm.payload.MaterialAttributeRequest(
                attribute.key(), attribute.value()))
            .toList();
    }

    private void requireStatus(Material material, MaterialStatus... allowedStatuses) {
        String current = normalizeStatus(material.getStatus(), MaterialStatus.ACTIVE);
        for (MaterialStatus allowedStatus : allowedStatuses) {
            if (allowedStatus.name().equals(current)) {
                return;
            }
        }
        throw new BusinessException("Material cannot transition from " + current + " using this action", "material.lifecycle.transition.invalid", current);
    }

    private String requireReason(MaterialLifecycleRequest request, String action) {
        String reason = request == null ? null : trim(request.reason());
        if (blank(reason)) {
            throw new BusinessException("Reason is required to " + action, "material.lifecycle.reason.required", action);
        }
        return reason;
    }

    private String normalizeStatus(String value, MaterialStatus defaultValue) {
        String normalized = normalize(value);
        if (blank(normalized)) {
            return defaultValue == null ? null : defaultValue.name();
        }
        try {
            return MaterialStatus.valueOf(normalized).name();
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("Invalid material status: " + value, "material.status.invalid", value);
        }
    }

    private ValuationMethod normalizeValuationMethod(ValuationMethod value) {
        if (value == null) {
            return ValuationMethod.ACTUAL_COST;
        }
        return value;
    }

    private String normalizeHazardClass(String value) {
        String normalized = normalize(value);
        if (blank(normalized)) {
            return null;
        }
        try {
            return HazardClass.valueOf(normalized).name();
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("Invalid hazard class: " + value, "material.hazard_class.invalid", value);
        }
    }
    private void resolveProductCodes(Material material) {
        resolveMxik(material);
        resolveTnved(material);
    }
    private void resolveMxik(Material material) {
        if (material.getMxik() == null && blank(material.getMxikCode())) {
            return;
        }
        MXIK requestedMxik = material.getMxik();
        String requestedCode = firstNonBlank(
            requestedMxik == null ? null : requestedMxik.getKod(),
            material.getMxikCode()
        );
        if (requestedMxik != null && requestedMxik.getId() != null) {
            MXIK resolved = mxikRepository.findAnyById(requestedMxik.getId())
                .orElseThrow(() -> new BadRequestException("Unknown MXIK id: " + requestedMxik.getId(), "material.mxik.unknown_id", requestedMxik.getId()));
            material.setMxik(resolved);
            material.setMxikCode(resolved.getKod());
            return;
        }
        if (!blank(requestedCode)) {
            var resolvedMxik = mxikRepository.findByKod(requestedCode.trim());
            if (resolvedMxik.isPresent()) {
                material.setMxik(resolvedMxik.get());
                material.setMxikCode(resolvedMxik.get().getKod());
                return;
            }
        }
        throw new BadRequestException("Unknown MXIK code: " + requestedCode, "material.mxik.unknown_code", requestedCode);
    }
    private void resolveTnved(Material material) {
        if (material.getTnvedCodeId() == null && blank(material.getTnvedCode())) {
            return;
        }
        TnvedCode ref = material.getTnvedCodeId() != null
            ? tnvedCodeRepository.findByIdAndIsDeletedFalse(material.getTnvedCodeId()).orElseThrow(() -> new BadRequestException("Unknown TN VED code id: " + material.getTnvedCodeId(), "material.tnved.unknown_id", material.getTnvedCodeId()))
            : tnvedCodeRepository.findByTenantIdAndCompanyIdAndTnvedCodeAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany(), normalize(material.getTnvedCode()))
                .orElseThrow(() -> new BadRequestException("Unknown TN VED code: " + material.getTnvedCode(), "material.tnved.unknown_code", material.getTnvedCode()));
        if (ref.isDeleted() || !TenantContext.currentTenant().equals(ref.getTenantId()) || !com.enterprise.mm.security.AccessScope.canAccessCompany(ref.getCompanyId())) {
            throw new BadRequestException("Unknown TN VED code id: " + material.getTnvedCodeId(), "material.tnved.unknown_id", material.getTnvedCodeId());
        }
        material.setTnvedCodeId(ref.getId());
        material.setTnvedCode(ref.getTnvedCode());
    }
    private String normalize(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }
    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
    private Material loadReplacement(UUID replacementId) {
        Material replacement = repository.findByIdAndIsDeletedFalse(replacementId)
            .orElseThrow(() -> new BadRequestException("Unknown replacement material id: " + replacementId, "material.replacement.unknown", replacementId));
        if (replacement.isDeleted() || !TenantContext.currentTenant().equals(replacement.getTenantId()) || !com.enterprise.mm.security.AccessScope.canAccessCompany(replacement.getCompanyId())) {
            throw new BadRequestException("Unknown replacement material id: " + replacementId, "material.replacement.unknown", replacementId);
        }
        return replacement;
    }
    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private MaterialSubtype parseMaterialSubtype(String value) {
        String normalized = normalize(value);
        if (blank(normalized)) return null;
        try {
            return MaterialSubtype.valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("Invalid material subtype: " + value, "material.subtype.invalid", value);
        }
    }

    private MaterialType parseMaterialType(String value) {
        String normalized = normalize(value);
        if (blank(normalized)) {
            return null;
        }
        try {
            return MaterialType.valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("Invalid material type: " + value, "material.type.invalid", value);
        }
    }

    @Transactional(readOnly = true)
    public Page<MaterialResponse> search(MaterialSearchRequest request, Pageable pageable) {
        return search(request, pageable, false, null, null);
    }

    @Transactional(readOnly = true)
    public Page<MaterialResponse> search(
        MaterialSearchRequest request,
        Pageable pageable,
        boolean includeAdvice,
        Integer advicePeriodDays,
        UUID adviceWarehouseId
    ) {
        MaterialSearchRequest criteria = request == null ? MaterialSearchRequest.empty() : request;
        SearchCriteria filter = SearchCriteria.of(criteria);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        String mxikType = normalize(criteria.type());
        MaterialType materialType = parseMaterialType(criteria.materialType());
        MaterialSubtype materialSubtype = parseMaterialSubtype(criteria.materialSubtype());
        // Unclassified materials (no resolvable MXIK) are treated as goods, so they stay visible on
        // the products page; the services page shows only materials with a SERVICE-typed MXIK code.
        boolean stockStatusEmpty =
                criteria.stockStatus() == null || criteria.stockStatus().isEmpty();
        boolean includeNullMxik = "GOODS".equals(mxikType);
        Page<MaterialResponse> responsePage = repository.search(
            TenantContext.currentTenant(),
            scoped ? CompanyContext.currentCompany() : null,
            filter.includeDeleted(),
            filter.normalizedSearch(),
            filter.fromDateTime(),
            filter.toDateTime(),
            filter.status(),
            !filter.hasStatus(),
            criteria.id(),
            criteria.code(),
            criteria.name(),
            criteria.description(),
            criteria.deleted(),
            criteria.createdBy(),
            criteria.updatedBy(),
            criteria.version(),
            criteria.materialGroupId(),
            criteria.unitOfMeasureId(),
            criteria.valuationMethod(),
            criteria.minStock(),
            criteria.safetyStock(),
            criteria.reorderPoint(),
            criteria.barcode(),
            criteria.qrCode(),
            criteria.rfidTag(),
            criteria.mxikCode(),
            criteria.tnvedCodeId(),
            criteria.tnvedCode(),
            criteria.article(),
            criteria.manufacturer(),
            criteria.countryOfOrigin(),
            criteria.shelfLifeDays(),
            normalize(criteria.hazardClass()),
            criteria.maxStock(),
            criteria.replacementMaterialId(),
            criteria.lifecycleReason(),
            criteria.lifecycleChangedAt(),
            materialType,
            materialSubtype,
            blank(mxikType) ? null : mxikType,
            includeNullMxik,
            criteria.stockStatus(),
            stockStatusEmpty,
            pageable
        ).map(mapper::toResponse);
        Page<MaterialResponse> withAttributes = withAttributes(responsePage);
        return includeAdvice ? materialAdviceService.enrich(withAttributes, advicePeriodDays, adviceWarehouseId) : withAttributes;
    }
    @Transactional
    public MaterialResponse get(UUID id) {
        return toDetailedResponse(expireShelfLifeIfNeeded(findEntity(id)));
    }

    @Transactional(readOnly = true)
    public Page<MaterialResponse> findByCreatedDateRange(java.time.LocalDate fromDate, java.time.LocalDate toDate, Pageable pageable) {
        if (fromDate == null || toDate == null) {
            throw new BadRequestException("fromDate and toDate are required", "date.range.required");
        }
        if (toDate.isBefore(fromDate)) {
            throw new BadRequestException("toDate must be greater than or equal to fromDate", "date.range.invalid");
        }
        LocalDateTime from = fromDate.atStartOfDay();
        LocalDateTime to = toDate.plusDays(1).atStartOfDay().minusNanos(1);
        return repository.findByTenantIdAndCompanyIdAndStockBalanceCreatedAtBetween(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                from,
                to,
                pageable
            )
            .map(mapper::toResponse);
    }
    @Transactional
    public MaterialResponse create(MaterialCreateRequest request) {
        Material entity = mapper.toEntity(request);
        beforeCreate(entity, request);
        Material saved = repository.saveAndFlush(entity);
        materialAttributeService.replaceAttributes(
            saved.getId(),
            request.getAttributes() == null ? List.of() : request.getAttributes()
        );
        recordValuationMethodHistory(saved, null, saved.getValuationMethod(), "Initial material valuation method");
        materialWmsIntegrationPublisher.publish(saved);
        auditService.logInCurrentTransaction(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return toDetailedResponse(saved);
    }
    @Transactional
    public MaterialResponse update(UUID id, MaterialUpdateRequest request) {
        Material entity = findEntity(id);
        MaterialSubtype previousSubtype = entity.getMaterialSubtype();
        MaterialSubtype requestedSubtype = request.getMaterialType() != null && request.getMaterialType() != MaterialType.TMB
            ? null : request.getMaterialSubtype() == null ? previousSubtype : request.getMaterialSubtype();
        if (!Objects.equals(previousSubtype, requestedSubtype)
            && (previousSubtype == MaterialSubtype.RETURNABLE_PACKAGING
                || requestedSubtype == MaterialSubtype.RETURNABLE_PACKAGING)
            && materialUsageService.isUsed(id)) {
            throw new BusinessException("Returnable packaging classification cannot change after material use");
        }
        if (previousSubtype == MaterialSubtype.RETURNABLE_PACKAGING
            && requestedSubtype != MaterialSubtype.RETURNABLE_PACKAGING
            && returnablePackagingLots != null
            && returnablePackagingLots.existsByTenantIdAndCompanyIdAndMaterialIdAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), id)) {
            throw new BusinessException("Material with returnable packaging history cannot be reclassified");
        }
        if (!Objects.equals(entity.getUnitOfMeasureId(), request.getUnitOfMeasureId()) && materialUsageService.isUsed(id))
            throw new BusinessException("Stock UOM cannot change after material is used in documents or stock; an approved conversion is required");
        guardOpeningImport(entity.getCompanyId());
        String oldSnapshot = auditService.snapshot(entity);
        String currentStatus = entity.getStatus();
        ValuationMethod previousValuationMethod = entity.getValuationMethod();
        MaterialType previousMaterialType = materialBusinessTypePolicy.normalizeMaterialType(entity.getMaterialType(), entity.getMxik());
        mapper.updateEntity(entity, request);
        if (request.getMaterialType() == null) {
            entity.setMaterialType(previousMaterialType);
        }
        enforceUpdateStatus(currentStatus, entity.getStatus());
        if (blank(entity.getStatus())) {
            entity.setStatus(currentStatus);
        }
        beforeUpdate(entity, request);
        requireFixedAssetsEnabledForMaterialTypeTransition(previousMaterialType, entity.getMaterialType());
        enforceMaterialTypeChangeAllowed(entity, previousMaterialType);
        Material saved = repository.saveAndFlush(entity);
        if (!Objects.equals(previousValuationMethod, saved.getValuationMethod())) {
            recordValuationMethodHistory(saved, previousValuationMethod, saved.getValuationMethod(), "Material valuation method changed");
        }
        if (request.getAttributes() != null) {
            materialAttributeService.replaceAttributes(saved.getId(), request.getAttributes());
        }
        materialWmsIntegrationPublisher.publish(saved);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return toDetailedResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<MaterialValuationMethodHistoryResponse> valuationMethodHistory(UUID id) {
        Material material = findEntity(id);
        return valuationMethodHistoryRepository
            .findByTenantIdAndCompanyIdAndMaterialIdAndIsDeletedFalseOrderByEffectiveAtDesc(
                TenantContext.currentTenant(),
                material.getCompanyId(),
                material.getId()
            )
            .stream()
            .map(this::toHistoryResponse)
            .toList();
    }
    @Transactional
    public void softDelete(UUID id) {
        Material entity = findEntity(id);
        guardOpeningImport(entity.getCompanyId());
        String oldSnapshot = auditService.snapshot(entity);
        if (materialUsageService.isUsed(entity.getId())) {
            throw new BusinessException("Material is already used in business documents or stock. Mark it OBSOLETE or PROHIBITED instead.", "material.delete.used");
        }
        entity.softDelete();
        Material saved = repository.saveAndFlush(entity);
        materialWmsIntegrationPublisher.publish(saved);
        materialAttributeService.softDeleteByMaterialId(entity.getId());
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public MaterialResponse restore(UUID id) {
        Material entity = repository.findAnyById(id)
            .orElseThrow(() -> notFound(id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw notFound(id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw notFound(id);
        }
        guardOpeningImport(entity.getCompanyId());
        String oldSnapshot = auditService.snapshot(entity);
        if (existsCode(entity.getTenantId(), entity.getCompanyId(), entity.getCode(), entity.getId())) {
            throw new BusinessException("Material code already exists in the current company", "material.code.duplicate");
        }
        entity.restore();
        Material saved = repository.saveAndFlush(entity);
        materialWmsIntegrationPublisher.publish(saved);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return toDetailedResponse(saved);
    }
    protected Material findEntity(UUID id) {
        Material entity = repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> notFound(id));
        if (entity.isDeleted()) {
            throw notFound(id);
        }
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw notFound(id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw notFound(id);
        }
        return entity;
    }

    /**
     * Shelf life is defined for the material master itself. Once it is expired, the material
     * becomes non-selectable before a new demand, reservation or WMS projection can use it.
     */
    private Material expireShelfLifeIfNeeded(Material material) {
        if (!MaterialStatus.ACTIVE.name().equals(material.getStatus())
                || material.getShelfLifeDays() == null
                || material.getShelfLifeDays() <= 0
                || material.getCreatedAt() == null
                || material.getCreatedAt().plusDays(material.getShelfLifeDays()).isAfter(LocalDateTime.now())) {
            return material;
        }
        guardOpeningImport(material.getCompanyId());
        material.setStatus(MaterialStatus.OBSOLETE.name());
        material.setLifecycleReason("Shelf life expired");
        material.setLifecycleChangedAt(LocalDateTime.now());
        Material saved = repository.saveAndFlush(material);
        materialWmsIntegrationPublisher.publish(saved);
        auditService.log("Material", saved.getId(), "AUTO_OBSOLETE_SHELF_LIFE", saved.getLifecycleReason(), null, null);
        return saved;
    }

    private NotFoundException notFound(UUID id) {
        return new NotFoundException(entityName + " not found: " + id, "error.entity.not_found", entityName, id);
    }

    protected boolean companyScoped() {
        return true;
    }

    private MaterialResponse toDetailedResponse(Material material) {
        MaterialResponse response = mapper.toResponse(material);
        response.setAttributes(materialAttributeService.getByMaterialId(material.getId()));
        return response;
    }

    private Page<MaterialResponse> withAttributes(Page<MaterialResponse> page) {
        List<MaterialResponse> responses = page.getContent();
        Map<UUID, List<com.enterprise.mm.payload.MaterialAttributeResponse>> attributesByMaterialId =
            materialAttributeService.getByMaterialIds(
                responses.stream()
                    .map(MaterialResponse::getId)
                    .toList()
            );
        responses.forEach(response ->
            response.setAttributes(attributesByMaterialId.getOrDefault(response.getId(), List.of()))
        );
        return page;
    }

    private void recordValuationMethodHistory(Material material, ValuationMethod oldMethod, ValuationMethod newMethod, String reason) {
        MaterialValuationMethodHistory history = new MaterialValuationMethodHistory();
        history.setCompanyId(material.getCompanyId());
        history.setCode("VAL-METHOD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        history.setName("Valuation method history " + material.getName());
        history.setStatus("ACTIVE");
        history.setMaterialId(material.getId());
        history.setOldValuationMethod(oldMethod != null ? oldMethod.name() : null);
        history.setNewValuationMethod(newMethod != null ? newMethod.name() : null);
        history.setEffectiveAt(LocalDateTime.now());
        history.setChangeReason(reason);
        valuationMethodHistoryRepository.save(history);
    }

    private MaterialValuationMethodHistoryResponse toHistoryResponse(MaterialValuationMethodHistory entity) {
        MaterialValuationMethodHistoryResponse response = new MaterialValuationMethodHistoryResponse();
        response.setId(entity.getId());
        response.setTenantId(entity.getTenantId());
        response.setCompanyId(entity.getCompanyId());
        response.setCode(entity.getCode());
        response.setName(entity.getName());
        response.setStatus(entity.getStatus());
        response.setDescription(entity.getDescription());
        response.setDeleted(entity.isDeleted());
        response.setCreatedAt(entity.getCreatedAt());
        response.setUpdatedAt(entity.getUpdatedAt());
        response.setCreatedBy(entity.getCreatedBy());
        response.setUpdatedBy(entity.getUpdatedBy());
        response.setVersion(entity.getVersion());
        response.setMaterialId(entity.getMaterialId());
        response.setOldValuationMethod(entity.getOldValuationMethod());
        response.setNewValuationMethod(entity.getNewValuationMethod());
        response.setEffectiveAt(entity.getEffectiveAt());
        response.setChangeReason(entity.getChangeReason());
        return response;
    }

    public MaterialDashboardResponse getDashboard() {
       UUID companyId =  CompanyContext.currentCompany();
       String tenantId = TenantContext.currentTenant();
        var result = repository.getDashboard(tenantId, companyId);
        return new MaterialDashboardResponse(
                result.getTotalMaterials(),
                result.getLowStock(),
                result.getOutOfStock(),
                result.getValue()
        );
    }


}


