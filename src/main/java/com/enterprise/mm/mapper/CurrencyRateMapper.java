package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.CurrencyRateResponse;
import com.enterprise.mm.payload.CurrencyRateUpdateRequest;
import com.enterprise.mm.payload.CurrencyRateCreateRequest;
import com.enterprise.mm.entity.CurrencyRate;
import org.springframework.stereotype.Component;

@Component
public class CurrencyRateMapper {
    public CurrencyRateResponse toResponse(CurrencyRate entity) {
        if (entity == null) {
            return null;
        }
        CurrencyRateResponse dto = new CurrencyRateResponse();
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
        dto.setCurrencyId(entity.getCurrencyId());
        dto.setCurrencyCode(entity.getCurrencyCode());
        dto.setBaseCurrencyCode(entity.getBaseCurrencyCode());
        dto.setRateDate(entity.getRateDate());
        dto.setNominal(entity.getNominal());
        dto.setRate(entity.getRate());
        dto.setSource(entity.getSource());
        dto.setOfficialRate(entity.isOfficialRate());
        return dto;
    }

    public CurrencyRate toEntity(CurrencyRateCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        CurrencyRate entity = new CurrencyRate();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setCurrencyId(dto.getCurrencyId());
        entity.setCurrencyCode(dto.getCurrencyCode());
        entity.setBaseCurrencyCode(dto.getBaseCurrencyCode());
        entity.setRateDate(dto.getRateDate());
        entity.setNominal(dto.getNominal());
        entity.setRate(dto.getRate());
        entity.setSource(dto.getSource());
        entity.setOfficialRate(dto.isOfficialRate());
        return entity;
    }

    public void updateEntity(CurrencyRate entity, CurrencyRateUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setCurrencyId(dto.getCurrencyId());
        entity.setCurrencyCode(dto.getCurrencyCode());
        entity.setBaseCurrencyCode(dto.getBaseCurrencyCode());
        entity.setRateDate(dto.getRateDate());
        entity.setNominal(dto.getNominal());
        entity.setRate(dto.getRate());
        entity.setSource(dto.getSource());
        entity.setOfficialRate(dto.isOfficialRate());
    }
}