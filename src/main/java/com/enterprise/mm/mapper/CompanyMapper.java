package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.CompanyResponse;
import com.enterprise.mm.payload.CompanyUpdateRequest;
import com.enterprise.mm.payload.CompanyCreateRequest;
import com.enterprise.mm.entity.Company;
import org.springframework.stereotype.Component;

@Component
public class CompanyMapper {
    public CompanyResponse toResponse(Company entity) {
        if (entity == null) {
            return null;
        }
        CompanyResponse dto = new CompanyResponse();
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
        dto.setLegalName(entity.getLegalName());
        dto.setShortName(entity.getShortName());
        dto.setTaxId(entity.getTaxId());
        dto.setOkedCode(entity.getOkedCode());
        dto.setRegistrationNumber(entity.getRegistrationNumber());
        dto.setRegistrationDate(entity.getRegistrationDate());
        dto.setLegalAddress(entity.getLegalAddress());
        dto.setPhone(entity.getPhone());
        dto.setEmail(entity.getEmail());
        dto.setNonMovingStockThresholdMonths(entity.getNonMovingStockThresholdMonths());
        dto.setNonMovingStockAutoDetectEnabled(entity.getNonMovingStockAutoDetectEnabled());
        return dto;
    }

    public Company toEntity(CompanyCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        Company entity = new Company();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setLegalName(dto.getLegalName());
        entity.setShortName(dto.getShortName());
        entity.setTaxId(dto.getTaxId());
        entity.setOkedCode(dto.getOkedCode());
        entity.setRegistrationNumber(dto.getRegistrationNumber());
        entity.setRegistrationDate(dto.getRegistrationDate());
        entity.setLegalAddress(dto.getLegalAddress());
        entity.setPhone(dto.getPhone());
        entity.setEmail(dto.getEmail());
        entity.setNonMovingStockThresholdMonths(dto.getNonMovingStockThresholdMonths());
        entity.setNonMovingStockAutoDetectEnabled(dto.getNonMovingStockAutoDetectEnabled());
        return entity;
    }

    public void updateEntity(Company entity, CompanyUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setLegalName(dto.getLegalName());
        entity.setShortName(dto.getShortName());
        entity.setTaxId(dto.getTaxId());
        entity.setOkedCode(dto.getOkedCode());
        entity.setRegistrationNumber(dto.getRegistrationNumber());
        entity.setRegistrationDate(dto.getRegistrationDate());
        entity.setLegalAddress(dto.getLegalAddress());
        entity.setPhone(dto.getPhone());
        entity.setEmail(dto.getEmail());
        entity.setNonMovingStockThresholdMonths(dto.getNonMovingStockThresholdMonths());
        entity.setNonMovingStockAutoDetectEnabled(dto.getNonMovingStockAutoDetectEnabled());
    }
}
