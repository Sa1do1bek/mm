package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.EquipmentResponse;
import com.enterprise.mm.payload.EquipmentUpdateRequest;
import com.enterprise.mm.payload.EquipmentCreateRequest;
import com.enterprise.mm.entity.Equipment;
import org.springframework.stereotype.Component;

@Component
public class EquipmentMapper {
    public EquipmentResponse toResponse(Equipment entity) {
        if (entity == null) {
            return null;
        }
        EquipmentResponse dto = new EquipmentResponse();
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
        dto.setExternalEquipmentId(entity.getExternalEquipmentId());
        dto.setModel(entity.getModel());
        dto.setManufacturerId(entity.getManufacturerId());
        dto.setManufacturerName(entity.getManufacturerRef() == null
            ? entity.getManufacturer()
            : entity.getManufacturerRef().getName());
        dto.setManufacturer(entity.getManufacturerRef() == null
            ? entity.getManufacturer()
            : entity.getManufacturerRef().getName());
        dto.setSerialNumber(entity.getSerialNumber());
        dto.setDepartmentId(entity.getDepartmentId());
        dto.setLocation(entity.getLocation());
        return dto;
    }

    public Equipment toEntity(EquipmentCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        Equipment entity = new Equipment();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setExternalEquipmentId(dto.getExternalEquipmentId());
        entity.setModel(dto.getModel());
        entity.setManufacturerId(dto.getManufacturerId());
        entity.setManufacturer(dto.getManufacturer());
        entity.setSerialNumber(dto.getSerialNumber());
        entity.setDepartmentId(dto.getDepartmentId());
        entity.setLocation(dto.getLocation());
        return entity;
    }

    public void updateEntity(Equipment entity, EquipmentUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setExternalEquipmentId(dto.getExternalEquipmentId());
        entity.setModel(dto.getModel());
        entity.setManufacturerId(dto.getManufacturerId());
        entity.setManufacturer(dto.getManufacturer());
        entity.setSerialNumber(dto.getSerialNumber());
        entity.setDepartmentId(dto.getDepartmentId());
        entity.setLocation(dto.getLocation());
    }
}
