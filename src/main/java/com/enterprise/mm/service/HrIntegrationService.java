package com.enterprise.mm.service;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.payload.HrDtos.DepartmentTreeNodeDto;
import com.enterprise.mm.payload.HrDtos.HrDepartmentDto;
import com.enterprise.mm.payload.HrDtos.HrEmployeeDto;
import com.enterprise.mm.payload.HrDtos.HrPositionDto;
import com.enterprise.mm.payload.HrDtos.HrSyncRequest;
import com.enterprise.mm.payload.HrDtos.HrSyncResultDto;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Department;
import com.enterprise.mm.entity.Employee;
import com.enterprise.mm.entity.IntegrationEvent;
import com.enterprise.mm.entity.Position;
import com.enterprise.mm.enums.EmployeeStatus;
import com.enterprise.mm.repository.DepartmentRepository;
import com.enterprise.mm.repository.EmployeeRepository;
import com.enterprise.mm.repository.PositionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class HrIntegrationService {
    private final DepartmentRepository departmentRepository;
    private final PositionRepository positionRepository;
    private final EmployeeRepository employeeRepository;
    private final IntegrationEventService integrationEventService;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<DepartmentTreeNodeDto> orgTree(String search) {
        List<DepartmentTreeNodeDto> tree = orgTree();
        if (search == null || search.isBlank()) {
            return tree;
        }
        String needle = search.trim().toLowerCase(Locale.ROOT);
        return tree.stream()
            .map(node -> filterNode(node, needle))
            .filter(Objects::nonNull)
            .toList();
    }

    @Transactional(readOnly = true)
    public List<DepartmentTreeNodeDto> orgTree() {
        String tenantId = TenantContext.currentTenant();
        List<Department> departments = departmentRepository.findByTenantIdAndIsDeletedFalseOrderBySortOrderAscNameAsc(tenantId).stream()
            .filter(department -> com.enterprise.mm.security.AccessScope.canAccessCompany(department.getCompanyId()))
            .toList();
        Map<UUID, Employee> employeesById = new HashMap<>();
        employeeRepository.findByTenantIdAndIsDeletedFalse(tenantId).stream()
            .filter(employee -> com.enterprise.mm.security.AccessScope.canAccessCompany(employee.getCompanyId()))
            .forEach(employee -> employeesById.put(employee.getId(), employee));
        Map<UUID, List<Department>> childrenByParent = new HashMap<>();
        List<Department> roots = new ArrayList<>();
        for (Department department : departments) {
            if (department.getParentDepartment() == null) {
                roots.add(department);
            } else {
                childrenByParent.computeIfAbsent(department.getParentDepartment().getId(), ignored -> new ArrayList<>()).add(department);
            }
        }
        departments.stream()
            .filter(department -> department.getParentDepartment() != null && departments.stream().noneMatch(parent -> parent.getId().equals(department.getParentDepartment().getId())))
            .forEach(roots::add);
        roots.sort(departmentComparator());
        return roots.stream().map(department -> toNode(tenantId, department, childrenByParent, employeesById)).toList();
    }
    @Transactional
    public HrSyncResultDto sync(HrSyncRequest request) {
        HrSyncRequest safeRequest = request == null ? new HrSyncRequest("HR", List.of(), List.of(), List.of()) : request;
        String tenantId = TenantContext.currentTenant();
        Map<String, Department> departmentsByExternalId = new HashMap<>();
        int departmentsUpserted = syncDepartments(tenantId, safeList(safeRequest.departments()), departmentsByExternalId);
        int positionsUpserted = syncPositions(tenantId, safeList(safeRequest.positions()), departmentsByExternalId);
        int employeesUpserted = syncEmployees(tenantId, safeList(safeRequest.employees()), departmentsByExternalId);
        syncPositionOccupancy(tenantId);
        IntegrationEvent event = integrationEventService.emit(
            "HR.ORG_STRUCTURE_SYNCED",
            "HR",
            null,
            Map.of(
                "sourceSystem", blankToDefault(safeRequest.sourceSystem(), "HR"),
                "departmentsUpserted", departmentsUpserted,
                "positionsUpserted", positionsUpserted,
                "employeesUpserted", employeesUpserted
            )
        );
        auditService.log("HRIntegration", event.getId(), "SYNC_APPLIED", "Departments=" + departmentsUpserted + ", positions=" + positionsUpserted + ", employees=" + employeesUpserted, null, null);
        return new HrSyncResultDto(departmentsUpserted, positionsUpserted, employeesUpserted, event.getId());
    }
    @Transactional
    public HrSyncResultDto requestSync() {
        IntegrationEvent event = integrationEventService.emit(
            "HR.SYNC_REQUESTED",
            "HR",
            null,
            Map.of("sourceSystem", "MM", "scope", "ORG_STRUCTURE")
        );
        auditService.log("HRIntegration", event.getId(), "SYNC_REQUESTED", "Requested HR org structure sync", null, null);
        return new HrSyncResultDto(0, 0, 0, event.getId());
    }
    private DepartmentTreeNodeDto toNode(String tenantId, Department department, Map<UUID, List<Department>> childrenByParent, Map<UUID, Employee> employeesById) {
        List<Department> children = childrenByParent.getOrDefault(department.getId(), List.of()).stream()
            .sorted(departmentComparator())
            .toList();
        Employee manager = department.getManagerEmployee();
        return new DepartmentTreeNodeDto(
            department.getId(),
            department.getName(),
            department.getStatus(),
            department.getDepartmentType(),
            department.getCostCenter(),
            manager == null ? null : manager.getId(),
            manager == null ? null : manager.getName(),
            positionRepository.countByTenantIdAndDepartment_IdAndIsDeletedFalse(tenantId, department.getId()),
            employeeRepository.countByTenantIdAndDepartment_IdAndIsDeletedFalse(tenantId, department.getId()),
            children.stream().map(child -> toNode(tenantId, child, childrenByParent, employeesById)).toList()
        );
    }
    private DepartmentTreeNodeDto filterNode(DepartmentTreeNodeDto node, String needle) {
        List<DepartmentTreeNodeDto> filteredChildren = node.children().stream()
            .map(child -> filterNode(child, needle))
            .filter(Objects::nonNull)
            .toList();
        if (!filteredChildren.isEmpty() || matchesNode(node, needle)) {
            return new DepartmentTreeNodeDto(
                node.id(),
                node.name(),
                node.status(),
                node.departmentType(),
                node.costCenter(),
                node.managerEmployeeId(),
                node.managerName(),
                node.positionCount(),
                node.employeeCount(),
                filteredChildren
            );
        }
        return null;
    }

    private boolean matchesNode(DepartmentTreeNodeDto node, String needle) {
        return contains(node.name(), needle)
            || contains(node.status(), needle)
            || contains(node.departmentType(), needle)
            || contains(node.costCenter(), needle)
            || contains(node.managerName(), needle);
    }

    private boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private int syncDepartments(String tenantId, List<HrDepartmentDto> departments, Map<String, Department> departmentsByExternalId) {
        int count = 0;
        for (HrDepartmentDto item : departments) {
            if (isBlank(item.externalId()) && isBlank(item.name())) {
                continue;
            }
            Department department = findDepartment(tenantId, item.externalId(), item.name());
            department.setExternalId(blankToNull(item.externalId()));
            department.setCode("HR-DEP-" + blankToDefault(item.externalId(), slug(item.name())));
            department.setName(blankToDefault(item.name(), "Department"));
            department.setDepartmentType(blankToDefault(item.departmentType(), "DEPARTMENT"));
            department.setCostCenter(blankToNull(item.costCenter()));
            department.setSortOrder(item.sortOrder() == null ? 100 : item.sortOrder());
            department.setStatus(blankToDefault(item.status(), "ACTIVE"));
            Department saved = departmentRepository.save(department);
            if (!isBlank(item.externalId())) {
                departmentsByExternalId.put(item.externalId(), saved);
            }
            count++;
        }
        for (HrDepartmentDto item : departments) {
            if (isBlank(item.externalId())) {
                continue;
            }
            Department department = departmentsByExternalId.get(item.externalId());
            Department parent = isBlank(item.parentExternalId()) ? null : departmentsByExternalId.get(item.parentExternalId());
            department.setParentDepartment(parent);
            department.setHierarchyPath(hierarchyPath(parent, department));
            departmentRepository.save(department);
        }
        return count;
    }
    private int syncPositions(String tenantId, List<HrPositionDto> positions, Map<String, Department> departmentsByExternalId) {
        int count = 0;
        for (HrPositionDto item : positions) {
            if (isBlank(item.externalId()) && isBlank(item.name())) {
                continue;
            }
            Position position = findPosition(tenantId, item.externalId(), item.name());
            position.setExternalId(blankToNull(item.externalId()));
            position.setCode("HR-POS-" + blankToDefault(item.externalId(), slug(item.name())));
            position.setName(blankToDefault(item.name(), "Position"));
            position.setDepartment(resolveDepartment(departmentsByExternalId, item.departmentExternalId()));
            position.setPositionCategory(blankToDefault(item.positionCategory(), "STAFF"));
            position.setGrade(blankToNull(item.grade()));
            position.setHeadcount(item.headcount() == null ? BigDecimal.ONE : item.headcount());
            if (position.getOccupiedHeadcount() == null) {
                position.setOccupiedHeadcount(BigDecimal.ZERO);
            }
            position.setStatus(blankToDefault(item.status(), "ACTIVE"));
            positionRepository.save(position);
            count++;
        }
        return count;
    }
    private void syncPositionOccupancy(String tenantId) {
        for (Position position : positionRepository.findByTenantIdAndIsDeletedFalse(tenantId)) {
            long occupied = employeeRepository.countOccupiedByPosition(tenantId, position.getCompanyId(), position.getId());
            position.setOccupiedHeadcount(BigDecimal.valueOf(occupied).setScale(4, RoundingMode.HALF_UP));
            positionRepository.save(position);
        }
    }

    private int syncEmployees(String tenantId, List<HrEmployeeDto> employees, Map<String, Department> departmentsByExternalId) {
        Map<String, Employee> employeesByExternalId = new HashMap<>();
        int count = 0;
        for (HrEmployeeDto item : employees) {
            if (isBlank(item.externalId()) && isBlank(item.personnelNumber()) && isBlank(item.fullName())) {
                continue;
            }
            Employee employee = findEmployee(tenantId, item.externalId(), item.personnelNumber());
            employee.setExternalId(blankToNull(item.externalId()));
            employee.setPersonnelNumber(blankToNull(item.personnelNumber()));
            employee.setCode("HR-EMP-" + blankToDefault(item.personnelNumber(), blankToDefault(item.externalId(), slug(item.fullName()))));
            employee.setName(blankToDefault(item.fullName(), "Employee"));
            employee.setDepartment(resolveDepartment(departmentsByExternalId, item.departmentExternalId()));
            employee.setPosition(resolvePosition(tenantId, item.positionExternalId()));
            employee.setEmail(blankToNull(item.email()));
            employee.setPhone(blankToNull(item.phone()));
            employee.setEmploymentStatus(parseEmployeeStatus(item.employmentStatus()));
            employee.setHireDate(item.hireDate());
            employee.setDismissalDate(item.dismissalDate());
            Employee saved = employeeRepository.save(employee);
            if (!isBlank(item.externalId())) {
                employeesByExternalId.put(item.externalId(), saved);
            }
            count++;
        }
        for (HrEmployeeDto item : employees) {
            if (isBlank(item.externalId()) || isBlank(item.managerExternalId())) {
                continue;
            }
            Employee employee = employeesByExternalId.get(item.externalId());
            Employee manager = employeesByExternalId.get(item.managerExternalId());
            if (employee != null && manager != null && !Objects.equals(employee.getId(), manager.getId())) {
                employee.setManagerEmployee(manager);
                employeeRepository.save(employee);
            }
        }
        return count;
    }

    private EmployeeStatus parseEmployeeStatus(String value) {
        if (isBlank(value)) {
            return EmployeeStatus.ACTIVE;
        }
        try {
            return EmployeeStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return EmployeeStatus.ACTIVE;
        }
    }

    private Department findDepartment(String tenantId, String externalId, String name) {
        if (!isBlank(externalId)) {
            return departmentRepository.findByTenantIdAndExternalIdAndIsDeletedFalse(tenantId, externalId).orElseGet(Department::new);
        }
        return departmentRepository.findByTenantIdAndCodeAndIsDeletedFalse(tenantId, "HR-DEP-" + slug(name)).orElseGet(Department::new);
    }
    private Position findPosition(String tenantId, String externalId, String name) {
        if (!isBlank(externalId)) {
            return positionRepository.findByTenantIdAndExternalIdAndIsDeletedFalse(tenantId, externalId).orElseGet(Position::new);
        }
        return positionRepository.findByTenantIdAndCodeAndIsDeletedFalse(tenantId, "HR-POS-" + slug(name)).orElseGet(Position::new);
    }
    private Employee findEmployee(String tenantId, String externalId, String personnelNumber) {
        if (!isBlank(externalId)) {
            return employeeRepository.findByTenantIdAndExternalIdAndIsDeletedFalse(tenantId, externalId).orElseGet(Employee::new);
        }
        if (!isBlank(personnelNumber)) {
            return employeeRepository.findByTenantIdAndPersonnelNumberAndIsDeletedFalse(tenantId, personnelNumber).orElseGet(Employee::new);
        }
        return new Employee();
    }
    private Department resolveDepartment(Map<String, Department> departmentsByExternalId, String externalId) {
        return isBlank(externalId) ? null : departmentsByExternalId.get(externalId);
    }
    private Position resolvePosition(String tenantId, String externalId) {
        if (isBlank(externalId)) {
            return null;
        }
        return positionRepository.findByTenantIdAndExternalIdAndIsDeletedFalse(tenantId, externalId)
            .orElse(null);
    }
    private String hierarchyPath(Department parent, Department department) {
        String segment = department.getCode() == null ? slug(department.getName()) : department.getCode();
        if (parent == null) {
            return "/" + segment;
        }
        String parentPath = parent.getHierarchyPath() == null ? "/" + parent.getCode() : parent.getHierarchyPath();
        return parentPath + "/" + segment;
    }
    private Comparator<Department> departmentComparator() {
        return Comparator.comparing((Department department) -> department.getSortOrder() == null ? 100 : department.getSortOrder())
            .thenComparing(Department::getName, Comparator.nullsLast(String::compareToIgnoreCase));
    }
    private <T> List<T> safeList(List<T> source) {
        return source == null ? List.of() : source;
    }
    private String blankToDefault(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }
    private String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
    private String slug(String value) {
        if (value == null || value.isBlank()) {
            return UUID.randomUUID().toString().substring(0, 8);
        }
        String slug = value.trim().toUpperCase().replaceAll("[^A-Z0-9]+", "-").replaceAll("(^-|-$)", "");
        return slug.isBlank() ? UUID.randomUUID().toString().substring(0, 8) : slug;
    }
}
