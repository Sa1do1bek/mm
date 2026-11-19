package com.enterprise.mm.mapper;

import com.enterprise.mm.entity.ReferenceMapping;
import com.enterprise.mm.payload.ReferenceMappingResponse;
import com.enterprise.mm.payload.ReferenceMappingUpdateRequest;
import com.enterprise.mm.payload.ReferenceMappingCreateRequest;
import org.springframework.stereotype.Component;

@Component
public class ReferenceMappingMapper {
    public ReferenceMappingResponse toResponse(ReferenceMapping entity) {
        if (entity == null) {
            return null;
        }
        ReferenceMappingResponse response = new ReferenceMappingResponse();
        response.setId(entity.getId());
        response.setTenantId(entity.getTenantId());
        response.setCompanyId(entity.getCompanyId());
        response.setCode(entity.getCode());
        response.setName(entity.getName());
        response.setStatus(entity.getStatus());
        response.setDescription(entity.getDescription());
        response.setDeleted(entity.isDeleted());
        response.setCreatedAt(entity.getCreatedAt());
        response.setUpdatedAt(entity.getUpdatedAt());
        response.setCreatedBy(entity.getCreatedBy());
        response.setUpdatedBy(entity.getUpdatedBy());
        response.setVersion(entity.getVersion());
        response.setReferenceEntityType(entity.getReferenceEntityType());
        response.setReferenceId(entity.getReferenceId());
        response.setReferenceCode(entity.getReferenceCode());
        response.setReferenceName(entity.getReferenceName());
        response.setExternalSystem(entity.getExternalSystem());
        response.setExternalCode(entity.getExternalCode());
        response.setExternalName(entity.getExternalName());
        response.setAccountingArea(entity.getAccountingArea());
        response.setChartOfAccountsCode(entity.getChartOfAccountsCode());
        response.setAccountCode(entity.getAccountCode());
        response.setSubAccountCode(entity.getSubAccountCode());
        response.setCostCenter(entity.getCostCenter());
        response.setProfitCenter(entity.getProfitCenter());
        response.setWarehouseAccountingCode(entity.getWarehouseAccountingCode());
        response.setTaxCode(entity.getTaxCode());
        response.setCurrencyCode(entity.getCurrencyCode());
        response.setPriority(entity.getPriority());
        response.setDefaultMapping(entity.isDefaultMapping());
        response.setValidFrom(entity.getValidFrom());
        response.setValidTo(entity.getValidTo());
        return response;
    }

    public ReferenceMapping toEntity(ReferenceMappingCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        ReferenceMapping entity = new ReferenceMapping();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setReferenceEntityType(dto.getReferenceEntityType());
        entity.setReferenceId(dto.getReferenceId());
        entity.setReferenceCode(dto.getReferenceCode());
        entity.setReferenceName(dto.getReferenceName());
        entity.setExternalSystem(dto.getExternalSystem());
        entity.setExternalCode(dto.getExternalCode());
        entity.setExternalName(dto.getExternalName());
        entity.setAccountingArea(dto.getAccountingArea());
        entity.setChartOfAccountsCode(dto.getChartOfAccountsCode());
        entity.setAccountCode(dto.getAccountCode());
        entity.setSubAccountCode(dto.getSubAccountCode());
        entity.setCostCenter(dto.getCostCenter());
        entity.setProfitCenter(dto.getProfitCenter());
        entity.setWarehouseAccountingCode(dto.getWarehouseAccountingCode());
        entity.setTaxCode(dto.getTaxCode());
        entity.setCurrencyCode(dto.getCurrencyCode());
        entity.setPriority(dto.getPriority());
        entity.setDefaultMapping(dto.isDefaultMapping());
        entity.setValidFrom(dto.getValidFrom());
        entity.setValidTo(dto.getValidTo());
        return entity;
    }

    public void updateEntity(ReferenceMapping entity, ReferenceMappingUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setReferenceEntityType(dto.getReferenceEntityType());
        entity.setReferenceId(dto.getReferenceId());
        entity.setReferenceCode(dto.getReferenceCode());
        entity.setReferenceName(dto.getReferenceName());
        entity.setExternalSystem(dto.getExternalSystem());
        entity.setExternalCode(dto.getExternalCode());
        entity.setExternalName(dto.getExternalName());
        entity.setAccountingArea(dto.getAccountingArea());
        entity.setChartOfAccountsCode(dto.getChartOfAccountsCode());
        entity.setAccountCode(dto.getAccountCode());
        entity.setSubAccountCode(dto.getSubAccountCode());
        entity.setCostCenter(dto.getCostCenter());
        entity.setProfitCenter(dto.getProfitCenter());
        entity.setWarehouseAccountingCode(dto.getWarehouseAccountingCode());
        entity.setTaxCode(dto.getTaxCode());
        entity.setCurrencyCode(dto.getCurrencyCode());
        entity.setPriority(dto.getPriority());
        entity.setDefaultMapping(dto.isDefaultMapping());
        entity.setValidFrom(dto.getValidFrom());
        entity.setValidTo(dto.getValidTo());
    }
}
