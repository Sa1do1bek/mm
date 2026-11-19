package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.LandedLogisticsEventResponse;
import com.enterprise.mm.payload.LandedLogisticsEventUpdateRequest;
import com.enterprise.mm.payload.LandedLogisticsEventCreateRequest;
import com.enterprise.mm.entity.LandedLogisticsEvent;
import org.springframework.stereotype.Component;

@Component
public class LandedLogisticsEventMapper {
    public LandedLogisticsEventResponse toResponse(LandedLogisticsEvent entity) {
        if (entity == null) {
            return null;
        }
        LandedLogisticsEventResponse dto = new LandedLogisticsEventResponse();
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
        dto.setImportDeliveryId(entity.getImportDeliveryId());
        dto.setImportDeliveryCode(BaseMapper.code(entity.getImportDelivery()));
        dto.setImportDeliveryName(BaseMapper.name(entity.getImportDelivery()));
        dto.setEventType(entity.getEventType());
        dto.setEventDate(entity.getEventDate());
        dto.setLocation(entity.getLocation());
        dto.setCarrierStatus(entity.getCarrierStatus());
        dto.setExternalSystem(entity.getExternalSystem());
        dto.setExternalEventId(entity.getExternalEventId());
        dto.setExceptionFlag(entity.isExceptionFlag());
        dto.setResolvedAt(entity.getResolvedAt());
        return dto;
    }

    public LandedLogisticsEvent toEntity(LandedLogisticsEventCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        LandedLogisticsEvent entity = new LandedLogisticsEvent();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setImportDeliveryId(dto.getImportDeliveryId());
        entity.setEventType(dto.getEventType());
        entity.setEventDate(dto.getEventDate());
        entity.setLocation(dto.getLocation());
        entity.setCarrierStatus(dto.getCarrierStatus());
        entity.setExternalSystem(dto.getExternalSystem());
        entity.setExternalEventId(dto.getExternalEventId());
        entity.setExceptionFlag(dto.isExceptionFlag());
        return entity;
    }

    public void updateEntity(LandedLogisticsEvent entity, LandedLogisticsEventUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setImportDeliveryId(dto.getImportDeliveryId());
        entity.setEventType(dto.getEventType());
        entity.setEventDate(dto.getEventDate());
        entity.setLocation(dto.getLocation());
        entity.setCarrierStatus(dto.getCarrierStatus());
        entity.setExternalSystem(dto.getExternalSystem());
        entity.setExternalEventId(dto.getExternalEventId());
        entity.setExceptionFlag(dto.isExceptionFlag());
    }
}
