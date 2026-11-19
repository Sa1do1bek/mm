package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.DeliveryScheduleResponse;
import com.enterprise.mm.payload.DeliveryScheduleUpdateRequest;
import com.enterprise.mm.payload.DeliveryScheduleCreateRequest;
import com.enterprise.mm.entity.DeliverySchedule;
import org.springframework.stereotype.Component;

@Component
public class DeliveryScheduleMapper {
    public DeliveryScheduleResponse toResponse(DeliverySchedule entity) {
        if (entity == null) {
            return null;
        }
        DeliveryScheduleResponse dto = new DeliveryScheduleResponse();
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
        dto.setPurchaseOrderId(entity.getPurchaseOrderId());
        dto.setPurchaseOrderCode(BaseMapper.code(entity.getPurchaseOrder()));
        dto.setPurchaseOrderName(BaseMapper.name(entity.getPurchaseOrder()));
        dto.setContractId(entity.getContractId());
        dto.setContractCode(BaseMapper.code(entity.getContract()));
        dto.setContractName(BaseMapper.name(entity.getContract()));
        dto.setScheduleDate(entity.getScheduleDate());
        dto.setPlannedQuantity(entity.getPlannedQuantity());
        dto.setPlannedAmount(entity.getPlannedAmount());
        dto.setReceivedQuantity(entity.getReceivedQuantity());
        dto.setReceivedAmount(entity.getReceivedAmount());
        dto.setOverdueFlag(entity.isOverdueFlag());
        dto.setControlledAt(entity.getControlledAt());
        dto.setBusinessImpactReference(entity.getBusinessImpactReference());
        dto.setBusinessImpactComment(entity.getBusinessImpactComment());
        dto.setAttributableShare(entity.getAttributableShare());
        dto.setImpactCurrencyCode(entity.getImpactCurrencyCode());
        return dto;
    }

    public DeliverySchedule toEntity(DeliveryScheduleCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        DeliverySchedule entity = new DeliverySchedule();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setPurchaseOrderId(dto.getPurchaseOrderId());
        entity.setContractId(dto.getContractId());
        entity.setScheduleDate(dto.getScheduleDate());
        entity.setPlannedQuantity(dto.getPlannedQuantity());
        entity.setPlannedAmount(dto.getPlannedAmount());
        entity.setBusinessImpactReference(dto.getBusinessImpactReference());
        entity.setBusinessImpactComment(dto.getBusinessImpactComment());
        entity.setAttributableShare(dto.getAttributableShare());
        entity.setImpactCurrencyCode(dto.getImpactCurrencyCode());
        return entity;
    }

    public void updateEntity(DeliverySchedule entity, DeliveryScheduleUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setPurchaseOrderId(dto.getPurchaseOrderId());
        entity.setContractId(dto.getContractId());
        entity.setScheduleDate(dto.getScheduleDate());
        entity.setPlannedQuantity(dto.getPlannedQuantity());
        entity.setPlannedAmount(dto.getPlannedAmount());
        entity.setBusinessImpactReference(dto.getBusinessImpactReference());
        entity.setBusinessImpactComment(dto.getBusinessImpactComment());
        entity.setAttributableShare(dto.getAttributableShare());
        entity.setImpactCurrencyCode(dto.getImpactCurrencyCode());
    }
}
