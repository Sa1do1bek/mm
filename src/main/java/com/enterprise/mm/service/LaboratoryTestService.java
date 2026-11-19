package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.payload.LaboratoryTestResponse;
import com.enterprise.mm.payload.LaboratoryTestUpdateRequest;
import com.enterprise.mm.payload.LaboratoryTestCreateRequest;
import com.enterprise.mm.payload.QualityControlDtos.LaboratoryResultRequest;
import com.enterprise.mm.payload.QualityControlDtos.LimsLaboratoryResultCallback;
import com.enterprise.mm.payload.LaboratoryTestSearchRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.LaboratoryTestMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.ExternalIntegrationSystem;
import com.enterprise.mm.enums.LaboratoryTestStatus;
import com.enterprise.mm.enums.QualityInspectionStatus;
import com.enterprise.mm.entity.IntegrationEvent;
import com.enterprise.mm.entity.LaboratoryTest;
import com.enterprise.mm.entity.QualityInspection;
import com.enterprise.mm.repository.EmployeeRepository;
import com.enterprise.mm.repository.LaboratoryTestRepository;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LaboratoryTestService {
    private final String entityName = "LaboratoryTest";
    private static final String LIMS_REQUEST_EVENT = "LIMS_LAB_TEST_REQUESTED";
    private static final String LIMS_RESULT_EVENT = "LIMS_LAB_RESULT_RECEIVED";
    private static final String LIMS_REQUEST_PATH = LimsGeneralIntegrationService.CREATE_SAMPLE_PATH;
    private static final Set<String> CLOSED_INSPECTION_STATUSES = Set.of(
        QualityInspectionStatus.PASSED.name(),
        QualityInspectionStatus.FAILED.name(),
        QualityInspectionStatus.RELEASED.name(),
        QualityInspectionStatus.REJECTED.name(),
        QualityInspectionStatus.CANCELLED.name()
    );
    private final LaboratoryTestRepository repository;
    private final RoleDataScopeService roleDataScopeService;
    private final LaboratoryTestMapper mapper;
    private final QualityControlGuard guard;
    private final QualityInspectionService inspectionService;
    private final EmployeeRepository employeeRepository;
    private final IntegrationEventService integrationEventService;
    private final LimsGeneralIntegrationService limsGeneralIntegrationService;
    private final AuditService auditService;

    protected void beforeCreate(LaboratoryTest entity, LaboratoryTestCreateRequest dto) {
        normalize(entity);
        QualityInspection inspection = inspectionService.findEntityForUpdate(
            entity.getQualityInspectionId());
        if (CLOSED_INSPECTION_STATUSES.contains(inspection.getStatus())) {
            throw new BusinessException(
                "Laboratory test cannot be created when quality inspection status is "
                    + inspection.getStatus());
        }
        entity.setMaterialId(inspection.getMaterialId());
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus("ACTIVE");
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

        if (entity.getSampleNumber() == null || entity.getSampleNumber().isBlank()) {
            entity.setSampleNumber(entity.getCode());
        }
        ensureUnique(entity);
        entity.setStatus(LaboratoryTestStatus.REQUESTED.name());
        entity.setRequestedAt(LocalDateTime.now());
    }
    protected void beforeUpdate(LaboratoryTest entity, LaboratoryTestUpdateRequest dto) {
        if (!LaboratoryTestStatus.REQUESTED.name().equals(entity.getStatus()) && !LaboratoryTestStatus.IN_PROGRESS.name().equals(entity.getStatus())) {
            throw new BusinessException("Only REQUESTED or IN_PROGRESS laboratory test can be edited");
        }
        normalize(entity);
        guard.inspection(entity.getQualityInspectionId());
        ensureUnique(entity);
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }
    @Transactional
    public LaboratoryTestResponse start(UUID id) {
        LaboratoryTest test = findEntity(id);
        if (!LaboratoryTestStatus.REQUESTED.name().equals(test.getStatus())) {
            throw new BusinessException("Only REQUESTED laboratory test can be started");
        }
        startInternal(test);
        auditService.log("LaboratoryTest", id, "START", test.getSampleNumber(), null, null);
        return mapper.toResponse(repository.save(test));
    }
    @Transactional
    public LaboratoryTestResponse sendToLims(UUID id) {
        LaboratoryTest test = findEntity(id);
        if (!LaboratoryTestStatus.REQUESTED.name().equals(test.getStatus()) && !LaboratoryTestStatus.IN_PROGRESS.name().equals(test.getStatus())) {
            throw new BusinessException("Only REQUESTED or IN_PROGRESS laboratory test can be sent to LIMS");
        }
        if (LaboratoryTestStatus.REQUESTED.name().equals(test.getStatus())) {
            startInternal(test);
        } else {
            ensureInspectionReadyForLab(test);
        }
        LocalDateTime now = LocalDateTime.now();
        LimsGeneralIntegrationService.CreatedLimsSample limsSample = limsGeneralIntegrationService.createSampleIfLive(test, now).orElse(null);
        String limsSampleId = limsSample == null ? limsSampleId(test) : limsSample.sampleNo();
        test.setLimsSampleId(limsSampleId);
        test.setLimsStatus(limsSample == null ? "SENT" : "CREATED");
        test.setLimsRequestedAt(now);
        LaboratoryTest saved = repository.save(test);
        IntegrationEvent event = integrationEventService.emit(
            LIMS_REQUEST_EVENT,
            ExternalIntegrationSystem.LIMS.name(),
            saved.getId(),
            limsRequestPayload(saved, now, limsSample)
        );
        if (limsSample != null) {
            integrationEventService.markPublished(event, "Delivered to LIMS sample " + limsSample.sampleNo());
        }
        auditService.log("LaboratoryTest", id, "SEND_LIMS", limsSampleId, null, null);
        return mapper.toResponse(saved);
    }
    @Transactional
    public LaboratoryTestResponse recordResult(UUID id, LaboratoryResultRequest request) {
        LaboratoryTest test = findEntity(id);
        if (!LaboratoryTestStatus.REQUESTED.name().equals(test.getStatus()) && !LaboratoryTestStatus.IN_PROGRESS.name().equals(test.getStatus())) {
            throw new BusinessException("Only REQUESTED or IN_PROGRESS laboratory test can receive result");
        }
        ensureInspectionReadyForLab(test);
        boolean passed = request != null && Boolean.TRUE.equals(request.passed());
        test.setPassed(passed);
        test.setMeasuredValue(request == null ? test.getMeasuredValue() : request.measuredValue());
        test.setResult(request == null ? test.getResult() : request.result());
        test.setProtocolAttachmentId(request == null ? test.getProtocolAttachmentId() : request.protocolAttachmentId());
        test.setCompletedAt(LocalDateTime.now());
        test.setStatus(passed ? LaboratoryTestStatus.PASSED.name() : LaboratoryTestStatus.FAILED.name());
        LaboratoryTest saved = repository.save(test);
        inspectionService.reconcileLaboratoryStatus(
            saved.getQualityInspectionId(),
            passed ? null : request == null ? "Laboratory test failed" : request.comment()
        );
        auditService.log("LaboratoryTest", id, "RESULT", saved.getStatus(), null, null);
        return mapper.toResponse(saved);
    }
    @Transactional
    public LaboratoryTestResponse applyLimsResult(LimsLaboratoryResultCallback request) {
        LaboratoryTest test = findForLimsCallback(request);
        if (LaboratoryTestStatus.CANCELLED.name().equals(test.getStatus())) {
            throw new BusinessException("Cancelled laboratory test cannot receive LIMS result");
        }
        LocalDateTime now = LocalDateTime.now();
        if (hasText(request.limsSampleId())) {
            test.setLimsSampleId(request.limsSampleId().trim());
        } else if (!hasText(test.getLimsSampleId())) {
            test.setLimsSampleId(limsSampleId(test));
        }
        test.setLimsStatus(hasText(request.limsStatus()) ? request.limsStatus().trim().toUpperCase() : "RESULT_RECEIVED");
        test.setLimsResultReceivedAt(now);
        if (request.passed() != null) {
            boolean passed = Boolean.TRUE.equals(request.passed());
            test.setPassed(passed);
            if (request.measuredValue() != null) {
                test.setMeasuredValue(request.measuredValue());
            }
            if (request.result() != null) {
                test.setResult(request.result());
            }
            if (request.protocolAttachmentId() != null) {
                test.setProtocolAttachmentId(request.protocolAttachmentId());
            }
            test.setCompletedAt(now);
            test.setStatus(passed ? LaboratoryTestStatus.PASSED.name() : LaboratoryTestStatus.FAILED.name());
        }
        LaboratoryTest saved = repository.save(test);
        if (request.passed() != null) {
            inspectionService.reconcileLaboratoryStatus(
                saved.getQualityInspectionId(),
                Boolean.TRUE.equals(request.passed())
                    ? null
                    : hasText(request.comment()) ? request.comment() : "LIMS laboratory test failed"
            );
        }
        integrationEventService.emit(
            LIMS_RESULT_EVENT,
            "LaboratoryTest",
            saved.getId(),
            limsResultPayload(saved, request, now)
        );
        auditService.log("LaboratoryTest", saved.getId(), "LIMS_RESULT", saved.getLimsStatus(), null, null);
        return mapper.toResponse(saved);
    }
    @Transactional
    public LaboratoryTestResponse cancel(UUID id) {
        LaboratoryTest test = findEntity(id);
        if (LaboratoryTestStatus.PASSED.name().equals(test.getStatus()) || LaboratoryTestStatus.FAILED.name().equals(test.getStatus())) {
            throw new BusinessException("Completed laboratory test cannot be cancelled");
        }
        test.setStatus(LaboratoryTestStatus.CANCELLED.name());
        auditService.log("LaboratoryTest", id, "CANCEL", test.getSampleNumber(), null, null);
        return mapper.toResponse(repository.save(test));
    }
    private void normalize(LaboratoryTest entity) {
        if (entity.getTestType() == null || entity.getTestType().isBlank()) {
            throw new BusinessException("testType is required");
        }
        entity.setTestType(entity.getTestType().trim().toUpperCase().replace('-', '_').replace(' ', '_'));
        if (entity.getSampleNumber() != null) {
            entity.setSampleNumber(entity.getSampleNumber().trim());
        }
        if (entity.getLimsSampleId() != null) {
            entity.setLimsSampleId(entity.getLimsSampleId().trim());
        }
        if (entity.getLimsStatus() != null) {
            entity.setLimsStatus(entity.getLimsStatus().trim().toUpperCase().replace('-', '_').replace(' ', '_'));
        }
        if (entity.getLabTechnicianEmployeeId() != null && employeeRepository.findByIdAndIsDeletedFalse(entity.getLabTechnicianEmployeeId()).isEmpty()) {
            throw new BusinessException("labTechnicianEmployeeId not found");
        }
    }
    private void ensureUnique(LaboratoryTest entity) {
        if (entity.getSampleNumber() == null || entity.getSampleNumber().isBlank()) {
            return;
        }
        repository.findByTenantIdAndCompanyIdAndSampleNumberAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany(), entity.getSampleNumber())
            .filter(existing -> !existing.getId().equals(entity.getId()))
            .ifPresent(existing -> {
                throw new BusinessException("Laboratory sample number already exists");
            });
    }
    private void startInternal(LaboratoryTest test) {
        test.setStatus(LaboratoryTestStatus.IN_PROGRESS.name());
        if (test.getStartedAt() == null) {
            test.setStartedAt(LocalDateTime.now());
        }
        ensureInspectionReadyForLab(test);
    }
    private void ensureInspectionReadyForLab(LaboratoryTest test) {
        QualityInspection inspection = guard.inspection(test.getQualityInspectionId());
        if (QualityInspectionStatus.LAB_TESTING.name().equals(inspection.getStatus())) {
            return;
        }
        if (QualityInspectionStatus.IN_QUARANTINE.name().equals(inspection.getStatus()) || QualityInspectionStatus.SAMPLING.name().equals(inspection.getStatus())) {
            inspectionService.sendToLab(test.getQualityInspectionId());
            return;
        }
        throw new BusinessException("Quality inspection must be IN_QUARANTINE, SAMPLING or LAB_TESTING before laboratory test can start");
    }
    private String limsSampleId(LaboratoryTest test) {
        if (hasText(test.getLimsSampleId())) {
            return test.getLimsSampleId().trim();
        }
        if (hasText(test.getSampleNumber())) {
            return test.getSampleNumber().trim();
        }
        return "LIMS-" + test.getId();
    }
    private LaboratoryTest findForLimsCallback(LimsLaboratoryResultCallback request) {
        if (request == null) {
            throw new BusinessException("LIMS callback payload is required");
        }
        if (request.laboratoryTestId() != null) {
            return findEntity(request.laboratoryTestId());
        }
        if (hasText(request.limsSampleId())) {
            return repository.findByTenantIdAndCompanyIdAndLimsSampleIdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany(), request.limsSampleId().trim())
                .orElseThrow(() -> new BusinessException("Laboratory test not found by LIMS sample id"));
        }
        if (hasText(request.sampleNumber())) {
            return repository.findByTenantIdAndCompanyIdAndSampleNumberAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany(), request.sampleNumber().trim())
                .orElseThrow(() -> new BusinessException("Laboratory test not found by sample number"));
        }
        throw new BusinessException("laboratoryTestId, limsSampleId or sampleNumber is required");
    }
    private Map<String, Object> limsRequestPayload(
        LaboratoryTest test,
        LocalDateTime requestedAt,
        LimsGeneralIntegrationService.CreatedLimsSample limsSample
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("targetPath", LIMS_REQUEST_PATH);
        payload.put("raw", limsSample == null ? limsRequestBody(test, requestedAt) : limsSample.request());
        if (limsSample != null) {
            payload.put("limsSampleId", limsSample.sampleId());
            payload.put("limsSampleNo", limsSample.sampleNo());
            payload.put("response", limsSample.response());
        }
        return payload;
    }
    private Map<String, Object> limsRequestBody(LaboratoryTest test, LocalDateTime requestedAt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sourceSystem", "MM");
        body.put("laboratoryTestId", test.getId());
        body.put("qualityInspectionId", test.getQualityInspectionId());
        body.put("materialId", test.getMaterialId());
        body.put("sampleNumber", test.getSampleNumber());
        body.put("limsSampleId", test.getLimsSampleId());
        body.put("testType", test.getTestType());
        body.put("testMethod", test.getTestMethod());
        body.put("specification", test.getSpecification());
        body.put("requestedAt", requestedAt.toString());
        return body;
    }
    private Map<String, Object> limsResultPayload(LaboratoryTest test, LimsLaboratoryResultCallback request, LocalDateTime receivedAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("laboratoryTestId", test.getId());
        payload.put("limsSampleId", test.getLimsSampleId());
        payload.put("sampleNumber", test.getSampleNumber());
        payload.put("limsStatus", test.getLimsStatus());
        payload.put("passed", request.passed());
        payload.put("measuredValue", test.getMeasuredValue());
        payload.put("result", test.getResult());
        payload.put("receivedAt", receivedAt.toString());
        return payload;
    }
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
    @Transactional(readOnly = true)
    public Page<LaboratoryTestResponse> search(LaboratoryTestSearchRequest request, Pageable pageable) {
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
            roleDataScopeService.effectiveWarehouseId(null),
            pageable
        ).map(mapper::toResponse);
    }
    @Transactional(readOnly = true)
    public LaboratoryTestResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public LaboratoryTestResponse create(LaboratoryTestCreateRequest dto) {
        LaboratoryTest entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        LaboratoryTest saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public LaboratoryTestResponse update(UUID id, LaboratoryTestUpdateRequest dto) {
        LaboratoryTest entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        LaboratoryTest saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        LaboratoryTest entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public LaboratoryTestResponse restore(UUID id) {
        LaboratoryTest entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        guard.inspection(entity.getQualityInspectionId());
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        LaboratoryTest saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected LaboratoryTest findEntity(UUID id) {
        LaboratoryTest entity = repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (entity.isDeleted()) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        guard.inspection(entity.getQualityInspectionId());
        return entity;
    }
    protected boolean companyScoped() {
        return true;
    }
}
