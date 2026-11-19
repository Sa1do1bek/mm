package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.ServiceOrderResponse;
import com.enterprise.mm.payload.ServiceOrderUpdateRequest;
import com.enterprise.mm.payload.ServiceOrderCreateRequest;
import com.enterprise.mm.entity.ServiceOrder;
import org.springframework.stereotype.Component;

@Component
public class ServiceOrderMapper {
    public ServiceOrderResponse toResponse(ServiceOrder entity) {
        if (entity == null) {
            return null;
        }
        ServiceOrderResponse dto = new ServiceOrderResponse();
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
        dto.setServiceRequestId(entity.getServiceRequestId());
        dto.setServiceRequestCode(BaseMapper.code(entity.getServiceRequest()));
        dto.setServiceRequestName(BaseMapper.name(entity.getServiceRequest()));
        dto.setSupplierId(entity.getSupplierId());
        dto.setSupplierCode(BaseMapper.supplierCode(entity.getSupplier()));
        dto.setSupplierName(BaseMapper.supplierName(entity.getSupplier()));
        dto.setContractId(entity.getContractId());
        dto.setContractCode(BaseMapper.code(entity.getContract()));
        dto.setContractName(BaseMapper.name(entity.getContract()));
        dto.setPurchaseOrderId(entity.getPurchaseOrderId());
        dto.setPurchaseOrderCode(BaseMapper.code(entity.getPurchaseOrder()));
        dto.setPurchaseOrderName(BaseMapper.name(entity.getPurchaseOrder()));
        dto.setServiceType(entity.getServiceType());
        dto.setTotalAmount(entity.getTotalAmount());
        dto.setCurrencyCode(entity.getCurrencyCode());
        return dto;
    }

    public ServiceOrder toEntity(ServiceOrderCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        ServiceOrder entity = new ServiceOrder();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setServiceRequestId(dto.getServiceRequestId());
        entity.setSupplierId(dto.getSupplierId());
        entity.setContractId(dto.getContractId());
        entity.setPurchaseOrderId(dto.getPurchaseOrderId());
        entity.setServiceType(dto.getServiceType());
        entity.setTotalAmount(dto.getTotalAmount());
        entity.setCurrencyCode(dto.getCurrencyCode());
        return entity;
    }

    public void updateEntity(ServiceOrder entity, ServiceOrderUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setServiceRequestId(dto.getServiceRequestId());
        entity.setSupplierId(dto.getSupplierId());
        entity.setContractId(dto.getContractId());
        entity.setPurchaseOrderId(dto.getPurchaseOrderId());
        entity.setServiceType(dto.getServiceType());
        entity.setTotalAmount(dto.getTotalAmount());
        entity.setCurrencyCode(dto.getCurrencyCode());
    }
}
