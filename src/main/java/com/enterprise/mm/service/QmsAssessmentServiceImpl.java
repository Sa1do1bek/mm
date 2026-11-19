package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Company;
import com.enterprise.mm.entity.Criteria;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierAssessment;
import com.enterprise.mm.entity.SupplierAssessmentDetail;
import com.enterprise.mm.enums.QmsAssessmentStatus;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.QmsAssessmentDtos.AddDetailRequest;
import com.enterprise.mm.payload.QmsAssessmentDtos.CreateDraftAssessmentRequest;
import com.enterprise.mm.payload.QmsAssessmentDtos.SupplierAssessmentDetailResponse;
import com.enterprise.mm.payload.QmsAssessmentDtos.SupplierAssessmentResponse;
import com.enterprise.mm.payload.QmsAssessmentDtos.UpdateAssessmentRequest;
import com.enterprise.mm.payload.QmsAssessmentCreateWithDetailsRequest;
import com.enterprise.mm.repository.CompanyRepository;
import com.enterprise.mm.repository.CriteriaRepository;
import com.enterprise.mm.repository.SupplierAssessmentRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.security.AccessScope;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
public class QmsAssessmentServiceImpl implements QmsAssessmentService {
    private static final BigDecimal A_CATEGORY_THRESHOLD = BigDecimal.valueOf(85);
    private static final BigDecimal B_CATEGORY_THRESHOLD = BigDecimal.valueOf(60);
    private static final String ENTITY_NAME = "SupplierAssessment";

    private final SupplierAssessmentRepository assessmentRepository;
    private final CriteriaRepository criteriaRepository;
    private final SupplierRepository supplierRepository;
    private final CompanyRepository companyRepository;
    private final AuditService auditService;
    private final SupplierAdmissionService admissionService;

    @Override
    @Transactional
    public SupplierAssessmentResponse createWithDetails(QmsAssessmentCreateWithDetailsRequest request) {
        validateAggregateRequest(request);
        Company company = company(request.companyId());
        Supplier supplier = supplier(request.supplierId());
        if (!Objects.equals(company.getId(), supplier.getCompanyId())) {
            throw new BusinessException("Supplier does not belong to assessor company: " + request.companyId());
        }

        SupplierAssessment assessment = new SupplierAssessment();
        assessment.setCompanyId(company.getId());
        assessment.setSupplier(supplier);
        assessment.setAssessmentDate(request.assessmentDate());
        assessment.setAssessedBy(request.assessedBy());
        assessment.setAssessmentStatus(QmsAssessmentStatus.DRAFT);
        assessment.setTotalWeightedScore(BigDecimal.ZERO);
        assessment.setCode(resolveCreateCode(company.getId(), request.code()));
        assessment.setName(defaultName(request.name(), supplier));
        assessment.setDescription(request.description());

        for (QmsAssessmentCreateWithDetailsRequest.Detail item : request.details()) {
            Criteria criterion = criteria(item.criteriaId(), company.getId());
            validateCriterionWeight(criterion);
            validateScore(item.givenScore(), criterion);
            SupplierAssessmentDetail detail = new SupplierAssessmentDetail();
            detail.setAssessment(assessment);
            detail.setCriteria(criterion);
            detail.setTenantId(TenantContext.currentTenant());
            detail.setCompanyId(company.getId());
            detail.setCode(detailCode(assessment, criterion));
            detail.setName(criterion.getName());
            detail.setStatus("ACTIVE");
            detail.setGivenScore(item.givenScore().setScale(2, RoundingMode.HALF_UP));
            detail.setComment(trimToNull(item.comment()));
            assessment.getDetails().add(detail);
        }

        SupplierAssessment saved = assessmentRepository.save(assessment);
        auditService.logInCurrentTransaction(ENTITY_NAME, saved.getId(), "CREATE_WITH_DETAILS", saved.getCode(), null, null);
        return toResponse(saved);
    }

