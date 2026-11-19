package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.BudgetItemResponse;
import com.enterprise.mm.payload.BudgetItemUpdateRequest;
import com.enterprise.mm.payload.BudgetItemCreateRequest;
import com.enterprise.mm.entity.BudgetItem;
import org.springframework.stereotype.Component;

@Component
public class BudgetItemMapper {
    public BudgetItemResponse toResponse(BudgetItem entity) {
        if (entity == null) {
            return null;
        }
        BudgetItemResponse dto = new BudgetItemResponse();
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
        dto.setAnnualLimit(entity.getAnnualLimit());
        dto.setReservedAmount(entity.getReservedAmount());
        dto.setCommittedAmount(entity.getCommittedAmount());
        dto.setUsedAmount(entity.getUsedAmount());
        return dto;
    }

    public BudgetItem toEntity(BudgetItemCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        BudgetItem entity = new BudgetItem();
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setAnnualLimit(dto.getAnnualLimit());
        return entity;
    }

    public void updateEntity(BudgetItem entity, BudgetItemUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }

        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setAnnualLimit(dto.getAnnualLimit());
    }
}
