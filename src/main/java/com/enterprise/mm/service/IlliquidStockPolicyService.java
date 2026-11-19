package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.IlliquidStockPolicy;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.IlliquidStockPolicyMapper;
import com.enterprise.mm.payload.IlliquidStockPolicyCreateRequest;
import com.enterprise.mm.payload.IlliquidStockPolicyResolvePreviewRequest;
import com.enterprise.mm.payload.IlliquidStockPolicyResolvePreviewResponse;
import com.enterprise.mm.payload.IlliquidStockPolicyResponse;
import com.enterprise.mm.payload.IlliquidStockPolicyUpdateRequest;
import com.enterprise.mm.repository.IlliquidStockPolicyRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
public class IlliquidStockPolicyService {
    private final IlliquidStockPolicyRepository repository;
    private final IlliquidStockPolicyMapper mapper;
    private final AuditService auditService;
    private final IlliquidStockPolicyResolver policyResolver;
    private static final String ENTITY_NAME = "IlliquidStockPolicy";

    public IlliquidStockPolicyService(
        IlliquidStockPolicyRepository repository,
        IlliquidStockPolicyMapper mapper,
        AuditService auditService,
        IlliquidStockPolicyResolver policyResolver
    ) {
        this.repository = repository;
        this.mapper = mapper;
        this.auditService = auditService;
        this.policyResolver = policyResolver;
    }

    @Transactional(readOnly = true)
    public Page<IlliquidStockPolicyResponse> search(
        String search,
        List<String> status,
        Boolean active,
        boolean includeDeleted,
        Pageable pageable
    ) {
        return repository.search(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            includeDeleted,
            active,
            blankToNull(search),
            status == null ? List.of() : status,
            status == null || status.isEmpty(),
            pageable
        ).map(mapper::toResponse);
    }

