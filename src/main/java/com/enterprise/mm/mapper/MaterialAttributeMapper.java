package com.enterprise.mm.mapper;

import com.enterprise.mm.entity.MaterialAttribute;
import com.enterprise.mm.payload.MaterialAttributeResponse;
import org.springframework.stereotype.Component;

@Component
public class MaterialAttributeMapper {
    public MaterialAttributeResponse toResponse(MaterialAttribute entity) {
        if (entity == null) {
            return null;
        }
        return new MaterialAttributeResponse(
            entity.getId(),
            entity.getKey(),
            entity.getValue(),
            entity.getSortOrder()
        );
    }
}
