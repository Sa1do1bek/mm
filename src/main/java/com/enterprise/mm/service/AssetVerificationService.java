package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AssetVerification;
import com.enterprise.mm.entity.AssetVerificationLine;
import com.enterprise.mm.entity.FixedAsset;
import com.enterprise.mm.enums.AssetVerificationDiscrepancyType;
import com.enterprise.mm.enums.AssetVerificationResolutionStatus;
import com.enterprise.mm.enums.AssetVerificationStatus;
import com.enterprise.mm.enums.AssetVerificationType;
import com.enterprise.mm.enums.FixedAssetCustodyKind;
import com.enterprise.mm.enums.FixedAssetLifecycleStatus;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.AssetFoundationMapper;
import com.enterprise.mm.payload.AssetVerificationCompleteRequest;
import com.enterprise.mm.payload.AssetVerificationCreateRequest;
import com.enterprise.mm.payload.AssetVerificationDiscrepancyRequest;
import com.enterprise.mm.payload.AssetVerificationDiscrepancyReviewRequest;
import com.enterprise.mm.payload.AssetVerificationLineResponse;
import com.enterprise.mm.payload.AssetVerificationResponse;
import com.enterprise.mm.payload.AssetVerificationScanRequest;
import com.enterprise.mm.payload.AssetVerificationSearchRequest;
import com.enterprise.mm.payload.VersionedAssetCommand;
import com.enterprise.mm.repository.AssetVerificationLineRepository;
import com.enterprise.mm.repository.AssetVerificationRepository;
import com.enterprise.mm.repository.FixedAssetRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@com.enterprise.mm.security.pilot.PilotCapabilityBoundary(value = com.enterprise.mm.security.pilot.PilotCapability.FIXED_ASSET_EXECUTION, reads = {"search", "get"})
public class AssetVerificationService {
    private static final String OBSERVATIONAL_ONLY_CONTRACT = "ASSET_VERIFICATION_OBSERVATIONAL_ONLY";
    // Asset verification is observational: discrepancies never mutate asset custody or lifecycle.

    private final AssetVerificationRepository repository;
    private final AssetVerificationLineRepository lineRepository;
    private final FixedAssetRepository fixedAssetRepository;
    private final AssetFoundationMapper mapper;
    private final IdempotencyService idempotencyService;

    @Autowired(required = false)
    private AssetIntegrationEventPublisher assetIntegrationEventPublisher;

