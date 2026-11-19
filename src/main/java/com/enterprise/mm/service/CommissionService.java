package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.entity.Commission;
import com.enterprise.mm.entity.CommissionMember;
import com.enterprise.mm.entity.Employee;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.TenderCommission;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.enums.CommissionMemberRole;
import com.enterprise.mm.enums.CommissionType;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.CommissionMapper;
import com.enterprise.mm.payload.AssignableRfqResponse;
import com.enterprise.mm.payload.CommissionCreateRequest;
import com.enterprise.mm.payload.CommissionMemberRequest;
import com.enterprise.mm.payload.CommissionMemberResponse;
import com.enterprise.mm.payload.CommissionResponse;
import com.enterprise.mm.payload.CommissionSearchRequest;
import com.enterprise.mm.payload.CommissionUpdateRequest;
import com.enterprise.mm.repository.CommissionMemberRepository;
import com.enterprise.mm.repository.CommissionRepository;
import com.enterprise.mm.repository.EmployeeRepository;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.repository.TenderCommissionRepository;
import com.enterprise.mm.repository.UserRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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
public class CommissionService {
    private final CommissionRepository repository;
    private final CommissionMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final TenderCommissionRepository tenderCommissionRepository;
    private final RFQRepository rfqRepository;
    private final TenderCommissionService tenderCommissionService;
    private final CommissionMapper mapper;
    private final AuditService auditService;
    private final String entityName = "Commission";

    @Transactional(readOnly = true)
    public Page<CommissionResponse> search(CommissionSearchRequest request, Pageable pageable) {
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
            request.type(),
            request.active(),
            pageable
        ).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public CommissionResponse get(UUID id) {
        return toResponse(findEntity(id));
    }

    @Transactional(readOnly = true)
    public Page<AssignableRfqResponse> searchAssignableRfqs(String search, Pageable pageable) {
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        String normalizedSearch = search == null || search.isBlank() ? null : search.trim();
        return rfqRepository.searchUnassignedToTenderCommission(
            TenantContext.currentTenant(),
            scoped ? CompanyContext.currentCompany() : null,
            normalizedSearch,
            pageable
        ).map(this::toAssignableRfq);
    }

    @Transactional
    public CommissionResponse create(CommissionCreateRequest dto) {
        validateOptionalRfq(dto.getType(), dto.getRfqId());
        Commission entity = mapper.toEntity(dto);
        beforeCreate(entity);
        Commission saved = repository.save(entity);
        syncMembers(saved, dto.getMembers());
        maybeAttachRfq(saved, dto.getRfqId());
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return toResponse(saved);
    }

    @Transactional
    public CommissionResponse update(UUID id, CommissionUpdateRequest dto) {
        Commission entity = findEntity(id);
        validateOptionalRfq(dto.getType() == null ? entity.getType() : dto.getType(), dto.getRfqId());
        String oldSnapshot = auditService.snapshot(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity);
        Commission saved = repository.save(entity);
        if (dto.getMembers() != null) {
            syncMembers(saved, dto.getMembers());
        }
        maybeAttachRfq(saved, dto.getRfqId());
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return toResponse(saved);
    }

    @Transactional
    public void softDelete(UUID id) {
        Commission entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }

    @Transactional
    public CommissionResponse restore(UUID id) {
        Commission entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        Commission saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return toResponse(saved);
    }

    Commission findEntity(UUID id) {
        Commission entity = repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        return entity;
    }

