package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.ManufacturerResponse;
import com.enterprise.mm.payload.ManufacturerUpdateRequest;
import com.enterprise.mm.payload.ManufacturerCreateRequest;
import com.enterprise.mm.entity.Manufacturer;
import org.springframework.stereotype.Component;

@Component
public class ManufacturerMapper {
    public ManufacturerResponse toResponse(Manufacturer entity) {
        if (entity == null) {
            return null;
        }
        ManufacturerResponse dto = new ManufacturerResponse();
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
        dto.setCountry(entity.getCountry());
        dto.setCountryCode(entity.getCountryCode());
        dto.setWebsite(entity.getWebsite());
        dto.setEmail(entity.getEmail());
        dto.setPhone(entity.getPhone());
        return dto;
    }

    public Manufacturer toEntity(ManufacturerCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        Manufacturer entity = new Manufacturer();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setCountry(dto.getCountry());
        entity.setCountryCode(dto.getCountryCode());
        entity.setWebsite(dto.getWebsite());
        entity.setEmail(dto.getEmail());
        entity.setPhone(dto.getPhone());
        return entity;
    }

    public void updateEntity(Manufacturer entity, ManufacturerUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setCountry(dto.getCountry());
        entity.setCountryCode(dto.getCountryCode());
        entity.setWebsite(dto.getWebsite());
        entity.setEmail(dto.getEmail());
        entity.setPhone(dto.getPhone());
    }
}