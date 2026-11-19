package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.ImportInsurancePolicyResponse;
import com.enterprise.mm.payload.ImportInsurancePolicyUpdateRequest;
import com.enterprise.mm.payload.ImportInsurancePolicyCreateRequest;
import com.enterprise.mm.entity.ImportInsurancePolicy;
import org.springframework.stereotype.Component;

@Component
public class ImportInsurancePolicyMapper {
    public ImportInsurancePolicyResponse toResponse(ImportInsurancePolicy entity) {
        if (entity == null) {
            return null;
        }
        ImportInsurancePolicyResponse dto = new ImportInsurancePolicyResponse();
        dto.setId(entity.getId());
        dto.setTenantId(entity.getTenantId());
        dto.setCompanyId(entity.getCompanyId());
        dto.setCode(entity.getCode());
        dto.setName(entity.getName());
        dto.setStatus(entity.getStatus());
        dto.setDescription(entity.getDescription());
        dto.setDeleted(entity.isDeleted());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        dto.setCreatedBy(entity.getCreatedBy());
        dto.setUpdatedBy(entity.getUpdatedBy());
        dto.setVersion(entity.getVersion());
        dto.setPolicyNumber(entity.getPolicyNumber());
        dto.setImportDeliveryId(entity.getImportDeliveryId());
        dto.setImportDeliveryCode(BaseMapper.code(entity.getImportDelivery()));
        dto.setImportDeliveryName(BaseMapper.name(entity.getImportDelivery()));
        dto.setInsurerSupplierId(entity.getInsurerSupplierId());
        dto.setInsurerSupplierCode(BaseMapper.supplierCode(entity.getInsurerSupplier()));
        dto.setInsurerSupplierName(BaseMapper.supplierName(entity.getInsurerSupplier()));
        dto.setInsurerName(entity.getInsurerName());
        dto.setCoverageType(entity.getCoverageType());
        dto.setInsuredAmount(entity.getInsuredAmount());
        dto.setPremiumAmount(entity.getPremiumAmount());
        dto.setDeductibleAmount(entity.getDeductibleAmount());
        dto.setCurrencyCode(entity.getCurrencyCode());
        dto.setAllocationMethod(entity.getAllocationMethod());
        dto.setIncludedInInventoryCost(entity.isIncludedInInventoryCost());
        dto.setValidFrom(entity.getValidFrom());
        dto.setValidTo(entity.getValidTo());
        dto.setPolicyAttachmentId(entity.getPolicyAttachmentId());
        dto.setLandedCostComponentId(entity.getLandedCostComponentId());
        dto.setLandedCostComponentCode(BaseMapper.code(entity.getLandedCostComponent()));
        dto.setLandedCostComponentName(BaseMapper.name(entity.getLandedCostComponent()));
        dto.setActivatedAt(entity.getActivatedAt());
        dto.setClaimedAt(entity.getClaimedAt());
        dto.setClosedAt(entity.getClosedAt());
        return dto;
    }

    public ImportInsurancePolicy toEntity(ImportInsurancePolicyCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        ImportInsurancePolicy entity = new ImportInsurancePolicy();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setPolicyNumber(dto.getPolicyNumber());
        entity.setImportDeliveryId(dto.getImportDeliveryId());
        entity.setInsurerSupplierId(dto.getInsurerSupplierId());
        entity.setInsurerName(dto.getInsurerName());
        entity.setCoverageType(dto.getCoverageType());
        entity.setInsuredAmount(dto.getInsuredAmount());
        entity.setPremiumAmount(dto.getPremiumAmount());
        entity.setDeductibleAmount(dto.getDeductibleAmount());
        entity.setCurrencyCode(dto.getCurrencyCode());
        entity.setAllocationMethod(dto.getAllocationMethod());
        entity.setIncludedInInventoryCost(dto.isIncludedInInventoryCost());
        entity.setValidFrom(dto.getValidFrom());
        entity.setValidTo(dto.getValidTo());
        entity.setPolicyAttachmentId(dto.getPolicyAttachmentId());
        entity.setLandedCostComponentId(dto.getLandedCostComponentId());
        entity.setActivatedAt(dto.getActivatedAt());
        entity.setClaimedAt(dto.getClaimedAt());
        entity.setClosedAt(dto.getClosedAt());
        return entity;
    }

    public void updateEntity(ImportInsurancePolicy entity, ImportInsurancePolicyUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setPolicyNumber(dto.getPolicyNumber());
        entity.setImportDeliveryId(dto.getImportDeliveryId());
        entity.setInsurerSupplierId(dto.getInsurerSupplierId());
        entity.setInsurerName(dto.getInsurerName());
        entity.setCoverageType(dto.getCoverageType());
        entity.setInsuredAmount(dto.getInsuredAmount());
        entity.setPremiumAmount(dto.getPremiumAmount());
        entity.setDeductibleAmount(dto.getDeductibleAmount());
        entity.setCurrencyCode(dto.getCurrencyCode());
        entity.setAllocationMethod(dto.getAllocationMethod());
        entity.setIncludedInInventoryCost(dto.isIncludedInInventoryCost());
        entity.setValidFrom(dto.getValidFrom());
        entity.setValidTo(dto.getValidTo());
        entity.setPolicyAttachmentId(dto.getPolicyAttachmentId());
    }
}
