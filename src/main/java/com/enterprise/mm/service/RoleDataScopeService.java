package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.entity.Department;
import com.enterprise.mm.entity.Employee;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.DemandPlanningCycleResponse;
import com.enterprise.mm.repository.DepartmentRepository;
import com.enterprise.mm.repository.DepartmentWarehouseAccessRepository;
import com.enterprise.mm.repository.EmployeeRepository;
import com.enterprise.mm.repository.UserRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.lang.reflect.Method;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RoleDataScopeService {
    private static final UUID NO_ACCESS_WAREHOUSE_ID = new UUID(0L, 0L);
    private static final Set<String> SELF_SCOPED_ROLES = Set.of("PROCUREMENT_MANAGER", "PROCUREMENT_SPECIALIST", "VIEWER", "DEPARTMENT_HEAD", "DEPARTMENT_WORKER");
    private static final Set<String> WAREHOUSE_SCOPED_ROLES = Set.of(
        "WAREHOUSE_OPERATOR", "WAREHOUSE_MANAGER", "WAREHOUSE_HEAD", "WAREHOUSE_KEEPER", "DEPARTMENT_HEAD"
    );
    private static final Set<String> GLOBAL_WAREHOUSE_BYPASS_ROLES = Set.of("ADMIN", "MM_MANAGER");
    private static final Set<String> UNRESTRICTED_ROLES = Set.of("ADMIN", "MM_MANAGER", "AUDITOR", "FINANCE_CONTROLLER", "BUDGET_CONTROLLER", "SUPPLIER_MANAGER");
    private static final Set<String> DEPARTMENT_MUTATION_BYPASS_ROLES = Set.of(
        "ADMIN", "MM_MANAGER"
    );
    private static final Set<String> OWN_DEPARTMENT_ONLY_ROLES = Set.of(
        "DEPARTMENT_HEAD", "DEPARTMENT_WORKER", "PROCUREMENT_MANAGER", "PROCUREMENT_SPECIALIST"
    );
    private static final Set<String> PROCUREMENT_PLANNING_ROLES = Set.of(
        "PROCUREMENT_MANAGER", "PROCUREMENT_SPECIALIST"
    );

    private final EmployeeRepository employeeRepository;
    private final DepartmentRepository departmentRepository;
    private final UserRepository userRepository;
    private final DepartmentWarehouseAccessRepository departmentWarehouseAccessRepository;

    /** Ordinary department actors are scoped by the request's owning department. */
    public boolean isDepartmentIssueScoped() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        Set<String> roles = normalizedRoles(auth);
        return (roles.contains("DEPARTMENT_HEAD") || roles.contains("DEPARTMENT_WORKER"))
            && roles.stream().noneMatch(UNRESTRICTED_ROLES::contains);
    }

    /** An independently assigned warehouse role keeps its warehouse-document authority. */
    public boolean isDepartmentWarehouseScoped() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return isDepartmentIssueScoped()
            && normalizedRoles(auth).stream().noneMatch(WAREHOUSE_SCOPED_ROLES::contains);
    }

    /** Department heads keep custody access to their department as well as its warehouses. */
    public boolean isDepartmentAssetScoped() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return isDepartmentIssueScoped() && (normalizedRoles(auth).contains("DEPARTMENT_HEAD")
            || isDepartmentWarehouseScoped());
    }

    public boolean canAccessDepartmentAsset(UUID departmentId, UUID warehouseId) {
        return !isDepartmentAssetScoped() || canReadIssueDepartment(departmentId)
            || (currentScope().warehouseScoped() && warehouseId != null && canAccessWarehouse(warehouseId));
    }

    public UUID issueDepartmentId() {
        return currentScope().departmentId();
    }

    public boolean canReadIssueDepartment(UUID departmentId) {
        return !isDepartmentIssueScoped()
            || (departmentId != null && Objects.equals(departmentId, issueDepartmentId()));
    }

    /** Cycles have company-wide collection, not a separate participant ACL. */
    public boolean canParticipateInCycle(DemandPlanningCycleResponse cycle) {
        UUID departmentId = issueDepartmentId();
        return cycle != null && "COLLECTING".equals(cycle.getStatus())
            && Objects.equals(cycle.getTenantId(), TenantContext.currentTenant())
            && Objects.equals(cycle.getCompanyId(), CompanyContext.currentCompany())
            && departmentId != null
            && departmentRepository.findByIdAndIsDeletedFalse(departmentId)
                .filter(d -> Objects.equals(d.getTenantId(), TenantContext.currentTenant())
                    && Objects.equals(d.getCompanyId(), CompanyContext.currentCompany())
                    && "ACTIVE".equals(d.getStatus())).isPresent();
    }

    /** Explicitly deny an unavailable warehouse, rather than presenting zero stock. */
    public UUID availabilityWarehouseId(UUID requestedWarehouseId) {
        DataScope scope = currentScope();
        if (scope.warehouseScoped()) {
            UUID effective = requestedWarehouseId == null ? scope.warehouseId() : requestedWarehouseId;
            if (effective == null || !scope.warehouseIds().contains(effective)) {
                throw new org.springframework.security.access.AccessDeniedException("Warehouse assignment required");
            }
            return effective;
        }
        if (isDepartmentIssueScoped()) {
            throw new org.springframework.security.access.AccessDeniedException("Warehouse availability requires warehouse authority");
        }
        return requestedWarehouseId;
    }

    /** Read-only aggregates may span assigned warehouses; commands still require one explicit warehouse. */
    public Set<UUID> warehouseReadScope(UUID requestedWarehouseId, Collection<UUID> requestedWarehouseIds) {
        DataScope scope = currentScope();
        Set<UUID> requested = requestedWarehouseIds == null ? Set.of() : new LinkedHashSet<>(requestedWarehouseIds);
        if (scope.warehouseScoped()) {
            if (scope.warehouseIds().isEmpty()
                || (requestedWarehouseId != null && !scope.warehouseIds().contains(requestedWarehouseId))
                || !scope.warehouseIds().containsAll(requested)) {
                throw new org.springframework.security.access.AccessDeniedException("Warehouse assignment required");
            }
            if (requested.isEmpty()) return requestedWarehouseId == null ? scope.warehouseIds() : Set.of(requestedWarehouseId);
        } else if (isDepartmentIssueScoped()) {
            throw new org.springframework.security.access.AccessDeniedException("Warehouse availability requires warehouse authority");
        }
        return requested.isEmpty() && requestedWarehouseId != null ? Set.of(requestedWarehouseId) : requested;
    }

    /** Returns the user's own department even when another role grants unrestricted document reads. */
    public UUID assignedDepartmentId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String username = authentication == null || !authentication.isAuthenticated()
            ? null : blankToNull(authentication.getName());
        if (username == null) return null;
        User user = userRepository.findByTenantIdAndCompanyIdAndUsernameAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), username).orElse(null);
        UUID userDepartment = userDepartmentId(user);
        if (userDepartment != null) return userDepartment;
        Employee employee = resolveCurrentEmployee(username, user);
        return employee != null && employee.getDepartment() != null
            ? employee.getDepartment().getId() : null;
    }

    /** Personal warehouse commands never inherit an administrator's global warehouse bypass. */
    public UUID requireAssignedWarehouseId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String username = authentication == null || !authentication.isAuthenticated() ? null : blankToNull(authentication.getName());
        if (username == null)
            throw new org.springframework.security.access.AccessDeniedException("Warehouse assignment required");
        User user = userRepository.findByTenantIdAndCompanyIdAndUsernameAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), username).orElse(null);
        Employee employee = resolveCurrentEmployee(username, user);
        UUID departmentId = employee != null && employee.getDepartment() != null
            ? employee.getDepartment().getId() : userDepartmentId(user);
        UUID directWarehouseId = employee == null || employee.getWarehouse() == null ? null : employee.getWarehouse().getId();
        boolean departmentHead = normalizedRoles(authentication).contains("DEPARTMENT_HEAD");
        Set<UUID> ids = warehouseIdsFor(departmentId, departmentHead ? null : directWarehouseId);
        // This explicit parameter belongs only to the fixed-asset stock operation; it never
        // changes the request's global data scope or permits an unassigned warehouse.
        if (org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
            instanceof org.springframework.web.context.request.ServletRequestAttributes attributes) {
            String selected = attributes.getRequest().getParameter("warehouseId");
            if (selected != null) {
                try {
                    UUID warehouseId = UUID.fromString(selected);
                    if (ids.contains(warehouseId)) return warehouseId;
                } catch (IllegalArgumentException ignored) {
                    // Invalid or unavailable explicit context fails closed.
                }
                throw new org.springframework.security.access.AccessDeniedException("Warehouse assignment required");
            }
        }
        if (ids.size() != 1)
            throw new org.springframework.security.access.AccessDeniedException("Exactly one assigned warehouse is required for this operation");
        return ids.iterator().next();
    }

    public boolean hasWarehouseAccess() {
        DataScope scope = currentScope();
        return scope.warehouseScoped() && !scope.warehouseIds().isEmpty();
    }

    public SearchCriteria applyWarehouseScope(SearchCriteria request) {
        SearchCriteria filter = SearchCriteria.of(request);
        DataScope scope = currentScope();
        if (!scope.warehouseScoped()) {
            return filter;
        }
        if (filter.warehouseId() != null) {
            return scope.warehouseIds().contains(filter.warehouseId())
                ? SearchCriteria.withWarehouseId(filter, filter.warehouseId())
                : SearchCriteria.withWarehouseId(filter, NO_ACCESS_WAREHOUSE_ID);
        }
        if (scope.warehouseIds().isEmpty()) {
            return SearchCriteria.withWarehouseId(filter, NO_ACCESS_WAREHOUSE_ID);
        }
        Set<UUID> allowed = new java.util.LinkedHashSet<>(scope.warehouseIds());
        if (filter.hasWarehouseIds()) allowed.retainAll(filter.warehouseIds());
        if (allowed.isEmpty()) return SearchCriteria.withWarehouseId(filter, NO_ACCESS_WAREHOUSE_ID);
        // Multi-aware queries consume warehouseIds; unknown legacy queries still fail closed.
        return SearchCriteria.withWarehouseScope(filter, NO_ACCESS_WAREHOUSE_ID, allowed);
    }

    public UUID effectiveWarehouseId(UUID requestedWarehouseId) {
        DataScope scope = currentScope();
        if (!scope.warehouseScoped()) {
            return requestedWarehouseId;
        }
        UUID effective = requestedWarehouseId == null ? scope.warehouseId() : requestedWarehouseId;
        if (effective == null || !scope.warehouseIds().contains(effective)) {
            return NO_ACCESS_WAREHOUSE_ID;
        }
        return effective;
    }

    public boolean canAccessWarehouse(UUID... warehouseIds) {
        DataScope scope = currentScope();
        if (!scope.warehouseScoped()) {
            return true;
        }
        if (scope.warehouseIds().isEmpty()) {
            return false;
        }
        return java.util.Arrays.stream(warehouseIds)
            .filter(Objects::nonNull)
            .anyMatch(scope.warehouseIds()::contains);
    }

    /** Check before mutating a warehouse-owned record, not after serializing its response. */
    public void assertWarehouseAccess(UUID warehouseId) {
        if (!canAccessWarehouse(warehouseId)) {
            throw new com.enterprise.mm.error.NotFoundException("Record is outside your assigned warehouse");
        }
    }

    public DataScope currentScope() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return DataScope.unrestricted();
        }
        Set<String> roles = normalizedRoles(authentication);
        boolean warehouseScoped = roles.stream().anyMatch(WAREHOUSE_SCOPED_ROLES::contains);
        boolean documentUnrestricted = roles.stream().anyMatch(UNRESTRICTED_ROLES::contains);
        if (roles.stream().anyMatch(GLOBAL_WAREHOUSE_BYPASS_ROLES::contains)
            || (documentUnrestricted && !warehouseScoped)) {
            return DataScope.unrestricted();
        }
        String username = blankToNull(authentication.getName());
        User user = username == null
            ? null
            : userRepository.findByTenantIdAndUsernameAndIsDeletedFalse(TenantContext.currentTenant(), username).orElse(null);
        Employee employee = resolveCurrentEmployee(username, user);
        boolean selfScoped = !documentUnrestricted && roles.stream().anyMatch(SELF_SCOPED_ROLES::contains);
        if (employee == null) {
            UUID departmentId = userDepartmentId(user);
            Set<UUID> warehouseIds = warehouseIdsFor(departmentId, null);
            return selfScoped || warehouseScoped
                ? new DataScope(selfScoped, false, warehouseScoped, userPrincipals(user, username),
                    userPrincipals(user, username), Set.of(), departmentId,
                    selectedWarehouseId(warehouseScoped, warehouseIds), warehouseIds)
                : DataScope.unrestricted();
        }
        Set<String> ownPrincipals = employeePrincipals(employee, user, username);
        List<Department> managedDepartments = documentUnrestricted
            ? List.of()
            : departmentRepository.findByTenantIdAndCompanyIdAndManagerEmployee_IdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                employee.getId()
            );
        Set<UUID> managedDepartmentIds = managedDepartments.stream()
            .map(Department::getId)
            .filter(Objects::nonNull)
            .collect(LinkedHashSet::new, Set::add, Set::addAll);
        Set<String> teamPrincipals = new LinkedHashSet<>(ownPrincipals);
        if (!managedDepartmentIds.isEmpty()) {
            List<Employee> teamEmployees = employeeRepository.findByTenantIdAndCompanyIdAndDepartment_IdInAndIsDeletedFalse(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                new ArrayList<>(managedDepartmentIds)
            );
            for (Employee teamEmployee : teamEmployees) {
                teamPrincipals.addAll(employeePrincipals(teamEmployee, null, null));
                String email = blankToNull(teamEmployee.getEmail());
                if (email != null) {
                    userRepository.findByTenantIdAndCompanyIdAndEmailIgnoreCaseAndIsDeletedFalse(
                        TenantContext.currentTenant(),
                        CompanyContext.currentCompany(),
                        email
                    ).forEach(teamUser -> {
                        addIfPresent(teamPrincipals, teamUser.getUsername());
                        addIfPresent(teamPrincipals, teamUser.getEmail());
                    });
                }
            }
        }
        UUID departmentId = employee.getDepartment() == null ? userDepartmentId(user) : employee.getDepartment().getId();
        UUID legacyWarehouseId = employee.getWarehouse() == null ? null : employee.getWarehouse().getId();
        Set<UUID> warehouseIds = warehouseIdsFor(departmentId, roles.contains("DEPARTMENT_HEAD") ? null : legacyWarehouseId);
        return new DataScope(
            selfScoped,
            !managedDepartmentIds.isEmpty(),
            warehouseScoped,
            ownPrincipals,
            teamPrincipals,
            managedDepartmentIds,
            departmentId,
            selectedWarehouseId(warehouseScoped, warehouseIds),
            warehouseIds
        );
    }

    public <T> Page<T> page(List<T> records, Pageable pageable) {
        if (pageable.isUnpaged()) {
            return new PageImpl<>(records);
        }
        int fromIndex = Math.toIntExact(pageable.getOffset());
        if (fromIndex >= records.size()) {
            return new PageImpl<>(List.of(), pageable, records.size());
        }
        int toIndex = Math.min(fromIndex + pageable.getPageSize(), records.size());
        return new PageImpl<>(records.subList(fromIndex, toIndex), pageable, records.size());
    }

    public <T, R> Page<R> filterAndMap(
        List<T> records,
        Pageable pageable,
        Predicate<T> predicate,
        Function<T, R> mapper
    ) {
        List<R> filtered = records.stream()
            .filter(predicate)
            .map(mapper)
            .toList();
        return page(filtered, pageable);
    }

    public boolean canAccessDocument(String createdBy, String owner, UUID departmentId) {
        DataScope scope = currentScope();
        return canAccessDocument(scope, createdBy, owner, departmentId);
    }

    public void assertDepartmentDocumentMutationAllowed(UUID departmentId, String documentName) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return;
        }
        Set<String> roles = normalizedRoles(authentication);
        if (roles.stream().anyMatch(DEPARTMENT_MUTATION_BYPASS_ROLES::contains)) {
            return;
        }
        if (roles.stream().noneMatch(OWN_DEPARTMENT_ONLY_ROLES::contains)) {
            return;
        }
        DataScope scope = currentScope();
        boolean permitted = departmentId != null
            && Objects.equals(departmentId, scope.departmentId());
        if (!permitted) {
            throw new BusinessException(documentName + " can be created or edited only in the user's permitted department");
        }
    }

    public boolean canAccessPayload(Object payload) {
        if (payload instanceof DemandPlanningCycleResponse cycle && isDepartmentIssueScoped()) {
            return canParticipateInCycle(cycle);
        }
        DataScope scope = currentScope();
        if (payload == null || !scope.hasAnyScope()) {
            return true;
        }
        if (scope.warehouseScoped()) {
            List<UUID> warehouseIds = new ArrayList<>();
            addIfPresent(warehouseIds, readUuid(payload, "warehouseId"));
            addIfPresent(warehouseIds, readUuid(payload, "targetWarehouseId"));
            if (!warehouseIds.isEmpty()) {
                return warehouseIds.stream().anyMatch(scope.warehouseIds()::contains);
            }
        }
        if (scope.hasDocumentScope() && hasDocumentScopeProperties(payload)) {
            if (canReadSharedProcurementPlanningPayload(payload)) {
                return true;
            }
            return canAccessDocument(
                scope,
                readString(payload, "createdBy"),
                firstNonBlank(readString(payload, "requestedBy"), readString(payload, "ownerUsername")),
                readUuid(payload, "departmentId")
            );
        }
        return true;
    }

    private boolean canAccessDocument(DataScope scope, String createdBy, String owner, UUID departmentId) {
        if (!scope.hasDocumentScope()) {
            return true;
        }
        if (isOwnDepartmentOnlyScope()) {
            if (departmentId != null) {
                return Objects.equals(departmentId, scope.departmentId());
            }
            return scope.matchesOwnPrincipal(createdBy) || scope.matchesOwnPrincipal(owner);
        }
        return scope.matchesOwnPrincipal(createdBy)
            || scope.matchesOwnPrincipal(owner)
            || scope.matchesTeamPrincipal(createdBy)
            || scope.matchesTeamPrincipal(owner)
            || scope.matchesDepartment(departmentId);
    }

    private boolean isOwnDepartmentOnlyScope() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }
        Set<String> roles = normalizedRoles(authentication);
        return roles.stream().anyMatch(OWN_DEPARTMENT_ONLY_ROLES::contains)
            && roles.stream().noneMatch(DEPARTMENT_MUTATION_BYPASS_ROLES::contains);
    }

    private boolean canReadSharedProcurementPlanningPayload(Object payload) {
        if (!(payload instanceof DemandPlanningCycleResponse)) {
            return false;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }
        return normalizedRoles(authentication).stream().anyMatch(PROCUREMENT_PLANNING_ROLES::contains);
    }

    private boolean hasDocumentScopeProperties(Object payload) {
        return hasReadableProperty(payload, "departmentId")
            || hasReadableProperty(payload, "requestedBy")
            || hasReadableProperty(payload, "ownerUsername");
    }

    private boolean hasReadableProperty(Object payload, String propertyName) {
        return getter(payload.getClass(), propertyName) != null;
    }

    private UUID readUuid(Object payload, String propertyName) {
        Object value = readProperty(payload, propertyName);
        return value instanceof UUID uuid ? uuid : null;
    }

    private String readString(Object payload, String propertyName) {
        Object value = readProperty(payload, propertyName);
        return value instanceof String string ? string : null;
    }

    private Object readProperty(Object payload, String propertyName) {
        Method method = getter(payload.getClass(), propertyName);
        if (method == null) {
            return null;
        }
        try {
            return method.invoke(payload);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private Method getter(Class<?> type, String propertyName) {
        String suffix = propertyName.substring(0, 1).toUpperCase(Locale.ROOT) + propertyName.substring(1);
        try {
            return type.getMethod("get" + suffix);
        } catch (NoSuchMethodException ignored) {
            try {
                return type.getMethod("is" + suffix);
            } catch (NoSuchMethodException ignoredAgain) {
                return null;
            }
        }
    }

    private String firstNonBlank(String first, String second) {
        String normalizedFirst = blankToNull(first);
        return normalizedFirst == null ? blankToNull(second) : normalizedFirst;
    }

    private Employee resolveCurrentEmployee(String username, User user) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        if (user != null && user.getEmployee() != null && !user.getEmployee().isDeleted()
            && Objects.equals(user.getEmployee().getTenantId(), tenantId)
            && Objects.equals(user.getEmployee().getCompanyId(), companyId)) {
            return user.getEmployee();
        }
        String userEmail = user == null ? null : blankToNull(user.getEmail());
        if (userEmail != null) {
            Employee byEmail = employeeRepository.findByTenantIdAndCompanyIdAndEmailIgnoreCaseAndIsDeletedFalse(tenantId, companyId, userEmail).orElse(null);
            if (byEmail != null) {
                return byEmail;
            }
        }
        if (username != null && username.contains("@")) {
            Employee byUsernameEmail = employeeRepository.findByTenantIdAndCompanyIdAndEmailIgnoreCaseAndIsDeletedFalse(tenantId, companyId, username).orElse(null);
            if (byUsernameEmail != null) {
                return byUsernameEmail;
            }
        }
        if (username != null) {
            Employee byPersonnel = employeeRepository.findByTenantIdAndPersonnelNumberAndIsDeletedFalse(tenantId, username).orElse(null);
            if (byPersonnel != null && Objects.equals(byPersonnel.getCompanyId(), companyId)) {
                return byPersonnel;
            }
            Employee byCode = employeeRepository.findByTenantIdAndCodeAndIsDeletedFalse(tenantId, username).orElse(null);
            if (byCode != null && Objects.equals(byCode.getCompanyId(), companyId)) {
                return byCode;
            }
            Employee byExternal = employeeRepository.findByTenantIdAndExternalIdAndIsDeletedFalse(tenantId, username).orElse(null);
            if (byExternal != null && Objects.equals(byExternal.getCompanyId(), companyId)) {
                return byExternal;
            }
        }
        return null;
    }

    private UUID userDepartmentId(User user) {
        return user == null || user.getDepartment() == null ? null : user.getDepartment().getId();
    }

    private Set<UUID> warehouseIdsFor(UUID departmentId, UUID legacyWarehouseId) {
        // A direct employee assignment is authoritative. Department grants are only the fallback.
        if (legacyWarehouseId != null) {
            return Set.of(legacyWarehouseId);
        }
        if (departmentId != null) {
            Set<UUID> mapped = departmentWarehouseAccessRepository.findActiveWarehouseIds(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), departmentId);
            return mapped == null ? Set.of() : Set.copyOf(mapped);
        }
        return Set.of();
    }

    private UUID selectedWarehouseId(boolean warehouseScoped, Set<UUID> warehouseIds) {
        if (!warehouseScoped || warehouseIds == null || warehouseIds.isEmpty()) {
            return null;
        }
        return warehouseIds.size() == 1 ? warehouseIds.iterator().next() : null;
    }

    private Set<String> normalizedRoles(Authentication authentication) {
        Set<String> roles = new LinkedHashSet<>();
        authentication.getAuthorities().forEach(authority -> {
            String value = blankToNull(authority == null ? null : authority.getAuthority());
            if (value != null) {
                roles.add(value.replaceFirst("^ROLE_", "").toUpperCase(Locale.ROOT));
            }
        });
        return roles;
    }

    private Set<String> employeePrincipals(Employee employee, User user, String username) {
        Set<String> values = userPrincipals(user, username);
        addIfPresent(values, employee.getEmail());
        addIfPresent(values, employee.getPersonnelNumber());
        addIfPresent(values, employee.getExternalId());
        addIfPresent(values, employee.getCode());
        addIfPresent(values, employee.getName());
        return values;
    }

    private Set<String> userPrincipals(User user, String username) {
        Set<String> values = new LinkedHashSet<>();
        addIfPresent(values, username);
        if (user != null) {
            addIfPresent(values, user.getUsername());
            addIfPresent(values, user.getEmail());
            addIfPresent(values, user.getFullName());
            addIfPresent(values, user.getName());
        }
        return values;
    }

    private void addIfPresent(Collection<String> values, String candidate) {
        String normalized = blankToNull(candidate);
        if (normalized != null) {
            values.add(normalized.toLowerCase(Locale.ROOT));
        }
    }

    private void addIfPresent(Collection<UUID> values, UUID candidate) {
        if (candidate != null) {
            values.add(candidate);
        }
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public record DataScope(
        boolean selfScoped,
        boolean departmentManagerScoped,
        boolean warehouseScoped,
        Set<String> ownPrincipals,
        Set<String> teamPrincipals,
        Set<UUID> managedDepartmentIds,
        UUID departmentId,
        UUID warehouseId,
        Set<UUID> warehouseIds
    ) {
        public DataScope {
            ownPrincipals = ownPrincipals == null ? Set.of() : Set.copyOf(ownPrincipals);
            teamPrincipals = teamPrincipals == null ? Set.of() : Set.copyOf(teamPrincipals);
            managedDepartmentIds = managedDepartmentIds == null ? Set.of() : Set.copyOf(managedDepartmentIds);
            warehouseIds = warehouseIds == null ? Set.of() : Set.copyOf(warehouseIds);
        }

        public DataScope(
            boolean selfScoped,
            boolean departmentManagerScoped,
            boolean warehouseScoped,
            Set<String> ownPrincipals,
            Set<String> teamPrincipals,
            Set<UUID> managedDepartmentIds,
            UUID departmentId,
            UUID warehouseId
        ) {
            this(selfScoped, departmentManagerScoped, warehouseScoped, ownPrincipals, teamPrincipals,
                managedDepartmentIds, departmentId, warehouseId,
                warehouseId == null ? Set.of() : Set.of(warehouseId));
        }

        public static DataScope unrestricted() {
            return new DataScope(false, false, false, Set.of(), Set.of(), Set.of(), null, null, Set.of());
        }

        public static DataScope restricted(boolean selfScoped, boolean warehouseScoped) {
            return new DataScope(selfScoped, false, warehouseScoped, Set.of(), Set.of(), Set.of(), null, null);
        }

        public static DataScope restricted(boolean selfScoped, boolean warehouseScoped, Set<String> ownPrincipals) {
            return restricted(selfScoped, warehouseScoped, ownPrincipals, null);
        }

        public static DataScope restricted(boolean selfScoped, boolean warehouseScoped, Set<String> ownPrincipals, UUID departmentId) {
            Set<String> principals = ownPrincipals == null ? Set.of() : Set.copyOf(ownPrincipals);
            return new DataScope(selfScoped, false, warehouseScoped, principals, principals, Set.of(), departmentId, null, Set.of());
        }

        public boolean hasAnyScope() {
            return hasDocumentScope() || warehouseScoped;
        }

        public boolean hasDocumentScope() {
            return selfScoped || departmentManagerScoped;
        }

        public boolean matchesOwnPrincipal(String value) {
            return matches(value, ownPrincipals);
        }

        public boolean matchesTeamPrincipal(String value) {
            return matches(value, teamPrincipals);
        }

        public boolean matchesDepartment(UUID value) {
            if (value == null) {
                return false;
            }
            return departmentManagerScoped && managedDepartmentIds.contains(value);
        }

        private boolean matches(String value, Set<String> principals) {
            if (value == null || principals.isEmpty()) {
                return false;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            return principals.contains(normalized);
        }
    }
}
