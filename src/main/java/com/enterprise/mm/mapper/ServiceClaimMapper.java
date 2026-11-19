package com.enterprise.mm.mapper;

import com.enterprise.mm.entity.ServiceClaim;
import com.enterprise.mm.payload.ServiceClaimCreateRequest;
import com.enterprise.mm.payload.ServiceClaimResponse;
import com.enterprise.mm.payload.ServiceClaimUpdateRequest;
import org.springframework.stereotype.Component;

@Component
public class ServiceClaimMapper {
    public ServiceClaimResponse toResponse(ServiceClaim entity) {
        if (entity == null) {
            return null;
        }
        ServiceClaimResponse dto = new ServiceClaimResponse();
        BaseMapper.copyBase(entity, dto);
        dto.setOriginType(entity.getOriginType() == null ? null : entity.getOriginType().name());
        dto.setServiceRequestId(entity.getServiceRequestId());
        dto.setServiceRequestCode(BaseMapper.code(entity.getServiceRequest()));
        dto.setServiceRequestName(BaseMapper.name(entity.getServiceRequest()));
        dto.setServiceActId(entity.getServiceActId());
        dto.setServiceActCode(BaseMapper.code(entity.getServiceAct()));
        dto.setServiceActName(BaseMapper.name(entity.getServiceAct()));
        dto.setServiceOrderId(entity.getServiceOrderId());
        dto.setServiceOrderCode(BaseMapper.code(entity.getServiceOrder()));
        dto.setServiceOrderName(BaseMapper.name(entity.getServiceOrder()));
        dto.setPurchaseOrderItemId(entity.getPurchaseOrderItemId());
        dto.setPurchaseOrderItemCode(BaseMapper.code(entity.getPurchaseOrderItem()));
        dto.setPurchaseOrderItemName(BaseMapper.name(entity.getPurchaseOrderItem()));
        dto.setWorkCompletionActId(entity.getWorkCompletionActId());
        dto.setWorkCompletionActCode(BaseMapper.code(entity.getWorkCompletionAct()));
        dto.setWorkCompletionActName(BaseMapper.name(entity.getWorkCompletionAct()));
        dto.setSupplierId(entity.getSupplierId());
        dto.setSupplierCode(BaseMapper.supplierCode(entity.getSupplier()));
        dto.setSupplierName(BaseMapper.supplierName(entity.getSupplier()));
        dto.setClaimType(entity.getClaimType());
        dto.setSeverity(entity.getSeverity());
        dto.setAssignedTo(entity.getAssignedTo());
        dto.setDeadline(entity.getDeadline());
        dto.setPenaltyAmount(entity.getPenaltyAmount());
        dto.setCurrencyCode(entity.getCurrencyCode());
        dto.setSupplierResponse(entity.getSupplierResponse());
        dto.setSentAt(entity.getSentAt());
        dto.setResolvedAt(entity.getResolvedAt());
        dto.setClosedAt(entity.getClosedAt());
        return dto;
    }

    public ServiceClaim toEntity(ServiceClaimCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        ServiceClaim entity = new ServiceClaim();
        update(entity, dto);
        return entity;
    }

    public void updateEntity(ServiceClaim entity, ServiceClaimUpdateRequest dto) {
        updateMutable(entity, dto);
    }

    private void update(ServiceClaim entity, ServiceClaimCreateRequest dto) {
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setServiceRequestId(dto.getServiceRequestId());
        entity.setServiceActId(dto.getServiceActId());
        entity.setServiceOrderId(dto.getServiceOrderId());
        entity.setPurchaseOrderItemId(dto.getPurchaseOrderItemId());
        entity.setWorkCompletionActId(dto.getWorkCompletionActId());
        entity.setSupplierId(dto.getSupplierId());
        updateMutable(entity, dto);
    }

    private void updateMutable(ServiceClaim entity, ServiceClaimCreateRequest dto) {
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setClaimType(dto.getClaimType());
        entity.setSeverity(dto.getSeverity());
        entity.setAssignedTo(dto.getAssignedTo());
        entity.setDeadline(dto.getDeadline());
        entity.setPenaltyAmount(dto.getPenaltyAmount());
        entity.setCurrencyCode(dto.getCurrencyCode());
        entity.setSupplierResponse(dto.getSupplierResponse());
    }
}
