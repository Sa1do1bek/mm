package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.payload.SupplierComplianceDtos.SupplierPortalActionRequest;
import com.enterprise.mm.payload.SupplierPortalSubmissionResponse;
import com.enterprise.mm.payload.SupplierPortalSubmissionUpdateRequest;
import com.enterprise.mm.payload.SupplierPortalSubmissionCreateRequest;
import com.enterprise.mm.payload.SupplierPortalSubmissionSearchRequest;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.SupplierPortalSubmissionMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.SupplierPortalStatus;
import com.enterprise.mm.enums.SupplierPortalSubmissionStatus;
import com.enterprise.mm.entity.SupplierAccreditationApplication;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierPortalAccount;
import com.enterprise.mm.entity.SupplierPortalSubmission;
import com.enterprise.mm.repository.SupplierPortalAccountRepository;
import com.enterprise.mm.repository.SupplierPortalSubmissionRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SupplierPortalSubmissionService {
    private final String entityName = "SupplierPortalSubmission";
    private final SupplierPortalSubmissionRepository repository;
    private final SupplierPortalSubmissionMapper mapper;
    private final SupplierPortalAccountRepository accountRepository;
    private final SupplierComplianceService complianceService;
    private final SupplierAccreditationService accreditationService;
    private final AuditService auditService;
    protected void beforeCreate(SupplierPortalSubmission entity, SupplierPortalSubmissionCreateRequest dto) {
        Supplier supplier = complianceService.validateSupplier(entity.getSupplierId());
        requireCurrentCompany(supplier, "Supplier");
        entity.setCompanyId(CompanyContext.currentCompany());
        validatePortalAccount(entity);
        validateAccreditationReference(entity);
        entity.setStatus(SupplierPortalSubmissionStatus.DRAFT.name());
        entity.setSubmittedAt(null);
        entity.setReviewedAt(null);
        entity.setReviewedBy(null);
        entity.setReviewComment(null);
        if (entity.getCode() == null || entity.getCode().isBlank()) {
            entity.setCode(entityName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_") + "-" + UUID.randomUUID().toString().substring(0, 8));
        }
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }
    protected void beforeUpdate(SupplierPortalSubmission entity, SupplierPortalSubmissionUpdateRequest dto) {
        Supplier supplier = complianceService.validateSupplier(entity.getSupplierId());
        requireCurrentCompany(supplier, "Supplier");
        validatePortalAccount(entity);
        validateAccreditationReference(entity);
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }
    @Transactional
    public SupplierPortalSubmissionResponse submit(UUID id) {
        SupplierPortalSubmission submission = findEntity(id);
        if (!SupplierPortalSubmissionStatus.DRAFT.name().equals(submission.getStatus())) {
            throw new BusinessException("Only DRAFT portal submission can be submitted");
        }
        validatePortalAccount(submission);
        validateAccreditationReference(submission);
        submission.setStatus(SupplierPortalSubmissionStatus.SUBMITTED.name());
        submission.setSubmittedAt(LocalDateTime.now());
        touchPortalSubmissionTime(submission);
        auditService.log("SupplierPortalSubmission", id, "SUBMIT", submission.getSubmissionType(), null, null);
        return mapper.toResponse(repository.save(submission));
    }
    @Transactional
    public SupplierPortalSubmissionResponse accept(UUID id, SupplierPortalActionRequest request) {
        SupplierPortalSubmission submission = findEntity(id);
        if (!SupplierPortalSubmissionStatus.SUBMITTED.name().equals(submission.getStatus())) {
            throw new BusinessException("Only SUBMITTED portal submission can be accepted");
        }
        requireMeaningfulComment(request == null ? null : request.comment());
        review(submission, SupplierPortalSubmissionStatus.ACCEPTED.name(), request);
        return mapper.toResponse(repository.saveAndFlush(submission));
    }
    @Transactional
    public SupplierPortalSubmissionResponse reject(UUID id, SupplierPortalActionRequest request) {
        SupplierPortalSubmission submission = findEntity(id);
        if (!SupplierPortalSubmissionStatus.SUBMITTED.name().equals(submission.getStatus())) {
            throw new BusinessException("Only SUBMITTED portal submission can be rejected");
        }
        requireMeaningfulComment(request == null ? null : request.comment());
        review(submission, SupplierPortalSubmissionStatus.REJECTED.name(), request);
        return mapper.toResponse(repository.saveAndFlush(submission));
    }
    private void review(SupplierPortalSubmission submission, String status, SupplierPortalActionRequest request) {
        submission.setStatus(status);
        submission.setReviewedAt(LocalDateTime.now());
        submission.setReviewedBy(currentUsername());
        submission.setReviewComment(request == null ? null : request.comment());
        auditService.log("SupplierPortalSubmission", submission.getId(), status, submission.getReviewComment(), null, null);
    }
    private void validatePortalAccount(SupplierPortalSubmission entity) {
        if (entity.getPortalAccountId() == null) {
            return;
        }
        SupplierPortalAccount account = findCurrentCompanyPortalAccount(entity.getPortalAccountId());
        if (!entity.getSupplierId().equals(account.getSupplierId())) {
            throw new BusinessException("Portal account belongs to another supplier");
        }
        if (!SupplierPortalStatus.ACTIVE.name().equals(account.getStatus())) {
            throw new BusinessException("Portal account must be ACTIVE for submissions");
        }
    }
    private void touchPortalSubmissionTime(SupplierPortalSubmission submission) {
        if (submission.getPortalAccountId() == null) {
            return;
        }
        SupplierPortalAccount account = findCurrentCompanyPortalAccount(submission.getPortalAccountId());
        if (!Objects.equals(submission.getSupplierId(), account.getSupplierId())) {
            throw new BusinessException("Portal account belongs to another supplier");
        }
        account.setLastSubmissionAt(LocalDateTime.now());
        accountRepository.save(account);
    }
    private void validateAccreditationReference(SupplierPortalSubmission entity) {
        if (!"SUPPLIER_ACCREDITATION_APPLICATION".equalsIgnoreCase(Objects.toString(entity.getReferenceEntityType(), ""))) {
            return;
        }
        if (entity.getReferenceId() == null) {
            throw new BusinessException("Accreditation referenceId is required");
        }
        SupplierAccreditationApplication application = accreditationService.validateApplication(entity.getReferenceId());
        requireCurrentCompany(application, "Accreditation application");
        if (!entity.getSupplierId().equals(application.getSupplierId())) {
            throw new BusinessException("Accreditation application belongs to another supplier");
        }
    }
    private String currentUsername() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? "system" : authentication.getName();
    }

    private SupplierPortalAccount findCurrentCompanyPortalAccount(UUID accountId) {
        return accountRepository.findByIdAndIsDeletedFalse(accountId)
            .filter(item -> !item.isDeleted())
            .filter(item -> TenantContext.currentTenant().equals(item.getTenantId()))
            .filter(item -> Objects.equals(CompanyContext.currentCompany(), item.getCompanyId()))
            .orElseThrow(() -> new BusinessException("Supplier portal account not found"));
    }

    private void requireCurrentCompany(com.enterprise.mm.common.BusinessEntity entity, String label) {
        if (entity != null && (!TenantContext.currentTenant().equals(entity.getTenantId())
            || !Objects.equals(CompanyContext.currentCompany(), entity.getCompanyId()))) {
            throw new BusinessException(label + " must belong to the current company");
        }
    }

    private void requireMeaningfulComment(String comment) {
        if (comment == null || comment.trim().length() < 3) {
            throw new com.enterprise.mm.error.BadRequestException(
                "comment must contain a meaningful operator explanation"
            );
        }
    }
    @Transactional(readOnly = true)
    public Page<SupplierPortalSubmissionResponse> search(SupplierPortalSubmissionSearchRequest request, Pageable pageable) {
        SearchCriteria filter = SearchCriteria.of(request);
        return repository.search(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            filter.includeDeleted(),
            searchPattern(filter),
            filter.fromDateTime(),
            filter.toDateTime(),
            filter.status(),
            !filter.hasStatus(),
            filter.supplierId(),
            request.portalAccountId(),
            filter.referenceId(),
            filter.normalizedReferenceEntityType(),
            SearchCriteria.normalizedToken(request.submissionType()),
            request.payloadJson(),
            request.attachmentId(),
            request.submittedFrom() == null ? null : request.submittedFrom().atStartOfDay(),
            request.submittedTo() == null ? null : request.submittedTo().atTime(java.time.LocalTime.MAX),
            request.reviewedFrom() == null ? null : request.reviewedFrom().atStartOfDay(),
            request.reviewedTo() == null ? null : request.reviewedTo().atTime(java.time.LocalTime.MAX),
            request.reviewedBy(),
            request.reviewComment(),
            pageable
        ).map(mapper::toResponse);
    }


    @Transactional(readOnly = true)
    public SupplierPortalSubmissionResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public SupplierPortalSubmissionResponse create(SupplierPortalSubmissionCreateRequest dto) {
        SupplierPortalSubmission entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        SupplierPortalSubmission saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public SupplierPortalSubmissionResponse update(UUID id, SupplierPortalSubmissionUpdateRequest dto) {
        SupplierPortalSubmission entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        SupplierPortalSubmission saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        SupplierPortalSubmission entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public SupplierPortalSubmissionResponse restore(UUID id) {
        SupplierPortalSubmission entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (!Objects.equals(CompanyContext.currentCompany(), entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        SupplierPortalSubmission saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected SupplierPortalSubmission findEntity(UUID id) {
        SupplierPortalSubmission entity = repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (entity.isDeleted()) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (!Objects.equals(CompanyContext.currentCompany(), entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        return entity;
    }
    protected boolean companyScoped() {
        return true;
    }

    private String searchPattern(SearchCriteria request) {
        String search = request.normalizedSearch();
        return search.isBlank() ? null : "%" + search.toLowerCase(Locale.ROOT) + "%";
    }
}
