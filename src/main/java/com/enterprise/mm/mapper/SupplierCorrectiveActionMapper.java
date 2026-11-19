package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.SupplierCorrectiveActionResponse;
import com.enterprise.mm.payload.SupplierCorrectiveActionUpdateRequest;
import com.enterprise.mm.payload.SupplierCorrectiveActionCreateRequest;
import com.enterprise.mm.entity.SupplierCorrectiveAction;
import org.springframework.stereotype.Component;

@Component
public class SupplierCorrectiveActionMapper {
    public SupplierCorrectiveActionResponse toResponse(SupplierCorrectiveAction entity) {
        if (entity == null) {
            return null;
        }
        SupplierCorrectiveActionResponse dto = new SupplierCorrectiveActionResponse();
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
        dto.setSourceType(entity.getSourceType());
        dto.setSourceId(entity.getSourceId());
        dto.setIssueTitle(entity.getIssueTitle());
        dto.setIssueDescription(entity.getIssueDescription());
        dto.setRootCause(entity.getRootCause());
        dto.setCorrectiveAction(entity.getCorrectiveAction());
        dto.setPreventiveAction(entity.getPreventiveAction());
        dto.setOwnerEmployeeId(entity.getOwnerEmployeeId());
        dto.setOwnerEmployeeCode(BaseMapper.employeeCode(entity.getOwnerEmployee()));
        dto.setOwnerEmployeeName(BaseMapper.employeeName(entity.getOwnerEmployee()));
        dto.setSupplierResponsibleName(entity.getSupplierResponsibleName());
        dto.setDueDate(entity.getDueDate());
        dto.setSubmittedAt(entity.getSubmittedAt());
        dto.setVerifiedAt(entity.getVerifiedAt());
        dto.setClosedAt(entity.getClosedAt());
        dto.setSeverity(entity.getSeverity());
        dto.setVerificationResult(entity.getVerificationResult());
        dto.setRecurrencePrevented(entity.isRecurrencePrevented());
        return dto;
    }

    public SupplierCorrectiveAction toEntity(SupplierCorrectiveActionCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        SupplierCorrectiveAction entity = new SupplierCorrectiveAction();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setSupplierId(dto.getSupplierId());
        entity.setSourceType(dto.getSourceType());
        entity.setSourceId(dto.getSourceId());
        entity.setIssueTitle(dto.getIssueTitle());
        entity.setIssueDescription(dto.getIssueDescription());
        entity.setRootCause(dto.getRootCause());
        entity.setCorrectiveAction(dto.getCorrectiveAction());
        entity.setPreventiveAction(dto.getPreventiveAction());
        entity.setOwnerEmployeeId(dto.getOwnerEmployeeId());
        entity.setSupplierResponsibleName(dto.getSupplierResponsibleName());
        entity.setDueDate(dto.getDueDate());
        entity.setSubmittedAt(dto.getSubmittedAt());
        entity.setVerifiedAt(dto.getVerifiedAt());
        entity.setClosedAt(dto.getClosedAt());
        entity.setSeverity(dto.getSeverity());
        entity.setVerificationResult(dto.getVerificationResult());
        entity.setRecurrencePrevented(dto.isRecurrencePrevented());
        return entity;
    }

    public void updateEntity(SupplierCorrectiveAction entity, SupplierCorrectiveActionUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setIssueTitle(dto.getIssueTitle());
        entity.setIssueDescription(dto.getIssueDescription());
        entity.setRootCause(dto.getRootCause());
        entity.setCorrectiveAction(dto.getCorrectiveAction());
        entity.setPreventiveAction(dto.getPreventiveAction());
        entity.setOwnerEmployeeId(dto.getOwnerEmployeeId());
        entity.setSupplierResponsibleName(dto.getSupplierResponsibleName());
        entity.setDueDate(dto.getDueDate());
        entity.setSeverity(dto.getSeverity());
        // Supplier/source provenance, lifecycle timestamps and verification metadata are command-owned.
    }
}
