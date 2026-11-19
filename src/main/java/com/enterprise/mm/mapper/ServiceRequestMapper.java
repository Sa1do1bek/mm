package com.enterprise.mm.mapper;

import com.enterprise.mm.entity.ServiceRequest;
import com.enterprise.mm.payload.ServiceRequestCreateRequest;
import com.enterprise.mm.payload.ServiceRequestResponse;
import com.enterprise.mm.payload.ServiceRequestUpdateRequest;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
@lombok.RequiredArgsConstructor
public class ServiceRequestMapper {
    private final com.enterprise.mm.service.UnitOfMeasureDisplayService unitDisplay;
    public ServiceRequestResponse toResponse(ServiceRequest entity) {
        if (entity == null) {
            return null;
        }
        ServiceRequestResponse dto = new ServiceRequestResponse();
        BaseMapper.copyBase(entity, dto);
        dto.setNumber(entity.getNumber());
        dto.setTitle(entity.getTitle());
        dto.setCategory(entity.getCategory());
        dto.setUrgency(entity.getUrgency());
        dto.setDepartmentId(entity.getDepartmentId());
        dto.setDepartmentCode(BaseMapper.code(entity.getDepartment()));
        dto.setDepartmentName(BaseMapper.name(entity.getDepartment()));
        dto.setResponsibleUserId(entity.getResponsibleUserId());
        dto.setResponsibleUserCode(BaseMapper.userCode(entity.getResponsibleUser()));
        dto.setResponsibleUserName(BaseMapper.userName(entity.getResponsibleUser()));
        dto.setSupplierId(entity.getSupplierId());
        dto.setSupplierCode(BaseMapper.supplierCode(entity.getSupplier()));
        dto.setSupplierName(BaseMapper.supplierName(entity.getSupplier()));
        dto.setContractId(entity.getContractId());
        dto.setContractCode(BaseMapper.code(entity.getContract()));
        dto.setContractName(BaseMapper.name(entity.getContract()));
        dto.setPurchaseOrderId(entity.getPurchaseOrderId());
        dto.setPurchaseOrderCode(BaseMapper.code(entity.getPurchaseOrder()));
        dto.setPurchaseOrderName(BaseMapper.name(entity.getPurchaseOrder()));
        dto.setBudgetItemId(entity.getBudgetItemId());
        dto.setBudgetItemCode(BaseMapper.code(entity.getBudgetItem()));
        dto.setBudgetItemName(BaseMapper.name(entity.getBudgetItem()));
        dto.setCostCenterId(entity.getCostCenterId());
        dto.setEstimatedAmount(entity.getEstimatedAmount());
        dto.setApprovedAmount(entity.getApprovedAmount());
        dto.setActualAmount(entity.getActualAmount());
        dto.setAcceptanceMode(entity.getAcceptanceMode());
        dto.setOrderedAmount(entity.getOrderedAmount());
        dto.setAcceptedAmount(entity.getAcceptedAmount());
        dto.setRejectedAmount(entity.getRejectedAmount());
        dto.setRemainingAmount(remaining(entity.getOrderedAmount(), entity.getAcceptedAmount(), entity.getRejectedAmount()));
        dto.setCurrencyCode(entity.getCurrencyCode());
        dto.setOrderedQuantity(entity.getOrderedQuantity());
        dto.setAcceptedQuantity(entity.getAcceptedQuantity());
        dto.setRejectedQuantity(entity.getRejectedQuantity());
        dto.setRemainingQuantity(remaining(entity.getOrderedQuantity(), entity.getAcceptedQuantity(), entity.getRejectedQuantity()));
        dto.setUomCode(entity.getUomCode());
        dto.setUomShortName(unitDisplay.shortName(entity, null, entity.getUomCode()));
        dto.setAcceptedVolume(entity.getAcceptedVolume());
        dto.setRejectedVolume(entity.getRejectedVolume());
        dto.setAcceptanceRemarks(entity.getAcceptanceRemarks());
        dto.setBudgetReservedAmount(entity.getBudgetReservedAmount());
        dto.setBudgetReservedAt(entity.getBudgetReservedAt());
        dto.setBudgetReleasedAt(entity.getBudgetReleasedAt());
        dto.setBudgetConsumedAt(entity.getBudgetConsumedAt());
        dto.setPlannedStartDate(entity.getPlannedStartDate());
        dto.setPlannedEndDate(entity.getPlannedEndDate());
        return dto;
    }

    public ServiceRequest toEntity(ServiceRequestCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        ServiceRequest entity = new ServiceRequest();
        update(entity, dto);
        return entity;
    }

    public void updateEntity(ServiceRequest entity, ServiceRequestUpdateRequest dto) {
        update(entity, dto);
    }

    private void update(ServiceRequest entity, ServiceRequestCreateRequest dto) {
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setNumber(dto.getNumber());
        entity.setTitle(dto.getTitle());
        entity.setCategory(dto.getCategory());
        entity.setUrgency(dto.getUrgency());
        entity.setDepartmentId(dto.getDepartmentId());
        entity.setResponsibleUserId(dto.getResponsibleUserId());
        entity.setSupplierId(dto.getSupplierId());
        entity.setContractId(dto.getContractId());
        entity.setPurchaseOrderId(dto.getPurchaseOrderId());
        entity.setBudgetItemId(dto.getBudgetItemId());
        entity.setCostCenterId(dto.getCostCenterId());
        entity.setEstimatedAmount(dto.getEstimatedAmount());
        entity.setApprovedAmount(dto.getApprovedAmount());
        entity.setActualAmount(dto.getActualAmount());
        entity.setAcceptanceMode(dto.getAcceptanceMode());
        entity.setOrderedAmount(dto.getOrderedAmount());
        entity.setCurrencyCode(dto.getCurrencyCode());
        entity.setOrderedQuantity(dto.getOrderedQuantity());
        entity.setUomCode(dto.getUomCode());
        entity.setPlannedStartDate(dto.getPlannedStartDate());
        entity.setPlannedEndDate(dto.getPlannedEndDate());
    }

    private BigDecimal remaining(BigDecimal ordered, BigDecimal accepted, BigDecimal rejected) {
        if (ordered == null) {
            return null;
        }
        BigDecimal used = (accepted == null ? BigDecimal.ZERO : accepted)
            .add(rejected == null ? BigDecimal.ZERO : rejected);
        return ordered.subtract(used).max(BigDecimal.ZERO);
    }
}
