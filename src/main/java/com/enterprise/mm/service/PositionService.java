package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import com.enterprise.mm.security.AccessScope;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.payload.PositionResponse;
import com.enterprise.mm.payload.PositionUpdateRequest;
import com.enterprise.mm.payload.PositionCreateRequest;
import com.enterprise.mm.payload.PositionSearchRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.PositionMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Position;
import com.enterprise.mm.repository.PositionRepository;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PositionService {
    private final PositionRepository repository;
    private final PositionMapper mapper;
    private final String entityName = "Position";
    private final AuditService auditService;
    private final RoleDataScopeService roleDataScopeService;
    protected void beforeCreate(Position entity, PositionCreateRequest dto) {
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus("ACTIVE");
        }
        if (entity.getCompanyId() == null) {
            entity.setCompanyId(CompanyContext.currentCompany());
        }
        if (entity.getCode() == null || entity.getCode().isBlank()) {
            entity.setCode(entityName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_") + "-" + UUID.randomUUID().toString().substring(0, 8));
        }
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));

        normalizeHeadcount(entity);
        roleDataScopeService.assertDepartmentDocumentMutationAllowed(idOf(entity.getDepartment()), "Position");
    }
    protected void beforeUpdate(Position entity, PositionUpdateRequest dto) {
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));

        normalizeHeadcount(entity);
        roleDataScopeService.assertDepartmentDocumentMutationAllowed(idOf(entity.getDepartment()), "Position");
    }
    private void normalizeHeadcount(Position entity) {
        if (entity.getHeadcount() == null) {
            entity.setHeadcount(BigDecimal.ONE);
        }
        if (entity.getOccupiedHeadcount() == null) {
            entity.setOccupiedHeadcount(BigDecimal.ZERO);
        }
        if (entity.getPositionCategory() == null || entity.getPositionCategory().isBlank()) {
            entity.setPositionCategory("STAFF");
        }
    }
    @Transactional(readOnly = true)
    public Page<PositionResponse> search(PositionSearchRequest request, Pageable pageable) {
        SearchCriteria filter = SearchCriteria.of(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        RoleDataScopeService.DataScope dataScope = roleDataScopeService.currentScope();
        var records = repository.search(
            TenantContext.currentTenant(),
            scoped ? CompanyContext.currentCompany() : null,
            filter.includeDeleted(),
            filter.normalizedSearch(),
            filter.fromDateTime(),
            filter.toDateTime(),
            filter.status(),
            !filter.hasStatus(),
            dataScope.hasDocumentScope() ? Pageable.unpaged() : pageable
        );
        if (!dataScope.hasDocumentScope()) {
            return records.map(mapper::toResponse);
        }
        return roleDataScopeService.filterAndMap(
            records.getContent(),
            pageable,
            entity -> roleDataScopeService.canAccessDocument(entity.getCreatedBy(), null, idOf(entity.getDepartment())),
            mapper::toResponse
        );
    }
    @Transactional(readOnly = true)
    public PositionResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public PositionResponse create(PositionCreateRequest dto) {
        Position entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        Position saved = repository.save(entity);
        auditService.logInCurrentTransaction(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public PositionResponse update(UUID id, PositionUpdateRequest dto) {
        Position entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        Position saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        Position entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public PositionResponse restore(UUID id) {
        Position entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (!roleDataScopeService.canAccessDocument(entity.getCreatedBy(), null, idOf(entity.getDepartment()))) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        Position saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected Position findEntity(UUID id) {
        Position entity = repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (entity.isDeleted()) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (!roleDataScopeService.canAccessDocument(entity.getCreatedBy(), null, idOf(entity.getDepartment()))) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        return entity;
    }
    protected boolean companyScoped() {
        return true;
    }

    private UUID idOf(com.enterprise.mm.common.BusinessEntity entity) {
        return entity == null ? null : entity.getId();
    }
}

