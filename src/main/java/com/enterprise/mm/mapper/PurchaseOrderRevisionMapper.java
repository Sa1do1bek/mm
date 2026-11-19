package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.PurchaseOrderRevisionDto;
import com.enterprise.mm.entity.PurchaseOrderRevision;
import org.springframework.stereotype.Component;

@Component
public class PurchaseOrderRevisionMapper {
    public PurchaseOrderRevisionDto toDto(PurchaseOrderRevision entity) {
        if (entity == null) {
            return null;
        }
        PurchaseOrderRevisionDto dto = new PurchaseOrderRevisionDto();
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
        dto.setRevisionNumber(entity.getRevisionNumber());
        dto.setReason(entity.getReason());
        dto.setDiffJson(entity.getDiffJson());
        dto.setSnapshotJson(entity.getSnapshotJson());
        dto.setRevisedAt(entity.getRevisedAt());
        return dto;
    }

    public PurchaseOrderRevision toEntity(PurchaseOrderRevisionDto dto) {
        if (dto == null) {
            return null;
        }
        PurchaseOrderRevision entity = new PurchaseOrderRevision();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setPurchaseOrderId(dto.getPurchaseOrderId());
        entity.setRevisionNumber(dto.getRevisionNumber());
        entity.setReason(dto.getReason());
        entity.setDiffJson(dto.getDiffJson());
        entity.setSnapshotJson(dto.getSnapshotJson());
        entity.setRevisedAt(dto.getRevisedAt());
        return entity;
    }

    public void updateEntity(PurchaseOrderRevision entity, PurchaseOrderRevisionDto dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setPurchaseOrderId(dto.getPurchaseOrderId());
        entity.setRevisionNumber(dto.getRevisionNumber());
        entity.setReason(dto.getReason());
        entity.setDiffJson(dto.getDiffJson());
        entity.setSnapshotJson(dto.getSnapshotJson());
        entity.setRevisedAt(dto.getRevisedAt());
    }
}