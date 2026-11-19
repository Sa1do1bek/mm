package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.SupplierAccreditationDecision;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.SupplierAccreditationDecisionMapper;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.payload.SupplierAccreditationDecisionCreateRequest;
import com.enterprise.mm.payload.SupplierAccreditationDecisionResponse;
import com.enterprise.mm.payload.SupplierAccreditationDecisionUpdateRequest;
import com.enterprise.mm.payload.SupplierAccreditationDecisionSearchRequest;
import com.enterprise.mm.repository.SupplierAccreditationDecisionRepository;
import jakarta.persistence.criteria.Predicate;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SupplierAccreditationDecisionService {
    private final SupplierAccreditationDecisionRepository repository;
    private final SupplierAccreditationDecisionMapper mapper;
    private final String entityName = "SupplierAccreditationDecision";

    @Transactional(readOnly = true)
    public Page<SupplierAccreditationDecisionResponse> search(SupplierAccreditationDecisionSearchRequest request, Pageable pageable) {
        SupplierAccreditationDecisionSearchRequest criteria = request == null
            ? SupplierAccreditationDecisionSearchRequest.empty()
            : request;
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        return repository.findAll(decisionSearchSpec(criteria, scoped), pageable).map(mapper::toResponse);
    }


    @Transactional(readOnly = true)
    public SupplierAccreditationDecisionResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }

    @Transactional
    public SupplierAccreditationDecisionResponse create(SupplierAccreditationDecisionCreateRequest dto) {
        throw immutableDecision();
    }

    @Transactional
    public SupplierAccreditationDecisionResponse update(UUID id, SupplierAccreditationDecisionUpdateRequest dto) {
        findEntity(id);
        throw immutableDecision();
    }

    @Transactional
    public void softDelete(UUID id) {
        findEntity(id);
        throw immutableDecision();
    }

    @Transactional
    public SupplierAccreditationDecisionResponse restore(UUID id) {
        SupplierAccreditationDecision entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        validateTenantCompanyScope(entity);
        throw immutableDecision();
    }

    protected SupplierAccreditationDecision findEntity(UUID id) {
        SupplierAccreditationDecision entity = repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        validateTenantCompanyScope(entity);
        return entity;
    }

    private void validateTenantCompanyScope(SupplierAccreditationDecision entity) {
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + entity.getId());
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + entity.getId());
        }
    }

    private BusinessException immutableDecision() {
        return new BusinessException(
            "Accreditation decisions are immutable and can only be recorded by the approval workflow",
            "ACCREDITATION_DECISION_IMMUTABLE"
        );
    }

    private boolean companyScoped() {
        return true;
    }

    private String searchPattern(SearchCriteria request) {
        String search = request.normalizedSearch();
        return search.isBlank() ? null : "%" + search.toLowerCase(Locale.ROOT) + "%";
    }

    private Specification<SupplierAccreditationDecision> decisionSearchSpec(
        SupplierAccreditationDecisionSearchRequest criteria,
        boolean scoped
    ) {
        SearchCriteria filter = SearchCriteria.of(criteria);
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("tenantId"), TenantContext.currentTenant()));
            if (scoped) {
                predicates.add(cb.equal(root.get("companyId"), CompanyContext.currentCompany()));
            }
            if (!filter.includeDeleted()) {
                predicates.add(cb.isFalse(root.get("isDeleted")));
            }
            String searchPattern = searchPattern(filter);
            if (searchPattern != null) {
                predicates.add(cb.or(
                    cb.like(cb.lower(root.get("code").as(String.class)), searchPattern),
                    cb.like(cb.lower(root.get("name").as(String.class)), searchPattern)
                ));
            }
            LocalDateTime dateFrom = filter.fromDateTime();
            if (dateFrom != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), dateFrom));
            }
            LocalDateTime dateTo = filter.toDateTime();
            if (dateTo != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), dateTo));
            }
            List<String> statuses = filter.hasStatus()
                ? filter.status().stream().filter(Objects::nonNull).toList()
                : List.of();
            if (!statuses.isEmpty()) {
                predicates.add(root.get("status").in(statuses));
            }
            LocalDate validFrom = criteria.validFrom();
            if (validFrom != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("validFrom"), validFrom));
            }
            LocalDate validTo = criteria.validTo();
            if (validTo != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("validTo"), validTo));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
