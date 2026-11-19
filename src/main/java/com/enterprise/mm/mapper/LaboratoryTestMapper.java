package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.LaboratoryTestResponse;
import com.enterprise.mm.payload.LaboratoryTestUpdateRequest;
import com.enterprise.mm.payload.LaboratoryTestCreateRequest;
import com.enterprise.mm.entity.LaboratoryTest;
import org.springframework.stereotype.Component;

@Component
public class LaboratoryTestMapper {
    public LaboratoryTestResponse toResponse(LaboratoryTest entity) {
        if (entity == null) {
            return null;
        }
        LaboratoryTestResponse dto = new LaboratoryTestResponse();
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
        dto.setQualityInspectionId(entity.getQualityInspectionId());
        dto.setQualityInspectionCode(BaseMapper.code(entity.getQualityInspection()));
        dto.setQualityInspectionName(BaseMapper.name(entity.getQualityInspection()));
        dto.setMaterialId(entity.getMaterialId());
        dto.setMaterialCode(BaseMapper.materialCode(entity.getMaterial()));
        dto.setMaterialName(BaseMapper.materialName(entity.getMaterial()));
        dto.setSampleNumber(entity.getSampleNumber());
        dto.setTestType(entity.getTestType());
        dto.setTestMethod(entity.getTestMethod());
        dto.setSpecification(entity.getSpecification());
        dto.setMeasuredValue(entity.getMeasuredValue());
        dto.setResult(entity.getResult());
        dto.setPassed(entity.isPassed());
        dto.setRequestedAt(entity.getRequestedAt());
        dto.setStartedAt(entity.getStartedAt());
        dto.setCompletedAt(entity.getCompletedAt());
        dto.setLabTechnicianEmployeeId(entity.getLabTechnicianEmployeeId());
        dto.setProtocolAttachmentId(entity.getProtocolAttachmentId());
        dto.setLimsSampleId(entity.getLimsSampleId());
        dto.setLimsStatus(entity.getLimsStatus());
        dto.setLimsRequestedAt(entity.getLimsRequestedAt());
        dto.setLimsResultReceivedAt(entity.getLimsResultReceivedAt());
        return dto;
    }

    public LaboratoryTest toEntity(LaboratoryTestCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        LaboratoryTest entity = new LaboratoryTest();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setQualityInspectionId(dto.getQualityInspectionId());
        entity.setMaterialId(dto.getMaterialId());
        entity.setSampleNumber(dto.getSampleNumber());
        entity.setTestType(dto.getTestType());
        entity.setTestMethod(dto.getTestMethod());
        entity.setSpecification(dto.getSpecification());
        entity.setMeasuredValue(dto.getMeasuredValue());
        entity.setResult(dto.getResult());
        entity.setPassed(dto.isPassed());
        entity.setRequestedAt(dto.getRequestedAt());
        entity.setStartedAt(dto.getStartedAt());
        entity.setCompletedAt(dto.getCompletedAt());
        entity.setLabTechnicianEmployeeId(dto.getLabTechnicianEmployeeId());
        entity.setProtocolAttachmentId(dto.getProtocolAttachmentId());
        entity.setLimsSampleId(dto.getLimsSampleId());
        entity.setLimsStatus(dto.getLimsStatus());
        entity.setLimsRequestedAt(dto.getLimsRequestedAt());
        entity.setLimsResultReceivedAt(dto.getLimsResultReceivedAt());
        return entity;
    }

    public void updateEntity(LaboratoryTest entity, LaboratoryTestUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setQualityInspectionId(dto.getQualityInspectionId());
        entity.setMaterialId(dto.getMaterialId());
        entity.setSampleNumber(dto.getSampleNumber());
        entity.setTestType(dto.getTestType());
        entity.setTestMethod(dto.getTestMethod());
        entity.setSpecification(dto.getSpecification());
        entity.setMeasuredValue(dto.getMeasuredValue());
        entity.setResult(dto.getResult());
        entity.setPassed(dto.isPassed());
        entity.setRequestedAt(dto.getRequestedAt());
        entity.setStartedAt(dto.getStartedAt());
        entity.setCompletedAt(dto.getCompletedAt());
        entity.setLabTechnicianEmployeeId(dto.getLabTechnicianEmployeeId());
        entity.setProtocolAttachmentId(dto.getProtocolAttachmentId());
        entity.setLimsSampleId(dto.getLimsSampleId());
        entity.setLimsStatus(dto.getLimsStatus());
        entity.setLimsRequestedAt(dto.getLimsRequestedAt());
        entity.setLimsResultReceivedAt(dto.getLimsResultReceivedAt());
    }
}