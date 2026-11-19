package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.ContractSpecificationResponse;
import com.enterprise.mm.payload.ContractSpecificationUpdateRequest;
import com.enterprise.mm.payload.ContractSpecificationCreateRequest;
import com.enterprise.mm.entity.ContractSpecification;
import org.springframework.stereotype.Component;

@Component
@lombok.RequiredArgsConstructor
public class ContractSpecificationMapper {
    private final com.enterprise.mm.service.UnitOfMeasureDisplayService unitDisplay;
    public ContractSpecificationResponse toResponse(ContractSpecification entity) {
        if (entity == null) {
            return null;
        }
        ContractSpecificationResponse dto = new ContractSpecificationResponse();
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
        dto.setContractId(entity.getContractId());
        dto.setLineType(entity.getLineType() == null ? null : entity.getLineType().name());
        dto.setMaterialId(entity.getMaterialId());
        dto.setMaterialGroupId(entity.getMaterialGroupId());
        dto.setUnitOfMeasureId(entity.getUnitOfMeasureId());
        dto.setMaterialTypeSnapshot(entity.getMaterialTypeSnapshot());
        dto.setServiceDescription(entity.getServiceDescription());
        dto.setServiceCategory(entity.getServiceCategory());
        dto.setUnit(entity.getUnit());
        dto.setUnitShortName(unitDisplay.shortName(entity, entity.getUnitOfMeasureId(), entity.getUnit()));
        dto.setPeriod(entity.getPeriod());
        dto.setStage(entity.getStage());
        dto.setServiceLimit(entity.getServiceLimit());
        dto.setSla(entity.getSla());
        dto.setPricingModel(entity.getPricingModel());
        dto.setQuantityLimit(entity.getQuantityLimit());
        dto.setPrice(entity.getPrice());
        dto.setSourceRfqItemId(entity.getSourceRfqItemId());
        dto.setSourceQuotationItemId(entity.getSourceQuotationItemId());
        dto.setSourcePurchaseRequestItemId(entity.getSourcePurchaseRequestItemId());
        return dto;
    }

    public ContractSpecification toEntity(ContractSpecificationCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        ContractSpecification entity = new ContractSpecification();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setContractId(dto.getContractId());
        entity.setLineType(dto.getLineType() == null ? null : com.enterprise.mm.enums.ProcurementLineType.valueOf(dto.getLineType().trim().toUpperCase(java.util.Locale.ROOT)));
        entity.setMaterialId(dto.getMaterialId());
        entity.setMaterialGroupId(dto.getMaterialGroupId());
        entity.setUnitOfMeasureId(dto.getUnitOfMeasureId());
        entity.setMaterialTypeSnapshot(dto.getMaterialTypeSnapshot());
        entity.setServiceDescription(dto.getServiceDescription());
        entity.setServiceCategory(dto.getServiceCategory());
        entity.setUnit(dto.getUnit());
        entity.setPeriod(dto.getPeriod());
        entity.setStage(dto.getStage());
        entity.setServiceLimit(dto.getServiceLimit());
        entity.setSla(dto.getSla());
        entity.setPricingModel(dto.getPricingModel());
        entity.setQuantityLimit(dto.getQuantityLimit());
        entity.setPrice(dto.getPrice());
        return entity;
    }

    public void updateEntity(ContractSpecification entity, ContractSpecificationUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setContractId(dto.getContractId());
        entity.setLineType(dto.getLineType() == null ? null : com.enterprise.mm.enums.ProcurementLineType.valueOf(dto.getLineType().trim().toUpperCase(java.util.Locale.ROOT)));
        entity.setMaterialId(dto.getMaterialId());
        entity.setMaterialGroupId(dto.getMaterialGroupId());
        entity.setUnitOfMeasureId(dto.getUnitOfMeasureId());
        entity.setMaterialTypeSnapshot(dto.getMaterialTypeSnapshot());
        entity.setServiceDescription(dto.getServiceDescription());
        entity.setServiceCategory(dto.getServiceCategory());
        entity.setUnit(dto.getUnit());
        entity.setPeriod(dto.getPeriod());
        entity.setStage(dto.getStage());
        entity.setServiceLimit(dto.getServiceLimit());
        entity.setSla(dto.getSla());
        entity.setPricingModel(dto.getPricingModel());
        entity.setQuantityLimit(dto.getQuantityLimit());
        entity.setPrice(dto.getPrice());
    }
}
