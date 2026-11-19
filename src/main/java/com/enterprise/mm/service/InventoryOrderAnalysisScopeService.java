package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Department;
import com.enterprise.mm.repository.DepartmentRepository;
import com.enterprise.mm.repository.DepartmentWarehouseAccessRepository;
import com.enterprise.mm.service.inventory.InventoryAccessService;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InventoryOrderAnalysisScopeService {
    private final InventoryAccessService access;
    private final RoleDataScopeService dataScope;
    private final DepartmentRepository departments;
    private final DepartmentWarehouseAccessRepository departmentWarehouses;

    public record Target(UUID departmentId, String name, List<String> aliases, List<UUID> warehouseIds) {}
    public record Scope(String mode, UUID companyId, List<UUID> departmentIds, List<Target> targets) {}

    @Transactional(readOnly = true)
    public Scope current() {
        UUID company = CompanyContext.currentCompany();
        if (access.hasRole("ADMIN")) return new Scope("ALL", company, List.of(), List.of());
        Set<UUID> departmentIds;
        boolean head = access.hasRole("DEPARTMENT_HEAD");
        boolean managedScope = false;
        if (head) {
            departmentIds = dataScope.currentScope().managedDepartmentIds();
            managedScope = !departmentIds.isEmpty();
            if (!managedScope) {
                UUID assigned = dataScope.assignedDepartmentId();
                departmentIds = assigned == null ? Set.of() : Set.of(assigned);
            }
        } else {
            UUID assigned = dataScope.assignedDepartmentId();
            departmentIds = assigned == null ? Set.of() : Set.of(assigned);
        }
        boolean requireActiveManager = managedScope;
        List<Department> allowed = departmentIds.stream()
            .map(departments::findByIdAndIsDeletedFalse)
            .flatMap(java.util.Optional::stream)
            .filter(d -> TenantContext.currentTenant().equals(d.getTenantId())
                && company.equals(d.getCompanyId()) && "ACTIVE".equals(d.getStatus())
                && (!requireActiveManager || d.getManagerEmployee() != null
                    && !d.getManagerEmployee().isDeleted()
                    && "ACTIVE".equals(d.getManagerEmployee().getStatus())))
            .sorted(Comparator.comparing(Department::getId)).toList();
        if (allowed.isEmpty()) throw new AccessDeniedException("An active assigned department is required for order analysis");
        List<Target> targets = allowed.stream().map(d -> new Target(d.getId(), d.getName(),
            java.util.stream.Stream.of(d.getCode(), d.getExternalId()).filter(s -> s != null && !s.isBlank()).distinct().toList(),
            departmentWarehouses.findActiveWarehouseIds(TenantContext.currentTenant(), company, d.getId())
                .stream().sorted().toList())).toList();
        return new Scope("DEPARTMENTS", company, allowed.stream().map(Department::getId).toList(), targets);
    }
}
