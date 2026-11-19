package com.enterprise.mm.mapper;

import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.payload.PurchaseRequestItemResponse;
import com.enterprise.mm.payload.PurchaseRequestItemUpdateRequest;
import com.enterprise.mm.payload.PurchaseRequestItemCreateRequest;
import org.springframework.stereotype.Component;

@Component
@lombok.RequiredArgsConstructor
public class PurchaseRequestItemMapper {
    private final com.enterprise.mm.service.UnitOfMeasureDisplayService unitDisplay;
    public PurchaseRequestItemResponse toResponse(PurchaseRequestItem entity) {
        if (entity == null) {
            return null;
        }
        PurchaseRequestItemResponse dto = new PurchaseRequestItemResponse();
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
        dto.setPurchaseRequestId(entity.getPurchaseRequestId());
        dto.setPurchaseRequestCode(BaseMapper.code(entity.getPurchaseRequest()));
        dto.setPurchaseRequestName(BaseMapper.name(entity.getPurchaseRequest()));
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
        dto.setQuantity(entity.getQuantity());
        dto.setEstimatedPrice(entity.getEstimatedPrice());
        dto.setRequiredDate(entity.fulfillmentDate(entity.getPurchaseRequest()));
        dto.setBudgetItemId(entity.getBudgetItemId());
        dto.setBudgetItemCode(BaseMapper.code(entity.getBudgetItem()));
        dto.setBudgetItemName(BaseMapper.name(entity.getBudgetItem()));
        if (dto.getPurchaseRequestId() == null && entity.getPurchaseRequest() != null) {
            dto.setPurchaseRequestId(entity.getPurchaseRequest().getId());
        }
        if (dto.getMaterialId() == null && entity.getMaterial() != null) {
            dto.setMaterialId(entity.getMaterial().getId());
        }
        if (dto.getBudgetItemId() == null && entity.getBudgetItem() != null) {
            dto.setBudgetItemId(entity.getBudgetItem().getId());
        }
        return dto;
    }

    public PurchaseRequestItem toEntity(PurchaseRequestItemCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        PurchaseRequestItem entity = new PurchaseRequestItem();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setPurchaseRequestId(dto.getPurchaseRequestId());
        entity.setLineType(dto.getLineType() == null ? null : com.enterprise.mm.enums.ProcurementLineType.valueOf(dto.getLineType().trim().toUpperCase(java.util.Locale.ROOT)));
        entity.setMaterialId(dto.getMaterialId());
        entity.setMaterialTypeSnapshot(dto.getMaterialTypeSnapshot());
        entity.setServiceRequestId(dto.getServiceRequestId());
        entity.setServiceDescription(dto.getServiceDescription());
        entity.setServiceCategory(dto.getServiceCategory());
        entity.setUnit(dto.getUnit());
        entity.setPeriod(dto.getPeriod());
        entity.setStage(dto.getStage());
        entity.setQuantity(dto.getQuantity());
        entity.setEstimatedPrice(dto.getEstimatedPrice());
        entity.setBudgetItemId(dto.getBudgetItemId());
        return entity;
    }

    public void updateEntity(PurchaseRequestItem entity, PurchaseRequestItemUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        if (!java.util.Objects.equals(entity.getMaterialId(), dto.getMaterialId())) entity.setMaterial(null);
        if (!java.util.Objects.equals(entity.getBudgetItemId(), dto.getBudgetItemId())) entity.setBudgetItem(null);
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setPurchaseRequestId(dto.getPurchaseRequestId());
        entity.setLineType(dto.getLineType() == null ? null : com.enterprise.mm.enums.ProcurementLineType.valueOf(dto.getLineType().trim().toUpperCase(java.util.Locale.ROOT)));
        entity.setMaterialId(dto.getMaterialId());
        entity.setMaterialTypeSnapshot(dto.getMaterialTypeSnapshot());
        entity.setServiceRequestId(dto.getServiceRequestId());
        entity.setServiceDescription(dto.getServiceDescription());
        entity.setServiceCategory(dto.getServiceCategory());
        entity.setUnit(dto.getUnit());
        entity.setPeriod(dto.getPeriod());
        entity.setStage(dto.getStage());
        entity.setQuantity(dto.getQuantity());
        entity.setEstimatedPrice(dto.getEstimatedPrice());
        entity.setBudgetItemId(dto.getBudgetItemId());
    }
}
