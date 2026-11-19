package com.enterprise.mm.mapper;

import com.enterprise.mm.common.DataQualityGuards;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.payload.PurchaseRequestCreateRequest;
import com.enterprise.mm.payload.PurchaseRequestResponse;
import com.enterprise.mm.payload.PurchaseRequestUpdateRequest;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class PurchaseRequestMapper {
    public PurchaseRequestResponse toResponse(PurchaseRequest entity) {
        if (entity == null) {
            return null;
        }
        PurchaseRequestResponse response = new PurchaseRequestResponse();
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
        response.setDepartmentId(entity.getDepartmentId());
        response.setDemandPlanId(entity.getDemandPlanId());
        response.setWarehouseId(entity.getWarehouseId());
        response.setRequestedBy(entity.getRequestedBy());
        response.setRequestType(entity.getRequestType() == null ? null : entity.getRequestType().name());
        response.setRequiredDate(entity.getRequiredDate());
        response.setTotalAmount(DataQualityGuards.roundMoney(entity.getTotalAmount(), entity.getCurrencyCode()));
        response.setCurrencyCode(entity.getCurrencyCode());
        return response;
    }

    public PurchaseRequest toEntity(PurchaseRequestCreateRequest request) {
        if (request == null) {
            return null;
        }
        PurchaseRequest entity = new PurchaseRequest();
        entity.setCode(request.getCode());
        entity.setName(request.getName());
        entity.setStatus(request.getStatus());
        entity.setDescription(request.getDescription());
        entity.setDepartmentId(request.getDepartmentId());
        entity.setDemandPlanId(request.getDemandPlanId());
        entity.setWarehouseId(request.getWarehouseId());
        entity.setRequestedBy(request.getRequestedBy());
        entity.setRequestType(parseRequestType(request.getRequestType()));
        entity.setRequiredDate(DataQualityGuards.requireSaneDate(request.getRequiredDate(), "requiredDate"));
        entity.setTotalAmount(DataQualityGuards.roundMoney(request.getTotalAmount(), request.getCurrencyCode()));
        entity.setCurrencyCode(request.getCurrencyCode());
        return entity;
    }

    public void updateEntity(PurchaseRequest entity, PurchaseRequestUpdateRequest request) {
        if (entity == null || request == null) {
            return;
        }
        entity.setCode(request.getCode());
        entity.setName(request.getName());
        entity.setDescription(request.getDescription());
        entity.setDepartmentId(request.getDepartmentId());
        entity.setDemandPlanId(request.getDemandPlanId());
        entity.setWarehouseId(request.getWarehouseId());
        entity.setRequestedBy(request.getRequestedBy());
        entity.setRequestType(parseRequestType(request.getRequestType()));
        entity.setRequiredDate(DataQualityGuards.requireSaneDate(request.getRequiredDate(), "requiredDate"));
        entity.setTotalAmount(DataQualityGuards.roundMoney(request.getTotalAmount(), request.getCurrencyCode()));
        entity.setCurrencyCode(request.getCurrencyCode());
    }

    private RequestType parseRequestType(String requestType) {
        if (requestType == null || requestType.isBlank()) {
            return null;
        }
        return RequestType.valueOf(requestType.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
    }
}
