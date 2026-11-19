package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.ClassifierResponse;
import com.enterprise.mm.payload.ClassifierUpdateRequest;
import com.enterprise.mm.payload.ClassifierCreateRequest;
import com.enterprise.mm.entity.Classifier;
import org.springframework.stereotype.Component;

@Component
public class ClassifierMapper {
    public ClassifierResponse toResponse(Classifier entity) {
        if (entity == null) {
            return null;
        }
        ClassifierResponse dto = new ClassifierResponse();
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
        dto.setClassifierType(entity.getClassifierType());
        return dto;
    }

    public Classifier toEntity(ClassifierCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        Classifier entity = new Classifier();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setClassifierType(dto.getClassifierType());
        return entity;
    }

    public void updateEntity(Classifier entity, ClassifierUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setClassifierType(dto.getClassifierType());
    }
}