    @Transactional(readOnly = true)
    public Page<AssetVerificationResponse> search(AssetVerificationSearchRequest request, Pageable pageable) {
        return repository.findByTenantIdAndCompanyIdAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), pageable)
            .map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public AssetVerificationResponse get(UUID id) {
        return toResponse(find(id));
    }

    @Transactional
    public AssetVerificationResponse create(AssetVerificationCreateRequest request) {
        return create(request, null);
    }

    @Transactional
    public AssetVerificationResponse create(AssetVerificationCreateRequest request, String idempotencyKey) {
        if (hasText(idempotencyKey)) {
            return idempotencyService.execute(
                "ASSET_VERIFICATION_CREATE", null, idempotencyKey, request, AssetVerificationResponse.class,
                () -> createNow(request)
            );
        }
        return createNow(request);
    }

    private AssetVerificationResponse createNow(AssetVerificationCreateRequest request) {
        AssetVerificationType type = parseType(request == null ? null : request.verificationType());
        AssetVerification verification = new AssetVerification();
        verification.setCode("AV-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        verification.setName("Asset verification " + type.name().toLowerCase(Locale.ROOT).replace('_', ' '));
        verification.setVerificationStatus(AssetVerificationStatus.IN_PROGRESS);
        verification.setVerificationType(type);
        verification.setWarehouseId(request == null ? null : request.warehouseId());
        verification.setDepartmentId(request == null ? null : request.departmentId());
        verification.setStartedAt(LocalDateTime.now());
        verification = repository.save(verification);

        for (FixedAsset asset : expectedAssets(type, verification.getWarehouseId(), verification.getDepartmentId())) {
            AssetVerificationLine line = new AssetVerificationLine();
            line.setCode("AVL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
            line.setName("Asset verification line for " + BusinessDisplayNamePolicy.requireHumanName("FixedAsset", asset.getName(), asset.getCode()));
            line.setStatus("OPEN");
            line.setAssetVerificationId(verification.getId());
            line.setFixedAssetId(asset.getId());
            line.setExpectedCustodyKind(asset.getCustodyKind());
            line.setExpectedEmployeeId(asset.getCustodianEmployeeId());
            line.setExpectedDepartmentId(asset.getDepartmentId());
            line.setExpectedWarehouseId(asset.getWarehouseId());
            line.setResolutionStatus(AssetVerificationResolutionStatus.OPEN);
            lineRepository.save(line);
        }
        return toResponse(verification);
    }

    @Transactional
    public AssetVerificationResponse scan(UUID id, AssetVerificationScanRequest request) {
        return scan(id, request, null);
    }

    @Transactional
    public AssetVerificationResponse scan(UUID id, AssetVerificationScanRequest request, String idempotencyKey) {
        if (hasText(idempotencyKey)) {
            return idempotencyService.execute(
                "ASSET_VERIFICATION_SCAN", id, idempotencyKey, request, AssetVerificationResponse.class,
                () -> find(id),
                () -> scanNow(id, request, idempotencyKey)
            );
        }
        return scanNow(id, request, null);
    }

    private AssetVerificationResponse scanNow(UUID id, AssetVerificationScanRequest request, String idempotencyKey) {
        AssetVerification verification = find(id);
        requireVersion(verification, request == null ? null : request.expectedVersion());
        requireStatus(verification, AssetVerificationStatus.IN_PROGRESS);
        FixedAsset asset = findScannedAsset(request);
        FixedAssetLifecycleStatus beforeLifecycle = asset.getLifecycleStatus();
        FixedAssetCustodyKind beforeCustody = asset.getCustodyKind();
        AssetVerificationLine line = lines(verification.getId()).stream()
            .filter(row -> row.getFixedAssetId().equals(asset.getId()))
            .findFirst()
            .orElseGet(() -> createExtraScanLine(verification, asset));
        line.setObservedCustodyKind(parseCustody(request.observedCustodyKind(), asset.getCustodyKind()));
        line.setObservedEmployeeId(request.observedEmployeeId());
        line.setObservedDepartmentId(request.observedDepartmentId());
        line.setObservedWarehouseId(request.observedWarehouseId());
        line.setObservedAt(LocalDateTime.now());
        line.setDiscrepancyType(discrepancy(line));
        line.setResolutionStatus(line.getDiscrepancyType() == null
            ? AssetVerificationResolutionStatus.ACCEPTED
            : AssetVerificationResolutionStatus.OPEN);
        lineRepository.save(line);
        if (line.getDiscrepancyType() != null) {
            publishDiscrepancyEvent(asset, beforeLifecycle, beforeCustody, verification, idempotencyKey);
        }
        return toResponse(repository.save(verification));
    }

    @Transactional
    public AssetVerificationResponse recordDiscrepancy(UUID id, AssetVerificationDiscrepancyRequest request) {
        AssetVerification verification = find(id);
        requireVersion(verification, request == null ? null : request.expectedVersion());
        AssetVerificationLine line = lines(verification.getId()).stream()
            .filter(row -> Objects.equals(row.getFixedAssetId(), request.fixedAssetId()))
            .findFirst()
            .orElseThrow(() -> new NotFoundException("Asset verification line not found for asset: " + request.fixedAssetId()));
        line.setDiscrepancyType(parseDiscrepancy(request.discrepancyType()));
        line.setResolutionStatus(AssetVerificationResolutionStatus.OPEN);
        lineRepository.save(line);
        FixedAsset asset = findAsset(line.getFixedAssetId());
        publishDiscrepancyEvent(asset, asset.getLifecycleStatus(), asset.getCustodyKind(), verification, null);
        return toResponse(repository.save(verification));
    }

    @Transactional
    public AssetVerificationResponse complete(UUID id, AssetVerificationCompleteRequest request) {
        AssetVerification verification = find(id);
        requireVersion(verification, request == null ? null : request.expectedVersion());
        requireStatus(verification, AssetVerificationStatus.IN_PROGRESS);
        for (AssetVerificationLine line : lines(verification.getId())) {
            if (line.getObservedAt() == null && line.getDiscrepancyType() == null) {
                line.setDiscrepancyType(AssetVerificationDiscrepancyType.MISSING_SCAN);
                line.setResolutionStatus(AssetVerificationResolutionStatus.OPEN);
                lineRepository.save(line);
                FixedAsset asset = findAsset(line.getFixedAssetId());
                publishDiscrepancyEvent(asset, asset.getLifecycleStatus(), asset.getCustodyKind(), verification, null);
            }
        }
        verification.setVerificationStatus(AssetVerificationStatus.COMPLETED);
        verification.setCompletedAt(LocalDateTime.now());
        return toResponse(repository.save(verification));
    }

    @Transactional
    public AssetVerificationResponse reviewDiscrepancies(UUID id, AssetVerificationDiscrepancyReviewRequest request) {
        AssetVerification verification = find(id);
        requireVersion(verification, request == null ? null : request.expectedVersion());
        List<AssetVerificationLine> existingLines = lines(verification.getId());
        for (var reviewed : request == null || request.discrepancies() == null ? List.<AssetVerificationDiscrepancyReviewRequest.ReviewedDiscrepancy>of() : request.discrepancies()) {
            AssetVerificationLine line = existingLines.stream()
                .filter(row -> row.getId().equals(reviewed.assetVerificationLineId()))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Asset verification line not found: " + reviewed.assetVerificationLineId()));
            line.setResolutionStatus(parseResolution(reviewed.resolutionStatus()));
            lineRepository.save(line);
        }
        return toResponse(repository.save(verification));
    }

    @Transactional
    public AssetVerificationResponse close(UUID id, VersionedAssetCommand request) {
        AssetVerification verification = find(id);
        requireVersion(verification, request == null ? null : request.expectedVersion());
        requireStatus(verification, AssetVerificationStatus.COMPLETED);
        verification.setVerificationStatus(AssetVerificationStatus.CLOSED);
        return toResponse(repository.save(verification));
    }

    private AssetVerification find(UUID id) {
        return repository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), id)
            .orElseThrow(() -> new NotFoundException("Asset verification not found: " + id));
    }

    private FixedAsset findScannedAsset(AssetVerificationScanRequest request) {
        if (request == null) {
            throw bad("Scan request is required", "ASSET_EVIDENCE_REQUIRED");
        }
        if (request.fixedAssetId() != null) {
            return fixedAssetRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
                    TenantContext.currentTenant(), CompanyContext.currentCompany(), request.fixedAssetId())
                .orElseThrow(() -> new NotFoundException("Fixed asset not found: " + request.fixedAssetId()));
        }
        if (hasText(request.inventoryNumber())) {
            return fixedAssetRepository.findByTenantIdAndCompanyIdAndInventoryNumberAndIsDeletedFalse(
                    TenantContext.currentTenant(), CompanyContext.currentCompany(), request.inventoryNumber().trim())
                .orElseThrow(() -> new NotFoundException("Fixed asset not found for inventory number: " + request.inventoryNumber()));
        }
        throw bad("fixedAssetId or inventoryNumber is required", "ASSET_EVIDENCE_REQUIRED");
    }

    private FixedAsset findAsset(UUID fixedAssetId) {
        return fixedAssetRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), fixedAssetId)
            .orElseThrow(() -> new NotFoundException("Fixed asset not found: " + fixedAssetId));
    }

    private void publishDiscrepancyEvent(
        FixedAsset asset,
        FixedAssetLifecycleStatus beforeLifecycle,
        FixedAssetCustodyKind beforeCustody,
        AssetVerification verification,
        String idempotencyKey
    ) {
        if (assetIntegrationEventPublisher != null) {
            assetIntegrationEventPublisher.publish(
                asset,
                "ASSET_VERIFICATION_DISCREPANCY_RECORDED",
                beforeLifecycle,
                beforeCustody,
                "ASSET_VERIFICATION",
                verification.getId(),
                java.time.LocalDate.now(),
                "system",
                idempotencyKey
            );
        }
    }

    private List<FixedAsset> expectedAssets(AssetVerificationType type, UUID warehouseId, UUID departmentId) {
        if (type == AssetVerificationType.WAREHOUSE) {
            if (warehouseId == null) {
                throw bad("warehouseId is required for warehouse verification", "ASSET_EVIDENCE_REQUIRED");
            }
            return fixedAssetRepository.findByTenantIdAndCompanyIdAndWarehouseIdAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), warehouseId);
        }
        if (type == AssetVerificationType.DEPARTMENT) {
            if (departmentId == null) {
                throw bad("departmentId is required for department verification", "ASSET_EVIDENCE_REQUIRED");
            }
            return fixedAssetRepository.findByTenantIdAndCompanyIdAndDepartmentIdAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), departmentId);
        }
        return List.of();
    }

    private AssetVerificationLine createExtraScanLine(AssetVerification verification, FixedAsset asset) {
        AssetVerificationLine line = new AssetVerificationLine();
        line.setCode("AVL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        line.setName("Extra scanned asset " + BusinessDisplayNamePolicy.requireHumanName("FixedAsset", asset.getName(), asset.getCode()));
        line.setStatus("OPEN");
        line.setAssetVerificationId(verification.getId());
        line.setFixedAssetId(asset.getId());
        line.setExpectedCustodyKind(asset.getCustodyKind());
        line.setExpectedEmployeeId(asset.getCustodianEmployeeId());
        line.setExpectedDepartmentId(asset.getDepartmentId());
        line.setExpectedWarehouseId(asset.getWarehouseId());
        line.setDiscrepancyType(AssetVerificationDiscrepancyType.EXTRA_SCAN);
        line.setResolutionStatus(AssetVerificationResolutionStatus.OPEN);
        return lineRepository.save(line);
    }

    private AssetVerificationDiscrepancyType discrepancy(AssetVerificationLine line) {
        if (line.getDiscrepancyType() == AssetVerificationDiscrepancyType.EXTRA_SCAN) {
            return AssetVerificationDiscrepancyType.EXTRA_SCAN;
        }
        if (line.getObservedCustodyKind() != line.getExpectedCustodyKind()) {
            return AssetVerificationDiscrepancyType.LOCATION_MISMATCH;
        }
        if (!Objects.equals(line.getObservedWarehouseId(), line.getExpectedWarehouseId())
            || !Objects.equals(line.getObservedDepartmentId(), line.getExpectedDepartmentId())) {
            return AssetVerificationDiscrepancyType.LOCATION_MISMATCH;
        }
        if (!Objects.equals(line.getObservedEmployeeId(), line.getExpectedEmployeeId())) {
            return AssetVerificationDiscrepancyType.CUSTODIAN_MISMATCH;
        }
        return null;
    }

    private AssetVerificationResponse toResponse(AssetVerification verification) {
        List<AssetVerificationLineResponse> lines = lineRepository
            .findByTenantIdAndCompanyIdAndAssetVerificationIdAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), verification.getId())
            .stream()
            .map(mapper::toResponse)
            .toList();
        return mapper.toResponse(verification, lines);
    }

    private void requireVersion(AssetVerification verification, Long expectedVersion) {
        if (expectedVersion == null) {
            throw bad("expectedVersion is required", "ASSET_VERSION_CONFLICT");
        }
        if (!Objects.equals(verification.getVersion(), expectedVersion)) {
            throw new ConflictException("Asset verification version conflict", "ASSET_VERSION_CONFLICT");
        }
    }

    private void requireStatus(AssetVerification verification, AssetVerificationStatus status) {
        if (verification.getVerificationStatus() != status) {
            throw bad("Asset verification status does not allow this transition", "ASSET_INVALID_TRANSITION");
        }
    }

    private List<AssetVerificationLine> lines(UUID verificationId) {
        return lineRepository.findByTenantIdAndCompanyIdAndAssetVerificationIdAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), verificationId);
    }

    private AssetVerificationType parseType(String value) {
        if (!hasText(value)) {
            return AssetVerificationType.FULL;
        }
        try {
            return AssetVerificationType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw bad("Unknown asset verification type: " + value, "ASSET_INVALID_TRANSITION");
        }
    }

    private FixedAssetCustodyKind parseCustody(String value, FixedAssetCustodyKind fallback) {
        if (!hasText(value)) {
            return fallback;
        }
        try {
            return FixedAssetCustodyKind.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw bad("Unknown fixed asset custody kind: " + value, "ASSET_INVALID_TRANSITION");
        }
    }

    private AssetVerificationDiscrepancyType parseDiscrepancy(String value) {
        if (!hasText(value)) {
            return AssetVerificationDiscrepancyType.MISSING_SCAN;
        }
        try {
            return AssetVerificationDiscrepancyType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw bad("Unknown discrepancy type: " + value, "ASSET_INVALID_TRANSITION");
        }
    }

    private AssetVerificationResolutionStatus parseResolution(String value) {
        if (!hasText(value)) {
            return AssetVerificationResolutionStatus.UNDER_REVIEW;
        }
        try {
            return AssetVerificationResolutionStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw bad("Unknown resolution status: " + value, "ASSET_INVALID_TRANSITION");
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private BadRequestException bad(String message, String code) {
        return new BadRequestException(message, code);
    }
}
