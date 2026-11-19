package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.MaterialGroup;
import com.enterprise.mm.entity.MXIK;
import com.enterprise.mm.enums.MaterialStatus;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.ManualReceiptMaterialDtos;
import com.enterprise.mm.payload.MaterialCreateRequest;
import com.enterprise.mm.payload.MaterialResponse;
import com.enterprise.mm.repository.MaterialAttributeTemplateRepository;
import com.enterprise.mm.repository.MaterialGroupRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.MXIKRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class ManualReceiptMaterialService {
    private static final int GENERATED_CODE_ATTEMPTS = 5;
    private static final String MATERIAL_CODE_INDEX = "uk_materials_active_normalized_company_code";
    private final RoleDataScopeService scope;
    private final WarehouseRepository warehouses;
    private final MaterialGroupRepository groups;
    private final MaterialAttributeTemplateRepository templates;
    private final MaterialRepository materials;
    private final MXIKRepository mxiks;
    private final MaterialService materialService;
    private final IdempotencyService idempotency;
    private final PlatformTransactionManager transactionManager;

    @Transactional(readOnly = true)
    public ManualReceiptMaterialDtos.CreateContext context(UUID warehouseId, UUID groupId) {
        checkWarehouse(warehouseId);
        List<ManualReceiptMaterialDtos.AttributeTemplate> attributes = groupId == null ? List.of() : templates
            .findActiveByScopeAndMaterialGroup(TenantContext.currentTenant(), CompanyContext.currentCompany(),
                requireActiveGroup(groupId).getId())
            .stream()
            .map(template -> new ManualReceiptMaterialDtos.AttributeTemplate(template.getAttributeName(),
                Boolean.TRUE.equals(template.getRequired()), template.getDataType(), template.getAllowedValues()))
            .toList();
        return new ManualReceiptMaterialDtos.CreateContext(true, "TMB", "MATERIAL", "QUANTITY", true,
            groupId, attributes);
    }

    @Transactional(readOnly = true)
    public ManualReceiptMaterialDtos.CodeAvailability codeAvailability(UUID warehouseId, String code) {
        checkWarehouse(warehouseId);
        String normalized = normalizeCode(code);
        boolean duplicate = materials.existsActiveNormalizedCode(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), normalized, null);
        return new ManualReceiptMaterialDtos.CodeAvailability(normalized, duplicate ? "DUPLICATE" : "AVAILABLE");
    }

    public MaterialResponse create(ManualReceiptMaterialDtos.CreateRequest request, String key) {
        if (request == null || request.warehouseId() == null) {
            throw new BadRequestException("Warehouse is required", "warehouse.required");
        }
        boolean generatedCode = request.code() == null || request.code().isBlank();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        for (int attempt = 0; attempt < (generatedCode ? GENERATED_CODE_ATTEMPTS : 1); attempt++) {
            try {
                return transaction.execute(status -> {
                    checkWarehouse(request.warehouseId());
                    return idempotency.executeRequired("MANUAL_RECEIPT_MATERIAL_CREATE", request.warehouseId(), key,
                        request, MaterialResponse.class, () -> createOnce(request));
                });
            } catch (RuntimeException exception) {
                boolean codeCollision = (exception instanceof BusinessException business
                    && "material.code.duplicate".equals(business.getMessageKey()))
                    || (exception instanceof DataIntegrityViolationException && materialCodeCollision(exception));
                if (!generatedCode || !codeCollision) throw exception;
                if (attempt == GENERATED_CODE_ATTEMPTS - 1) {
                    throw new BusinessException("Unable to generate a unique material code", "material.code.generation_failed");
                }
            }
        }
        throw new IllegalStateException("Material creation attempts exhausted");
    }

    private static boolean materialCodeCollision(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().toLowerCase(Locale.ROOT).contains(MATERIAL_CODE_INDEX)) {
                return true;
            }
        }
        return false;
    }

    private MaterialResponse createOnce(ManualReceiptMaterialDtos.CreateRequest request) {
        if (request.materialGroupId() != null) requireActiveGroup(request.materialGroupId());
        if (request.mxikId() != null && request.mxikCode() != null && !request.mxikCode().isBlank()) {
            MXIK resolved = mxiks.findAnyById(request.mxikId())
                .orElseThrow(() -> new BadRequestException("Unknown MXIK id", "material.mxik.unknown_id"));
            if (!resolved.getKod().equals(request.mxikCode().trim())) {
                throw new BadRequestException("MXIK id and code do not match", "material.mxik.inconsistent");
            }
        }
        MaterialCreateRequest material = new MaterialCreateRequest();
        material.setName(request.name());
        material.setMaterialGroupId(request.materialGroupId());
        material.setUnitOfMeasureId(request.unitOfMeasureId());
        material.setCode(request.code());
        material.setMxikId(request.mxikId());
        material.setMxikCode(request.mxikCode());
        material.setMaterialType(MaterialType.TMB);
        material.setMaterialSubtype(request.materialSubtype());
        material.setStatus(MaterialStatus.ACTIVE);
        material.setManufacturer(request.manufacturer());
        material.setManufacturerId(request.manufacturerId());
        material.setArticle(request.article());
        material.setBarcode(request.barcode());
        material.setTrackingMode(request.trackingMode());
        material.setBatchTrackingPolicy(request.batchTrackingPolicy());
        material.setSerialTrackingPolicy(request.serialTrackingPolicy());
        material.setIncomingInspectionRequired(request.incomingInspectionRequired());
        material.setAttributes(request.attributes());
        return materialService.create(material);
    }

    private void checkWarehouse(UUID warehouseId) {
        scope.assertWarehouseAccess(warehouseId);
        warehouses.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), warehouseId)
            .orElseThrow(() -> new NotFoundException("Warehouse not found"));
    }

    private MaterialGroup requireActiveGroup(UUID groupId) {
        return groups.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), groupId)
            .filter(group -> "ACTIVE".equals(group.getStatus()))
            .orElseThrow(() -> new BadRequestException("Material group must be active in the current company",
                "material.group.active"));
    }

    private String normalizeCode(String code) {
        if (code == null || code.isBlank()) {
            throw new BadRequestException("Material code is required for availability check", "material.code.required");
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        if (normalized.length() > 80) {
            throw new BadRequestException("Material code exceeds 80 characters", "material.code.too_long");
        }
        return normalized;
    }
}
