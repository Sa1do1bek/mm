package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.payload.EquipmentResponse;
import com.enterprise.mm.payload.EquipmentUpdateRequest;
import com.enterprise.mm.payload.EquipmentCreateRequest;
import com.enterprise.mm.payload.EquipmentSearchRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.mapper.EquipmentMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Equipment;
import com.enterprise.mm.entity.Manufacturer;
import com.enterprise.mm.repository.EquipmentRepository;
import com.enterprise.mm.repository.ManufacturerRepository;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EquipmentService {
    private final EquipmentRepository repository;
    private final EquipmentMapper mapper;
    private final String entityName = "Equipment";
    private final AuditService auditService;
    private final ManufacturerRepository manufacturerRepository;
    private final RoleDataScopeService roleDataScopeService;
    @Transactional(readOnly = true)
    public Page<EquipmentResponse> search(EquipmentSearchRequest request, Pageable pageable) {
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
            entity -> roleDataScopeService.canAccessDocument(entity.getCreatedBy(), null, entity.getDepartmentId()),
            mapper::toResponse
        );
    }
    @Transactional(readOnly = true)
    public EquipmentResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public EquipmentResponse create(EquipmentCreateRequest dto) {
        Equipment entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        Equipment saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public EquipmentResponse update(UUID id, EquipmentUpdateRequest dto) {
        Equipment entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        Equipment saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        Equipment entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public EquipmentResponse restore(UUID id) {
        Equipment entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (!roleDataScopeService.canAccessDocument(entity.getCreatedBy(), null, entity.getDepartmentId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        Equipment saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected Equipment findEntity(UUID id) {
        Equipment entity = repository.findByIdAndIsDeletedFalse(id)
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
        if (!roleDataScopeService.canAccessDocument(entity.getCreatedBy(), null, entity.getDepartmentId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        return entity;
    }
    protected void beforeCreate(Equipment entity, EquipmentCreateRequest dto) {
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
        validateManufacturer(entity);
        roleDataScopeService.assertDepartmentDocumentMutationAllowed(entity.getDepartmentId(), "Equipment");
    }
    protected void beforeUpdate(Equipment entity, EquipmentUpdateRequest dto) {
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
        validateManufacturer(entity);
        roleDataScopeService.assertDepartmentDocumentMutationAllowed(entity.getDepartmentId(), "Equipment");
    }

    private void validateManufacturer(Equipment entity) {
        if (entity.getManufacturerId() == null) {
            entity.setManufacturerRef(null);
            return;
        }
        Manufacturer manufacturer = manufacturerRepository
            .findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), entity.getManufacturerId())
            .filter(candidate -> "ACTIVE".equalsIgnoreCase(candidate.getStatus()))
            .orElseThrow(() -> new BadRequestException(
                "Manufacturer must be active in the current company",
                "equipment.manufacturer.invalid",
                entity.getManufacturerId()
            ));
        entity.setManufacturerRef(manufacturer);
        entity.setManufacturer(manufacturer.getName());
    }
    protected boolean companyScoped() {
        return true;
    }
}