    @Transactional(readOnly = true)
    public IlliquidStockPolicyResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }

    @Transactional
    public IlliquidStockPolicyResponse create(IlliquidStockPolicyCreateRequest request) {
        validateDateRange(request.getEffectiveFrom(), request.getEffectiveTo());
        validateMaterialType(request.getMaterialType());
        IlliquidStockPolicy entity = mapper.toEntity(request);
        beforeCreate(entity);

        IlliquidStockPolicy saved = repository.save(entity);
        auditService.log(ENTITY_NAME, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }

    @Transactional
    public IlliquidStockPolicyResponse update(UUID id, IlliquidStockPolicyUpdateRequest request) {
        IlliquidStockPolicy entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        if (entity.isActive()) {
            throw new BusinessException("Active illiquid stock policy cannot be modified. Deactivate it first.");
        }
        validateDateRange(request.getEffectiveFrom(), request.getEffectiveTo());
        validateMaterialType(request.getMaterialType());
        mapper.updateEntity(entity, request);
        beforeUpdate(entity);
        IlliquidStockPolicy saved = repository.save(entity);
        auditService.log(ENTITY_NAME, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }

    private void validateMaterialType(String materialType) {
        if (materialType != null && ("WORKS".equalsIgnoreCase(materialType.trim()) || "WORK".equalsIgnoreCase(materialType.trim()))) {
            throw new BusinessException("WORKS is not a valid material type. Materials must be physical goods or services.");
        }
    }


    @Transactional
    public IlliquidStockPolicyResponse activate(UUID id) {
        IlliquidStockPolicy entity = findEntity(id);
        validateOverlap(entity);
        entity.setActive(true);
        entity.setStatus("ACTIVE");
        entity.setActivatedAt(LocalDateTime.now());
        entity.setActivatedBy(currentUsername());
        IlliquidStockPolicy saved = repository.save(entity);
        auditService.log(ENTITY_NAME, saved.getId(), "ACTIVATE", saved.getCode(), null, null);
        return mapper.toResponse(saved);
    }

    @Transactional
    public IlliquidStockPolicyResponse deactivate(UUID id) {
        IlliquidStockPolicy entity = findEntity(id);
        entity.setActive(false);
        entity.setStatus("INACTIVE");
        IlliquidStockPolicy saved = repository.save(entity);
        auditService.log(ENTITY_NAME, saved.getId(), "DEACTIVATE", saved.getCode(), null, null);
        return mapper.toResponse(saved);
    }

    @Transactional(readOnly = true)
    public IlliquidStockPolicyResolvePreviewResponse resolvePreview(IlliquidStockPolicyResolvePreviewRequest request) {
        var match = policyResolver.resolve(repository.findActivePoliciesForResolution(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            request.getAsOfDate()
        ), request.getLegalEntityCode(), request.getWarehouseId(), request.getMaterialCategory(),
            request.getMaterialType());

        IlliquidStockPolicyResolvePreviewResponse response = new IlliquidStockPolicyResolvePreviewResponse();
        if (match.isEmpty()) {
            response.setMatched(false);
            response.setResolutionReason("No active policy matched the supplied scope");
            return response;
        }
        response.setMatched(true);
        response.setResolutionReason("Most specific active policy resolved successfully");
        response.setPolicy(mapper.toResponse(match.orElseThrow()));
        return response;
    }

    private IlliquidStockPolicy findEntity(UUID id) {
        IlliquidStockPolicy entity = repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException(ENTITY_NAME + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(ENTITY_NAME + " not found: " + id);
        }
        if (!Objects.equals(CompanyContext.currentCompany(), entity.getCompanyId())) {
            throw new NotFoundException(ENTITY_NAME + " not found: " + id);
        }
        return entity;
    }

    private void beforeCreate(IlliquidStockPolicy entity) {
        if (entity.getCompanyId() == null) {
            entity.setCompanyId(CompanyContext.currentCompany());
        }
        if (entity.getCode() == null || entity.getCode().isBlank()) {
            entity.setCode("ILLIQUID_POLICY-" + UUID.randomUUID().toString().substring(0, 8));
        }
        entity.setStatus("DRAFT");
        entity.setActive(false);
    }

    private void beforeUpdate(IlliquidStockPolicy entity) {
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }

    private void validateDateRange(LocalDate effectiveFrom, LocalDate effectiveTo) {
        if (effectiveFrom == null) {
            throw new BusinessException("Effective from date is required");
        }
        if (effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
            throw new BusinessException("Effective to date cannot be earlier than effective from date");
        }
    }

    private void validateOverlap(IlliquidStockPolicy candidate) {
        for (IlliquidStockPolicy existing : repository.findActivePolicies(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            candidate.getId()
        )) {
            if (datesOverlap(candidate, existing)
                && scopesOverlap(candidate, existing)
                && specificity(candidate) <= specificity(existing)) {
                throw new BusinessException("Active illiquid stock policy overlaps with existing active policy " + existing.getCode());
            }
        }
    }

    private int specificity(IlliquidStockPolicy policy) {
        int score = 0;
        if (normalize(policy.getLegalEntityCode()) != null) score++;
        if (policy.getWarehouseId() != null) score++;
        if (normalize(policy.getMaterialCategory()) != null) score++;
        if (normalize(policy.getMaterialType()) != null) score++;
        return score;
    }

    private boolean scopesOverlap(IlliquidStockPolicy left, IlliquidStockPolicy right) {
        return overlapsDimension(left.getLegalEntityCode(), right.getLegalEntityCode())
            && overlapsDimension(left.getWarehouseId(), right.getWarehouseId())
            && overlapsDimension(normalize(left.getMaterialCategory()), normalize(right.getMaterialCategory()))
            && overlapsDimension(normalize(left.getMaterialType()), normalize(right.getMaterialType()));
    }

    private boolean datesOverlap(IlliquidStockPolicy left, IlliquidStockPolicy right) {
        LocalDate leftEnd = left.getEffectiveTo() == null ? LocalDate.of(9999, 12, 31) : left.getEffectiveTo();
        LocalDate rightEnd = right.getEffectiveTo() == null ? LocalDate.of(9999, 12, 31) : right.getEffectiveTo();
        return !left.getEffectiveFrom().isAfter(rightEnd) && !right.getEffectiveFrom().isAfter(leftEnd);
    }

    private <T> boolean overlapsDimension(T left, T right) {
        return left == null || right == null || Objects.equals(left, right);
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? null : authentication.getName();
    }
}
