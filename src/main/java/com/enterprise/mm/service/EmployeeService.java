package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.payload.EmployeeResponse;
import com.enterprise.mm.payload.EmployeeUpdateRequest;
import com.enterprise.mm.payload.EmployeeCreateRequest;
import com.enterprise.mm.payload.EmployeeSearchRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.EmployeeMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Employee;
import com.enterprise.mm.entity.Position;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.enums.EmployeeStatus;
import com.enterprise.mm.enums.RoleName;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.repository.EmployeeRepository;
import com.enterprise.mm.repository.PositionRepository;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EmployeeService {
    private final EmployeeRepository repository;
    private final PositionRepository positionRepository;
    private final UserRepository userRepository;
    private final WarehouseRepository warehouseRepository;
    private final EmployeeMapper mapper;
    private final String entityName = "Employee";
    private final AuditService auditService;
    protected void beforeCreate(Employee entity, EmployeeCreateRequest dto) {
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

        normalizeEmployee(entity);
        applyWarehouseAssignment(entity, dto);
        validatePositionCapacity(entity, null);
        entity.setRoles(resolveRoles(dto));
    }
    protected void beforeUpdate(Employee entity, EmployeeUpdateRequest dto) {
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));

        normalizeEmployee(entity);
        applyWarehouseAssignment(entity, dto);
        validatePositionCapacity(entity, entity.getId());
        if (dto.getRoles() != null) {
            entity.setRoles(resolveRoles(dto));
        }
    }
    private void normalizeEmployee(Employee entity) {
        if (entity.getEmploymentStatus() == null) {
            entity.setEmploymentStatus(EmployeeStatus.ACTIVE);
        }
        if (entity.getPersonnelNumber() != null && (entity.getCode() == null || entity.getCode().isBlank())) {
            entity.setCode(entity.getPersonnelNumber());
        }
    }

    private void applyWarehouseAssignment(Employee employee, EmployeeCreateRequest request) {
        // Older clients omit this field: preserve assignments made through the warehouse form.
        // An explicit null clears the assignment.
        if (!request.isWarehouseAssignmentProvided()) return;
        if (request.getWarehouseId() == null) {
            employee.setWarehouse(null);
            return;
        }
        var warehouse = warehouseRepository.findByIdAndIsDeletedFalse(request.getWarehouseId())
            .filter(item -> TenantContext.currentTenant().equals(item.getTenantId()))
            .filter(item -> Objects.equals(employee.getCompanyId(), item.getCompanyId()))
            .filter(item -> "ACTIVE".equals(item.getStatus()))
            .orElseThrow(() -> new BadRequestException("Assigned warehouse must be active and belong to the employee company"));
        employee.setWarehouse(warehouse);
    }
    private Set<RoleName> resolveRoles(EmployeeCreateRequest dto) {
        Set<RoleName> roles = new HashSet<>();
        if (dto.getRoles() != null) {
            dto.getRoles().forEach(role -> parseRole(role).ifPresent(roles::add));
        }
        return roles;
    }

    private Optional<RoleName> parseRole(String role) {
        if (role == null || role.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(RoleName.valueOf(role.trim().toUpperCase(Locale.ROOT).replace("ROLE_", "")));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
    @Transactional(readOnly = true)
    public Page<EmployeeResponse> search(EmployeeSearchRequest request, Pageable pageable) {
        SearchCriteria filter = SearchCriteria.of(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        return repository.search(
            TenantContext.currentTenant(),
            scoped ? CompanyContext.currentCompany() : null,
            filter.includeDeleted(),
            filter.normalizedSearch(),
            filter.status(),
            !filter.hasStatus(),
            filter.fromDateTime(),
            filter.toDateTime(),
            normalizeEmploymentStatus(request == null ? null : request.employmentStatus()),
            pageable
        ).map(mapper::toResponse);
    }
    @Transactional(readOnly = true)
    public EmployeeResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public EmployeeResponse create(EmployeeCreateRequest dto) {
        Employee entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        Employee saved = repository.save(entity);
        syncUserLink(saved, dto.getUserId());
        syncPositionOccupancy(saved.getPosition());
        auditService.logInCurrentTransaction(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public EmployeeResponse update(UUID id, EmployeeUpdateRequest dto) {
        Employee entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        Position previousPosition = entity.getPosition();
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        Employee saved = repository.save(entity);
        syncUserLink(saved, dto.getUserId());
        syncPositionOccupancy(previousPosition);
        syncPositionOccupancy(saved.getPosition());
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        Employee entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        Position previousPosition = entity.getPosition();
        entity.softDelete();
        repository.save(entity);
        syncPositionOccupancy(previousPosition);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public EmployeeResponse restore(UUID id) {
        Employee entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        Employee saved = repository.save(entity);
        syncPositionOccupancy(saved.getPosition());
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected Employee findEntity(UUID id) {
        Employee entity = repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));

        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        return entity;
    }
    protected boolean companyScoped() {
        return true;
    }
    private String normalizeEmploymentStatus(String employmentStatus) {
        if (employmentStatus == null || employmentStatus.isBlank()) {
            return null;
        }
        String normalized = employmentStatus.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return EmployeeStatus.valueOf(normalized).name();
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException(
                "Invalid employee employment status filter. Select one of: " + allowedEmploymentStatuses(),
                "employee.employment_status.invalid"
            );
        }
    }

    private String allowedEmploymentStatuses() {
        return Arrays.stream(EmployeeStatus.values())
            .map(Enum::name)
            .collect(java.util.stream.Collectors.joining(", "));
    }

    private void syncUserLink(Employee employee, UUID userId) {
        Optional<User> currentLink = userRepository.findByTenantIdAndEmployee_IdAndIsDeletedFalse(
            TenantContext.currentTenant(),
            employee.getId()
        );
        if (userId == null) {
            currentLink.ifPresent(user -> {
                user.setEmployee(null);
                userRepository.save(user);
            });
            employee.setUser(null);
            return;
        }
        User user = userRepository.findByIdAndIsDeletedFalse(userId)
            .orElseThrow(() -> new NotFoundException("User not found: " + userId));
        if (!TenantContext.currentTenant().equals(user.getTenantId())
            || !Objects.equals(employee.getCompanyId(), user.getCompanyId())
            || !com.enterprise.mm.security.AccessScope.canAccessCompany(user.getCompanyId())) {
            throw new NotFoundException("User not found or does not belong to the employee company: " + userId);
        }
        if (user.getEmployee() != null && !Objects.equals(user.getEmployee().getId(), employee.getId())) {
            throw new BusinessException("User is already attached to another employee: " + userId);
        }
        currentLink
            .filter(existing -> !existing.getId().equals(user.getId()))
            .ifPresent(existing -> {
                existing.setEmployee(null);
                userRepository.save(existing);
            });
        user.setEmployee(employee);
        userRepository.save(user);
        employee.setUser(user);
    }

    private void validatePositionCapacity(Employee entity, UUID employeeId) {
        Position positionRef = entity.getPosition();
        if (positionRef == null || !occupiesHeadcount(entity)) {
            return;
        }
        Position position = positionRepository.findByIdAndIsDeletedFalse(positionRef.getId())
            .orElseThrow(() -> new NotFoundException("Position not found: " + positionRef.getId()));
        if (!TenantContext.currentTenant().equals(position.getTenantId())
            || !Objects.equals(entity.getCompanyId(), position.getCompanyId())) {
            throw new BusinessException("Employee position does not belong to the employee company");
        }
        BigDecimal headcount = defaultHeadcount(position.getHeadcount());
        long occupied = employeeId == null
            ? repository.countOccupiedByPosition(TenantContext.currentTenant(), entity.getCompanyId(), position.getId())
            : repository.countOccupiedByPositionExcludingEmployee(TenantContext.currentTenant(), entity.getCompanyId(), position.getId(), employeeId);
        if (BigDecimal.valueOf(occupied + 1L).compareTo(headcount) > 0) {
            throw new BusinessException("Position headcount exceeded for: " + position.getName());
        }
    }

    private void syncPositionOccupancy(Position positionRef) {
        if (positionRef == null) {
            return;
        }
        positionRepository.findByIdAndIsDeletedFalse(positionRef.getId()).ifPresent(position -> {
            long occupied = repository.countOccupiedByPosition(
                TenantContext.currentTenant(),
                position.getCompanyId(),
                position.getId()
            );
            position.setOccupiedHeadcount(BigDecimal.valueOf(occupied).setScale(4, RoundingMode.HALF_UP));
            positionRepository.save(position);
        });
    }

    private boolean occupiesHeadcount(Employee entity) {
        return entity != null && !entity.isDeleted() && entity.getEmploymentStatus() != EmployeeStatus.DISMISSED;
    }

    private BigDecimal defaultHeadcount(BigDecimal headcount) {
        return headcount == null ? BigDecimal.ONE : headcount;
    }
}
