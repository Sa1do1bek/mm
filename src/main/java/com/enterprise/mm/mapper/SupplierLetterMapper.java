package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.SupplierLetterResponse;
import com.enterprise.mm.payload.SupplierLetterUpdateRequest;
import com.enterprise.mm.payload.SupplierLetterCreateRequest;
import com.enterprise.mm.entity.SupplierLetter;
import org.springframework.stereotype.Component;

@Component
public class SupplierLetterMapper {
    public SupplierLetterResponse toResponse(SupplierLetter entity) {
        if (entity == null) {
            return null;
        }
        SupplierLetterResponse dto = new SupplierLetterResponse();
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
        dto.setSupplierId(entity.getSupplierId());
        dto.setSupplierCode(BaseMapper.supplierCode(entity.getSupplier()));
        dto.setSupplierName(BaseMapper.supplierName(entity.getSupplier()));
        dto.setLetterNumber(entity.getLetterNumber());
        dto.setLetterDate(entity.getLetterDate());
        dto.setDirection(entity.getDirection());
        dto.setSubject(entity.getSubject());
        dto.setChannel(entity.getChannel());
        return dto;
    }

    public SupplierLetter toEntity(SupplierLetterCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        SupplierLetter entity = new SupplierLetter();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setSupplierId(dto.getSupplierId());
        entity.setLetterNumber(dto.getLetterNumber());
        entity.setLetterDate(dto.getLetterDate());
        entity.setDirection(dto.getDirection());
        entity.setSubject(dto.getSubject());
        entity.setChannel(dto.getChannel());
        return entity;
    }

    public void updateEntity(SupplierLetter entity, SupplierLetterUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setSupplierId(dto.getSupplierId());
        entity.setLetterNumber(dto.getLetterNumber());
        entity.setLetterDate(dto.getLetterDate());
        entity.setDirection(dto.getDirection());
        entity.setSubject(dto.getSubject());
        entity.setChannel(dto.getChannel());
    }
}