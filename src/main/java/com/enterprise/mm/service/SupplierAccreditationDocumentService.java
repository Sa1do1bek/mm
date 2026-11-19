package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.SupplierAccreditationApplication;
import com.enterprise.mm.entity.SupplierAccreditationDocument;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.SupplierAccreditationDocumentMapper;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.payload.SupplierAccreditationDocumentCreateRequest;
import com.enterprise.mm.payload.SupplierAccreditationDocumentActionRequest;
import com.enterprise.mm.payload.SupplierAccreditationDocumentResponse;
import com.enterprise.mm.payload.SupplierAccreditationDocumentUpdateRequest;
import com.enterprise.mm.payload.SupplierAccreditationDocumentSearchRequest;
import com.enterprise.mm.repository.SupplierAccreditationDocumentRepository;
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
public class SupplierAccreditationDocumentService {
    private final SupplierAccreditationDocumentRepository repository;
    private final SupplierAccreditationDocumentMapper mapper;
    private final SupplierAccreditationService accreditationService;
    private final SupplierAccreditationEvidencePolicy evidencePolicy;
    private final AuditService auditService;
    private final String entityName = "SupplierAccreditationDocument";

    @Transactional(readOnly = true)
    public Page<SupplierAccreditationDocumentResponse> search(SupplierAccreditationDocumentSearchRequest request, Pageable pageable) {
        SupplierAccreditationDocumentSearchRequest criteria = request == null
            ? SupplierAccreditationDocumentSearchRequest.empty()
            : request;
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        return repository.findAll(documentSearchSpec(criteria, scoped), pageable).map(mapper::toResponse);
    }


    @Transactional(readOnly = true)
    public SupplierAccreditationDocumentResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }

    @Transactional
    public SupplierAccreditationDocumentResponse create(SupplierAccreditationDocumentCreateRequest dto) {
        SupplierAccreditationDocument entity = mapper.toEntity(dto);
        beforeCreate(entity);
        SupplierAccreditationDocument saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }

    @Transactional
    public SupplierAccreditationDocumentResponse update(UUID id, SupplierAccreditationDocumentUpdateRequest dto) {
        SupplierAccreditationDocument entity = findMutableEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        ensureEditable(entity);
        UUID applicationId = entity.getApplicationId();
        String status = entity.getStatus();
        LocalDateTime verifiedAt = entity.getVerifiedAt();
        String verifiedBy = entity.getVerifiedBy();
        mapper.updateEntity(entity, dto);
        entity.setApplicationId(applicationId);
        entity.setStatus(status);
        entity.setVerifiedAt(verifiedAt);
        entity.setVerifiedBy(verifiedBy);
        beforeUpdate(entity);
        SupplierAccreditationDocument saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }

    @Transactional
    public SupplierAccreditationDocumentResponse verify(
        UUID id,
        SupplierAccreditationDocumentActionRequest request
    ) {
        SupplierAccreditationDocument entity = findMutableEntity(id);
        ensureEditable(entity);
        String reason = requireReason(request);
        validateParentScope(entity);
        evidencePolicy.requireActiveLinkedAttachment(entity);
        entity.setStatus("VERIFIED");
        entity.setVerifiedAt(LocalDateTime.now());
        entity.setVerifiedBy(currentUsername());
        SupplierAccreditationDocument saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "VERIFY", reason, null, null);
        return mapper.toResponse(saved);
    }

    @Transactional
    public SupplierAccreditationDocumentResponse reject(
        UUID id,
        SupplierAccreditationDocumentActionRequest request
    ) {
        SupplierAccreditationDocument entity = findMutableEntity(id);
        ensureEditable(entity);
        String reason = requireReason(request);
        validateParentScope(entity);
        evidencePolicy.requireActiveLinkedAttachment(entity);
        entity.setStatus("REJECTED");
        entity.setVerifiedAt(LocalDateTime.now());
        entity.setVerifiedBy(currentUsername());
        SupplierAccreditationDocument saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "REJECT", reason, null, null);
        return mapper.toResponse(saved);
    }

    @Transactional
    public void softDelete(UUID id) {
        SupplierAccreditationDocument entity = findMutableEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        ensureEditable(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }

    @Transactional
    public SupplierAccreditationDocumentResponse restore(UUID id) {
        SupplierAccreditationDocument entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        validateTenantCompanyScope(entity);
        requireCurrentCompany(entity);
        if (!entity.isDeleted()) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        ensureEditable(entity);
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        SupplierAccreditationDocument saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }

    protected SupplierAccreditationDocument findEntity(UUID id) {
        SupplierAccreditationDocument entity = repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        validateTenantCompanyScope(entity);
        return entity;
    }

    private SupplierAccreditationDocument findMutableEntity(UUID id) {
        SupplierAccreditationDocument entity = findEntity(id);
        requireCurrentCompany(entity);
        return entity;
    }

    private void beforeCreate(SupplierAccreditationDocument entity) {
        SupplierAccreditationApplication application = accreditationService
            .validateApplicationForMutation(entity.getApplicationId());
        entity.setStatus("PENDING_VERIFICATION");
        entity.setVerifiedAt(null);
        entity.setVerifiedBy(null);
        entity.setCompanyId(application.getCompanyId());
        if (entity.getCode() == null || entity.getCode().isBlank()) {
            entity.setCode(entityName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_") + "-" + UUID.randomUUID().toString().substring(0, 8));
        }
        if (entity.getName() == null || entity.getName().isBlank()) {
            entity.setName(entity.getDocumentType());
        }
    }

    private void beforeUpdate(SupplierAccreditationDocument entity) {
        validateParentScope(entity);
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }

    private void validateTenantCompanyScope(SupplierAccreditationDocument entity) {
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + entity.getId());
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + entity.getId());
        }
    }

    private void ensureEditable(SupplierAccreditationDocument entity) {
        if (!"PENDING_VERIFICATION".equals(entity.getStatus())) {
            throw new BusinessException("verified or rejected accreditation evidence is immutable");
        }
    }

    private void validateParentScope(SupplierAccreditationDocument entity) {
        SupplierAccreditationApplication application = accreditationService
            .validateApplicationForMutation(entity.getApplicationId());
        if (!Objects.equals(application.getCompanyId(), entity.getCompanyId())) {
            throw new BusinessException("Accreditation evidence must belong to the same company as its application");
        }
    }

    private void requireCurrentCompany(SupplierAccreditationDocument entity) {
        if (!Objects.equals(CompanyContext.currentCompany(), entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + entity.getId());
        }
    }

    private String requireReason(SupplierAccreditationDocumentActionRequest request) {
        if (request == null || request.reason() == null || request.reason().isBlank()) {
            throw new BusinessException("Evidence verification or rejection reason is required");
        }
        return request.reason().trim();
    }

    private boolean companyScoped() {
        return true;
    }

    private String searchPattern(SearchCriteria request) {
        String search = request.normalizedSearch();
        return search.isBlank() ? null : "%" + search.toLowerCase(Locale.ROOT) + "%";
    }

    private Specification<SupplierAccreditationDocument> documentSearchSpec(
        SupplierAccreditationDocumentSearchRequest criteria,
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

    private String currentUsername() {
        var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? "system" : authentication.getName();
    }
}
