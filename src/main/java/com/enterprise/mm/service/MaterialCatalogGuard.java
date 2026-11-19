package com.enterprise.mm.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Equipment;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.enums.MaterialStatus;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.repository.EquipmentRepository;
import com.enterprise.mm.repository.MaterialRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class MaterialCatalogGuard {
    private final MaterialRepository materialRepository;
    private final EquipmentRepository equipmentRepository;
    private final com.enterprise.mm.security.pilot.PilotCapabilityPolicy pilotCapabilityPolicy;

    public Material requireActiveMaterial(UUID materialId, String fieldName) {
        Material material = requireMaterial(materialId, fieldName);
        if (!MaterialStatus.ACTIVE.name().equals(material.getStatus())) {
            throw new BusinessException(
                fieldName + " is " + material.getStatus().toLowerCase(java.util.Locale.ROOT) + " and cannot be used for new business operations",
                "material.guard.not_active",
                fieldName,
                material.getStatus()
            );
        }
        pilotCapabilityPolicy.requireMaterialExecution(material);
        return material;
    }

    public Material requireUsableMaterial(UUID materialId, String fieldName) {
        return requireActiveMaterial(materialId, fieldName);
    }

    /** Quantity facts do not execute the individual asset/serial lifecycle. */
    public Material requireActiveFixedAssetStockMaterial(UUID materialId, String fieldName) {
        Material material = requireMaterial(materialId, fieldName);
        if (!MaterialStatus.ACTIVE.name().equals(material.getStatus()))
            throw new BusinessException("Select an active fixed-asset material", "material.guard.not_active");
        if (material.getMaterialType() != MaterialType.FIXED_ASSET
            || !CompanyContext.currentCompany().equals(material.getCompanyId()))
            throw new BusinessException("Select a fixed-asset material in your company", "FIXED_ASSET_MATERIAL_REQUIRED");
        pilotCapabilityPolicy.requireMaterialExecution(material);
        return material;
    }

    public Material requireActiveTmbMaterial(UUID materialId, String fieldName) {
        Material material = requireActiveMaterial(materialId, fieldName);
        if (material.getMaterialType() != MaterialType.TMB) {
            boolean fixedAsset = material.getMaterialType() == MaterialType.FIXED_ASSET;
            throw new BusinessException(
                fieldName + " must reference a TMB material for stock operations",
                fixedAsset ? "FIXED_ASSET_STOCK_FORBIDDEN" : "TMB_STOCK_ONLY",
                fieldName,
                material.getMaterialType()
            );
        }
        return material;
    }

    public Material requireMaterial(UUID materialId, String fieldName) {
        if (materialId == null) {
            throw new BusinessException(fieldName + " is required", "validation.field.required", fieldName);
        }
        var opt = materialRepository.findById(materialId);
        if (opt.isEmpty()) {
            log.warn("MaterialCatalogGuard: material [{}] not found in DB for field [{}]", materialId, fieldName);
        } else {
            Material m = opt.get();
            if (m.isDeleted()) {
                log.warn("MaterialCatalogGuard: material [{}] is soft-deleted", materialId);
            } else if (!TenantContext.currentTenant().equals(m.getTenantId())) {
                log.warn("MaterialCatalogGuard: tenant mismatch for material [{}] — expected [{}] but found [{}]",
                    materialId, TenantContext.currentTenant(), m.getTenantId());
            } else if (!com.enterprise.mm.security.AccessScope.canAccessCompany(m.getCompanyId())) {
                log.warn("MaterialCatalogGuard: company access denied for material [{}] — material company [{}], current scope [{}]",
                    materialId, m.getCompanyId(), CompanyContext.currentCompany());
            }
        }
        return materialRepository.findByIdAndIsDeletedFalse(materialId)
            .filter(material -> !material.isDeleted())
            .filter(material -> TenantContext.currentTenant().equals(material.getTenantId()))
            .filter(material -> com.enterprise.mm.security.AccessScope.canAccessCompany(material.getCompanyId()))
            .orElseThrow(() -> new BusinessException(fieldName + " not found: " + materialId, "validation.field.not_found", fieldName, materialId));
    }
    public Equipment requireEquipment(UUID equipmentId) {
        if (equipmentId == null) {
            throw new BusinessException("Equipment is required", "equipment.required");
        }
        return equipmentRepository.findByIdAndIsDeletedFalse(equipmentId)
            .filter(equipment -> !equipment.isDeleted())
            .filter(equipment -> TenantContext.currentTenant().equals(equipment.getTenantId()))
            .filter(equipment -> com.enterprise.mm.security.AccessScope.canAccessCompany(equipment.getCompanyId()))
            .orElseThrow(() -> new BusinessException("Equipment not found: " + equipmentId, "equipment.not_found", equipmentId));
    }
}

