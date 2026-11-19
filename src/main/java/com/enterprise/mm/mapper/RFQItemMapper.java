package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.RFQItemResponse;
import com.enterprise.mm.payload.RFQItemUpdateRequest;
import com.enterprise.mm.payload.RFQItemCreateRequest;
import com.enterprise.mm.entity.RFQItem;
import org.springframework.stereotype.Component;

@Component
@lombok.RequiredArgsConstructor
public class RFQItemMapper {
    private final com.enterprise.mm.service.UnitOfMeasureDisplayService unitDisplay;
    public RFQItemResponse toResponse(RFQItem entity) {
        if (entity == null) {
            return null;
        }
        RFQItemResponse dto = new RFQItemResponse();
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
        dto.setRfqId(entity.getRfqId());
        dto.setRfqCode(BaseMapper.code(entity.getRfq()));
        dto.setRfqName(BaseMapper.name(entity.getRfq()));
        dto.setLineType(entity.getLineType() == null ? null : entity.getLineType().name());
        dto.setMaterialId(entity.getMaterialId());
        dto.setMaterialCode(BaseMapper.materialCode(entity.getMaterial()));
        dto.setMaterialName(BaseMapper.materialName(entity.getMaterial()));
        dto.setMaterialTypeSnapshot(entity.getMaterialTypeSnapshot());
        dto.setServiceRequestId(entity.getServiceRequestId());
        dto.setServiceDescription(entity.getServiceDescription());
        dto.setServiceCategory(entity.getServiceCategory());
        dto.setUnit(entity.getUnit());
        dto.setUnitShortName(unitDisplay.shortName(entity, entity.getUnit(), entity.getMaterial()));
        dto.setPeriod(entity.getPeriod());
        dto.setStage(entity.getStage());
        dto.setBudgetItemId(entity.getBudgetItemId());
        dto.setQuantity(entity.getQuantity());
        return dto;
    }

    public RFQItem toEntity(RFQItemCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        RFQItem entity = new RFQItem();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setRfqId(dto.getRfqId());
        entity.setLineType(dto.getLineType() == null ? null : com.enterprise.mm.enums.ProcurementLineType.valueOf(dto.getLineType().trim().toUpperCase(java.util.Locale.ROOT)));
        entity.setMaterialId(dto.getMaterialId());
        entity.setMaterialTypeSnapshot(dto.getMaterialTypeSnapshot());
        entity.setServiceRequestId(dto.getServiceRequestId());
        entity.setServiceDescription(dto.getServiceDescription());
        entity.setServiceCategory(dto.getServiceCategory());
        entity.setUnit(dto.getUnit());
        entity.setPeriod(dto.getPeriod());
        entity.setStage(dto.getStage());
        entity.setBudgetItemId(dto.getBudgetItemId());
        entity.setQuantity(dto.getQuantity());
        return entity;
    }

    public void updateEntity(RFQItem entity, RFQItemUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setRfqId(dto.getRfqId());
        entity.setLineType(dto.getLineType() == null ? null : com.enterprise.mm.enums.ProcurementLineType.valueOf(dto.getLineType().trim().toUpperCase(java.util.Locale.ROOT)));
        entity.setMaterialId(dto.getMaterialId());
        entity.setMaterialTypeSnapshot(dto.getMaterialTypeSnapshot());
        entity.setServiceRequestId(dto.getServiceRequestId());
        entity.setServiceDescription(dto.getServiceDescription());
        entity.setServiceCategory(dto.getServiceCategory());
        entity.setUnit(dto.getUnit());
        entity.setPeriod(dto.getPeriod());
        entity.setStage(dto.getStage());
        entity.setBudgetItemId(dto.getBudgetItemId());
        entity.setQuantity(dto.getQuantity());
    }
}
