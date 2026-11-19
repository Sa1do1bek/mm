package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.payload.ConsignmentStockTransactionResponse;
import com.enterprise.mm.payload.ConsignmentStockTransactionUpdateRequest;
import com.enterprise.mm.payload.ConsignmentStockTransactionCreateRequest;
import com.enterprise.mm.payload.ConsignmentStockTransactionSearchRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.ConsignmentStockTransactionMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ConsignmentStockTransaction;
import com.enterprise.mm.repository.ConsignmentStockTransactionRepository;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ConsignmentStockTransactionService {
    private final ConsignmentStockTransactionRepository repository;
    private final ConsignmentStockTransactionMapper mapper;
    private final MaterialCatalogGuard materialCatalogGuard;
    private final String entityName = "ConsignmentStockTransaction";
    private final AuditService auditService;
    private final RoleDataScopeService roleDataScopeService;
    @Transactional(readOnly = true)
    public Page<ConsignmentStockTransactionResponse> search(ConsignmentStockTransactionSearchRequest request, Pageable pageable) {
        SearchCriteria filter = roleDataScopeService.applyWarehouseScope(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        return repository.search(
            TenantContext.currentTenant(),
            scoped ? CompanyContext.currentCompany() : null,
            filter.includeDeleted(),
            filter.normalizedSearch(),
            filter.fromDateTime(),
            filter.toDateTime(),
            filter.status(),
            !filter.hasStatus(),
            filter.hasWarehouseIds() ? null : filter.warehouseId(),
            filter.warehouseIds(), !filter.hasWarehouseIds(),
            filter.supplierId(),
            filter.materialIds(),
            !filter.hasMaterialIds(),
            pageable
        ).map(mapper::toResponse);
    }
    @Transactional(readOnly = true)
    public ConsignmentStockTransactionResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public ConsignmentStockTransactionResponse create(ConsignmentStockTransactionCreateRequest dto) {
        ConsignmentStockTransaction entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        ConsignmentStockTransaction saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public ConsignmentStockTransactionResponse update(UUID id, ConsignmentStockTransactionUpdateRequest dto) {
        ConsignmentStockTransaction entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        ConsignmentStockTransaction saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        ConsignmentStockTransaction entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public ConsignmentStockTransactionResponse restore(UUID id) {
        ConsignmentStockTransaction entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        roleDataScopeService.assertWarehouseAccess(entity.getWarehouseId());
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        ConsignmentStockTransaction saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected ConsignmentStockTransaction findEntity(UUID id) {
        ConsignmentStockTransaction entity = repository.findByIdAndIsDeletedFalse(id)
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
        roleDataScopeService.assertWarehouseAccess(entity.getWarehouseId());
        return entity;
    }
    protected void beforeCreate(ConsignmentStockTransaction entity, ConsignmentStockTransactionCreateRequest dto) {
        roleDataScopeService.assertWarehouseAccess(entity.getWarehouseId());
        materialCatalogGuard.requireActiveTmbMaterial(entity.getMaterialId(), "materialId");
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
    }
    protected void beforeUpdate(ConsignmentStockTransaction entity, ConsignmentStockTransactionUpdateRequest dto) {
        roleDataScopeService.assertWarehouseAccess(entity.getWarehouseId());
        materialCatalogGuard.requireActiveTmbMaterial(entity.getMaterialId(), "materialId");
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }
    protected boolean companyScoped() {
        return true;
    }
}
