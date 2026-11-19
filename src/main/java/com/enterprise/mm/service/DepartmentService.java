package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;

import com.enterprise.mm.payload.DepartmentResponse;
import com.enterprise.mm.payload.DepartmentUpdateRequest;
import com.enterprise.mm.payload.DepartmentCreateRequest;
import com.enterprise.mm.payload.DepartmentSearchRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.DepartmentMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Department;
import com.enterprise.mm.entity.Employee;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.entity.DepartmentWarehouseAccess;
import com.enterprise.mm.repository.DepartmentRepository;
import com.enterprise.mm.repository.DepartmentWarehouseAccessRepository;
import com.enterprise.mm.repository.DepartmentTypeRepository;
import com.enterprise.mm.repository.EmployeeRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DepartmentService {
    private final DepartmentRepository repository;
    private final DepartmentMapper mapper;
    private final String entityName = "Department";
    private final AuditService auditService;
    private final DepartmentTypeRepository departmentTypeRepository;
    private final EmployeeRepository employeeRepository;
    private final WarehouseRepository warehouseRepository;
    private final DepartmentWarehouseAccessRepository departmentWarehouseAccessRepository;

    protected void beforeCreate(Department entity, DepartmentCreateRequest dto) {
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

        normalizeOrgFields(entity);
        ensureUniqueName(entity);
    }
    protected void beforeUpdate(Department entity, DepartmentUpdateRequest dto) {
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));

        normalizeOrgFields(entity);
        ensureUniqueName(entity);
    }
    private void normalizeOrgFields(Department entity) {
        if (entity.getDepartmentType() == null || entity.getDepartmentType().isBlank()) {
            entity.setDepartmentType("DEPARTMENT");
        } else {
            entity.setDepartmentType(entity.getDepartmentType().trim().toUpperCase(Locale.ROOT));
        }
        departmentTypeRepository.findFirstByTenantIdAndCompanyIdAndCodeIgnoreCaseAndIsDeletedFalseOrderByCreatedAtAsc(
                TenantContext.currentTenant(), entity.getCompanyId(), entity.getDepartmentType())
            .orElseThrow(() -> new BusinessException("Unknown department type: " + entity.getDepartmentType()));
        if (entity.getSortOrder() == null) {
            entity.setSortOrder(100);
        }
        String ownSegment = entity.getCode() == null || entity.getCode().isBlank() ? entity.getName() : entity.getCode();
        UUID parentDepartmentId = idOf(entity.getParentDepartment());
        if (parentDepartmentId == null) {
            entity.setHierarchyPath("/" + ownSegment);
            return;
        }
        Department parent = loadScopedParent(parentDepartmentId, entity.getCompanyId());
        validateAcyclicHierarchy(entity, parent);
        entity.setParentDepartment(parent);
        String parentPath = parent.getHierarchyPath() == null || parent.getHierarchyPath().isBlank()
            ? "/" + parent.getCode()
            : parent.getHierarchyPath();
        entity.setHierarchyPath(parentPath + "/" + ownSegment);
    }

    private Department loadScopedParent(UUID parentId, UUID companyId) {
        return repository.findByIdAndIsDeletedFalse(parentId)
            .filter(department -> !department.isDeleted())
            .filter(department -> TenantContext.currentTenant().equals(department.getTenantId()))
            .filter(department -> Objects.equals(companyId, department.getCompanyId()))
            .orElseThrow(() -> new BusinessException("Parent department not found in the current company"));
    }

    private void validateAcyclicHierarchy(Department entity, Department proposedParent) {
        Set<UUID> visited = new HashSet<>();
        List<String> parentChain = new ArrayList<>();
        UUID entityId = entity.getId();
        Department cursor = proposedParent;
        while (cursor != null) {
            UUID cursorId = cursor.getId();
            if (cursorId == null) {
                throw new BusinessException("Parent department has no persistent identity");
            }
            String cursorLabel = departmentLabel(cursor);
            parentChain.add(cursorLabel);
            if (entityId != null && Objects.equals(entityId, cursorId)) {
                throw new BusinessException("Department hierarchy cycle detected: parent chain points back to "
                    + departmentLabel(entity) + " via " + String.join(" -> ", parentChain));
            }
            if (!visited.add(cursorId)) {
                throw new BusinessException("Department hierarchy cycle detected: existing parent chain loops at "
                    + cursorLabel + " via " + String.join(" -> ", parentChain));
            }
            UUID nextParentId = idOf(cursor.getParentDepartment());
            if (entityId != null && Objects.equals(entityId, nextParentId)) {
                throw new BusinessException("Department hierarchy cycle detected: parent chain points back to "
                    + departmentLabel(entity) + " via " + String.join(" -> ", parentChain)
                    + " -> " + departmentLabel(entity));
            }
            cursor = nextParentId == null ? null : loadScopedParent(nextParentId, entity.getCompanyId());
        }
    }

    private String departmentLabel(Department department) {
        if (department == null) {
            return "<null>";
        }
        String code = department.getCode();
        String name = department.getName();
        UUID id = department.getId();
        if (code != null && !code.isBlank()) {
            return code + "[" + id + "]";
        }
        if (name != null && !name.isBlank()) {
            return name + "[" + id + "]";
        }
        return String.valueOf(id);
    }

    private UUID idOf(Department department) {
        return department == null ? null : department.getId();
    }

    private void ensureUniqueName(Department entity) {
        if (entity.getName() == null || entity.getName().isBlank()) {
            return;
        }
        String tenantId = TenantContext.currentTenant();
        UUID companyId = entity.getCompanyId() == null ? CompanyContext.currentCompany() : entity.getCompanyId();
        repository.findByTenantIdAndCompanyIdAndNameIgnoreCaseAndIsDeletedFalse(tenantId, companyId, entity.getName().trim())
            .filter(existing -> !Objects.equals(existing.getId(), entity.getId()))
            .ifPresent(existing -> {
                throw new BusinessException("Department name already exists in this company");
            });
    }
    @Transactional(readOnly = true)
    public Page<DepartmentResponse> search(DepartmentSearchRequest request, Pageable pageable) {
        SearchCriteria filter = SearchCriteria.of(request);
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
            pageable
        ).map(this::toResponse);
    }
    @Transactional(readOnly = true)
    public DepartmentResponse get(UUID id) {
        return toResponse(findEntity(id));
    }
    @Transactional
    public DepartmentResponse create(DepartmentCreateRequest dto) {
        Department entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        Department saved = repository.save(entity);
        syncWarehouseAccess(saved, dto.getWarehouseIds());
        attachUnassignedManagerToDepartment(saved);
        auditService.logInCurrentTransaction(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return toResponse(saved);
    }
    @Transactional
    public List<DepartmentResponse> createBulk(List<DepartmentCreateRequest> dtos) {
        List<DepartmentResponse> responses = new ArrayList<>();
        if (dtos == null || dtos.isEmpty()) {
            return responses;
        }
        for (DepartmentCreateRequest dto : dtos) {
            responses.add(create(dto));
        }
        return responses;
    }
    @Transactional
    public DepartmentResponse update(UUID id, DepartmentUpdateRequest dto) {
        Department entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        Department saved = repository.save(entity);
        syncWarehouseAccess(saved, dto.getWarehouseIds());
        attachUnassignedManagerToDepartment(saved);
        auditService.logInCurrentTransaction(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        Department entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public DepartmentResponse restore(UUID id) {
        Department entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        validateScope(entity);
        if (!entity.isDeleted()) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        beforeUpdate(entity, new DepartmentUpdateRequest());
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        Department saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return toResponse(saved);
    }
    protected Department findEntity(UUID id) {
        Department entity = repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        validateScope(entity);
        return entity;
    }

    private void validateScope(Department entity) {
        if (!TenantContext.currentTenant().equals(entity.getTenantId())
            || (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped())
                && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId()))) {
            throw new NotFoundException(entityName + " not found: " + entity.getId());
        }
    }
    private void attachUnassignedManagerToDepartment(Department department) {
        UUID managerId = idOf(department.getManagerEmployee());
        if (managerId == null) {
            return;
        }
        Employee manager = employeeRepository.findByIdAndIsDeletedFalse(managerId)
            .filter(employee -> TenantContext.currentTenant().equals(employee.getTenantId()))
            .filter(employee -> Objects.equals(department.getCompanyId(), employee.getCompanyId()))
            .orElseThrow(() -> new BusinessException("Manager employee not found in the current company"));
        if (manager.getDepartment() == null) {
            manager.setDepartment(department);
            employeeRepository.save(manager);
        }
    }
    private UUID idOf(Employee employee) {
        return employee == null ? null : employee.getId();
    }

    private void syncWarehouseAccess(Department department, Set<UUID> requestedWarehouseIds) {
        if (requestedWarehouseIds == null) {
            return;
        }
        Set<UUID> warehouseIds = requestedWarehouseIds.stream()
            .filter(Objects::nonNull)
            .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        List<Warehouse> warehouses = warehouseIds.isEmpty()
            ? List.of()
            : warehouseRepository.findByTenantIdAndCompanyIdAndIdIn(
                TenantContext.currentTenant(), department.getCompanyId(), warehouseIds);
        Set<UUID> validIds = warehouses.stream()
            .filter(warehouse -> !warehouse.isDeleted())
            .filter(warehouse -> "ACTIVE".equals(warehouse.getStatus()))
            .map(Warehouse::getId)
            .collect(java.util.stream.Collectors.toSet());
        if (!validIds.equals(warehouseIds)) {
            throw new BusinessException("One or more warehouseIds are inactive or do not belong to the department's company");
        }

        List<DepartmentWarehouseAccess> existing = departmentWarehouseAccessRepository
            .findByTenantIdAndCompanyIdAndDepartment_Id(
                TenantContext.currentTenant(), department.getCompanyId(), department.getId());
        if (!existing.isEmpty()) {
            // Flush removals before inserting the replacement set so the unique constraint
            // cannot observe both the old and new grant for the same warehouse.
            departmentWarehouseAccessRepository.deleteAllInBatch(existing);
            departmentWarehouseAccessRepository.flush();
        }
        if (!warehouses.isEmpty()) {
            List<DepartmentWarehouseAccess> grants = warehouses.stream().map(warehouse -> {
                DepartmentWarehouseAccess access = new DepartmentWarehouseAccess();
                access.setTenantId(TenantContext.currentTenant());
                access.setCompanyId(department.getCompanyId());
                access.setDepartment(department);
                access.setWarehouse(warehouse);
                return access;
            }).toList();
            departmentWarehouseAccessRepository.saveAllAndFlush(grants);
        }
    }

    private DepartmentResponse toResponse(Department department) {
        DepartmentResponse response = mapper.toResponse(department);
        response.setWarehouseIds(Set.copyOf(departmentWarehouseAccessRepository.findActiveWarehouseIds(
            TenantContext.currentTenant(), department.getCompanyId(), department.getId())));
        return response;
    }

    protected boolean companyScoped() {
        return true;
    }
}
