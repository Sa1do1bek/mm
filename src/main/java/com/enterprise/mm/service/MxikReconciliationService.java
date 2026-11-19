package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.MXIK;
import com.enterprise.mm.entity.AiAnalysisJob;
import com.enterprise.mm.enums.AiAnalysisJobStatus;
import com.enterprise.mm.enums.AiAnalysisType;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.repository.AiAnalysisJobRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.MXIKRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MxikReconciliationService {
    private final MaterialRepository materials;
    private final MXIKRepository mxiks;
    private final MaterialBusinessTypePolicy materialTypePolicy;
    private final AuditService auditService;
    private final MaterialWmsIntegrationPublisher materialWmsIntegrationPublisher;
    private final AiAnalysisJobRepository aiJobs;
    private final ObjectMapper objectMapper;

    public record MaterialRow(UUID id, String code, String name, String materialType,
                              String groupName, String mxikCode, String mxikName, Long version,
                              String suggestedMxikCode, UUID suggestionJobId, String suggestionConfidence,
                              String suggestionReason) {}
    public record Assignment(UUID materialId, Long expectedVersion, String mxikCode, UUID recommendationJobId) {
        public Assignment(UUID materialId, Long expectedVersion, String mxikCode) {
            this(materialId, expectedVersion, mxikCode, null);
        }
    }
    public record AssignmentRequest(List<Assignment> assignments) {}

    @Transactional(readOnly = true)
    public Page<MaterialRow> list(boolean missingOnly, String search, Pageable pageable) {
        String term = search == null || search.isBlank() ? null : search.trim().toLowerCase(Locale.ROOT);
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        Page<Material> result = term == null
            ? materials.findForMxikReconciliation(tenantId, companyId, missingOnly, pageable)
            : materials.searchForMxikReconciliation(tenantId, companyId, missingOnly, term, pageable);
        Map<String, String> mxikNames = mxikNames(result.getContent());
        var latestJobs = latestMxikJobs(result.getContent());
        return result.map(material -> row(material, mxikName(material, mxikNames), latestJobs.get(material.getId())));
    }

    @Transactional(readOnly = true)
    public MaterialRow get(UUID id) {
        Material material = materials.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), id)
            .filter(item -> CompanyContext.currentCompany().equals(item.getCompanyId()))
            .orElseThrow(() -> new BadRequestException("Material not found in current company"));
        return row(material, mxikName(material, mxikNames(List.of(material))),
            latestMxikJobs(List.of(material)).get(material.getId()));
    }

    @Transactional(readOnly = true)
    public void validateMaterialAccess(UUID id) {
        materials.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), id)
            .filter(item -> CompanyContext.currentCompany().equals(item.getCompanyId()))
            .orElseThrow(() -> new BadRequestException("Material not found in current company"));
    }

    @Transactional(readOnly = true)
    public List<MaterialRow> sameGroupWithMxik(UUID id) {
        Material material = materials.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), id)
            .filter(item -> CompanyContext.currentCompany().equals(item.getCompanyId()))
            .orElseThrow(() -> new BadRequestException("Material not found in current company"));
        if (material.getMaterialGroupId() == null) return List.of();
        List<Material> peers = materials.findMxikPeers(TenantContext.currentTenant(), CompanyContext.currentCompany(),
            id, material.getMaterialGroupId(), org.springframework.data.domain.PageRequest.of(0, 200))
            .stream()
            .sorted(Comparator.comparingInt((Material peer) -> sharedNameTokens(material.getName(), peer.getName()))
                .reversed().thenComparing(Material::getName))
            .limit(10).toList();
        Map<String, String> mxikNames = mxikNames(peers);
        return peers.stream().map(peer -> row(peer, mxikName(peer, mxikNames))).toList();
    }

    @Transactional
    public List<MaterialRow> assign(AssignmentRequest request) {
        if (request == null || request.assignments() == null || request.assignments().isEmpty()
            || request.assignments().size() > 100) {
            throw new BadRequestException("Select between 1 and 100 materials");
        }
        var seen = new HashSet<UUID>();
        for (Assignment assignment : request.assignments()) {
            if (assignment == null || assignment.materialId() == null || assignment.expectedVersion() == null
                || assignment.mxikCode() == null || assignment.mxikCode().isBlank()
                || !seen.add(assignment.materialId())) {
                throw new BadRequestException("Each selected material needs a unique ID, version and MXIK code");
            }
        }
        var result = new ArrayList<MaterialRow>();
        for (Assignment assignment : request.assignments().stream()
            .sorted(Comparator.comparing(Assignment::materialId)).toList()) {
            Material material = materials.lockForMxikReconciliation(TenantContext.currentTenant(),
                CompanyContext.currentCompany(), assignment.materialId())
                .orElseThrow(() -> new BadRequestException("Material not found in current company"));
            if (!assignment.expectedVersion().equals(material.getVersion())) {
                throw new BadRequestException("Material changed since review; reload before assigning MXIK");
            }
            MXIK mxik = mxiks.findByKod(assignment.mxikCode().trim())
                .orElseThrow(() -> new BadRequestException("Unknown MXIK code"));
            materialTypePolicy.validateMxikCompatibility(material.getMaterialType(), mxik);
            if (assignment.recommendationJobId() != null) {
                verifyRecommendation(assignment.recommendationJobId(), material, mxik.getKod());
            }
            String oldSnapshot = auditService.snapshot(material);
            material.setMxikCode(mxik.getKod());
            material.setMxik(mxik);
            Material saved = materials.saveAndFlush(material);
            materialWmsIntegrationPublisher.publish(saved);
            auditService.logInCurrentTransaction("Material", saved.getId(),
                assignment.recommendationJobId() == null ? "MXIK_ASSIGN_MANUAL" : "MXIK_APPROVE_AI",
                saved.getCode(), oldSnapshot, saved);
            result.add(row(saved, mxik.getName()));
        }
        return result;
    }

    private void verifyRecommendation(UUID jobId, Material material, String code) {
        AiAnalysisJob job = aiJobs.findScoped(jobId, TenantContext.currentTenant(), CompanyContext.currentCompany())
            .orElseThrow(() -> new BadRequestException("AI recommendation job not found"));
        if (job.getStatus() != AiAnalysisJobStatus.COMPLETED
            || job.getAnalysisType() == null
            || !"MATERIAL_MXIK_MATCH".equals(job.getAnalysisType().name())
            || !"MATERIAL".equals(job.getEntityType())
            || !material.getId().equals(job.getEntityId())) {
            throw new BadRequestException("AI recommendation does not match this material");
        }
        if (job.getResultPayload() == null || job.getResultPayload().isBlank()) {
            throw new BadRequestException("AI recommendation result is missing");
        }
        try {
            var result = objectMapper.readTree(job.getResultPayload());
            if (result == null || !"RECOMMENDED".equals(result.path("status").asText())
                || !code.equals(result.path("recommendedMxik").path("code").asText())
                || !material.getId().toString().equals(result.path("materialId").asText())
                || material.getVersion() == null
                || material.getVersion().longValue() != result.path("materialVersion").asLong(-1L)) {
                throw new BadRequestException("AI recommendation is stale or differs from the selected MXIK");
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new BadRequestException("AI recommendation result is invalid");
        }
    }

    private Map<String, String> mxikNames(List<Material> materials) {
        Set<String> codes = materials.stream().map(Material::getMxikCode)
            .filter(code -> code != null && !code.isBlank()).collect(Collectors.toSet());
        if (codes.isEmpty()) return Map.of();
        return mxiks.findByKodIn(codes).stream()
            .collect(Collectors.toMap(MXIK::getKod, MXIK::getName));
    }

    private String mxikName(Material material, Map<String, String> names) {
        String code = material.getMxikCode();
        return code == null ? null : names.get(code);
    }

    private MaterialRow row(Material material, String mxikName) {
        return row(material, mxikName, null);
    }

    private MaterialRow row(Material material, String mxikName, AiAnalysisJob job) {
        String suggestedCode = null;
        UUID suggestionJobId = null;
        String confidence = null;
        String reason = null;
        if (job != null && job.getStatus() == AiAnalysisJobStatus.COMPLETED
            && job.getResultPayload() != null && material.getVersion() != null) {
            try {
                var result = objectMapper.readTree(job.getResultPayload());
                if ("RECOMMENDED".equals(result.path("status").asText())
                    && material.getId().toString().equals(result.path("materialId").asText())
                    && material.getVersion().longValue() == result.path("materialVersion").asLong(-1L)) {
                    String code = result.path("recommendedMxik").path("code").asText("");
                    if (!code.isBlank()) {
                        suggestedCode = code;
                        suggestionJobId = job.getId();
                        confidence = result.path("confidence").asText(null);
                        reason = result.path("reason").asText(null);
                    }
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
                // Invalid upstream payload is never offered for approval.
            }
        }
        return new MaterialRow(material.getId(), material.getCode(), material.getName(),
            material.getMaterialType() == null ? null : material.getMaterialType().name(),
            material.getMaterialGroup() == null ? null : material.getMaterialGroup().getName(),
            material.getMxikCode(), mxikName, material.getVersion(),
            suggestedCode, suggestionJobId, confidence, reason);
    }

    private Map<UUID, AiAnalysisJob> latestMxikJobs(List<Material> page) {
        if (page.isEmpty()) return Map.of();
        Set<UUID> ids = page.stream().map(Material::getId).collect(Collectors.toSet());
        return aiJobs.findForMaterials(TenantContext.currentTenant(), CompanyContext.currentCompany(),
                AiAnalysisType.MATERIAL_MXIK_MATCH, ids).stream()
            .collect(Collectors.toMap(AiAnalysisJob::getEntityId, job -> job,
                (first, second) -> first.getRequestedAt().isAfter(second.getRequestedAt()) ? first : second));
    }

    private int sharedNameTokens(String first, String second) {
        Set<String> tokens = java.util.Arrays.stream((first == null ? "" : first.toLowerCase(Locale.ROOT))
            .split("[^\\p{L}\\p{N}]+")).filter(token -> token.length() >= 3)
            .collect(java.util.stream.Collectors.toSet());
        return (int) java.util.Arrays.stream((second == null ? "" : second.toLowerCase(Locale.ROOT))
            .split("[^\\p{L}\\p{N}]+")).filter(token -> token.length() >= 3)
            .distinct().filter(tokens::contains).count();
    }
}
