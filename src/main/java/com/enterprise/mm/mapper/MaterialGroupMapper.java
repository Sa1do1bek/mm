package com.enterprise.mm.mapper;

import com.enterprise.mm.enums.MaterialGroupStatus;
import com.enterprise.mm.payload.MaterialGroupResponse;
import com.enterprise.mm.payload.MaterialGroupUpdateRequest;
import com.enterprise.mm.payload.MaterialGroupCreateRequest;
import com.enterprise.mm.entity.MaterialGroup;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class MaterialGroupMapper {
    public MaterialGroupResponse toResponse(MaterialGroup entity) {
        if (entity == null) {
            return null;
        }
        MaterialGroupResponse dto = new MaterialGroupResponse();
        dto.setId(entity.getId());
        dto.setParentId(entity.getParentId());
        dto.setAdditionalCategory(entity.getAdditionalCategory());
        dto.setLowerCategory(entity.getLowerCategory());
        dto.setTenantId(entity.getTenantId());
        dto.setCompanyId(entity.getCompanyId());
        dto.setCode(entity.getCode());
        dto.setName(entity.getName());
        dto.setStatus(entity.getStatus());
        dto.setMaterialGroupStatus(parseEnum(MaterialGroupStatus.class, entity.getStatus()));
        dto.setDescription(entity.getDescription());
        dto.setDeleted(entity.isDeleted());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        dto.setCreatedBy(entity.getCreatedBy());
        dto.setUpdatedBy(entity.getUpdatedBy());
        dto.setVersion(entity.getVersion());
        return dto;
    }

    public MaterialGroup toEntity(MaterialGroupCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        MaterialGroup entity = new MaterialGroup();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(enumName(dto.getStatus()));
        entity.setDescription(dto.getDescription());
        entity.setAdditionalCategory(dto.getAdditionalCategory());
        entity.setLowerCategory(dto.getLowerCategory());
        return entity;
    }

    public void updateEntity(MaterialGroup entity, MaterialGroupUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(enumName(dto.getStatus()));
        entity.setDescription(dto.getDescription());
        entity.setAdditionalCategory(dto.getAdditionalCategory());
        entity.setLowerCategory(dto.getLowerCategory());
    }

    private String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private <T extends Enum<T>> T parseEnum(Class<T> type, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
