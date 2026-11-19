package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ManagementOpportunityPolicy;
import com.enterprise.mm.enums.ManagementOpportunityType;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.ManagementOpportunityPolicyMapper;
import com.enterprise.mm.payload.ManagementOpportunityPolicyCreateRequest;
import com.enterprise.mm.payload.ManagementOpportunityPolicyResolveRequest;
import com.enterprise.mm.payload.ManagementOpportunityPolicyResolveResponse;
import com.enterprise.mm.payload.ManagementOpportunityPolicyResponse;
import com.enterprise.mm.payload.ManagementOpportunityPolicyUpdateRequest;
import com.enterprise.mm.repository.ManagementOpportunityPolicyRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ManagementOpportunityPolicyService {
    private static final String ENTITY_NAME = "ManagementOpportunityPolicy";

    private final ManagementOpportunityPolicyRepository repository;
    private final ManagementOpportunityPolicyMapper mapper;
    private final AuditService auditService;

    public ManagementOpportunityPolicyService(
        ManagementOpportunityPolicyRepository repository,
        ManagementOpportunityPolicyMapper mapper,
        AuditService auditService
    ) {
        this.repository = repository;
        this.mapper = mapper;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public Page<ManagementOpportunityPolicyResponse> search(
        String search,
        List<String> status,
        Boolean active,
        ManagementOpportunityType type,
        boolean includeDeleted,
        Pageable pageable
    ) {
        return repository.search(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            includeDeleted,
            active,
            type,
            searchPattern(search),
            status == null ? List.of() : status,
            status == null || status.isEmpty(),
            pageable
        ).map(mapper::toResponse);
    }

    @Transactional(readOnly = true)
    public ManagementOpportunityPolicyResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }

    @Transactional
    public ManagementOpportunityPolicyResponse create(ManagementOpportunityPolicyCreateRequest request) {
        validateRequest(request);
        ManagementOpportunityPolicy entity = mapper.toEntity(request);
        beforeCreate(entity);
        ManagementOpportunityPolicy saved = repository.save(entity);
        auditService.log(ENTITY_NAME, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }

    @Transactional
    public ManagementOpportunityPolicyResponse update(UUID id, ManagementOpportunityPolicyUpdateRequest request) {
        ManagementOpportunityPolicy entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        if (entity.isActive()) {
            throw new BusinessException("Active management opportunity policy cannot be modified. Deactivate it first.");
        }
        validateRequest(request);
        mapper.updateEntity(entity, request);
        normalize(entity);
        ManagementOpportunityPolicy saved = repository.save(entity);
        auditService.log(ENTITY_NAME, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }

    @Transactional
    public ManagementOpportunityPolicyResponse activate(UUID id) {
        ManagementOpportunityPolicy entity = findEntity(id);
        validateEntity(entity);
        validateOverlap(entity);
        entity.setActive(true);
        entity.setStatus("ACTIVE");
        entity.setActivatedAt(LocalDateTime.now());
        entity.setActivatedBy(currentUsername());
        ManagementOpportunityPolicy saved = repository.save(entity);
        auditService.log(ENTITY_NAME, saved.getId(), "ACTIVATE", saved.getCode(), null, null);
        return mapper.toResponse(saved);
    }

    @Transactional
    public ManagementOpportunityPolicyResponse deactivate(UUID id) {
        ManagementOpportunityPolicy entity = findEntity(id);
        entity.setActive(false);
        entity.setStatus("INACTIVE");
        ManagementOpportunityPolicy saved = repository.save(entity);
        auditService.log(ENTITY_NAME, saved.getId(), "DEACTIVATE", saved.getCode(), null, null);
        return mapper.toResponse(saved);
    }

    @Transactional(readOnly = true, noRollbackFor = BusinessException.class)
    public ManagementOpportunityPolicy resolveActivePolicy(
        ManagementOpportunityType type,
        ManagementOpportunityScope scope,
        LocalDate asOfDate
    ) {
        if (type == null) {
            throw new BusinessException("Management opportunity type is required");
        }
        LocalDate date = asOfDate == null ? LocalDate.now() : asOfDate;
        ManagementOpportunityScope normalizedScope = scope == null ? new ManagementOpportunityScope(null, null, null) : scope;
        return repository.findActivePoliciesForResolution(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                type,
                date
            ).stream()
            .filter(policy -> matches(policy, normalizedScope))
            .sorted(resolutionComparator())
            .findFirst()
            .orElseThrow(() -> new BusinessException("No active management opportunity policy matched the supplied scope"));
    }

    @Transactional(readOnly = true)
    public ManagementOpportunityPolicyResolveResponse resolvePreview(ManagementOpportunityPolicyResolveRequest request) {
        ManagementOpportunityPolicyResolveResponse response = new ManagementOpportunityPolicyResolveResponse();
        try {
            ManagementOpportunityPolicy policy = resolveActivePolicy(
                request.getOpportunityType(),
                new ManagementOpportunityScope(request.getWarehouseId(), request.getMaterialGroupId(), request.getSupplierId()),
                request.getAsOfDate()
            );
            response.setMatched(true);
            response.setResolutionReason("Most specific active policy resolved successfully");
            response.setPolicy(mapper.toResponse(policy));
            return response;
        } catch (BusinessException exception) {
            response.setMatched(false);
            response.setResolutionReason(exception.getMessage());
            return response;
        }
    }

    private void beforeCreate(ManagementOpportunityPolicy entity) {
        normalize(entity);
        entity.setStatus("DRAFT");
        entity.setActive(false);
        if (entity.getCode() == null || entity.getCode().isBlank()) {
            entity.setCode("MO-POLICY-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        }
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
        if (entity.getCompanyId() == null) {
            entity.setCompanyId(CompanyContext.currentCompany());
        }
    }

    private void normalize(ManagementOpportunityPolicy entity) {
        if (entity.getCurrencyCode() != null && !entity.getCurrencyCode().isBlank()) {
            entity.setCurrencyCode(entity.getCurrencyCode().trim().toUpperCase(Locale.ROOT));
        }
        if (entity.getCode() != null) {
            entity.setCode(blankToNull(entity.getCode()));
        }
    }

    private void validateRequest(ManagementOpportunityPolicyCreateRequest request) {
        if (request == null) {
            throw new BusinessException("Management opportunity policy request is required");
        }
        if (request.getName() == null || request.getName().isBlank()) {
            throw new BusinessException("Policy name is required");
        }
        if (request.getOpportunityType() == null) {
            throw new BusinessException("Management opportunity type is required");
        }
        validateDateRange(request.getEffectiveFrom(), request.getEffectiveTo());
        validateRatio(request.getAnnualCarryingCostRate(), "Annual carrying cost rate");
        validateRatio(request.getAttributableShare(), "Attributable share");
        validatePositive(request.getDailyImpactRate(), "Daily impact rate");
        if (request.getCurrencyCode() != null && !request.getCurrencyCode().isBlank()
            && request.getCurrencyCode().trim().length() != 3) {
            throw new BusinessException("currencyCode must be a 3-letter ISO code");
        }
        validateTypeRequirements(request.getOpportunityType(), request.getAnnualCarryingCostRate(), request.getDailyImpactRate(), request.getCurrencyCode());
    }

    private void validateEntity(ManagementOpportunityPolicy entity) {
        validateDateRange(entity.getEffectiveFrom(), entity.getEffectiveTo());
        validateRatio(entity.getAnnualCarryingCostRate(), "Annual carrying cost rate");
        validateRatio(entity.getAttributableShare(), "Attributable share");
        validatePositive(entity.getDailyImpactRate(), "Daily impact rate");
        validateTypeRequirements(entity.getOpportunityType(), entity.getAnnualCarryingCostRate(), entity.getDailyImpactRate(), entity.getCurrencyCode());
    }

    private void validateTypeRequirements(
        ManagementOpportunityType type,
        BigDecimal annualCarryingCostRate,
        BigDecimal dailyImpactRate,
        String currencyCode
    ) {
        if (type == ManagementOpportunityType.EXCESS_CARRYING_COST && annualCarryingCostRate == null) {
            throw new BusinessException("Annual carrying cost rate is required for excess carrying cost policy");
        }
        if (type == ManagementOpportunityType.SUPPLIER_DELAY_EXPOSURE) {
            if (dailyImpactRate == null) {
                throw new BusinessException("Daily impact rate is required for supplier delay exposure policy");
            }
            if (currencyCode == null || currencyCode.isBlank()) {
                throw new BusinessException("currencyCode is required for supplier delay exposure policy");
            }
        }
    }

    private void validateDateRange(LocalDate effectiveFrom, LocalDate effectiveTo) {
        if (effectiveFrom == null) {
            throw new BusinessException("Effective from date is required");
        }
        if (effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
            throw new BusinessException("Effective to date cannot be earlier than effective from date");
        }
    }

    private void validateRatio(BigDecimal value, String label) {
        if (value != null && (value.signum() <= 0 || value.compareTo(BigDecimal.ONE) > 0)) {
            throw new BusinessException(label + " must be in (0, 1]");
        }
    }

    private void validatePositive(BigDecimal value, String label) {
        if (value != null && value.signum() <= 0) {
            throw new BusinessException(label + " must be greater than zero");
        }
    }

    private void validateOverlap(ManagementOpportunityPolicy candidate) {
        for (ManagementOpportunityPolicy existing : repository.findActivePolicies(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            candidate.getOpportunityType(),
            candidate.getId()
        )) {
            if (datesOverlap(candidate, existing) && sameScope(candidate, existing)) {
                throw new BusinessException("Active management opportunity policy overlaps with existing active policy " + existing.getCode());
            }
        }
    }

    private boolean matches(ManagementOpportunityPolicy policy, ManagementOpportunityScope scope) {
        return matchesDimension(policy.getSupplierId(), scope.supplierId())
            && matchesDimension(policy.getMaterialGroupId(), scope.materialGroupId())
            && matchesDimension(policy.getWarehouseId(), scope.warehouseId());
    }

    private boolean sameScope(ManagementOpportunityPolicy left, ManagementOpportunityPolicy right) {
        return Objects.equals(left.getSupplierId(), right.getSupplierId())
            && Objects.equals(left.getMaterialGroupId(), right.getMaterialGroupId())
            && Objects.equals(left.getWarehouseId(), right.getWarehouseId());
    }

    private boolean datesOverlap(ManagementOpportunityPolicy left, ManagementOpportunityPolicy right) {
        LocalDate leftEnd = left.getEffectiveTo() == null ? LocalDate.of(9999, 12, 31) : left.getEffectiveTo();
        LocalDate rightEnd = right.getEffectiveTo() == null ? LocalDate.of(9999, 12, 31) : right.getEffectiveTo();
        return !left.getEffectiveFrom().isAfter(rightEnd) && !right.getEffectiveFrom().isAfter(leftEnd);
    }

    private Comparator<ManagementOpportunityPolicy> resolutionComparator() {
        return Comparator
            .comparingInt((ManagementOpportunityPolicy policy) -> policy.getSupplierId() == null ? 0 : 1).reversed()
            .thenComparing(Comparator.comparingInt((ManagementOpportunityPolicy policy) -> policy.getMaterialGroupId() == null ? 0 : 1).reversed())
            .thenComparing(Comparator.comparingInt((ManagementOpportunityPolicy policy) -> policy.getWarehouseId() == null ? 0 : 1).reversed())
            .thenComparing(ManagementOpportunityPolicy::getEffectiveFrom, Comparator.reverseOrder())
            .thenComparing(ManagementOpportunityPolicy::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()));
    }

    private <T> boolean matchesDimension(T policyValue, T requestValue) {
        return policyValue == null || Objects.equals(policyValue, requestValue);
    }

    private ManagementOpportunityPolicy findEntity(UUID id) {
        ManagementOpportunityPolicy entity = repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException(ENTITY_NAME + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(ENTITY_NAME + " not found: " + id);
        }
        if (!Objects.equals(CompanyContext.currentCompany(), entity.getCompanyId())) {
            throw new NotFoundException(ENTITY_NAME + " not found: " + id);
        }
        return entity;
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String searchPattern(String value) {
        String normalized = blankToNull(value);
        return normalized == null ? null : "%" + normalized.toLowerCase(Locale.ROOT) + "%";
    }

    private String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? null : authentication.getName();
    }
}
