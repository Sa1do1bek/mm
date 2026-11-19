package com.enterprise.mm.mapper;

import com.enterprise.mm.entity.ServiceAcceptanceAct;
import com.enterprise.mm.payload.ServiceAcceptanceActCreateRequest;
import com.enterprise.mm.payload.ServiceAcceptanceActResponse;
import com.enterprise.mm.payload.ServiceAcceptanceActUpdateRequest;
import org.springframework.stereotype.Component;

@Component
public class ServiceAcceptanceActMapper {
    public ServiceAcceptanceActResponse toResponse(ServiceAcceptanceAct entity) {
        if (entity == null) {
            return null;
        }
        ServiceAcceptanceActResponse dto = new ServiceAcceptanceActResponse();
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
        dto.setServiceOrderId(entity.getServiceOrderId());
        dto.setServiceOrderCode(BaseMapper.code(entity.getServiceOrder()));
        dto.setServiceOrderName(BaseMapper.name(entity.getServiceOrder()));
        dto.setPurchaseOrderId(entity.getPurchaseOrderId());
        dto.setPurchaseOrderCode(BaseMapper.code(entity.getPurchaseOrder()));
        dto.setPurchaseOrderName(BaseMapper.name(entity.getPurchaseOrder()));
        dto.setContractId(entity.getContractId());
        dto.setContractCode(BaseMapper.code(entity.getContract()));
        dto.setContractName(BaseMapper.name(entity.getContract()));
        dto.setAcceptedAt(entity.getAcceptedAt());
        dto.setAcceptedBy(entity.getAcceptedBy());
        dto.setAmount(entity.getAmount());
        dto.setVatAmount(entity.getVatAmount());
        dto.setVatRate(entity.getVatRate());
        dto.setPeriodStart(entity.getPeriodStart());
        dto.setPeriodEnd(entity.getPeriodEnd());
        dto.setStage(entity.getStage());
        dto.setDocumentReviewedAt(entity.getDocumentReviewedAt());
        dto.setFinanceReviewedAt(entity.getFinanceReviewedAt());
        dto.setSentToPaymentAt(entity.getSentToPaymentAt());
        dto.setPaidAt(entity.getPaidAt());
        dto.setBudgetConsumedAt(entity.getBudgetConsumedAt());
        dto.setContractConsumedAt(entity.getContractConsumedAt());
        dto.setPaymentReference(entity.getPaymentReference());
        dto.setCommissionNotes(entity.getCommissionNotes());
        dto.setRemarks(entity.getRemarks());
        return dto;
    }

    public ServiceAcceptanceAct toEntity(ServiceAcceptanceActCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        ServiceAcceptanceAct entity = new ServiceAcceptanceAct();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setServiceRequestId(dto.getServiceRequestId());
        entity.setServiceOrderId(dto.getServiceOrderId());
        entity.setPurchaseOrderId(dto.getPurchaseOrderId());
        entity.setContractId(dto.getContractId());
        entity.setAcceptedAt(dto.getAcceptedAt());
        entity.setAcceptedBy(dto.getAcceptedBy());
        entity.setAmount(dto.getAmount());
        entity.setVatAmount(dto.getVatAmount());
        entity.setVatRate(dto.getVatRate());
        entity.setPeriodStart(dto.getPeriodStart());
        entity.setPeriodEnd(dto.getPeriodEnd());
        entity.setStage(dto.getStage());
        entity.setCommissionNotes(dto.getCommissionNotes());
        entity.setRemarks(dto.getRemarks());
        entity.setPaymentReference(dto.getPaymentReference());
        return entity;
    }

    public void updateEntity(ServiceAcceptanceAct entity, ServiceAcceptanceActUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setServiceRequestId(dto.getServiceRequestId());
        entity.setServiceOrderId(dto.getServiceOrderId());
        entity.setPurchaseOrderId(dto.getPurchaseOrderId());
        entity.setContractId(dto.getContractId());
        entity.setAcceptedAt(dto.getAcceptedAt());
        entity.setAcceptedBy(dto.getAcceptedBy());
        entity.setAmount(dto.getAmount());
        entity.setVatAmount(dto.getVatAmount());
        entity.setVatRate(dto.getVatRate());
        entity.setPeriodStart(dto.getPeriodStart());
        entity.setPeriodEnd(dto.getPeriodEnd());
        entity.setStage(dto.getStage());
        entity.setCommissionNotes(dto.getCommissionNotes());
        entity.setRemarks(dto.getRemarks());
        entity.setPaymentReference(dto.getPaymentReference());
    }
}
