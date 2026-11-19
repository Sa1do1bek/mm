package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AssetAllocation;
import com.enterprise.mm.entity.AssetEvent;
import com.enterprise.mm.entity.AssetHandover;
import com.enterprise.mm.entity.AssetHandoverLine;
import com.enterprise.mm.entity.Contract;
import com.enterprise.mm.entity.FixedAsset;
import com.enterprise.mm.enums.AssetAllocationStatus;
import com.enterprise.mm.enums.AssetHandoverLineStatus;
import com.enterprise.mm.enums.AssetHandoverStatus;
import com.enterprise.mm.enums.AssetHandoverType;
import com.enterprise.mm.enums.FixedAssetCustodyKind;
import com.enterprise.mm.enums.FixedAssetLifecycleStatus;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.AssetFoundationMapper;
import com.enterprise.mm.payload.AssetHandoverCreateRequest;
import com.enterprise.mm.payload.AssetHandoverLineRequest;
import com.enterprise.mm.payload.AssetHandoverLineResponse;
import com.enterprise.mm.payload.AssetHandoverResponse;
import com.enterprise.mm.payload.AssetHandoverSearchRequest;
import com.enterprise.mm.payload.AssetHandoverUpdateRequest;
import com.enterprise.mm.payload.VersionedAssetCommand;
import com.enterprise.mm.repository.AssetAllocationRepository;
import com.enterprise.mm.repository.AssetEventRepository;
import com.enterprise.mm.repository.AssetHandoverLineRepository;
import com.enterprise.mm.repository.AssetHandoverRepository;
import com.enterprise.mm.repository.ContractRepository;
import com.enterprise.mm.repository.ContractSpecificationRepository;
import com.enterprise.mm.repository.FixedAssetRepository;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@com.enterprise.mm.security.pilot.PilotCapabilityBoundary(value = com.enterprise.mm.security.pilot.PilotCapability.FIXED_ASSET_EXECUTION, reads = {"search", "get", "findForApproval"})
public class AssetHandoverService {
    public static final String APPROVAL_MODULE = "ASSET_HANDOVER";

    private final AssetHandoverRepository repository;
    private final AssetHandoverLineRepository lineRepository;
    private final AssetAllocationRepository allocationRepository;
    private final FixedAssetRepository fixedAssetRepository;
    private final AssetEventRepository eventRepository;
    private final AssetFoundationMapper mapper;
    private final ApprovalEngineService approvalEngineService;
    private final IdempotencyService idempotencyService;
    private final ContractRepository contractRepository;
    private final ContractSpecificationRepository contractSpecificationRepository;
    private final RoleDataScopeService roleDataScopeService;

    @Autowired(required = false)
    private AssetIntegrationEventPublisher assetIntegrationEventPublisher;

    @Transactional(readOnly = true)
    public Page<AssetHandoverResponse> search(AssetHandoverSearchRequest request, Pageable pageable) {
        AssetHandoverStatus status = parseOptionalStatus(request == null ? null : request.status());
        AssetHandoverType handoverType = parseOptionalHandoverType(request == null ? null : request.handoverType());
        UUID fixedAssetId = request == null ? null : request.fixedAssetId();
        UUID fromWarehouseId = request == null ? null : request.fromWarehouseId();
        boolean departmentScoped = roleDataScopeService.isDepartmentAssetScoped();
        java.util.Set<UUID> warehouseIds = java.util.Set.of();
        if (departmentScoped) {
            var scope = roleDataScopeService.currentScope();
            if (scope.warehouseScoped()) warehouseIds = scope.warehouseIds();
        }
        return repository.search(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), status, handoverType, fixedAssetId, fromWarehouseId,
                departmentScoped, roleDataScopeService.issueDepartmentId(), warehouseIds, warehouseIds.isEmpty(), pageable)
            .map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public AssetHandoverResponse get(UUID id) {
        return toResponse(find(id));
    }

    @Transactional
    public AssetHandoverResponse create(AssetHandoverCreateRequest request) {
        return create(request, null);
    }

    @Transactional
    public AssetHandoverResponse create(AssetHandoverCreateRequest request, String idempotencyKey) {
        if (hasText(idempotencyKey)) {
            return idempotencyService.execute(
                "ASSET_HANDOVER_CREATE", null, idempotencyKey, request, AssetHandoverResponse.class,
                () -> createNow(request)
            );
        }
        return createNow(request);
    }

