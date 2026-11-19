package com.enterprise.mm.mapper;

import com.enterprise.mm.common.DataQualityGuards;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.payload.PurchaseOrderResponse;
import com.enterprise.mm.payload.PurchaseOrderUpdateRequest;
import com.enterprise.mm.payload.PurchaseOrderCreateRequest;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class PurchaseOrderMapper {
    public PurchaseOrderResponse toResponse(PurchaseOrder entity) {
        if (entity == null) {
            return null;
        }
        PurchaseOrderResponse dto = new PurchaseOrderResponse();
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
        dto.setPurchaseRequestId(entity.getPurchaseRequestId());
        dto.setPurchaseRequestCode(BaseMapper.code(entity.getPurchaseRequest()));
        dto.setPurchaseRequestName(BaseMapper.name(entity.getPurchaseRequest()));
        dto.setContractId(entity.getContractId());
        dto.setContractCode(BaseMapper.code(entity.getContract()));
        dto.setContractName(BaseMapper.name(entity.getContract()));
        dto.setOrderDate(entity.getOrderDate());
        dto.setExpectedDeliveryDate(entity.getExpectedDeliveryDate());
        dto.setRequestType(entity.getRequestType() == null ? null : entity.getRequestType().name());
        dto.setTotalAmount(DataQualityGuards.roundMoney(entity.getTotalAmount(), entity.getCurrencyCode()));
        dto.setCurrencyCode(entity.getCurrencyCode());
        dto.setExchangeRate(entity.getExchangeRate());
        if (dto.getSupplierId() == null && entity.getSupplier() != null) {
            dto.setSupplierId(entity.getSupplier().getId());
        }
        if (dto.getPurchaseRequestId() == null && entity.getPurchaseRequest() != null) {
            dto.setPurchaseRequestId(entity.getPurchaseRequest().getId());
        }
        if (dto.getContractId() == null && entity.getContract() != null) {
            dto.setContractId(entity.getContract().getId());
        }
        return dto;
    }

    public PurchaseOrder toEntity(PurchaseOrderCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        PurchaseOrder entity = new PurchaseOrder();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setSupplierId(dto.getSupplierId());
        entity.setPurchaseRequestId(dto.getPurchaseRequestId());
        entity.setContractId(dto.getContractId());
        entity.setOrderDate(DataQualityGuards.requireSaneDate(dto.getOrderDate(), "orderDate"));
        entity.setExpectedDeliveryDate(DataQualityGuards.requireSaneDate(dto.getExpectedDeliveryDate(), "expectedDeliveryDate"));
        DataQualityGuards.requireDateOrder(
            entity.getOrderDate(), entity.getExpectedDeliveryDate(), "orderDate", "expectedDeliveryDate");
        entity.setRequestType(parseRequestType(dto.getRequestType()));
        entity.setTotalAmount(DataQualityGuards.roundMoney(dto.getTotalAmount(), dto.getCurrencyCode()));
        entity.setCurrencyCode(dto.getCurrencyCode());
        entity.setExchangeRate(dto.getExchangeRate());
        return entity;
    }

    public void updateEntity(PurchaseOrder entity, PurchaseOrderUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setSupplierId(dto.getSupplierId());
        entity.setPurchaseRequestId(dto.getPurchaseRequestId());
        entity.setContractId(dto.getContractId());
        entity.setOrderDate(DataQualityGuards.requireSaneDate(dto.getOrderDate(), "orderDate"));
        entity.setExpectedDeliveryDate(DataQualityGuards.requireSaneDate(dto.getExpectedDeliveryDate(), "expectedDeliveryDate"));
        DataQualityGuards.requireDateOrder(
            entity.getOrderDate(), entity.getExpectedDeliveryDate(), "orderDate", "expectedDeliveryDate");
        entity.setRequestType(parseRequestType(dto.getRequestType()));
        entity.setTotalAmount(DataQualityGuards.roundMoney(dto.getTotalAmount(), dto.getCurrencyCode()));
        entity.setCurrencyCode(dto.getCurrencyCode());
        entity.setExchangeRate(dto.getExchangeRate());
    }

    private RequestType parseRequestType(String requestType) {
        if (requestType == null || requestType.isBlank()) {
            return null;
        }
        return RequestType.valueOf(requestType.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
    }
}