    private CommissionResponse toResponse(Commission entity) {
        CommissionResponse response = mapper.toResponse(entity);
        response.setMembers(memberResponses(entity.getId()));
        if (entity.getType() == CommissionType.TENDER) {
            response.setAssignedRfqIds(
                tenderCommissionRepository
                    .findByTenantIdAndCommissionIdAndActiveTrueAndIsDeletedFalse(TenantContext.currentTenant(), entity.getId())
                    .stream()
                    .map(TenderCommission::getRfqId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList()
            );
        } else {
            response.setAssignedRfqIds(List.of());
        }
        return response;
    }

    private AssignableRfqResponse toAssignableRfq(RFQ rfq) {
        AssignableRfqResponse response = new AssignableRfqResponse();
        response.setId(rfq.getId());
        response.setCode(rfq.getCode());
        response.setName(rfq.getName());
        response.setStatus(rfq.getStatus());
        response.setCompanyId(rfq.getCompanyId());
        return response;
    }

    private void validateOptionalRfq(CommissionType type, UUID rfqId) {
        if (rfqId == null) {
            return;
        }
        if (type != CommissionType.TENDER) {
            throw new BadRequestException("rfqId is only allowed for TENDER commissions");
        }
    }

    private void maybeAttachRfq(Commission commission, UUID rfqId) {
        if (rfqId == null) {
            return;
        }
        if (commission.getType() != CommissionType.TENDER) {
            throw new BadRequestException("rfqId is only allowed for TENDER commissions");
        }
        tenderCommissionService.attachCommissionToRfq(rfqId, commission.getId());
    }

    private List<CommissionMemberResponse> memberResponses(UUID commissionId) {
        return memberRepository.findByTenantIdAndCommissionIdAndIsDeletedFalseOrderByCreatedAtAsc(TenantContext.currentTenant(), commissionId)
            .stream()
            .filter(CommissionMember::isActive)
            .map(this::memberResponse)
            .toList();
    }

    private CommissionMemberResponse memberResponse(CommissionMember member) {
        CommissionMemberResponse response = new CommissionMemberResponse();
        response.setId(member.getId());
        response.setUserId(member.getUserId());
        response.setEmployeeId(member.getEmployeeId());
        response.setName(member.getName());
        response.setMemberName(member.getMemberName());
        response.setRole(member.getMemberRole());
        response.setActive(member.isActive());
        return response;
    }

    private void syncMembers(Commission commission, List<CommissionMemberRequest> requestedMembers) {
        List<CommissionMember> existing = memberRepository.findByTenantIdAndCommissionIdAndIsDeletedFalseOrderByCreatedAtAsc(
            TenantContext.currentTenant(),
            commission.getId()
        );
        if (requestedMembers == null || requestedMembers.isEmpty()) {
            throw new BadRequestException("commission members are required");
        }

        java.util.Map<String, CommissionMember> existingByKey = existing.stream()
            .collect(java.util.stream.Collectors.toMap(this::duplicateKey, member -> member));
        Set<String> seenMembers = new LinkedHashSet<>();
        List<CommissionMember> members = new ArrayList<>();
        for (CommissionMemberRequest requestedMember : requestedMembers) {
            if (requestedMember == null) {
                throw new BadRequestException("members cannot contain null values");
            }
            validateMemberReference(commission, requestedMember);
            String duplicateKey = duplicateKey(requestedMember);
            if (!seenMembers.add(duplicateKey)) {
                throw new BadRequestException("Commission member is duplicated: " + duplicateKey);
            }
            User user = optionalUser(requestedMember.userId());
            Employee employee = optionalEmployee(requestedMember.employeeId());
            CommissionMember member = existingByKey.remove(duplicateKey);
            if (member == null) {
                member = createMember(commission, user, employee, requestedMember);
            } else {
                updateMember(member, user, employee, requestedMember);
            }
            members.add(member);
        }
        existingByKey.values().forEach(CommissionMember::softDelete);
        members.addAll(existingByKey.values());
        memberRepository.saveAll(members);
    }

    private CommissionMember createMember(Commission commission, User user, Employee employee, CommissionMemberRequest request) {
        CommissionMember member = new CommissionMember();
        member.setCompanyId(commission.getCompanyId());
        member.setCommissionId(commission.getId());
        member.setUserId(user == null ? null : user.getId());
        member.setEmployeeId(employee == null ? null : employee.getId());
        member.setMemberRole(request.role() == null ? CommissionMemberRole.MEMBER : request.role());
        member.setMemberName(resolveMemberName(user, employee, request.memberName()));
        member.setStatus("ACTIVE");
        member.setCode("COM-MEM-" + UUID.randomUUID().toString().substring(0, 8));
        member.setName(member.getMemberName());
        member.setActive(request.active() == null || request.active());
        return member;
    }

    private void updateMember(CommissionMember member, User user, Employee employee, CommissionMemberRequest request) {
        member.setUserId(user == null ? null : user.getId());
        member.setEmployeeId(employee == null ? null : employee.getId());
        member.setMemberRole(request.role() == null ? CommissionMemberRole.MEMBER : request.role());
        member.setMemberName(resolveMemberName(user, employee, request.memberName()));
        member.setName(member.getMemberName());
        member.setActive(request.active() == null || request.active());
    }

    private void validateMemberReference(Commission commission, CommissionMemberRequest request) {
        if (commission.getType() == CommissionType.INVENTORY && request.userId() == null) {
            throw new BadRequestException("Inventory commission member userId is required");
        }
        if (request.userId() == null && request.employeeId() == null && isBlank(request.memberName())) {
            throw new BadRequestException("Commission member requires userId, employeeId or memberName");
        }
    }

    private String duplicateKey(CommissionMemberRequest request) {
        if (request.userId() != null) {
            return "USER:" + request.userId();
        }
        if (request.employeeId() != null) {
            return "EMPLOYEE:" + request.employeeId();
        }
        return "NAME:" + request.memberName().trim().toLowerCase(Locale.ROOT);
    }

    private String duplicateKey(CommissionMember member) {
        if (member.getUserId() != null) {
            return "USER:" + member.getUserId();
        }
        if (member.getEmployeeId() != null) {
            return "EMPLOYEE:" + member.getEmployeeId();
        }
        return "NAME:" + member.getMemberName().trim().toLowerCase(Locale.ROOT);
    }

    private User optionalUser(UUID userId) {
        if (userId == null) {
            return null;
        }
        User user = userRepository.findByIdAndIsDeletedFalse(userId)
            .orElseThrow(() -> new NotFoundException("Commission member user not found: " + userId));
        if (!TenantContext.currentTenant().equals(user.getTenantId())) {
            throw new NotFoundException("Commission member user not found: " + userId);
        }
        if (user.getCompanyId() != null && !com.enterprise.mm.security.AccessScope.canAccessCompany(user.getCompanyId())) {
            throw new NotFoundException("Commission member user not found: " + userId);
        }
        if (!user.isEnabled()) {
            throw new BadRequestException("Commission member user is disabled: " + userId);
        }
        return user;
    }

    private Employee optionalEmployee(UUID employeeId) {
        if (employeeId == null) {
            return null;
        }
        Employee employee = employeeRepository.findByIdAndIsDeletedFalse(employeeId)
            .orElseThrow(() -> new NotFoundException("Commission member employee not found: " + employeeId));
        if (!TenantContext.currentTenant().equals(employee.getTenantId())) {
            throw new NotFoundException("Commission member employee not found: " + employeeId);
        }
        if (!com.enterprise.mm.security.AccessScope.canAccessCompany(employee.getCompanyId())) {
            throw new NotFoundException("Commission member employee not found: " + employeeId);
        }
        return employee;
    }

    private String resolveMemberName(User user, Employee employee, String requestedName) {
        if (!isBlank(requestedName)) {
            return requestedName.trim();
        }
        if (user != null) {
            return user.getFullName() == null || user.getFullName().isBlank() ? user.getUsername() : user.getFullName();
        }
        if (employee != null) {
            return employee.getName() == null || employee.getName().isBlank() ? employee.getCode() : employee.getName();
        }
        throw new BadRequestException("Commission member name is required");
    }

    private void beforeCreate(Commission entity) {
        normalize(entity);
        applyCreateCompany(entity);
        if (entity.getCode() == null || entity.getCode().isBlank()) {
            entity.setCode(entityName.toUpperCase(Locale.ROOT) + "-" + UUID.randomUUID().toString().substring(0, 8));
        }
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }

    private void beforeUpdate(Commission entity) {
        normalize(entity);
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }

    private void normalize(Commission entity) {
        if (entity.getType() == null) {
            throw new BadRequestException("commission type is required");
        }
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus("ACTIVE");
        }
        if (entity.getValidFrom() != null && entity.getValidTo() != null && entity.getValidTo().isBefore(entity.getValidFrom())) {
            throw new BadRequestException("validTo must be greater than or equal to validFrom");
        }
        LocalDate today = LocalDate.now();
        if (entity.getValidTo() != null && entity.getValidTo().isBefore(today)) {
            entity.setActive(false);
        }
    }

    protected boolean companyScoped() {
        return true;
    }

    private void applyCreateCompany(Commission entity) {
        if (entity.getCompanyId() == null) {
            entity.setCompanyId(CompanyContext.currentCompany());
            return;
        }
        if (!com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new BadRequestException("Company is not accessible: " + entity.getCompanyId());
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
