package com.enterprise.mm.mapper;

import com.enterprise.mm.entity.WorkCompletionAct;
import com.enterprise.mm.enums.WorkInspectionResult;
import com.enterprise.mm.payload.WorkCompletionActCreateRequest;
import com.enterprise.mm.payload.WorkCompletionActResponse;
import com.enterprise.mm.payload.WorkCompletionActUpdateRequest;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class WorkCompletionActMapper {
    public WorkCompletionActResponse toResponse(WorkCompletionAct entity) {
        if (entity == null) {
            return null;
        }
        WorkCompletionActResponse dto = new WorkCompletionActResponse();
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
        dto.setPurchaseOrderId(entity.getPurchaseOrderId());
        dto.setPurchaseOrderCode(BaseMapper.code(entity.getPurchaseOrder()));
        dto.setPurchaseOrderName(BaseMapper.name(entity.getPurchaseOrder()));
        dto.setPurchaseOrderItemId(entity.getPurchaseOrderItemId());
        dto.setPurchaseOrderItemCode(BaseMapper.code(entity.getPurchaseOrderItem()));
        dto.setPurchaseOrderItemName(BaseMapper.name(entity.getPurchaseOrderItem()));
        dto.setContractId(entity.getContractId());
        dto.setContractCode(BaseMapper.code(entity.getContract()));
        dto.setContractName(BaseMapper.name(entity.getContract()));
        dto.setWorkLocation(entity.getWorkLocation());
        dto.setMilestone(entity.getMilestone());
        dto.setPeriodStart(entity.getPeriodStart());
        dto.setPeriodEnd(entity.getPeriodEnd());
        dto.setCompletedQuantity(entity.getCompletedQuantity());
        dto.setAmount(entity.getAmount());
        dto.setInspectionResult(entity.getInspectionResult() == null ? null : entity.getInspectionResult().name());
        dto.setAcceptedAt(entity.getAcceptedAt());
        dto.setAcceptedBy(entity.getAcceptedBy());
        dto.setCommissionNotes(entity.getCommissionNotes());
        dto.setRemarks(entity.getRemarks());
        dto.setDocumentReviewedAt(entity.getDocumentReviewedAt());
        dto.setFinanceReviewedAt(entity.getFinanceReviewedAt());
        dto.setSentToPaymentAt(entity.getSentToPaymentAt());
        dto.setPaidAt(entity.getPaidAt());
        dto.setContractConsumedAt(entity.getContractConsumedAt());
        dto.setPaymentReference(entity.getPaymentReference());
        return dto;
    }

    public WorkCompletionAct toEntity(WorkCompletionActCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        WorkCompletionAct entity = new WorkCompletionAct();
        copy(entity, dto);
        return entity;
    }

    public void updateEntity(WorkCompletionAct entity, WorkCompletionActUpdateRequest dto) {
        copy(entity, dto);
    }

    private void copy(WorkCompletionAct entity, WorkCompletionActCreateRequest dto) {
        if (dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setPurchaseOrderId(dto.getPurchaseOrderId());
        entity.setPurchaseOrderItemId(dto.getPurchaseOrderItemId());
        entity.setContractId(dto.getContractId());
        entity.setWorkLocation(dto.getWorkLocation());
        entity.setMilestone(dto.getMilestone());
        entity.setPeriodStart(dto.getPeriodStart());
        entity.setPeriodEnd(dto.getPeriodEnd());
        entity.setCompletedQuantity(dto.getCompletedQuantity());
        entity.setAmount(dto.getAmount());
        entity.setInspectionResult(parseInspectionResult(dto.getInspectionResult()));
        entity.setCommissionNotes(dto.getCommissionNotes());
        entity.setRemarks(dto.getRemarks());
    }

    private WorkInspectionResult parseInspectionResult(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return WorkInspectionResult.valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
    }
}