    private AssetHandoverResponse createNow(AssetHandoverCreateRequest request) {
        List<AssetHandoverLineRequest> requestedLines = request == null || request.lines() == null ? List.of() : request.lines();
        if (requestedLines.isEmpty()) {
            throw bad("Asset handover requires at least one line", "ASSET_EVIDENCE_REQUIRED");
        }
        AssetHandoverType handoverType = parseHandoverType(request.handoverType());
        FixedAssetCustodyKind fromCustody = parseCustody(request.fromCustodyType(), FixedAssetCustodyKind.WAREHOUSE);
        FixedAssetCustodyKind toCustody = parseCustody(request.toCustodyType(), defaultToCustody(handoverType));
        validateDestination(handoverType, toCustody, request.toWarehouseId(), request.toDepartmentId());
        requireDepartmentHandover(request.fromDepartmentId(), request.toDepartmentId(), request.fromWarehouseId(), request.toWarehouseId());
        List<FixedAsset> assets = validateLines(
            requestedLines, fromCustody, request.fromWarehouseId(), request.fromDepartmentId(), request.fromEmployeeId());
        UUID contractId = requireContractForIssue(handoverType, request.contractId(), assets);

        AssetHandover handover = new AssetHandover();
        handover.setCode("AH-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        handover.setName("Asset handover " + handoverType.name().toLowerCase(Locale.ROOT).replace('_', ' '));
        handover.setHandoverStatus(AssetHandoverStatus.DRAFT);
        handover.setContractId(contractId);
        copyHeader(
            handover,
            handoverType,
            fromCustody,
            request.fromEmployeeId(),
            request.fromDepartmentId(),
            request.fromWarehouseId(),
            toCustody,
            request.toEmployeeId(),
            request.toDepartmentId(),
            request.toWarehouseId()
        );
        handover = repository.save(handover);

        for (FixedAsset asset : assets) {
            AssetHandoverLine line = new AssetHandoverLine();
            line.setCode("AHL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
            line.setName("Asset handover line for " + BusinessDisplayNamePolicy.requireHumanName("FixedAsset", asset.getName(), asset.getCode()));
            line.setStatus("PENDING");
            line.setAssetHandoverId(handover.getId());
            line.setFixedAssetId(asset.getId());
            line.setLineStatus(AssetHandoverLineStatus.PENDING);
            lineRepository.save(line);
        }
        return toResponse(handover);
    }

    private UUID requireContractForIssue(AssetHandoverType type, UUID contractId, List<FixedAsset> assets) {
        if (type != AssetHandoverType.ISSUE) return contractId;
        if (contractId == null) throw new BusinessException("Contract is required for equipment issue");
        Contract contract = contractRepository.findByIdAndIsDeletedFalse(contractId)
            .filter(c -> contractId.equals(c.getId()) && TenantContext.currentTenant().equals(c.getTenantId())
                && CompanyContext.currentCompany().equals(c.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("Contract not found in your company"));
        LocalDate today = LocalDate.now();
        if (!"ACTIVE".equals(contract.getStatus())
            || (contract.getStartDate() != null && contract.getStartDate().isAfter(today))
            || (contract.getEndDate() != null && contract.getEndDate().isBefore(today)))
            throw new BusinessException("Contract must be active on the operation date");
        if (assets != null && !assets.isEmpty()) {
            var specs = contractSpecificationRepository.findByTenantIdAndContractIdAndIsDeletedFalse(
                TenantContext.currentTenant(), contractId);
            Set<UUID> specMaterialIds = new HashSet<>();
            for (var spec : specs) {
                if (spec.getLineType() == ProcurementLineType.MATERIAL && spec.getMaterialId() != null) {
                    specMaterialIds.add(spec.getMaterialId());
                }
            }
            for (FixedAsset asset : assets) {
                if (asset.getMaterialId() == null || !specMaterialIds.contains(asset.getMaterialId())) {
                    throw new BusinessException("Contract does not have a matching specification for equipment material");
                }
            }
        }
        return contractId;
    }

    @Transactional
    public AssetHandoverResponse update(UUID id, AssetHandoverUpdateRequest request) {
        return update(id, request, null);
    }

    @Transactional
    public AssetHandoverResponse update(UUID id, AssetHandoverUpdateRequest request, String idempotencyKey) {
        if (hasText(idempotencyKey)) {
            return idempotencyService.execute(
                "ASSET_HANDOVER_UPDATE", id, idempotencyKey, request, AssetHandoverResponse.class,
                () -> find(id),
                () -> updateNow(id, request)
            );
        }
        return updateNow(id, request);
    }

    private AssetHandoverResponse updateNow(UUID id, AssetHandoverUpdateRequest request) {
        AssetHandover handover = find(id);
        requireVersion(handover, request == null ? null : request.expectedVersion());
        requireStatus(handover, AssetHandoverStatus.DRAFT);
        AssetHandoverType handoverType = parseHandoverType(request.handoverType());
        FixedAssetCustodyKind fromCustody = parseCustody(request.fromCustodyType(), FixedAssetCustodyKind.WAREHOUSE);
        FixedAssetCustodyKind toCustody = parseCustody(request.toCustodyType(), defaultToCustody(handoverType));
        validateDestination(handoverType, toCustody, request.toWarehouseId(), request.toDepartmentId());
        requireDepartmentHandover(request.fromDepartmentId(), request.toDepartmentId(), request.fromWarehouseId(), request.toWarehouseId());
        List<FixedAsset> assets = validateLines(
            request.lines() == null ? List.of() : request.lines(),
            fromCustody,
            request.fromWarehouseId(),
            request.fromDepartmentId(),
            request.fromEmployeeId()
        );
        handover.setContractId(requireContractForIssue(handoverType, request.contractId(), assets));
        copyHeader(
            handover,
            handoverType,
            fromCustody,
            request.fromEmployeeId(),
            request.fromDepartmentId(),
            request.fromWarehouseId(),
            toCustody,
            request.toEmployeeId(),
            request.toDepartmentId(),
            request.toWarehouseId()
        );
        lines(handover.getId()).forEach(line -> {
            line.softDelete();
            lineRepository.save(line);
        });
        for (FixedAsset asset : assets) {
            AssetHandoverLine line = new AssetHandoverLine();
            line.setCode("AHL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
            line.setName("Asset handover line for " + BusinessDisplayNamePolicy.requireHumanName("FixedAsset", asset.getName(), asset.getCode()));
            line.setStatus("PENDING");
            line.setAssetHandoverId(handover.getId());
            line.setFixedAssetId(asset.getId());
            line.setLineStatus(AssetHandoverLineStatus.PENDING);
            lineRepository.save(line);
        }
        return toResponse(repository.save(handover));
    }

    @Transactional
    public AssetHandoverResponse submit(UUID id, VersionedAssetCommand request) {
        return submit(id, request, null);
    }

    @Transactional
    public AssetHandoverResponse submit(UUID id, VersionedAssetCommand request, String idempotencyKey) {
        if (hasText(idempotencyKey)) {
            return idempotencyService.execute(
                "ASSET_HANDOVER_SUBMIT", id, idempotencyKey, request, AssetHandoverResponse.class,
                () -> find(id),
                () -> submitNow(id, request)
            );
        }
        return submitNow(id, request);
    }

    private AssetHandoverResponse submitNow(UUID id, VersionedAssetCommand request) {
        AssetHandover handover = find(id);
        requireVersion(handover, request == null ? null : request.expectedVersion());
        if (handover.getHandoverStatus() == AssetHandoverStatus.SUBMITTED) {
            return toResponse(handover);
        }
        requireStatus(handover, AssetHandoverStatus.DRAFT);
        validateLinesCurrent(handover);
        if (handover.getHandoverType() == AssetHandoverType.ISSUE) {
            List<FixedAsset> assets = lines(handover.getId()).stream()
                .map(line -> findAsset(line.getFixedAssetId()))
                .toList();
            requireContractForIssue(handover.getHandoverType(), handover.getContractId(), assets);
        }
        var approval = approvalEngineService.start(APPROVAL_MODULE, id);
        handover.setApprovalInstanceId(approval == null ? handover.getApprovalInstanceId() : approval.id());
        handover.setHandoverStatus(AssetHandoverStatus.SUBMITTED);
        return toResponse(repository.save(handover));
    }

    @Transactional
    public AssetHandoverResponse dispatch(UUID id, VersionedAssetCommand request) {
        return dispatch(id, request, null);
    }

    @Transactional
    public AssetHandoverResponse dispatch(UUID id, VersionedAssetCommand request, String idempotencyKey) {
        if (hasText(idempotencyKey)) {
            return idempotencyService.execute(
                "ASSET_HANDOVER_DISPATCH", id, idempotencyKey, request, AssetHandoverResponse.class,
                () -> find(id),
                () -> dispatchNow(id, request, idempotencyKey)
            );
        }
        return dispatchNow(id, request, null);
    }

    private AssetHandoverResponse dispatchNow(UUID id, VersionedAssetCommand request, String idempotencyKey) {
        AssetHandover handover = find(id);
        if (roleDataScopeService.isDepartmentAssetScoped()
            && !roleDataScopeService.canAccessDepartmentAsset(handover.getFromDepartmentId(), handover.getFromWarehouseId())) {
            throw new org.springframework.security.access.AccessDeniedException("Only the sending department can dispatch this handover");
        }
        requireVersion(handover, request == null ? null : request.expectedVersion());
        requireStatus(handover, AssetHandoverStatus.APPROVED);
        if (handover.getHandoverType() == AssetHandoverType.ISSUE) {
            List<FixedAsset> assets = lines(handover.getId()).stream()
                .map(line -> findAsset(line.getFixedAssetId()))
                .toList();
            requireContractForIssue(handover.getHandoverType(), handover.getContractId(), assets);
        }
        for (AssetHandoverLine line : lines(handover.getId())) {
            FixedAsset asset = findAsset(line.getFixedAssetId());
            FixedAssetLifecycleStatus beforeLifecycle = asset.getLifecycleStatus();
            FixedAssetCustodyKind beforeCustody = asset.getCustodyKind();
            asset.setCustodyKind(FixedAssetCustodyKind.IN_TRANSIT);
            asset.setActiveTransferId(handover.getId());
            fixedAssetRepository.save(asset);
            line.setLineStatus(AssetHandoverLineStatus.DISPATCHED);
            line.setStatus("DISPATCHED");
            lineRepository.save(line);
            appendEvent(asset, "ASSET_HANDOVER_DISPATCHED", beforeLifecycle, beforeCustody, handover, request, idempotencyKey);
        }
        handover.setHandoverStatus(AssetHandoverStatus.DISPATCHED);
        handover.setDispatchedAt(LocalDateTime.now());
        return toResponse(repository.save(handover));
    }

    @Transactional
    public AssetHandoverResponse acknowledge(UUID id, VersionedAssetCommand request) {
        return acknowledge(id, request, null);
    }

    @Transactional
    public AssetHandoverResponse acknowledge(UUID id, VersionedAssetCommand request, String idempotencyKey) {
        if (hasText(idempotencyKey)) {
            return idempotencyService.execute(
                "ASSET_HANDOVER_ACKNOWLEDGE", id, idempotencyKey, request, AssetHandoverResponse.class,
                () -> find(id),
                () -> acknowledgeNow(id, request, idempotencyKey)
            );
        }
        return acknowledgeNow(id, request, null);
    }

    private AssetHandoverResponse acknowledgeNow(UUID id, VersionedAssetCommand request, String idempotencyKey) {
        AssetHandover handover = find(id);
        if (roleDataScopeService.isDepartmentAssetScoped()
            && !roleDataScopeService.canAccessDepartmentAsset(handover.getToDepartmentId(), handover.getToWarehouseId())) {
            throw new org.springframework.security.access.AccessDeniedException("Only the receiving department can acknowledge this handover");
        }
        requireVersion(handover, request == null ? null : request.expectedVersion());
        requireStatus(handover, AssetHandoverStatus.DISPATCHED);
        for (AssetHandoverLine line : lines(handover.getId())) {
            FixedAsset asset = findAsset(line.getFixedAssetId());
            FixedAssetLifecycleStatus beforeLifecycle = asset.getLifecycleStatus();
            FixedAssetCustodyKind beforeCustody = asset.getCustodyKind();
            applyDestination(asset, handover);
            asset.setActiveTransferId(null);
            fixedAssetRepository.save(asset);
            line.setLineStatus(AssetHandoverLineStatus.ACKNOWLEDGED);
            line.setStatus("ACKNOWLEDGED");
            lineRepository.save(line);
            appendEvent(asset, acknowledgedEventType(handover), beforeLifecycle, beforeCustody, handover, request, idempotencyKey);
        }
        releaseAllocations(handover, AssetAllocationStatus.RELEASED, "ACKNOWLEDGED");
        handover.setHandoverStatus(AssetHandoverStatus.ACKNOWLEDGED);
        handover.setAcknowledgedAt(LocalDateTime.now());
        return toResponse(repository.save(handover));
    }

    @Transactional
    public AssetHandoverResponse reportException(UUID id, VersionedAssetCommand request) {
        return reportException(id, request, null);
    }

    @Transactional
    public AssetHandoverResponse reportException(UUID id, VersionedAssetCommand request, String idempotencyKey) {
        if (hasText(idempotencyKey)) {
            return idempotencyService.execute(
                "ASSET_HANDOVER_EXCEPTION", id, idempotencyKey, request, AssetHandoverResponse.class,
                () -> find(id),
                () -> reportExceptionNow(id, request, idempotencyKey)
            );
        }
        return reportExceptionNow(id, request, null);
    }

    private AssetHandoverResponse reportExceptionNow(UUID id, VersionedAssetCommand request, String idempotencyKey) {
        AssetHandover handover = find(id);
        requireVersion(handover, request == null ? null : request.expectedVersion());
        requireAnyStatus(handover, AssetHandoverStatus.DISPATCHED, AssetHandoverStatus.RETURN_RESOLUTION);
        for (AssetHandoverLine line : lines(handover.getId())) {
            line.setLineStatus(AssetHandoverLineStatus.EXCEPTION_REPORTED);
            line.setStatus("EXCEPTION_REPORTED");
            lineRepository.save(line);
            FixedAsset asset = findAsset(line.getFixedAssetId());
            appendEvent(asset, "ASSET_HANDOVER_EXCEPTION_REPORTED", asset.getLifecycleStatus(), asset.getCustodyKind(), handover, request, idempotencyKey);
        }
        handover.setHandoverStatus(AssetHandoverStatus.EXCEPTION_REPORTED);
        handover.setExceptionStatus("OPEN");
        return toResponse(repository.save(handover));
    }

    @Transactional
    public AssetHandoverResponse acknowledgeReturn(UUID id, VersionedAssetCommand request) {
        return acknowledgeReturn(id, request, null);
    }

    @Transactional
    public AssetHandoverResponse acknowledgeReturn(UUID id, VersionedAssetCommand request, String idempotencyKey) {
        if (hasText(idempotencyKey)) {
            return idempotencyService.execute(
                "ASSET_HANDOVER_ACKNOWLEDGE_RETURN", id, idempotencyKey, request, AssetHandoverResponse.class,
                () -> find(id),
                () -> acknowledgeReturnNow(id, request, idempotencyKey)
            );
        }
        return acknowledgeReturnNow(id, request, null);
    }

    private AssetHandoverResponse acknowledgeReturnNow(UUID id, VersionedAssetCommand request, String idempotencyKey) {
        AssetHandover handover = find(id);
        requireVersion(handover, request == null ? null : request.expectedVersion());
        requireAnyStatus(handover, AssetHandoverStatus.DISPATCHED, AssetHandoverStatus.EXCEPTION_REPORTED);
        for (AssetHandoverLine line : lines(handover.getId())) {
            FixedAsset asset = findAsset(line.getFixedAssetId());
            appendEvent(asset, "ASSET_HANDOVER_RETURN_ACKNOWLEDGED", asset.getLifecycleStatus(), asset.getCustodyKind(), handover, request, idempotencyKey);
        }
        handover.setHandoverStatus(AssetHandoverStatus.RETURN_RESOLUTION);
        return toResponse(repository.save(handover));
    }

    @Transactional
    public AssetHandoverResponse returnResolution(UUID id, VersionedAssetCommand request) {
        return returnResolution(id, request, null);
    }

    @Transactional
    public AssetHandoverResponse returnResolution(UUID id, VersionedAssetCommand request, String idempotencyKey) {
        if (hasText(idempotencyKey)) {
            return idempotencyService.execute(
                "ASSET_HANDOVER_RETURN_RESOLUTION", id, idempotencyKey, request, AssetHandoverResponse.class,
                () -> find(id),
                () -> returnResolutionNow(id, request, idempotencyKey)
            );
        }
        return returnResolutionNow(id, request, null);
    }

    private AssetHandoverResponse returnResolutionNow(UUID id, VersionedAssetCommand request, String idempotencyKey) {
        AssetHandover handover = find(id);
        requireVersion(handover, request == null ? null : request.expectedVersion());
        requireStatus(handover, AssetHandoverStatus.RETURN_RESOLUTION);
        for (AssetHandoverLine line : lines(handover.getId())) {
            FixedAsset asset = findAsset(line.getFixedAssetId());
            FixedAssetLifecycleStatus beforeLifecycle = asset.getLifecycleStatus();
            FixedAssetCustodyKind beforeCustody = asset.getCustodyKind();
            applySourceReturn(asset, handover);
            asset.setActiveTransferId(null);
            fixedAssetRepository.save(asset);
            line.setLineStatus(AssetHandoverLineStatus.CLOSED);
            line.setStatus("CLOSED");
            lineRepository.save(line);
            appendEvent(asset, "ASSET_HANDOVER_RETURN_RESOLVED", beforeLifecycle, beforeCustody, handover, request, idempotencyKey);
        }
        releaseAllocations(handover, AssetAllocationStatus.RELEASED, "RETURN_RESOLUTION");
        handover.setHandoverStatus(AssetHandoverStatus.CLOSED);
        handover.setExceptionStatus("RESOLVED");
        handover.setClosedAt(LocalDateTime.now());
        return toResponse(repository.save(handover));
    }

    @Transactional
    public AssetHandoverResponse cancel(UUID id, VersionedAssetCommand request) {
        return cancel(id, request, null);
    }

    @Transactional
    public AssetHandoverResponse cancel(UUID id, VersionedAssetCommand request, String idempotencyKey) {
        if (hasText(idempotencyKey)) {
            return idempotencyService.execute(
                "ASSET_HANDOVER_CANCEL", id, idempotencyKey, request, AssetHandoverResponse.class,
                () -> find(id),
                () -> cancelNow(id, request, idempotencyKey)
            );
        }
        return cancelNow(id, request, null);
    }

    private AssetHandoverResponse cancelNow(UUID id, VersionedAssetCommand request, String idempotencyKey) {
        AssetHandover handover = find(id);
        requireVersion(handover, request == null ? null : request.expectedVersion());
        requireAnyStatus(handover, AssetHandoverStatus.DRAFT, AssetHandoverStatus.SUBMITTED, AssetHandoverStatus.APPROVED);
        for (AssetHandoverLine line : lines(handover.getId())) {
            line.setLineStatus(AssetHandoverLineStatus.CANCELLED);
            line.setStatus("CANCELLED");
            lineRepository.save(line);
            FixedAsset asset = findAsset(line.getFixedAssetId());
            appendEvent(asset, "ASSET_HANDOVER_CANCELLED", asset.getLifecycleStatus(), asset.getCustodyKind(), handover, request, idempotencyKey);
        }
        releaseAllocations(handover, AssetAllocationStatus.CANCELLED, "CANCELLED");
        handover.setHandoverStatus(AssetHandoverStatus.CANCELLED);
        handover.setClosedAt(LocalDateTime.now());
        return toResponse(repository.save(handover));
    }

    public AssetHandover findForApproval(UUID id) {
        return find(id);
    }

    public void markSubmittedFromApproval(UUID id) {
        AssetHandover handover = find(id);
        if (handover.getHandoverStatus() == AssetHandoverStatus.SUBMITTED) {
            return;
        }
        requireStatus(handover, AssetHandoverStatus.DRAFT);
        validateLinesCurrent(handover);
        handover.setHandoverStatus(AssetHandoverStatus.SUBMITTED);
        repository.save(handover);
    }

    public void markApprovedFromApproval(UUID id) {
        AssetHandover handover = find(id);
        if (handover.getHandoverStatus() == AssetHandoverStatus.APPROVED) {
            return;
        }
        requireStatus(handover, AssetHandoverStatus.SUBMITTED);
        validateLinesCurrent(handover);
        for (AssetHandoverLine line : lines(handover.getId())) {
            if (line.getAssetAllocationId() != null) {
                continue;
            }
            FixedAsset asset = findAsset(line.getFixedAssetId());
            if (allocationRepository.existsByTenantIdAndCompanyIdAndFixedAssetIdAndStatusAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), asset.getId(), AssetAllocationStatus.ACTIVE.name())) {
                throw business("Fixed asset is already allocated", "ASSET_ALREADY_ALLOCATED");
            }
            AssetAllocation allocation = new AssetAllocation();
            allocation.setCode("AA-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
            allocation.setName("Asset allocation for " + BusinessDisplayNamePolicy.requireHumanName("FixedAsset", asset.getName(), asset.getCode()));
            allocation.setFixedAssetId(asset.getId());
            allocation.setAssetHandoverId(handover.getId());
            allocation.setAllocatedAt(LocalDateTime.now());
            allocation.setAllocationStatus(AssetAllocationStatus.ACTIVE);
            allocation = allocationRepository.save(allocation);
            line.setAssetAllocationId(allocation.getId());
            lineRepository.save(line);
        }
        handover.setHandoverStatus(AssetHandoverStatus.APPROVED);
        handover.setApprovedAt(LocalDateTime.now());
        repository.save(handover);
    }

    public void markRejectedFromApproval(UUID id) {
        AssetHandover handover = find(id);
        requireStatus(handover, AssetHandoverStatus.SUBMITTED);
        for (AssetHandoverLine line : lines(handover.getId())) {
            line.setLineStatus(AssetHandoverLineStatus.CANCELLED);
            line.setStatus("REJECTED");
            lineRepository.save(line);
        }
        handover.setHandoverStatus(AssetHandoverStatus.REJECTED);
        handover.setClosedAt(LocalDateTime.now());
        repository.save(handover);
    }

    private AssetHandover find(UUID id) {
        return repository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), id)
            .filter(handover -> !roleDataScopeService.isDepartmentAssetScoped()
                || roleDataScopeService.canAccessDepartmentAsset(handover.getFromDepartmentId(), handover.getFromWarehouseId())
                || roleDataScopeService.canAccessDepartmentAsset(handover.getToDepartmentId(), handover.getToWarehouseId()))
            .orElseThrow(() -> new NotFoundException("Asset handover not found: " + id));
    }

    private void requireDepartmentHandover(UUID fromDepartmentId, UUID toDepartmentId, UUID fromWarehouseId, UUID toWarehouseId) {
        if (roleDataScopeService.isDepartmentAssetScoped()
            && !roleDataScopeService.canAccessDepartmentAsset(fromDepartmentId, fromWarehouseId)
            && !roleDataScopeService.canAccessDepartmentAsset(toDepartmentId, toWarehouseId)) {
            throw new org.springframework.security.access.AccessDeniedException("Handover must involve your department");
        }
    }

    private FixedAsset findAsset(UUID id) {
        return fixedAssetRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), id)
            .orElseThrow(() -> new NotFoundException("Fixed asset not found: " + id));
    }

    private List<AssetHandoverLine> lines(UUID handoverId) {
        return lineRepository.findByTenantIdAndCompanyIdAndAssetHandoverIdAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), handoverId);
    }

    private List<FixedAsset> validateLines(
        List<AssetHandoverLineRequest> requestedLines,
        FixedAssetCustodyKind fromCustody,
        UUID fromWarehouseId,
        UUID fromDepartmentId,
        UUID fromEmployeeId
    ) {
        if (requestedLines.isEmpty()) {
            throw bad("Asset handover requires at least one line", "ASSET_EVIDENCE_REQUIRED");
        }
        HashSet<UUID> seen = new HashSet<>();
        List<FixedAsset> assets = new ArrayList<>();
        for (AssetHandoverLineRequest line : requestedLines) {
            if (line == null || line.fixedAssetId() == null) {
                throw bad("fixedAssetId is required", "ASSET_EVIDENCE_REQUIRED");
            }
            if (!seen.add(line.fixedAssetId())) {
                throw conflict("Fixed asset appears more than once on the handover", "ASSET_ALREADY_ALLOCATED");
            }
            FixedAsset asset = findAsset(line.fixedAssetId());
            validateAssetEligibility(asset, fromCustody, fromWarehouseId, fromDepartmentId, fromEmployeeId);
            assets.add(asset);
        }
        return assets;
    }

    private void validateLinesCurrent(AssetHandover handover) {
        List<AssetHandoverLine> handoverLines = lines(handover.getId());
        if (handoverLines.isEmpty()) {
            throw bad("Asset handover requires at least one line", "ASSET_EVIDENCE_REQUIRED");
        }
        for (AssetHandoverLine line : handoverLines) {
            validateAssetEligibility(findAsset(line.getFixedAssetId()), handover.getFromCustodyType(), handover);
        }
    }

    private void validateAssetEligibility(
        FixedAsset asset,
        FixedAssetCustodyKind fromCustody,
        UUID fromWarehouseId,
        UUID fromDepartmentId,
        UUID fromEmployeeId
    ) {
        if (roleDataScopeService.isDepartmentAssetScoped() && fromCustody != FixedAssetCustodyKind.WAREHOUSE
            && !roleDataScopeService.canReadIssueDepartment(asset.getDepartmentId())) {
            throw new org.springframework.security.access.AccessDeniedException("Handover source asset must belong to your department");
        }
        if (asset.getLifecycleStatus() == FixedAssetLifecycleStatus.DISPOSED
            || asset.getLifecycleStatus() == FixedAssetLifecycleStatus.CANCELLED) {
            throw business("Fixed asset lifecycle status is not eligible for handover", "ASSET_INVALID_TRANSITION");
        }
        if (asset.getCustodyKind() != fromCustody) {
            throw business("Fixed asset custody does not match handover source", "ASSET_SCOPE_DENIED");
        }
        validateSourceFields(asset, fromCustody, fromWarehouseId, fromDepartmentId, fromEmployeeId);
        if (allocationRepository.existsByTenantIdAndCompanyIdAndFixedAssetIdAndStatusAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), asset.getId(), AssetAllocationStatus.ACTIVE.name())) {
            throw business("Fixed asset is already allocated", "ASSET_ALREADY_ALLOCATED");
        }
    }

    private void validateAssetEligibility(FixedAsset asset, FixedAssetCustodyKind fromCustody, AssetHandover handover) {
        if (asset.getCustodyKind() != fromCustody) {
            throw business("Fixed asset custody does not match handover source", "ASSET_SCOPE_DENIED");
        }
        validateSourceFields(asset, fromCustody, handover.getFromWarehouseId(), handover.getFromDepartmentId(), handover.getFromEmployeeId());
    }

    private void validateSourceFields(FixedAsset asset, FixedAssetCustodyKind custody, UUID warehouseId, UUID departmentId, UUID employeeId) {
        if (custody == FixedAssetCustodyKind.WAREHOUSE && !Objects.equals(asset.getWarehouseId(), warehouseId)) {
            throw business("Fixed asset warehouse does not match handover source", "ASSET_SCOPE_DENIED");
        }
        if (custody == FixedAssetCustodyKind.DEPARTMENT) {
            if (departmentId != null && !Objects.equals(asset.getDepartmentId(), departmentId)) {
                throw business("Fixed asset department does not match handover source", "ASSET_SCOPE_DENIED");
            }
            if (employeeId != null && !Objects.equals(asset.getCustodianEmployeeId(), employeeId)) {
                throw business("Fixed asset custodian does not match handover source", "ASSET_SCOPE_DENIED");
            }
        }
    }

    private void validateDestination(AssetHandoverType type, FixedAssetCustodyKind toCustody, UUID toWarehouseId, UUID toDepartmentId) {
        if (type == AssetHandoverType.ISSUE && toCustody != FixedAssetCustodyKind.DEPARTMENT) {
            throw bad("Issue handover destination must be a department", "ASSET_SCOPE_DENIED");
        }
        if (type == AssetHandoverType.RETURN_TO_WAREHOUSE && toCustody != FixedAssetCustodyKind.WAREHOUSE) {
            throw bad("Return handover destination must be a warehouse", "ASSET_SCOPE_DENIED");
        }
        if ((toCustody == FixedAssetCustodyKind.WAREHOUSE && toWarehouseId == null)
            || (toCustody == FixedAssetCustodyKind.DEPARTMENT && toDepartmentId == null)) {
            throw bad("Handover destination is required", "ASSET_EVIDENCE_REQUIRED");
        }
    }

    private void copyHeader(
        AssetHandover handover,
        AssetHandoverType type,
        FixedAssetCustodyKind fromCustody,
        UUID fromEmployeeId,
        UUID fromDepartmentId,
        UUID fromWarehouseId,
        FixedAssetCustodyKind toCustody,
        UUID toEmployeeId,
        UUID toDepartmentId,
        UUID toWarehouseId
    ) {
        handover.setHandoverType(type);
        handover.setFromCustodyType(fromCustody);
        handover.setFromEmployeeId(fromEmployeeId);
        handover.setFromDepartmentId(fromDepartmentId);
        handover.setFromWarehouseId(fromWarehouseId);
        handover.setToCustodyType(toCustody);
        handover.setToEmployeeId(toEmployeeId);
        handover.setToDepartmentId(toDepartmentId);
        handover.setToWarehouseId(toWarehouseId);
    }

    private void applyDestination(FixedAsset asset, AssetHandover handover) {
        asset.setCustodyKind(handover.getToCustodyType());
        asset.setCustodianEmployeeId(handover.getToEmployeeId());
        asset.setDepartmentId(handover.getToDepartmentId());
        asset.setWarehouseId(handover.getToWarehouseId());
        asset.setStorageLocationId(null);
        if (handover.getHandoverType() == AssetHandoverType.ISSUE) {
            asset.setLifecycleStatus(FixedAssetLifecycleStatus.ISSUED_PENDING_COMMISSIONING);
        } else if (handover.getHandoverType() == AssetHandoverType.RETURN_TO_WAREHOUSE) {
            asset.setLifecycleStatus(FixedAssetLifecycleStatus.IN_WAREHOUSE);
        } else if (handover.getHandoverType() == AssetHandoverType.REPAIR) {
            asset.setLifecycleStatus(FixedAssetLifecycleStatus.UNDER_REPAIR);
        } else if (handover.getHandoverType() == AssetHandoverType.SUPPLIER_RETURN) {
            asset.setLifecycleStatus(FixedAssetLifecycleStatus.RETURNED_TO_SUPPLIER);
        }
    }

    private void applySourceReturn(FixedAsset asset, AssetHandover handover) {
        asset.setCustodyKind(handover.getFromCustodyType());
        asset.setCustodianEmployeeId(handover.getFromEmployeeId());
        asset.setDepartmentId(handover.getFromDepartmentId());
        asset.setWarehouseId(handover.getFromWarehouseId());
        asset.setStorageLocationId(null);
    }

    private void releaseAllocations(AssetHandover handover, AssetAllocationStatus status, String reason) {
        for (AssetAllocation allocation : allocationRepository.findByTenantIdAndCompanyIdAndAssetHandoverIdAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), handover.getId())) {
            if (allocation.getAllocationStatus() != AssetAllocationStatus.ACTIVE) {
                continue;
            }
            allocation.setAllocationStatus(status);
            allocation.setReleasedAt(LocalDateTime.now());
            allocation.setReleaseReason(reason);
            allocationRepository.save(allocation);
        }
    }

    private void appendEvent(
        FixedAsset asset,
        String eventType,
        FixedAssetLifecycleStatus beforeLifecycle,
        FixedAssetCustodyKind beforeCustody,
        AssetHandover handover,
        VersionedAssetCommand request,
        String idempotencyKey
    ) {
        AssetEvent event = new AssetEvent();
        event.setCode("AE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        event.setName(eventType);
        event.setStatus("POSTED");
        event.setFixedAssetId(asset.getId());
        event.setEventType(eventType);
        event.setIdempotencyKey(trimToNull(idempotencyKey));
        event.setBeforeLifecycleStatus(beforeLifecycle);
        event.setAfterLifecycleStatus(asset.getLifecycleStatus());
        event.setBeforeCustodyKind(beforeCustody);
        event.setAfterCustodyKind(asset.getCustodyKind());
        event.setEffectiveDate(LocalDate.now());
        event.setRecordedAt(LocalDateTime.now());
        event.setActorUsername("system");
        event.setReason(trimToNull(request == null ? null : request.reason()));
        event.setApprovalInstanceId(handover.getApprovalInstanceId());
        event.setSourceDocumentType(APPROVAL_MODULE);
        event.setSourceDocumentId(handover.getId());
        event.setAggregateVersion(asset.getVersion());
        eventRepository.save(event);
        publishIntegrationEvent(
            asset,
            eventType,
            beforeLifecycle,
            beforeCustody,
            APPROVAL_MODULE,
            handover.getId(),
            LocalDate.now(),
            "system",
            idempotencyKey
        );
    }

    private String acknowledgedEventType(AssetHandover handover) {
        if (handover.getHandoverType() == AssetHandoverType.RETURN_TO_WAREHOUSE) {
            return "ASSET_RETURNED_TO_WAREHOUSE";
        }
        return "ASSET_HANDOVER_ACKNOWLEDGED";
    }

    private void publishIntegrationEvent(
        FixedAsset asset,
        String eventType,
        FixedAssetLifecycleStatus beforeLifecycle,
        FixedAssetCustodyKind beforeCustody,
        String sourceDocumentType,
        UUID sourceDocumentId,
        LocalDate effectiveDate,
        String actor,
        String idempotencyKey
    ) {
        if (assetIntegrationEventPublisher != null) {
            assetIntegrationEventPublisher.publish(
                asset,
                eventType,
                beforeLifecycle,
                beforeCustody,
                sourceDocumentType,
                sourceDocumentId,
                effectiveDate,
                actor,
                idempotencyKey
            );
        }
    }

    private AssetHandoverResponse toResponse(AssetHandover handover) {
        List<AssetHandoverLineResponse> responseLines = lines(handover.getId())
            .stream()
            .map(mapper::toResponse)
            .toList();
        return mapper.toResponse(handover, responseLines);
    }

    private AssetHandoverType parseHandoverType(String value) {
        if (!hasText(value)) {
            return AssetHandoverType.ISSUE;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if ("WAREHOUSE_TO_DEPARTMENT".equals(normalized) || "WAREHOUSE_TO_EMPLOYEE".equals(normalized)) {
            return AssetHandoverType.ISSUE;
        }
        if ("DEPARTMENT_TO_WAREHOUSE".equals(normalized) || "EMPLOYEE_TO_WAREHOUSE".equals(normalized)) {
            return AssetHandoverType.RETURN_TO_WAREHOUSE;
        }
        try {
            return AssetHandoverType.valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            throw bad("Unknown asset handover type: " + value, "ASSET_INVALID_TRANSITION");
        }
    }

    private AssetHandoverType parseOptionalHandoverType(String value) {
        return hasText(value) ? parseHandoverType(value) : null;
    }

    private AssetHandoverStatus parseOptionalStatus(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            return AssetHandoverStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw bad("Unknown asset handover status: " + value, "ASSET_INVALID_TRANSITION");
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

    private FixedAssetCustodyKind defaultToCustody(AssetHandoverType type) {
        return type == AssetHandoverType.RETURN_TO_WAREHOUSE ? FixedAssetCustodyKind.WAREHOUSE : FixedAssetCustodyKind.DEPARTMENT;
    }

    private void requireVersion(AssetHandover handover, Long expectedVersion) {
        if (expectedVersion == null) {
            throw bad("expectedVersion is required", "ASSET_VERSION_CONFLICT");
        }
        if (!Objects.equals(handover.getVersion(), expectedVersion)) {
            throw conflict("Asset handover version conflict", "ASSET_VERSION_CONFLICT");
        }
    }

    private void requireStatus(AssetHandover handover, AssetHandoverStatus status) {
        if (handover.getHandoverStatus() != status) {
            throw business("Asset handover is not in required status " + status, "ASSET_INVALID_TRANSITION");
        }
    }

    private void requireAnyStatus(AssetHandover handover, AssetHandoverStatus... statuses) {
        for (AssetHandoverStatus status : statuses) {
            if (handover.getHandoverStatus() == status) {
                return;
            }
        }
        throw business("Asset handover status does not allow this transition", "ASSET_INVALID_TRANSITION");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private BadRequestException bad(String message, String code) {
        return new BadRequestException(message, code);
    }

    private ConflictException conflict(String message, String code) {
        return new ConflictException(message, code);
    }

    private BusinessException business(String message, String code) {
        return new BusinessException(message, code);
    }
}
