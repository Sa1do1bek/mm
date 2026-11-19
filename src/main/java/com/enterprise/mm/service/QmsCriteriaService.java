package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Company;
import com.enterprise.mm.entity.Criteria;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.QmsCriteriaDtos.CriteriaCreateRequest;
import com.enterprise.mm.payload.QmsCriteriaDtos.CriteriaResponse;
import com.enterprise.mm.payload.QmsCriteriaDtos.CriteriaUpdateRequest;
import com.enterprise.mm.payload.QmsCriteriaSearchRequest;
import com.enterprise.mm.repository.CompanyRepository;
import com.enterprise.mm.repository.CriteriaRepository;
import com.enterprise.mm.security.AccessScope;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class QmsCriteriaService {
    private static final String ENTITY_NAME = "Criteria";

    private final CriteriaRepository repository;
    private final CompanyRepository companyRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public Page<CriteriaResponse> search(QmsCriteriaSearchRequest request, Pageable pageable) {
        UUID scopedCompanyId = resolveSearchCompanyId(request.companyId());
        String normalizedSearch = normalizeSearch(request.search());
        return repository.search(
            TenantContext.currentTenant(),
            scopedCompanyId,
            request.includeDeleted(),
            request.includeInactive(),
            normalizedSearch == null ? "" : normalizedSearch,
            normalizedSearch == null,
            startOfDay(request.dateFrom()),
            endOfDay(request.dateTo()),
            pageable
        ).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public CriteriaResponse get(UUID id) {
        return toResponse(findEntity(id));
    }

    @Transactional
    public CriteriaResponse create(CriteriaCreateRequest request) {
        Company company = currentCompany(request.companyId());
        Criteria criteria = new Criteria();
        criteria.setCompanyId(company.getId());
        apply(criteria, resolveCreateCode(company.getId(), request.code()), request.name(), request.description(), request.maxScore(), request.weight(), request.isActive());
        ensureUnique(criteria);

        Criteria saved = repository.save(criteria);
        auditService.log(ENTITY_NAME, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return toResponse(saved);
    }

    @Transactional
    public CriteriaResponse update(UUID id, CriteriaUpdateRequest request) {
        Criteria criteria = findMutableEntity(id);
        String oldSnapshot = auditService.snapshot(criteria);
        // Criteria codes are generated identifiers and remain stable after creation.
        // The read-only UI intentionally omits them from update payloads.
        apply(criteria, criteria.getCode(), request.name(), request.description(), request.maxScore(), request.weight(), request.isActive());
        ensureUnique(criteria);

        Criteria saved = repository.save(criteria);
        auditService.log(ENTITY_NAME, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return toResponse(saved);
    }

    @Transactional
    public void softDelete(UUID id) {
        Criteria criteria = findMutableEntity(id);
        criteria.setIsActive(false);
        criteria.setStatus("INACTIVE");
        criteria.softDelete();
        repository.save(criteria);
        auditService.log(ENTITY_NAME, id, "SOFT_DELETE", criteria.getCode(), null, null);
    }

    private Criteria findEntity(UUID id) {
        Criteria criteria = repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException(ENTITY_NAME + " not found: " + id));
        validateTenantAndCompany(criteria, id);
        return criteria;
    }

    private Criteria findMutableEntity(UUID id) {
        Criteria criteria = findEntity(id);
        if (!Objects.equals(CompanyContext.currentCompany(), criteria.getCompanyId())) {
            throw new NotFoundException(ENTITY_NAME + " not found: " + id);
        }
        return criteria;
    }

    private Company company(UUID companyId) {
        Company company = companyRepository.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), companyId)
            .orElseThrow(() -> new NotFoundException("Company not found: " + companyId));
        if (AccessScope.companyScoped(true) && !AccessScope.canAccessCompany(company.getId())) {
            throw new NotFoundException("Company not found: " + companyId);
        }
        return company;
    }

    private Company currentCompany(UUID companyId) {
        Company company = company(companyId);
        if (!Objects.equals(CompanyContext.currentCompany(), company.getId())) {
            throw new NotFoundException("Company not found: " + companyId);
        }
        return company;
    }

    private UUID resolveSearchCompanyId(UUID companyId) {
        if (AccessScope.companyScoped(true)) {
            UUID currentCompany = CompanyContext.currentCompany();
            if (companyId != null && !Objects.equals(companyId, currentCompany)) {
                throw new NotFoundException("Company not found: " + companyId);
            }
            return currentCompany;
        }
        if (companyId != null) {
            company(companyId);
        }
        return companyId;
    }

    private void validateTenantAndCompany(Criteria criteria, UUID id) {
        if (!TenantContext.currentTenant().equals(criteria.getTenantId())) {
            throw new NotFoundException(ENTITY_NAME + " not found: " + id);
        }
        if (AccessScope.companyScoped(true) && !AccessScope.canAccessCompany(criteria.getCompanyId())) {
            throw new NotFoundException(ENTITY_NAME + " not found: " + id);
        }
    }

    private void apply(
        Criteria criteria,
        String code,
        String name,
        String description,
        BigDecimal maxScore,
        BigDecimal weight,
        Boolean active
    ) {
        criteria.setCode(requireText(code, "code"));
        criteria.setName(requireText(name, "name"));
        criteria.setDescription(description);
        criteria.setMaxScore(maxScore(maxScore).setScale(2, RoundingMode.HALF_UP));
        criteria.setWeight(weight(weight).setScale(3, RoundingMode.HALF_UP));
        criteria.setIsActive(active == null || active);
        criteria.setStatus(Boolean.TRUE.equals(criteria.getIsActive()) ? "ACTIVE" : "INACTIVE");
    }

    private void ensureUnique(Criteria criteria) {
        repository.findByTenantIdAndCompanyIdAndCodeAndIsDeletedFalse(
                TenantContext.currentTenant(),
                criteria.getCompanyId(),
                criteria.getCode()
            )
            .filter(existing -> !existing.getId().equals(criteria.getId()))
            .ifPresent(existing -> {
                throw new BusinessException("Criteria code already exists in this company");
            });
    }

    private String resolveCreateCode(UUID companyId, String code) {
        if (code != null && !code.isBlank()) {
            return code.trim();
        }
        return generateUniqueCode(companyId);
    }

    private String generateUniqueCode(UUID companyId) {
        int seed = (int) Math.floorMod(System.currentTimeMillis(), 100_000);
        for (int attempt = 0; attempt < 100_000; attempt++) {
            String candidate = "QC-" + String.format(Locale.ROOT, "%05d", (seed + attempt) % 100_000);
            if (repository.findByTenantIdAndCompanyIdAndCodeAndIsDeletedFalse(TenantContext.currentTenant(), companyId, candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new BusinessException("Unable to generate unique criteria code");
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(fieldName + " is required");
        }
        return value.trim();
    }

    private BigDecimal positive(BigDecimal value, String fieldName) {
        if (value == null || value.signum() <= 0) {
            throw new BusinessException(fieldName + " must be greater than zero");
        }
        return value;
    }

    private BigDecimal maxScore(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ONE) < 0) {
            throw new BusinessException("maxScore must be at least 1");
        }
        return value;
    }

    private BigDecimal weight(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ONE) < 0 || value.compareTo(BigDecimal.TEN) > 0) {
            throw new BusinessException("weight must be between 1 and 10");
        }
        return value;
    }

    private String normalizeSearch(String search) {
        if (search == null || search.isBlank()) {
            return null;
        }
        return search.trim();
    }

    private LocalDateTime startOfDay(LocalDate date) {
        return date == null ? null : date.atStartOfDay();
    }

    private LocalDateTime endOfDay(LocalDate date) {
        return date == null ? null : date.atTime(LocalTime.MAX);
    }

    private CriteriaResponse toResponse(Criteria criteria) {
        return new CriteriaResponse(
            criteria.getId(),
            criteria.getTenantId(),
            criteria.getCompanyId(),
            criteria.getCode(),
            criteria.getName(),
            criteria.getStatus(),
            criteria.getDescription(),
            criteria.getMaxScore(),
            criteria.getWeight(),
            criteria.getIsActive(),
            criteria.isDeleted(),
            criteria.getCreatedAt(),
            criteria.getUpdatedAt(),
            criteria.getVersion()
        );
    }
}
