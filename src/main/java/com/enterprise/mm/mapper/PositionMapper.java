package com.enterprise.mm.mapper;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.entity.Department;
import com.enterprise.mm.payload.PositionResponse;
import com.enterprise.mm.payload.PositionUpdateRequest;
import com.enterprise.mm.payload.PositionCreateRequest;
import com.enterprise.mm.entity.Position;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class PositionMapper {
    @PersistenceContext
    private EntityManager entityManager;

    public PositionResponse toResponse(Position entity) {
        if (entity == null) {
            return null;
        }
        PositionResponse dto = new PositionResponse();
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
        dto.setExternalId(entity.getExternalId());
        dto.setDepartmentId(idOf(entity.getDepartment()));
        dto.setPositionCategory(entity.getPositionCategory());
        dto.setGrade(entity.getGrade());
        dto.setHeadcount(entity.getHeadcount());
        dto.setOccupiedHeadcount(entity.getOccupiedHeadcount());
        dto.setAvailableHeadcount(availableHeadcount(entity));
        return dto;
    }

    public Position toEntity(PositionCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        Position entity = new Position();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setExternalId(dto.getExternalId());
        entity.setDepartment(reference(Department.class, dto.getDepartmentId()));
        entity.setPositionCategory(dto.getPositionCategory());
        entity.setGrade(dto.getGrade());
        entity.setHeadcount(dto.getHeadcount());
        return entity;
    }

    public void updateEntity(Position entity, PositionUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setExternalId(dto.getExternalId());
        entity.setDepartment(reference(Department.class, dto.getDepartmentId()));
        entity.setPositionCategory(dto.getPositionCategory());
        entity.setGrade(dto.getGrade());
        entity.setHeadcount(dto.getHeadcount());
    }

    private BigDecimal availableHeadcount(Position entity) {
        BigDecimal headcount = entity.getHeadcount() == null ? BigDecimal.ONE : entity.getHeadcount();
        BigDecimal occupied = entity.getOccupiedHeadcount() == null ? BigDecimal.ZERO : entity.getOccupiedHeadcount();
        BigDecimal available = headcount.subtract(occupied);
        return available.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : available;
    }

    private <T> T reference(Class<T> entityClass, UUID id) {
        return id == null ? null : entityManager.getReference(entityClass, id);
    }

    private UUID idOf(BusinessEntity entity) {
        return entity == null ? null : entity.getId();
    }
}