    @Override
    @Transactional
    public SupplierAssessmentResponse createDraftAssessment(CreateDraftAssessmentRequest request) {
        Company company = company(request.companyId());
        Supplier supplier = supplier(request.supplierId());
        if (!Objects.equals(company.getId(), supplier.getCompanyId())) {
            throw new BusinessException("Supplier does not belong to assessor company: " + request.companyId());
        }

        SupplierAssessment assessment = new SupplierAssessment();
        assessment.setCompanyId(company.getId());
        assessment.setSupplier(supplier);
        assessment.setAssessmentDate(request.assessmentDate());
        assessment.setAssessedBy(request.assessedBy());
        assessment.setAssessmentStatus(QmsAssessmentStatus.DRAFT);
        assessment.setTotalWeightedScore(BigDecimal.ZERO);
        assessment.setCode(resolveCreateCode(company.getId(), request.code()));
        assessment.setName(defaultName(request.name(), supplier));
        assessment.setDescription(request.description());

        SupplierAssessment saved = assessmentRepository.save(assessment);
        auditService.log(ENTITY_NAME, saved.getId(), "CREATE_DRAFT", saved.getCode(), null, null);
        return toResponse(saved);
    }

    @Override
    @Transactional
    public SupplierAssessmentResponse updateDraftAssessment(UUID assessmentId, UpdateAssessmentRequest request) {
        SupplierAssessment assessment = findMutableAssessment(assessmentId);
        requireDraft(assessment);
        assessment.setAssessmentDate(request.assessmentDate());
        assessment.setAssessedBy(request.assessedBy());
        assessment.setCode(defaultCode(request.code()));
        assessment.setName(request.name() == null || request.name().isBlank() ? assessment.getName() : request.name());
        assessment.setDescription(request.description());

        SupplierAssessment saved = assessmentRepository.save(assessment);
        auditService.log(ENTITY_NAME, saved.getId(), "UPDATE_DRAFT", saved.getCode(), null, null);
        return toResponse(saved);
    }

    @Override
    @Transactional
    public SupplierAssessmentResponse addDetail(UUID assessmentId, AddDetailRequest request) {
        SupplierAssessment assessment = findMutableAssessment(assessmentId);
        requireDraft(assessment);
        Criteria criteria = criteria(request.criteriaId(), assessment.getCompanyId());
        validateScore(request.givenScore(), criteria);

        SupplierAssessmentDetail detail = assessment.getDetails().stream()
            .filter(existing -> existing.getCriteria() != null && Objects.equals(existing.getCriteria().getId(), criteria.getId()))
            .findFirst()
            .orElseGet(() -> {
                SupplierAssessmentDetail created = new SupplierAssessmentDetail();
                created.setAssessment(assessment);
                created.setCriteria(criteria);
                created.setCompanyId(assessment.getCompanyId());
                created.setCode(detailCode(assessment, criteria));
                created.setName(criteria.getName());
                created.setStatus("ACTIVE");
                assessment.getDetails().add(created);
                return created;
            });
        detail.setGivenScore(request.givenScore().setScale(2, RoundingMode.HALF_UP));
        detail.setComment(trimToNull(request.comment()));
        detail.setTenantId(assessment.getTenantId());

        SupplierAssessment saved = assessmentRepository.save(assessment);
        auditService.log(ENTITY_NAME, saved.getId(), "UPSERT_DETAIL", criteria.getCode(), null, null);
        return toResponse(saved);
    }

