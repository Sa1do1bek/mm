package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Department;
import com.enterprise.mm.entity.Employee;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.enums.RoleName;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.UserMapper;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.payload.UserCreateRequest;
import com.enterprise.mm.payload.UserResponse;
import com.enterprise.mm.payload.UserUpdateRequest;
import com.enterprise.mm.payload.UserSearchRequest;
import com.enterprise.mm.repository.CompanyRepository;
import com.enterprise.mm.repository.DepartmentRepository;
import com.enterprise.mm.repository.EmployeeRepository;
import com.enterprise.mm.repository.UserRepository;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import io.jsonwebtoken.lang.Assert;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {
    private final UserRepository repository;
    private final UserMapper mapper;
    private final String entityName = "User";
    private final AuditService auditService;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final DepartmentRepository departmentRepository;
    private final EmployeeRepository employeeRepository;
    private final CompanyRepository companyRepository;

    protected void beforeCreate(User entity, UserCreateRequest dto) {
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus("ACTIVE");
        }
        if (entity.getCode() == null || entity.getCode().isBlank()) {
            entity.setCode(entityName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_") + "-" + UUID.randomUUID().toString().substring(0, 8));
        }
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));

        Set<RoleName> roles = resolveRoles(dto);
        applyCreateScope(entity, dto, roles);

        if (dto.getDepartmentId() != null) {
            Department department = departmentRepository.findByIdAndTenantIdAndCompanyIdAndIsDeletedFalse(
                    dto.getDepartmentId(),
                    TenantContext.currentTenant(),
                    entity.getCompanyId()
            ).orElseThrow(() -> new NotFoundException(
                    "Department not found or does not belong to the user's company: " + dto.getDepartmentId()
            ));
            entity.setDepartment(department);
        }
        validateAndAttachEmployee(entity, dto.getEmployeeId());

        entity.setPasswordHash(passwordEncoder.encode(dto.getPassword() == null ? "ChangeMe123!" : dto.getPassword()));
        entity.setRoles(roles);
        validateUniqueUsername(entity);
    }

    protected boolean beforeUpdate(User entity, UserUpdateRequest dto, UUID previousCompanyId) {
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));

        boolean passwordChanged = false;
        if (dto.getPassword() != null && !dto.getPassword().isBlank()) {
            if (!passwordEncoder.matches(dto.getPassword(), entity.getPasswordHash())) {
                passwordPolicy.validateNewPassword(dto.getPassword());
                entity.setPasswordHash(passwordEncoder.encode(dto.getPassword()));
                entity.setTokenVersion(Math.incrementExact(entity.getTokenVersion()));
                passwordChanged = true;
            }
        }
        if (dto.getRoles() != null) {
            entity.setRoles(resolveRoles(dto));
        }

        if (entity.getCompanyId() == null && !isSuperAdminUser(entity)) {
            entity.setCompanyId(CompanyContext.currentCompany());
        }
        boolean companyChanged = !Objects.equals(previousCompanyId, entity.getCompanyId());
        if (entity.getCompanyId() != null && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new BadRequestException("Company is not accessible: " + entity.getCompanyId());
        }
        entity.setSuperAdmin(isSuperAdminUser(entity));
        validateCompany(entity.getCompanyId());
        validateAndAttachDepartment(entity, dto, companyChanged);
        validateAndAttachEmployee(entity, dto.getEmployeeId());
        entity.reconcilePrimaryEmail();
        validateUniqueUsername(entity);
        return passwordChanged;
    }



    public void validateAndAttachDepartment(User user, UserUpdateRequest dto, boolean companyChanged) {
        if (dto.getDepartmentId() != null) {
            Assert.notNull(user.getCompanyId(), "No company found for this user. Before attaching department, user must have a company");
            Department department = departmentRepository.findByIdAndTenantIdAndCompanyIdAndIsDeletedFalse(
                    dto.getDepartmentId(),
                    TenantContext.currentTenant(),
                    user.getCompanyId()
            ).orElseThrow(() -> new NotFoundException(
                    "Department not found or does not belong to the user's company: " + dto.getDepartmentId()
            ));
            user.setDepartment(department);
        } else if (companyChanged) {
            user.setDepartment(null);
        }
    }

    private void validateAndAttachEmployee(User user, UUID employeeId) {
        if (employeeId == null) {
            user.setEmployee(null);
            return;
        }
        if (user.getCompanyId() == null) {
            throw new BadRequestException("Employee can be attached only to a company-scoped user");
        }
        Employee employee = employeeRepository.findByIdAndIsDeletedFalse(employeeId)
            .orElseThrow(() -> new NotFoundException("Employee not found: " + employeeId));
        if (!TenantContext.currentTenant().equals(employee.getTenantId())
            || !Objects.equals(user.getCompanyId(), employee.getCompanyId())
            || !com.enterprise.mm.security.AccessScope.canAccessCompany(employee.getCompanyId())) {
            throw new NotFoundException("Employee not found or does not belong to the user's company: " + employeeId);
        }
        repository.findByTenantIdAndEmployee_IdAndIsDeletedFalse(TenantContext.currentTenant(), employeeId)
            .filter(existing -> user.getId() == null || !existing.getId().equals(user.getId()))
            .ifPresent(existing -> {
                throw new BadRequestException("Employee is already attached to another user: " + employeeId);
            });
        if (employee.getDepartment() != null) {
            if (user.getDepartment() != null
                && !Objects.equals(user.getDepartment().getId(), employee.getDepartment().getId())) {
                throw new BadRequestException("User department must match the linked employee's department");
            }
            user.setDepartment(employee.getDepartment());
        }
        user.setEmployee(employee);
    }

    private Set<RoleName> resolveRoles(UserCreateRequest dto) {
        Set<RoleName> roles = new HashSet<>();
        if (dto.getRoles() != null) {
            dto.getRoles().forEach(role -> parseRole(role).ifPresent(roles::add));
        }
        return roles;
    }

    private java.util.Optional<RoleName> parseRole(String role) {
        if (role == null || role.isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(RoleName.valueOf(role.trim().toUpperCase(Locale.ROOT).replace("ROLE_", "")));
        } catch (IllegalArgumentException ex) {
            return java.util.Optional.empty();
        }
    }

    private void applyCreateScope(User entity, UserCreateRequest dto, Set<RoleName> roles) {
        if (isGlobalSuperAdminCreate(dto, roles)) {
            entity.setCompanyId(null);
            entity.setSuperAdmin(true);
        } else if (entity.getCompanyId() == null) {
            entity.setCompanyId(CompanyContext.currentCompany());
        }
        if (entity.getCompanyId() != null && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new BadRequestException("Company is not accessible: " + entity.getCompanyId());
        }
        validateCompany(entity.getCompanyId());
    }

    private boolean isGlobalSuperAdminCreate(UserCreateRequest dto, Set<RoleName> roles) {
        // Global super-admin only when company is intentionally omitted.
        // If frontend sends companyId, keep company-scoped ADMIN (do not wipe companyId).
        return dto.getCompanyId() == null
            && dto.getDepartmentId() == null
            && dto.getEmployeeId() == null
            && roles.size() == 1
            && roles.contains(RoleName.ADMIN);
    }

    private boolean isSuperAdminUser(User entity) {
        return entity.getCompanyId() == null && entity.getRoles() != null && entity.getRoles().contains(RoleName.ADMIN);
    }

    private void validateCompany(UUID companyId) {
        if (companyId == null) {
            return;
        }
        if (companyRepository.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), companyId).isEmpty()) {
            throw new NotFoundException("Company not found: " + companyId);
        }
    }

    private void validateUniqueUsername(User entity) {
        if (entity.getUsername() == null || entity.getUsername().isBlank()) {
            return;
        }
        var existing = entity.getCompanyId() == null
            ? repository.findByTenantIdAndCompanyIdIsNullAndUsernameAndIsDeletedFalse(TenantContext.currentTenant(), entity.getUsername())
            : repository.findByTenantIdAndCompanyIdAndUsernameAndIsDeletedFalse(TenantContext.currentTenant(), entity.getCompanyId(), entity.getUsername());
        existing
            .filter(user -> entity.getId() == null || !entity.getId().equals(user.getId()))
            .ifPresent(user -> {
                throw new BadRequestException("Username already exists in this scope: " + entity.getUsername());
            });
    }

    @Transactional(readOnly = true)
    public Page<UserResponse> search(UserSearchRequest request, Pageable pageable) {
        SearchCriteria filter = SearchCriteria.of(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        UUID requestedCompanyId = request.companyId();
        UUID effectiveCompanyId = scoped ? CompanyContext.currentCompany() : requestedCompanyId;
        if (requestedCompanyId != null && !com.enterprise.mm.security.AccessScope.canAccessCompany(requestedCompanyId)) {
            throw new BadRequestException("Company is not accessible: " + requestedCompanyId);
        }
        return repository.search(
            TenantContext.currentTenant(),
            effectiveCompanyId,
            filter.includeDeleted(),
            filter.normalizedSearch(),
            filter.status(),
            !filter.hasStatus(),
            filter.fromDateTime(),
            filter.toDateTime(),
            pageable
        ).map(mapper::toResponse);
    }

    @Transactional(readOnly = true)
    public UserResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }

    @Transactional
    public UserResponse create(UserCreateRequest dto) {
        User entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        User saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }

    @Transactional
    public UserResponse update(UUID id, UserUpdateRequest dto) {
        User entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        UUID previousCompanyId = entity.getCompanyId();
        mapper.updateEntity(entity, dto);
        boolean passwordChanged = beforeUpdate(entity, dto, previousCompanyId);
        User saved = repository.save(entity);
        if (passwordChanged) {
            auditService.logInCurrentTransaction(
                entityName,
                saved.getId(),
                "ADMIN_PASSWORD_RESET",
                saved.getCode()
            );
        }
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }

    @Transactional
    public void softDelete(UUID id) {
        User entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }

    @Transactional
    public UserResponse restore(UUID id) {
        User entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId()) && !isSuperAdminUser(entity)) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        User saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }

    protected User findEntity(UUID id) {
        User entity = repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (entity.isDeleted()) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId()) && !isSuperAdminUser(entity)) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        return entity;
    }

    protected boolean companyScoped() {
        return true;
    }
}
