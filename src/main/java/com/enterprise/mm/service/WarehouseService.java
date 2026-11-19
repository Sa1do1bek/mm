package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.payload.WarehouseResponse;
import com.enterprise.mm.payload.WarehouseUpdateRequest;
import com.enterprise.mm.payload.WarehouseCreateRequest;
import com.enterprise.mm.payload.WarehouseSearchRequest;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.WarehouseMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Employee;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.EmployeeRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WarehouseService {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ExactOpeningImportGuard openingImportGuard;

    private void guardOpeningImport(UUID companyId) {
        if (openingImportGuard != null) openingImportGuard.assertOperationsAllowed(companyId);
    }

    private final WarehouseRepository repository;
    private final EmployeeRepository employeeRepository;
    private final WarehouseMapper mapper;
    private final String entityName = "Warehouse";
    private final AuditService auditService;
    private final RoleDataScopeService roleDataScopeService;
    @Transactional(readOnly = true)
    public Page<WarehouseResponse> search(WarehouseSearchRequest request, Pageable pageable) {
        WarehouseSearchRequest criteria = request == null ? WarehouseSearchRequest.empty() : request;
        SearchCriteria filter = SearchCriteria.of(criteria);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        RoleDataScopeService.DataScope dataScope = roleDataScopeService.currentScope();
        Set<UUID> permittedWarehouseIds = dataScope.warehouseIds().isEmpty()
            ? Set.of(new UUID(0L, 0L))
            : dataScope.warehouseIds();
        return repository.search(
            TenantContext.currentTenant(),
            scoped ? CompanyContext.currentCompany() : null,
            filter.includeDeleted(),
            searchPattern(criteria.search()),
            filter.fromDateTime(),
            filter.toDateTime(),
            filter.status(),
            !filter.hasStatus(),
            dataScope.warehouseScoped(),
            permittedWarehouseIds,
            criteria.id(),
            searchPattern(criteria.code()),
            searchPattern(criteria.name()),
            searchPattern(criteria.description()),
            criteria.deleted(),
            searchPattern(criteria.createdBy()),
            searchPattern(criteria.updatedBy()),
            criteria.version(),
            searchPattern(criteria.address()),
            pageable
        ).map(this::toResponse);
    }
    @Transactional(readOnly = true)
    public WarehouseResponse get(UUID id) {
        return toResponse(findEntity(id));
    }
    @Transactional
    public WarehouseResponse create(WarehouseCreateRequest dto) {
        Warehouse entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        Warehouse saved = repository.save(entity);
        syncResponsibleEmployees(saved, dto.getResponsibleEmployeeIds());
        auditService.logInCurrentTransaction(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return toResponse(saved);
    }
    @Transactional
    public WarehouseResponse update(UUID id, WarehouseUpdateRequest dto) {
        Warehouse entity = findEntity(id);
        guardOpeningImport(entity.getCompanyId());
        String oldSnapshot = auditService.snapshot(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        Warehouse saved = repository.save(entity);
        syncResponsibleEmployees(saved, dto.getResponsibleEmployeeIds());
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        Warehouse entity = findEntity(id);
        guardOpeningImport(entity.getCompanyId());
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public WarehouseResponse restore(UUID id) {
        Warehouse entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        assertWarehouseAccess(entity.getId());
        guardOpeningImport(entity.getCompanyId());
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        Warehouse saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return toResponse(saved);
    }
    protected Warehouse findEntity(UUID id) {
        Warehouse entity = repository.findByIdAndIsDeletedFalse(id)
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
        assertWarehouseAccess(entity.getId());
        return entity;
    }

    private void assertWarehouseAccess(UUID warehouseId) {
        if (!roleDataScopeService.canAccessWarehouse(warehouseId)) {
            throw new NotFoundException("Warehouse not found in your permitted warehouse scope");
        }
    }

    protected void beforeCreate(Warehouse entity, WarehouseCreateRequest dto) {
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
    protected void beforeUpdate(Warehouse entity, WarehouseUpdateRequest dto) {
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }
    protected boolean companyScoped() {
        return true;
    }

    private String searchPattern(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return "%" + value.trim().toLowerCase(Locale.ROOT) + "%";
    }

    private void syncResponsibleEmployees(Warehouse warehouse, List<UUID> responsibleEmployeeIds) {
        UUID warehouseId = warehouse.getId();
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();

        List<Employee> currentEmployees = employeeRepository.findByTenantIdAndCompanyIdAndWarehouse_IdAndIsDeletedFalse(tenantId, companyId, warehouseId);
        List<Employee> resolvedEmployees = resolveResponsibleEmployees(responsibleEmployeeIds);
        Set<UUID> targetIds = resolvedEmployees.stream().map(Employee::getId).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        List<Employee> changedEmployees = new ArrayList<>();
        for (Employee employee : currentEmployees) {
            if (!targetIds.contains(employee.getId())) {
                employee.setWarehouse(null);
                changedEmployees.add(employee);
            }
        }
        for (Employee employee : resolvedEmployees) {
            if (employee.getWarehouse() == null || !warehouseId.equals(employee.getWarehouse().getId())) {
                employee.setWarehouse(warehouse);
                changedEmployees.add(employee);
            }
        }
        if (!changedEmployees.isEmpty()) {
            employeeRepository.saveAll(changedEmployees);
        }
    }

    private List<Employee> resolveResponsibleEmployees(List<UUID> responsibleEmployeeIds) {
        if (responsibleEmployeeIds == null || responsibleEmployeeIds.isEmpty()) {
            return List.of();
        }
        List<UUID> uniqueIds = new ArrayList<>(new LinkedHashSet<>(responsibleEmployeeIds));
        List<Employee> employees = employeeRepository.findByTenantIdAndCompanyIdAndIdInAndIsDeletedFalse(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            uniqueIds
        );
        if (employees.size() != uniqueIds.size()) {
            throw new BusinessException("One or more responsibleEmployeeIds not found");
        }
        Set<UUID> resolvedIds = employees.stream().map(Employee::getId).collect(java.util.stream.Collectors.toSet());
        for (UUID id : uniqueIds) {
            if (!resolvedIds.contains(id)) {
                throw new BusinessException("One or more responsibleEmployeeIds not found");
            }
        }
        return employees;
    }

    private WarehouseResponse toResponse(Warehouse warehouse) {
        WarehouseResponse response = mapper.toResponse(warehouse);
        response.setResponsibleEmployeeIds(
            employeeRepository.findByTenantIdAndCompanyIdAndWarehouse_IdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                warehouse.getId()
            ).stream().map(Employee::getId).toList()
        );
        return response;
    }
}

