package com.enterprise.mm.mapper;

import com.enterprise.mm.entity.User;
import com.enterprise.mm.enums.RoleName;
import com.enterprise.mm.payload.UserCreateRequest;
import com.enterprise.mm.payload.UserResponse;
import com.enterprise.mm.payload.UserUpdateRequest;
import java.util.LinkedHashSet;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class UserMapper {
    public UserResponse toResponse(User entity) {
        if (entity == null) {
            return null;
        }
        UserResponse dto = new UserResponse();
        dto.setId(entity.getId());
        dto.setTenantId(entity.getTenantId());
        dto.setCompanyId(entity.getCompanyId());
        dto.setCompanyCode(BaseMapper.code(entity.getCompany()));
        dto.setCompanyName(BaseMapper.name(entity.getCompany()));
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
        dto.setEmployeeId(entity.getEmployee() != null ? entity.getEmployee().getId() : null);
        dto.setUsername(entity.getUsername());
        dto.setDepartmentId(entity.getDepartment() != null ? entity.getDepartment().getId() : null);
        dto.setFullName(entity.getFullName());
        dto.setEmail(entity.getEmail());
        dto.setEnabled(entity.isEnabled());
        dto.setPassword(null);
        dto.setRoles(safeCollection(entity.getRoles()).stream()
            .map(RoleName::name)
            .collect(Collectors.toCollection(LinkedHashSet::new)));
        dto.setSuperAdmin(isSuperAdmin(entity));
        return dto;
    }

    public User toEntity(UserCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        User entity = new User();
        entity.setCompanyId(dto.getCompanyId());
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setUsername(dto.getUsername());
        entity.setFullName(dto.getFullName());
        entity.setEmail(dto.getEmail());
        entity.setEnabled(dto.isEnabled());
        return entity;
    }

    public void updateEntity(User entity, UserUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCompanyId(dto.getCompanyId());
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setUsername(dto.getUsername());
        entity.setFullName(dto.getFullName());
        entity.setEmail(dto.getEmail());
        entity.setEnabled(dto.isEnabled());
    }

    private boolean isSuperAdmin(User user) {
        return user.getCompanyId() == null && safeCollection(user.getRoles()).contains(RoleName.ADMIN);
    }

    private <T> java.util.Collection<T> safeCollection(java.util.Collection<T> values) {
        return values == null ? java.util.List.of() : values;
    }
}
