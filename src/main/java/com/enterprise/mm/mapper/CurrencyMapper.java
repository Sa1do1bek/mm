package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.CurrencyResponse;
import com.enterprise.mm.payload.CurrencyUpdateRequest;
import com.enterprise.mm.payload.CurrencyCreateRequest;
import com.enterprise.mm.entity.Currency;
import org.springframework.stereotype.Component;

@Component
public class CurrencyMapper {
    public CurrencyResponse toResponse(Currency entity) {
        if (entity == null) {
            return null;
        }
        CurrencyResponse dto = new CurrencyResponse();
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
        dto.setIsoCode(entity.getIsoCode());
        dto.setNumericCode(entity.getNumericCode());
        dto.setSymbol(entity.getSymbol());
        dto.setMinorUnit(entity.getMinorUnit());
        dto.setBaseCurrency(entity.isBaseCurrency());
        return dto;
    }

    public Currency toEntity(CurrencyCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        Currency entity = new Currency();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setIsoCode(dto.getIsoCode());
        entity.setNumericCode(dto.getNumericCode());
        entity.setSymbol(dto.getSymbol());
        entity.setMinorUnit(dto.getMinorUnit());
        entity.setBaseCurrency(dto.isBaseCurrency());
        return entity;
    }

    public void updateEntity(Currency entity, CurrencyUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setIsoCode(dto.getIsoCode());
        entity.setNumericCode(dto.getNumericCode());
        entity.setSymbol(dto.getSymbol());
        entity.setMinorUnit(dto.getMinorUnit());
        entity.setBaseCurrency(dto.isBaseCurrency());
    }
}