    @Override
    @Transactional
    public SupplierAssessmentResponse approveAssessment(UUID assessmentId) {
        SupplierAssessment assessment = findMutableAssessment(assessmentId);
        requireDraft(assessment);
        Supplier supplier = assessment.getSupplier();
        UUID supplierId = supplier == null ? null : supplier.getId();
        if (supplier == null
            || !Objects.equals(TenantContext.currentTenant(), supplier.getTenantId())
            || !Objects.equals(CompanyContext.currentCompany(), supplier.getCompanyId())
            || !Objects.equals(supplier.getCompanyId(), assessment.getCompanyId())) {
            throw new NotFoundException("Supplier not found: " + supplierId);
        }
        BigDecimal finalScore = calculateFinalScore(assessment);
        assessment.setTotalWeightedScore(finalScore);
        assessment.setAssessmentStatus(QmsAssessmentStatus.APPROVED);

        supplier.setRating(finalScore);
        supplier.setCategory(resolveCategory(finalScore));
        supplierRepository.save(supplier);
        admissionService.recalculate(supplier.getId());

        SupplierAssessment saved = assessmentRepository.save(assessment);
        auditService.log(ENTITY_NAME, saved.getId(), "APPROVE", finalScore.toPlainString(), null, null);
        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public SupplierAssessmentResponse getAssessment(UUID assessmentId) {
        return toResponse(findAssessment(assessmentId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SupplierAssessmentResponse> searchAssessments(UUID supplierId, boolean scoreDesc, Pageable pageable) {
        return searchAssessments(supplierId, null, List.of(), null, null, scoreDesc, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SupplierAssessmentResponse> searchAssessments(UUID supplierId, String search, List<String> status, LocalDate dateFrom, LocalDate dateTo, boolean scoreDesc, Pageable pageable) {
        UUID companyId = AccessScope.companyScoped(true) ? CompanyContext.currentCompany() : null;
        List<String> normalizedStatuses = normalizeStatuses(status);
        return assessmentRepository.search(
                TenantContext.currentTenant(),
                companyId,
                supplierId,
                blankToNull(search),
                normalizedStatuses.isEmpty(),
                normalizedStatuses,
                dateFrom,
                dateTo,
                scoreDesc,
                pageable
            )
            .map(this::toResponse);
    }

    private List<String> normalizeStatuses(List<String> status) {
        if (status == null) {
            return List.of();
        }
        return status.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(value -> value.trim().toUpperCase(Locale.ROOT))
            .toList();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private BigDecimal calculateFinalScore(SupplierAssessment assessment) {
        List<Criteria> activeCriteria = criteriaRepository
            .findByTenantIdAndCompanyIdAndIsActiveTrueAndIsDeletedFalseOrderByCodeAsc(
                assessment.getTenantId(), assessment.getCompanyId());
        if (activeCriteria.isEmpty()) {
            throw new BusinessException("Assessment company must configure at least one active criteria");
        }
        List<SupplierAssessmentDetail> details = assessment.getDetails().stream()
            .filter(detail -> !detail.isDeleted())
            .toList();
        Set<UUID> detailIds = new HashSet<>();
        for (SupplierAssessmentDetail detail : details) {
            UUID criteriaId = detail.getCriteria() == null ? null : detail.getCriteria().getId();
            if (criteriaId == null) {
                throw new BusinessException("Assessment contains missing criteria");
            }
            if (!detailIds.add(criteriaId)) {
                throw new BusinessException("Assessment contains duplicate criteria");
            }
        }
        Map<UUID, Criteria> authoritative = activeCriteria.stream()
            .collect(java.util.stream.Collectors.toMap(Criteria::getId, criterion -> criterion));
        if (!detailIds.equals(authoritative.keySet())) {
            throw new BusinessException("Assessment must score every active criteria exactly once");
        }

        BigDecimal weightedScore = BigDecimal.ZERO;
        BigDecimal totalWeight = BigDecimal.ZERO;
        for (SupplierAssessmentDetail detail : details) {
            Criteria criterion = authoritative.get(detail.getCriteria().getId());
            validateCriterionScope(criterion, assessment);
            validateCriterionWeight(criterion);
            validateScore(detail.getGivenScore(), criterion);
            BigDecimal normalized = detail.getGivenScore()
                .divide(criterion.getMaxScore(), 12, RoundingMode.HALF_UP)
                .multiply(criterion.getWeight());
            weightedScore = weightedScore.add(normalized);
            totalWeight = totalWeight.add(criterion.getWeight());
        }
        if (totalWeight.signum() <= 0) {
            throw new BusinessException("Total criteria weight must be greater than zero");
        }
        return weightedScore
            .divide(totalWeight, 12, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100))
            .setScale(2, RoundingMode.HALF_UP);
    }

    private SupplierAssessment findAssessment(UUID id) {
        SupplierAssessment assessment = assessmentRepository.findWithDetailsByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException(ENTITY_NAME + " not found: " + id));
        if (!TenantContext.currentTenant().equals(assessment.getTenantId())) {
            throw new NotFoundException(ENTITY_NAME + " not found: " + id);
        }
        if (AccessScope.companyScoped(true) && !AccessScope.canAccessCompany(assessment.getCompanyId())) {
            throw new NotFoundException(ENTITY_NAME + " not found: " + id);
        }
        return assessment;
    }

    private SupplierAssessment findMutableAssessment(UUID id) {
        SupplierAssessment assessment = findAssessment(id);
        if (!Objects.equals(CompanyContext.currentCompany(), assessment.getCompanyId())) {
            throw new NotFoundException(ENTITY_NAME + " not found: " + id);
        }
        return assessment;
    }

    private Company company(UUID companyId) {
        Company company = companyRepository.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), companyId)
            .orElseThrow(() -> new NotFoundException("Company not found: " + companyId));
        if (!Objects.equals(CompanyContext.currentCompany(), company.getId())) {
            throw new NotFoundException("Company not found: " + companyId);
        }
        return company;
    }

    private Supplier supplier(UUID supplierId) {
        Supplier supplier = supplierRepository.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), supplierId)
            .orElseThrow(() -> new NotFoundException("Supplier not found: " + supplierId));
        if (!Objects.equals(CompanyContext.currentCompany(), supplier.getCompanyId())) {
            throw new NotFoundException("Supplier not found: " + supplierId);
        }
        return supplier;
    }

    private Criteria criteria(UUID criteriaId, UUID companyId) {
        Criteria criteria = criteriaRepository.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), criteriaId)
            .orElseThrow(() -> new NotFoundException("Criteria not found: " + criteriaId));
        if (!Objects.equals(criteria.getCompanyId(), companyId)) {
            throw new NotFoundException("Criteria not found: " + criteriaId);
        }
        if (Boolean.FALSE.equals(criteria.getIsActive())) {
            throw new BusinessException("Criteria is inactive: " + criteriaId);
        }
        return criteria;
    }

    private void requireDraft(SupplierAssessment assessment) {
        if (assessment.getAssessmentStatus() != QmsAssessmentStatus.DRAFT) {
            throw new BusinessException("Only DRAFT assessments can be modified or approved");
        }
    }

    private void validateScore(BigDecimal givenScore, Criteria criteria) {
        if (givenScore == null) {
            throw new BadRequestException("givenScore is required");
        }
        if (givenScore.signum() < 0) {
            throw new BadRequestException("givenScore cannot be negative");
        }
        if (criteria.getMaxScore() == null || criteria.getMaxScore().signum() <= 0) {
            throw new BusinessException("Criteria maxScore must be greater than zero");
        }
        if (givenScore.compareTo(criteria.getMaxScore()) > 0) {
            throw new BusinessException("givenScore cannot exceed criteria maxScore");
        }
    }

    private void validateCriterionWeight(Criteria criteria) {
        if (criteria.getWeight() == null || criteria.getWeight().signum() <= 0) {
            throw new BusinessException("Criteria weight must be greater than zero");
        }
    }

    private void validateCriterionScope(Criteria criteria, SupplierAssessment assessment) {
        if (criteria == null
            || criteria.isDeleted()
            || Boolean.FALSE.equals(criteria.getIsActive())
            || !Objects.equals(criteria.getTenantId(), assessment.getTenantId())
            || !Objects.equals(criteria.getCompanyId(), assessment.getCompanyId())) {
            throw new BusinessException("Assessment contains inactive, deleted, or foreign criteria");
        }
    }

    private void validateAggregateRequest(QmsAssessmentCreateWithDetailsRequest request) {
        if (request == null || request.details() == null || request.details().isEmpty()) {
            throw new BusinessException("Assessment details are required");
        }
        Set<UUID> ids = new HashSet<>();
        for (QmsAssessmentCreateWithDetailsRequest.Detail detail : request.details()) {
            if (detail == null || detail.criteriaId() == null) {
                throw new BusinessException("Assessment criteria is required");
            }
            if (!ids.add(detail.criteriaId())) {
                throw new BusinessException("Assessment contains duplicate criteria");
            }
        }
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String resolveCategory(BigDecimal finalScore) {
        if (finalScore.compareTo(A_CATEGORY_THRESHOLD) >= 0) {
            return "A";
        }
        if (finalScore.compareTo(B_CATEGORY_THRESHOLD) >= 0) {
            return "B";
        }
        return "C";
    }

    private String defaultCode(String code) {
        if (code != null && !code.isBlank()) {
            return code.trim();
        }
        return "QA-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
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
            String candidate = "QA-" + String.format(Locale.ROOT, "%05d", (seed + attempt) % 100_000);
            if (assessmentRepository.findByTenantIdAndCompanyIdAndCodeAndIsDeletedFalse(TenantContext.currentTenant(), companyId, candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new BusinessException("Unable to generate unique assessment code");
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String defaultName(String name, Supplier supplier) {
        if (name != null && !name.isBlank()) {
            return name.trim();
        }
        return "QMS assessment for " + supplier.getName();
    }

    private String detailCode(SupplierAssessment assessment, Criteria criteria) {
        String base = assessment.getCode() == null ? "QA" : assessment.getCode();
        String suffix = criteria.getCode() == null ? criteria.getId().toString().substring(0, 8) : criteria.getCode();
        return (base + "-" + suffix).length() > 80 ? UUID.randomUUID().toString().substring(0, 8) : base + "-" + suffix;
    }

    private SupplierAssessmentResponse toResponse(SupplierAssessment assessment) {
        return new SupplierAssessmentResponse(
            assessment.getId(),
            assessment.getTenantId(),
            assessment.getCompanyId(),
            assessment.getSupplier() == null ? null : assessment.getSupplier().getId(),
            assessment.getCode(),
            assessment.getName(),
            assessment.getDescription(),
            assessment.getAssessmentDate(),
            assessment.getTotalWeightedScore(),
            assessment.getAssessmentStatus(),
            assessment.getAssessedBy(),
            assessment.isDeleted(),
            assessment.getCreatedAt(),
            assessment.getUpdatedAt(),
            assessment.getVersion(),
            assessment.getDetails().stream()
                .filter(detail -> !detail.isDeleted())
                .sorted(Comparator.comparing(detail -> detail.getCriteria() == null ? "" : Objects.toString(detail.getCriteria().getCode(), "")))
                .map(this::toDetailResponse)
                .toList()
        );
    }

    private SupplierAssessmentDetailResponse toDetailResponse(SupplierAssessmentDetail detail) {
        Criteria criteria = detail.getCriteria();
        return new SupplierAssessmentDetailResponse(
            detail.getId(),
            criteria == null ? null : criteria.getId(),
            criteria == null ? null : criteria.getCode(),
            criteria == null ? null : criteria.getName(),
            criteria == null ? null : criteria.getMaxScore(),
            criteria == null ? null : criteria.getWeight(),
            detail.getGivenScore(),
            detail.getComment()
        );
    }
}
