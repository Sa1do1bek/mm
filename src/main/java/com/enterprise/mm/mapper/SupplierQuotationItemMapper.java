package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.SupplierQuotationItemResponse;
import com.enterprise.mm.payload.SupplierQuotationItemUpdateRequest;
import com.enterprise.mm.payload.SupplierQuotationItemCreateRequest;
import com.enterprise.mm.entity.SupplierQuotationItem;
import org.springframework.stereotype.Component;

@Component
@lombok.RequiredArgsConstructor
public class SupplierQuotationItemMapper {
    private final com.enterprise.mm.service.UnitOfMeasureDisplayService unitDisplay;
    public SupplierQuotationItemResponse toResponse(SupplierQuotationItem entity) {
        if (entity == null) {
            return null;
        }
        SupplierQuotationItemResponse dto = new SupplierQuotationItemResponse();
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
        dto.setQuotationId(entity.getQuotationId());
        dto.setQuotationCode(BaseMapper.code(entity.getQuotation()));
        dto.setQuotationName(BaseMapper.name(entity.getQuotation()));
        dto.setRfqItemId(entity.getRfqItemId());
        dto.setRfqItemCode(BaseMapper.code(entity.getRfqItem()));
        dto.setRfqItemName(BaseMapper.name(entity.getRfqItem()));
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
        dto.setPricingModel(entity.getPricingModel());
        dto.setBudgetItemId(entity.getBudgetItemId());
        dto.setQuantity(entity.getQuantity());
        dto.setUnitPrice(entity.getUnitPrice());
        dto.setDeliveryDays(entity.getDeliveryDays());
        return dto;
    }

    public SupplierQuotationItem toEntity(SupplierQuotationItemCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        SupplierQuotationItem entity = new SupplierQuotationItem();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setQuotationId(dto.getQuotationId());
        entity.setRfqItemId(dto.getRfqItemId());
        entity.setLineType(dto.getLineType() == null ? null : com.enterprise.mm.enums.ProcurementLineType.valueOf(dto.getLineType().trim().toUpperCase(java.util.Locale.ROOT)));
        entity.setMaterialId(dto.getMaterialId());
        entity.setMaterialTypeSnapshot(dto.getMaterialTypeSnapshot());
        entity.setServiceRequestId(dto.getServiceRequestId());
        entity.setServiceDescription(dto.getServiceDescription());
        entity.setServiceCategory(dto.getServiceCategory());
        entity.setUnit(dto.getUnit());
        entity.setPeriod(dto.getPeriod());
        entity.setStage(dto.getStage());
        entity.setPricingModel(dto.getPricingModel());
        entity.setBudgetItemId(dto.getBudgetItemId());
        entity.setQuantity(dto.getQuantity());
        entity.setUnitPrice(dto.getUnitPrice());
        entity.setDeliveryDays(dto.getDeliveryDays());
        return entity;
    }

    public void updateEntity(SupplierQuotationItem entity, SupplierQuotationItemUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setQuotationId(dto.getQuotationId());
        entity.setRfqItemId(dto.getRfqItemId());
        entity.setLineType(dto.getLineType() == null ? null : com.enterprise.mm.enums.ProcurementLineType.valueOf(dto.getLineType().trim().toUpperCase(java.util.Locale.ROOT)));
        entity.setMaterialId(dto.getMaterialId());
        entity.setMaterialTypeSnapshot(dto.getMaterialTypeSnapshot());
        entity.setServiceRequestId(dto.getServiceRequestId());
        entity.setServiceDescription(dto.getServiceDescription());
        entity.setServiceCategory(dto.getServiceCategory());
        entity.setUnit(dto.getUnit());
        entity.setPeriod(dto.getPeriod());
        entity.setStage(dto.getStage());
        entity.setPricingModel(dto.getPricingModel());
        entity.setBudgetItemId(dto.getBudgetItemId());
        entity.setQuantity(dto.getQuantity());
        entity.setUnitPrice(dto.getUnitPrice());
        entity.setDeliveryDays(dto.getDeliveryDays());
    }
}
