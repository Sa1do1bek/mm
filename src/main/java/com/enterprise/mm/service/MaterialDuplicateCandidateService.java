package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.MaterialDuplicateCandidate;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.MaterialDuplicateCandidateMapper;
import com.enterprise.mm.payload.MaterialDuplicateCandidateCreateRequest;
import com.enterprise.mm.payload.MaterialDuplicateCandidateResponse;
import com.enterprise.mm.payload.MaterialDuplicateCandidateUpdateRequest;
import com.enterprise.mm.payload.MaterialDuplicateCandidateSearchRequest;
import com.enterprise.mm.payload.MaterialDedupDtos.MaterialDedupMatch;
import com.enterprise.mm.payload.MaterialDedupDtos.MaterialDedupResult;
import com.enterprise.mm.payload.MaterialGovernanceActionRequest;
import com.enterprise.mm.payload.MaterialLifecycleRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.repository.MaterialDuplicateCandidateRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MaterialDuplicateCandidateService {
    private static final ObjectMapper AI_EVIDENCE_OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    private final MaterialDuplicateCandidateRepository repository;
    private final MaterialDuplicateCandidateMapper mapper;
    private final MaterialCatalogGuard catalogGuard;
    private final AuditService auditService;
    private final MaterialService materialService;
    private final String entityName = "MaterialDuplicateCandidate";

    @Transactional(readOnly = true)
    public Page<MaterialDuplicateCandidateResponse> search(MaterialDuplicateCandidateSearchRequest request, Pageable pageable) {
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
            filter.materialIds(),
            !filter.hasMaterialIds(),
            pageable
        ).map(mapper::toResponse);
    }

    @Transactional(readOnly = true)
    public MaterialDuplicateCandidateResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }

    @Transactional
    public MaterialDuplicateCandidateResponse create(MaterialDuplicateCandidateCreateRequest dto) {
        MaterialDuplicateCandidate entity = mapper.toEntity(dto);
        beforeCreate(entity);
        entity.setStatus("OPEN");
        entity.setDecisionComment(null);
        entity.setDecidedAt(null);
        entity.setMergedAt(null);
        MaterialDuplicateCandidate saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }

    @Transactional
    public MaterialDuplicateCandidateResponse update(UUID id, MaterialDuplicateCandidateUpdateRequest dto) {
        MaterialDuplicateCandidate entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        requireOpen(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity);
        MaterialDuplicateCandidate saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }

    @Transactional
    public MaterialDuplicateCandidateResponse confirm(UUID id, MaterialGovernanceActionRequest request) {
        return transition(id, request, Set.of("OPEN"), "CONFIRMED", "CONFIRM", false);
    }

    @Transactional
    public MaterialDuplicateCandidateResponse reject(UUID id, MaterialGovernanceActionRequest request) {
        return transition(id, request, Set.of("OPEN"), "REJECTED", "REJECT", false);
    }

    @Transactional
    public MaterialDuplicateCandidateResponse merge(UUID id, MaterialGovernanceActionRequest request) {
        return transition(id, request, Set.of("CONFIRMED"), "MERGED", "MERGE", true);
    }

    @Transactional
    public int importAiScanResult(UUID aiJobId, MaterialDedupResult result) {
        if (aiJobId == null || result == null || result.matches().isEmpty()) {
            return 0;
        }
        int changed = 0;
        UUID queryMaterialId = result.query() == null ? null : result.query().materialId();
        for (MaterialDedupMatch match : result.matches()) {
            UUID sourceMaterialId = match.resolvedSourceMaterialId(queryMaterialId);
            UUID candidateMaterialId = match.candidateMaterialId();
            if (sourceMaterialId == null || candidateMaterialId == null || sourceMaterialId.equals(candidateMaterialId)) {
                continue;
            }
            UUID materialId = canonicalFirst(sourceMaterialId, candidateMaterialId);
            UUID canonicalCandidateId = canonicalSecond(sourceMaterialId, candidateMaterialId);
            MaterialDuplicateCandidate entity = repository.findActiveByCanonicalPair(
                    TenantContext.currentTenant(),
                    CompanyContext.currentCompany(),
                    materialId,
                    canonicalCandidateId
                )
                .orElseGet(() -> newAiCandidate(materialId, canonicalCandidateId));
            if (!"OPEN".equals(entity.getStatus())) {
                continue;
            }
            applyAiEvidence(entity, aiJobId, match);
            MaterialDuplicateCandidate saved = repository.save(entity);
            auditService.log(entityName, saved.getId(), "AI_SCAN_IMPORT", saved.getCode(), null, saved);
            changed++;
        }
        return changed;
    }

    @Transactional
    public void softDelete(UUID id) {
        MaterialDuplicateCandidate entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }

    @Transactional
    public MaterialDuplicateCandidateResponse restore(UUID id) {
        MaterialDuplicateCandidate entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        validateScope(entity);
        if (!entity.isDeleted()) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        beforeUpdate(entity);
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        MaterialDuplicateCandidate saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }

    protected void beforeCreate(MaterialDuplicateCandidate entity) {
        defaultBaseFields(entity);
        normalize(entity);
    }

    protected void beforeUpdate(MaterialDuplicateCandidate entity) {
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
        normalize(entity);
    }

    protected MaterialDuplicateCandidate findEntity(UUID id) {
        MaterialDuplicateCandidate entity = repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        validateScope(entity);
        return entity;
    }

    private void validateScope(MaterialDuplicateCandidate entity) {
        if (!TenantContext.currentTenant().equals(entity.getTenantId())
            || (companyScoped() && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId()))) {
            throw new NotFoundException(entityName + " not found: " + entity.getId());
        }
    }

    protected boolean companyScoped() {
        return true;
    }

    private void normalize(MaterialDuplicateCandidate entity) {
        canonicalizePair(entity);
        catalogGuard.requireMaterial(entity.getMaterialId(), "Material");
        catalogGuard.requireMaterial(entity.getCandidateMaterialId(), "Candidate material");
        if (entity.getMaterialId().equals(entity.getCandidateMaterialId())) {
            throw new BusinessException("Material cannot be matched with itself");
        }
        if (entity.getSimilarityScore() == null) {
            entity.setSimilarityScore(BigDecimal.ZERO);
        }
        if (entity.getSimilarityScore().compareTo(BigDecimal.ZERO) < 0 || entity.getSimilarityScore().compareTo(BigDecimal.ONE) > 0) {
            throw new BusinessException("Similarity score must be between 0 and 1");
        }
    }

    private MaterialDuplicateCandidate newAiCandidate(UUID materialId, UUID candidateMaterialId) {
        MaterialDuplicateCandidate entity = new MaterialDuplicateCandidate();
        entity.setMaterialId(materialId);
        entity.setCandidateMaterialId(candidateMaterialId);
        entity.setStatus("OPEN");
        entity.setName("AI duplicate candidate");
        beforeCreate(entity);
        return entity;
    }

    private void applyAiEvidence(MaterialDuplicateCandidate entity, UUID aiJobId, MaterialDedupMatch match) {
        entity.setAiSourceJobId(aiJobId);
        entity.setSimilarityScore(parseScore(match.similarityScore(), match.similarityPercent()));
        String reason = trimToMax(firstNonBlank(match.reason(), match.verdict(), "AI duplicate candidate"), 240);
        entity.setReason(reason);
        entity.setAiEvidenceJson(writeEvidence(match));
    }

    private BigDecimal parseScore(String score, String percent) {
        String normalized = firstNonBlank(score, percent);
        if (normalized == null) {
            return BigDecimal.ZERO;
        }
        try {
            BigDecimal value = new BigDecimal(normalized.trim().replace("%", ""));
            if (percent != null && normalized.equals(percent)) {
                value = value.movePointLeft(2);
            }
            if (value.compareTo(BigDecimal.ONE) > 0) {
                value = value.movePointLeft(2);
            }
            if (value.compareTo(BigDecimal.ZERO) < 0) {
                return BigDecimal.ZERO;
            }
            if (value.compareTo(BigDecimal.ONE) > 0) {
                return BigDecimal.ONE;
            }
            return value;
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }

    private String writeEvidence(MaterialDedupMatch match) {
        try {
            return AI_EVIDENCE_OBJECT_MAPPER.writeValueAsString(match);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private void canonicalizePair(MaterialDuplicateCandidate entity) {
        UUID materialId = entity.getMaterialId();
        UUID candidateMaterialId = entity.getCandidateMaterialId();
        if (materialId == null || candidateMaterialId == null || materialId.compareTo(candidateMaterialId) <= 0) {
            return;
        }
        entity.setMaterialId(candidateMaterialId);
        entity.setCandidateMaterialId(materialId);
    }

    private UUID canonicalFirst(UUID first, UUID second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    private UUID canonicalSecond(UUID first, UUID second) {
        return first.compareTo(second) <= 0 ? second : first;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String trimToMax(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private void defaultBaseFields(MaterialDuplicateCandidate entity) {
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus("OPEN");
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
    }

    private MaterialDuplicateCandidateResponse transition(
        UUID id,
        MaterialGovernanceActionRequest request,
        Set<String> allowedStatuses,
        String targetStatus,
        String action,
        boolean merged
    ) {
        MaterialDuplicateCandidate entity = findEntity(id);
        String comment = requireCommand(entity, request, allowedStatuses, action);
        if (merged) {
            materialService.markObsolete(
                entity.getCandidateMaterialId(),
                new MaterialLifecycleRequest(entity.getMaterialId(), comment)
            );
        }
        entity.setStatus(targetStatus);
        entity.setDecisionComment(comment);
        entity.setDecidedAt(LocalDateTime.now());
        if (merged) {
            entity.setMergedAt(LocalDateTime.now());
        }
        MaterialDuplicateCandidate saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), action, comment, null, null);
        return mapper.toResponse(saved);
    }

    private String requireCommand(
        MaterialDuplicateCandidate entity,
        MaterialGovernanceActionRequest request,
        Set<String> allowedStatuses,
        String action
    ) {
        if (request == null || request.expectedVersion() == null) {
            throw new BadRequestException("expectedVersion is required for duplicate candidate commands");
        }
        if (!Objects.equals(entity.getVersion(), request.expectedVersion())) {
            throw new ConflictException(
                "Duplicate candidate version conflict: expected=" + request.expectedVersion()
                    + ", actual=" + entity.getVersion());
        }
        if (!allowedStatuses.contains(entity.getStatus())) {
            throw new BusinessException(
                "Duplicate candidate cannot " + action.toLowerCase(Locale.ROOT)
                    + " from " + entity.getStatus());
        }
        String comment = request.comment() == null ? null : request.comment().trim();
        if (comment == null || comment.isBlank()) {
            throw new BadRequestException("Comment is required for duplicate candidate commands");
        }
        return comment;
    }

    private void requireOpen(MaterialDuplicateCandidate entity) {
        if (!"OPEN".equals(entity.getStatus())) {
            throw new BusinessException("Only OPEN duplicate candidates are editable");
        }
    }
}
