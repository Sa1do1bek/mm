package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AdvanceShippingNotice;
import com.enterprise.mm.entity.AdvanceShippingNoticeItem;
import com.enterprise.mm.entity.AssetAcceptance;
import com.enterprise.mm.entity.AssetAcceptanceLine;
import com.enterprise.mm.entity.AssetAcceptanceUnit;
import com.enterprise.mm.entity.AssetEvent;
import com.enterprise.mm.entity.AssetManagementSettings;
import com.enterprise.mm.entity.Contract;
import com.enterprise.mm.entity.ContractSpecification;
import com.enterprise.mm.entity.Employee;
import com.enterprise.mm.entity.FixedAsset;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.enums.AssetAcceptanceApprovalStatus;
import com.enterprise.mm.enums.AssetAcceptanceLineQualityDecision;
import com.enterprise.mm.enums.AssetAcceptancePostingStatus;
import com.enterprise.mm.enums.AssetAcceptanceUnitQualityDecision;
import com.enterprise.mm.enums.AdvanceShippingNoticeStatus;
import com.enterprise.mm.enums.FixedAssetCustodyKind;
import com.enterprise.mm.enums.FixedAssetLifecycleStatus;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.ProcurementSourceDocumentType;
import com.enterprise.mm.enums.PurchaseOrderStatus;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.integration.faktura.FakturaReceiptEvidenceService;
import com.enterprise.mm.mapper.AssetFoundationMapper;
import com.enterprise.mm.payload.AssetAcceptanceCreateRequest;
import com.enterprise.mm.payload.AssetAcceptanceLineRequest;
import com.enterprise.mm.payload.AssetAcceptanceLineResponse;
import com.enterprise.mm.payload.AssetAcceptancePostRequest;
import com.enterprise.mm.payload.AssetAcceptanceQualityDecisionRequest;
import com.enterprise.mm.payload.AssetAcceptanceResponse;
import com.enterprise.mm.payload.AssetAcceptanceRestrictedReversalRequest;
import com.enterprise.mm.payload.AssetAcceptanceSearchRequest;
import com.enterprise.mm.payload.AssetAcceptanceUnitRequest;
import com.enterprise.mm.payload.AssetAcceptanceUpdateRequest;
import com.enterprise.mm.payload.VersionedAssetCommand;
import com.enterprise.mm.repository.AssetAcceptanceLineRepository;
import com.enterprise.mm.repository.AssetAcceptanceRepository;
import com.enterprise.mm.repository.AssetAcceptanceUnitRepository;
import com.enterprise.mm.repository.AssetEventRepository;
import com.enterprise.mm.repository.AdvanceShippingNoticeItemRepository;
import com.enterprise.mm.repository.AdvanceShippingNoticeRepository;
import com.enterprise.mm.repository.ContractInvoiceEvidenceSnapshotRepository;
import com.enterprise.mm.repository.ContractPaymentRepository;
import com.enterprise.mm.repository.ContractRepository;
import com.enterprise.mm.repository.ContractSpecificationRepository;
import com.enterprise.mm.repository.EmployeeRepository;
import com.enterprise.mm.repository.FixedAssetRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.StorageLocationRepository;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@com.enterprise.mm.security.pilot.PilotCapabilityBoundary(value = com.enterprise.mm.security.pilot.PilotCapability.FIXED_ASSET_EXECUTION, reads = {"search", "get", "findForApproval", "hasAcceptedUnpostedUnit", "allUnitsResolved"})
public class AssetAcceptanceService {
    public static final String APPROVAL_MODULE = "ASSET_ACCEPTANCE";

    private final AssetAcceptanceRepository repository;
    private final AssetAcceptanceLineRepository lineRepository;
    private final AssetAcceptanceUnitRepository unitRepository;
    private final AssetFoundationMapper mapper;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final FixedAssetRepository fixedAssetRepository;
    private final AssetEventRepository assetEventRepository;
    private final AssetManagementSettingsService settingsService;
    private final StorageLocationRepository storageLocationRepository;
    private final EmployeeRepository employeeRepository;
    private final ApprovalEngineService approvalEngineService;
    private final IdempotencyService idempotencyService;
    private final ContractInvoiceEvidenceSnapshotRepository evidenceSnapshotRepository;
    private final ContractPaymentRepository contractPaymentRepository;
    private final ContractRepository contractRepository;
    private final ContractSpecificationRepository contractSpecificationRepository;
    private final FakturaReceiptEvidenceService fakturaEvidence;

    @Autowired(required = false)
    private AssetIntegrationEventPublisher assetIntegrationEventPublisher;

    @Autowired(required = false)
    private AdvanceShippingNoticeRepository asnRepository;

    @Autowired(required = false)
    private AdvanceShippingNoticeItemRepository asnItemRepository;

    @Autowired(required = false)
    private FixedAssetService fixedAssetService;

    @Autowired(required = false)
    private EntityManager entityManager;

    @Autowired
    private MaterialCatalogGuard materialCatalogGuard;

    @Value("${app.features.fixed-assets.enabled:${FIXED_ASSETS_ENABLED:false}}")
    private boolean fixedAssetsEnabled;

    @Transactional(readOnly = true)
    public Page<AssetAcceptanceResponse> search(AssetAcceptanceSearchRequest request, Pageable pageable) {
        return repository.findByTenantIdAndCompanyIdAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), pageable)
            .map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public AssetAcceptanceResponse get(UUID id) {
        return toResponse(find(id));
    }

    @Transactional
    public AssetAcceptanceResponse create(AssetAcceptanceCreateRequest request) {
        return create(request, null);
    }

    @Transactional
    public AssetAcceptanceResponse create(AssetAcceptanceCreateRequest request, String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            UUID resourceId = request == null ? null : request.purchaseOrderId();
            return idempotencyService.execute(
                "ASSET_ACCEPTANCE_CREATE", resourceId, idempotencyKey, request, AssetAcceptanceResponse.class,
                () -> createNow(request)
            );
        }
        return createNow(request);
    }

    private AssetAcceptanceResponse createNow(AssetAcceptanceCreateRequest request) {
        requireFixedAssetsEnabled();
        AssetManagementSettings settings = settingsService.requireConfigured();
        validateCentralWarehouse(request == null ? null : request.warehouseId(), request == null ? null : request.storageLocationId(), settings);
        validateEmployee(request == null ? null : request.receivedByEmployeeId(), "receivedByEmployeeId");
        PurchaseOrder purchaseOrder = findPurchaseOrderForUpdate(request == null ? null : request.purchaseOrderId());
        List<PurchaseOrderItem> orderItems = orderItems(purchaseOrder.getId());
        Map<UUID, PurchaseOrderItem> itemById = orderItems.stream()
            .collect(Collectors.toMap(PurchaseOrderItem::getId, Function.identity()));
        List<AssetAcceptanceLineRequest> requestedLines = request == null || request.lines() == null ? List.of() : request.lines();
        if (requestedLines.isEmpty()) {
            throw bad("Asset acceptance must contain at least one line", "ASSET_EVIDENCE_REQUIRED");
        }
        UUID supplierId = request.supplierId() == null ? purchaseOrder.getSupplierId() : request.supplierId();
        if (!Objects.equals(supplierId, purchaseOrder.getSupplierId())) {
            throw bad("Asset acceptance supplier must match purchase order supplier", "ASSET_SCOPE_DENIED");
        }
        UUID contractId = request.contractId() == null ? purchaseOrder.getContractId() : request.contractId();
        if (!Objects.equals(contractId, purchaseOrder.getContractId())) {
            throw bad("Asset acceptance contract must match purchase order contract", "ASSET_SCOPE_DENIED");
        }
        List<ValidatedAcceptanceLine> validatedLines = new ArrayList<>();
        for (AssetAcceptanceLineRequest lineRequest : requestedLines) {
            PurchaseOrderItem item = validateAssetPurchaseOrderLine(lineRequest, itemById);
            BigDecimal deliveredQuantity = wholePositive(lineRequest.deliveredQuantity(), "deliveredQuantity");
            validateUnits(lineRequest.units(), deliveredQuantity);
            BigDecimal openSupplierObligation = fixedAssetOpenSupplierObligation(item);
            if (deliveredQuantity.compareTo(openSupplierObligation) > 0) {
                throw business("Delivered fixed asset quantity exceeds open purchase order quantity", "ASSET_ACCEPTANCE_OVER_RECEIPT");
            }
            validatedLines.add(new ValidatedAcceptanceLine(lineRequest, item, deliveredQuantity));
        }
        AdvanceShippingNotice asn = validateAcceptanceAsn(request.asnId(), purchaseOrder, supplierId, validatedLines);

        AssetAcceptance acceptance = new AssetAcceptance();
        acceptance.setCode("AA-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        acceptance.setName("Asset acceptance for " + firstNonBlank(purchaseOrder.getName(), "purchase order"));
        acceptance.setStatus("DRAFT");
        acceptance.setContractId(contractId);
        acceptance.setPurchaseOrderId(purchaseOrder.getId());
        acceptance.setSupplierId(supplierId);
        acceptance.setAsnId(asn == null ? null : asn.getId());
        acceptance.setDeliveryReference(trimToNull(request.deliveryReference()));
        acceptance.setWarehouseId(request.warehouseId());
        acceptance.setStorageLocationId(request.storageLocationId());
        acceptance.setReceivedByEmployeeId(request.receivedByEmployeeId());
        acceptance.setReceiptDate(request.receiptDate() == null ? LocalDate.now() : request.receiptDate());
        acceptance.setReceivedAt(request.receivedAt() == null ? LocalDateTime.now() : request.receivedAt());
        acceptance.setCurrencyCode(firstNonBlank(request.currencyCode(), purchaseOrder.getCurrencyCode()));
        acceptance.setExchangeRate(purchaseOrder.getExchangeRate());
        acceptance.setExchangeRateSource(trimToNull(request.exchangeRateSource()));
        acceptance.setSourceEvidence(trimToNull(request.sourceEvidence()));
        acceptance = repository.save(acceptance);
        fakturaEvidence.attach(contractId, FakturaReceiptEvidenceService.ReceiptKind.ASSET_ACCEPTANCE,
            acceptance.getId(), acceptance.getReceiptDate(), request.fakturaPowerOfAttorneyId(),
            request.fakturaInvoiceId(), request.missingFakturaReason());

        for (ValidatedAcceptanceLine validatedLine : validatedLines) {
            AssetAcceptanceLineRequest lineRequest = validatedLine.request();
            PurchaseOrderItem item = validatedLine.purchaseOrderItem();
            BigDecimal deliveredQuantity = validatedLine.deliveredQuantity();
            AssetAcceptanceLine line = new AssetAcceptanceLine();
            line.setCode("AAL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
            line.setName("Asset acceptance line for " + firstNonBlank(item.getName(), "ordered asset"));
            line.setStatus("DRAFT");
            line.setAssetAcceptanceId(acceptance.getId());
            line.setPurchaseOrderItemId(item.getId());
            line.setMaterialId(item.getMaterialId());
            line.setMaterialTypeSnapshot(MaterialType.FIXED_ASSET);
            line.setUnit(firstNonBlank(lineRequest.unit(), item.getUnit()));
            line.setDeliveredQuantity(deliveredQuantity);
            line.setPendingQuantity(deliveredQuantity);
            line.setAcceptedQuantity(BigDecimal.ZERO);
            line.setRejectedQuantity(BigDecimal.ZERO);
            line.setHoldQuantity(BigDecimal.ZERO);
            line.setAcceptedCurrencyCode(purchaseOrder.getCurrencyCode());
            line.setAcceptedValue(nvl(item.getUnitPrice()).multiply(deliveredQuantity));
            line = lineRepository.save(line);
            int sequence = 1;
            for (AssetAcceptanceUnitRequest unitRequest : lineRequest.units()) {
                AssetAcceptanceUnit unit = new AssetAcceptanceUnit();
                unit.setCode("AAU-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
                unit.setName("Asset acceptance unit " + sequence);
                unit.setStatus("PENDING");
                unit.setAssetAcceptanceLineId(line.getId());
                unit.setPurchaseOrderItemId(item.getId());
                unit.setMaterialId(item.getMaterialId());
                unit.setMaterialTypeSnapshot(MaterialType.FIXED_ASSET);
                unit.setLineSequence(unitRequest.lineSequence() == null ? sequence : unitRequest.lineSequence());
                String manufacturerSerial = trimToNull(firstNonBlank(unitRequest.manufacturerSerialNumber(), unitRequest.serialNumber()));
                String receivingUnitNumber = nextReceivingUnitNumber();
                unit.setReceivingUnitNumber(receivingUnitNumber);
                unit.setManufacturerSerialNumber(manufacturerSerial);
                unit.setSerialNumber(manufacturerSerial);
                unit.setTemporaryTag(receivingUnitNumber);
                unit.setCondition(trimToNull(unitRequest.condition()));
                unitRepository.save(unit);
                sequence++;
            }
        }
        return toResponse(acceptance);
    }

    @Transactional
    public AssetAcceptanceResponse update(UUID id, AssetAcceptanceUpdateRequest request) {
        return update(id, request, null);
    }

    @Transactional
    public AssetAcceptanceResponse update(UUID id, AssetAcceptanceUpdateRequest request, String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            return idempotencyService.execute(
                "ASSET_ACCEPTANCE_UPDATE", id, idempotencyKey, request, AssetAcceptanceResponse.class,
                () -> {
                    requireFixedAssetsEnabled();
                    find(id);
                },
                () -> updateNow(id, request)
            );
        }
        return updateNow(id, request);
    }

    private AssetAcceptanceResponse updateNow(UUID id, AssetAcceptanceUpdateRequest request) {
        requireFixedAssetsEnabled();
        AssetAcceptance acceptance = find(id);
        requireVersion(acceptance, request == null ? null : request.expectedVersion());
        if (acceptance.getApprovalStatus() != AssetAcceptanceApprovalStatus.DRAFT) {
            throw business("Only draft asset acceptances can be edited", "ASSET_INVALID_TRANSITION");
        }
        acceptance.setDeliveryReference(trimToNull(request.deliveryReference()));
        acceptance.setSourceEvidence(trimToNull(request.sourceEvidence()));
        return toResponse(repository.save(acceptance));
    }

    @Transactional
    public AssetAcceptanceResponse recordQualityDecision(UUID id, AssetAcceptanceQualityDecisionRequest request) {
        return recordQualityDecision(id, request, null);
    }

    @Transactional
    public AssetAcceptanceResponse recordQualityDecision(UUID id, AssetAcceptanceQualityDecisionRequest request, String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            return idempotencyService.execute(
                "ASSET_ACCEPTANCE_QUALITY", id, idempotencyKey, request, AssetAcceptanceResponse.class,
                () -> {
                    requireFixedAssetsEnabled();
                    find(id);
                },
                () -> recordQualityDecisionNow(id, request)
            );
        }
        return recordQualityDecisionNow(id, request);
    }

    private AssetAcceptanceResponse recordQualityDecisionNow(UUID id, AssetAcceptanceQualityDecisionRequest request) {
        requireFixedAssetsEnabled();
        AssetAcceptance acceptance = find(id);
        requireVersion(acceptance, request == null ? null : request.expectedVersion());
        if (acceptance.getPostingStatus() == AssetAcceptancePostingStatus.CANCELLED) {
            throw business("Cancelled asset acceptance cannot be changed", "ASSET_INVALID_TRANSITION");
        }
        List<AssetAcceptanceLine> lines = lines(acceptance.getId());
        Map<UUID, AssetAcceptanceUnit> unitById = lines.stream()
            .flatMap(line -> units(line.getId()).stream())
            .collect(Collectors.toMap(AssetAcceptanceUnit::getId, Function.identity()));
        for (var decision : request == null || request.units() == null ? List.<AssetAcceptanceQualityDecisionRequest.UnitDecision>of() : request.units()) {
            AssetAcceptanceUnit unit = unitById.get(decision.assetAcceptanceUnitId());
            if (unit == null) {
                throw bad("Asset acceptance unit is outside this acceptance", "ASSET_SCOPE_DENIED");
            }
            if (unit.getPostedFixedAssetId() != null) {
                throw business("Posted asset acceptance unit quality cannot be changed", "ASSET_ACCEPTANCE_UNIT_ALREADY_POSTED");
            }
            unit.setQualityDecision(parseUnitQuality(decision.qualityDecision()));
            unit.setQualityDecisionAt(LocalDateTime.now());
            unit.setQualityDecisionBy(currentActor());
            unitRepository.save(unit);
        }
        for (AssetAcceptanceLine line : lines) {
            refreshLineQuality(line, units(line.getId()));
            lineRepository.save(line);
        }
        return toResponse(repository.save(acceptance));
    }

    @Transactional
    public AssetAcceptanceResponse submit(UUID id, VersionedAssetCommand request) {
        return submit(id, request, null);
    }

    @Transactional
    public AssetAcceptanceResponse submit(UUID id, VersionedAssetCommand request, String idempotencyKey) {
        requireFixedAssetsEnabled();
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            return idempotencyService.execute(
                "ASSET_ACCEPTANCE_SUBMIT", id, idempotencyKey, request, AssetAcceptanceResponse.class,
                () -> {
                    requireFixedAssetsEnabled();
                    find(id);
                },
                () -> submitNow(id, request)
            );
        }
        return submitNow(id, request);
    }

    private AssetAcceptanceResponse submitNow(UUID id, VersionedAssetCommand request) {
        AssetAcceptance acceptance = find(id);
        requireVersion(acceptance, request == null ? null : request.expectedVersion());
        if (acceptance.getPostingStatus() == AssetAcceptancePostingStatus.CANCELLED) {
            throw business("Cancelled asset acceptance cannot be submitted", "ASSET_INVALID_TRANSITION");
        }
        if (acceptance.getApprovalStatus() == AssetAcceptanceApprovalStatus.APPROVED) {
            return toResponse(acceptance);
        }
        if (acceptance.getApprovalStatus() != AssetAcceptanceApprovalStatus.DRAFT) {
            throw business("Only draft asset acceptance can be submitted", "ASSET_INVALID_TRANSITION");
        }
        requireAllUnitsResolved(acceptance.getId());
        var approval = approvalEngineService.start(APPROVAL_MODULE, id);
        acceptance.setApprovalInstanceId(approval == null ? acceptance.getApprovalInstanceId() : approval.id());
        acceptance.setApprovalStatus(AssetAcceptanceApprovalStatus.SUBMITTED);
        return toResponse(repository.save(acceptance));
    }

    @Transactional
    public AssetAcceptanceResponse post(UUID id, AssetAcceptancePostRequest request) {
        return post(id, request, null);
    }

    @Transactional
    public AssetAcceptanceResponse post(UUID id, AssetAcceptancePostRequest request, String idempotencyKey) {
        requireFixedAssetsEnabled();
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            return idempotencyService.execute(
                "ASSET_ACCEPTANCE_POST", id, idempotencyKey, request, AssetAcceptanceResponse.class,
                () -> {
                    requireFixedAssetsEnabled();
                    find(id);
                },
                () -> postNow(id, request, idempotencyKey)
            );
        }
        return postNow(id, request, null);
    }

    private AssetAcceptanceResponse postNow(UUID id, AssetAcceptancePostRequest request, String idempotencyKey) {
        AssetAcceptance acceptance = find(id);
        if (acceptance.getPostingStatus() == AssetAcceptancePostingStatus.POSTED) {
            return toResponse(acceptance);
        }
        if (acceptance.getPostingStatus() == AssetAcceptancePostingStatus.CANCELLED) {
            throw business("Cancelled asset acceptance cannot be posted", "ASSET_INVALID_TRANSITION");
        }
        fakturaEvidence.recheck(FakturaReceiptEvidenceService.ReceiptKind.ASSET_ACCEPTANCE,
            acceptance.getId(), acceptance.getReceiptDate());
        requireVersion(acceptance, request == null ? null : request.expectedVersion());
        if (acceptance.getApprovalStatus() != AssetAcceptanceApprovalStatus.APPROVED) {
            throw business("Asset acceptance must be approved before posting", "ASSET_ACCEPTANCE_NOT_APPROVED");
        }
        requireAllUnitsResolved(acceptance.getId());
        PurchaseOrder purchaseOrder = acceptance.getPurchaseOrderId() == null ? null : findPurchaseOrderForUpdate(acceptance.getPurchaseOrderId());
        Map<UUID, PurchaseOrderItem> itemById = (purchaseOrder == null ? List.<PurchaseOrderItem>of() : orderItems(purchaseOrder.getId())).stream()
            .collect(Collectors.toMap(PurchaseOrderItem::getId, Function.identity()));
        if (purchaseOrder != null) validateAcceptanceAsn(acceptance.getAsnId(), purchaseOrder, acceptance.getSupplierId(), lines(acceptance.getId()).stream()
            .map(line -> new ValidatedAcceptanceLine(null, itemById.get(line.getPurchaseOrderItemId()), nvl(line.getDeliveredQuantity())))
            .toList());
        int created = 0;
        for (AssetAcceptanceLine line : lines(acceptance.getId())) {
            PurchaseOrderItem item = itemById.get(line.getPurchaseOrderItemId());
            if (purchaseOrder != null && item == null) {
                throw bad("Asset acceptance line references a missing purchase order line", "ASSET_SCOPE_DENIED");
            }
            if (purchaseOrder != null) {
                validateAssetLineSnapshot(item, line.getMaterialId());
            } else {
                if (materialCatalogGuard.requireActiveMaterial(line.getMaterialId(), "materialId").getMaterialType() != MaterialType.FIXED_ASSET) {
                    throw bad("Manual receipt requires fixed-asset materials", "ASSET_SCOPE_DENIED");
                }
                validateManualAcceptanceLineContract(acceptance, line);
            }
            List<AssetAcceptanceUnit> eligibleUnits = units(line.getId()).stream()
                .filter(unit -> unit.getQualityDecision() == AssetAcceptanceUnitQualityDecision.ACCEPTED)
                .filter(unit -> unit.getPostedFixedAssetId() == null)
                .toList();
            List<BigDecimal> allocatedValues = allocatedUnitValues(line, eligibleUnits.size());
            for (int index = 0; index < eligibleUnits.size(); index++) {
                AssetAcceptanceUnit unit = eligibleUnits.get(index);
                if (fixedAssetRepository.existsByTenantIdAndCompanyIdAndSourceAcceptanceUnitIdAndIsDeletedFalse(
                    TenantContext.currentTenant(), CompanyContext.currentCompany(), unit.getId())) {
                    throw conflict("Accepted unit has already produced a fixed asset", "ASSET_ACCEPTANCE_UNIT_ALREADY_POSTED");
                }
                FixedAsset asset = createFixedAsset(acceptance, line, unit, allocatedValues.get(index));
                unit.setPostedFixedAssetId(asset.getId());
                unitRepository.save(unit);
                appendAcceptancePostedEvent(asset, acceptance, request == null ? null : request.reason(), idempotencyKey);
                created++;
            }
            if (purchaseOrder != null) recordPurchaseOrderFulfillment(line, item);
            lineRepository.save(line);
            if (purchaseOrder != null) purchaseOrderItemRepository.save(item);
        }
        if (purchaseOrder != null) {
            applyAsnShipmentProcessing(acceptance);
            updatePurchaseOrderReceivingStatus(purchaseOrder, itemById.values().stream().toList());
        }
        if (created == 0 && acceptance.getPostingStatus() == AssetAcceptancePostingStatus.POSTED) {
            return toResponse(acceptance);
        }
        acceptance.setPostingStatus(AssetAcceptancePostingStatus.POSTED);
        acceptance.setPostedAt(LocalDateTime.now());
        acceptance.setPostedBy(currentActor());
        return toResponse(repository.save(acceptance));
    }

    @Transactional
    public AssetAcceptanceResponse restrictedReversal(UUID id, AssetAcceptanceRestrictedReversalRequest request) {
        return restrictedReversal(id, request, null);
    }

    @Transactional
    public AssetAcceptanceResponse restrictedReversal(UUID id, AssetAcceptanceRestrictedReversalRequest request, String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            return idempotencyService.execute(
                "ASSET_ACCEPTANCE_REVERSAL", id, idempotencyKey, request, AssetAcceptanceResponse.class,
                () -> {
                    requireFixedAssetsEnabled();
                    find(id);
                },
                () -> restrictedReversalNow(id, request)
            );
        }
        return restrictedReversalNow(id, request);
    }

    private AssetAcceptanceResponse restrictedReversalNow(UUID id, AssetAcceptanceRestrictedReversalRequest request) {
        requireFixedAssetsEnabled();
        AssetAcceptance acceptance = find(id);
        requireVersion(acceptance, request == null ? null : request.expectedVersion());
        if (acceptance.getPostingStatus() != AssetAcceptancePostingStatus.POSTED) {
            throw business("Only posted asset acceptance can be reversed", "ASSET_INVALID_TRANSITION");
        }
        List<AssetAcceptanceUnit> postedUnits = lines(acceptance.getId()).stream()
            .flatMap(line -> units(line.getId()).stream())
            .filter(unit -> unit.getPostedFixedAssetId() != null)
            .toList();
        if (hasCommercialDependencies(acceptance)) {
            throw business("Asset acceptance reversal has downstream fixed asset dependencies", "ASSET_REVERSAL_HAS_DEPENDENCIES");
        }
        List<FixedAsset> postedAssets = postedUnits.stream()
            .map(unit -> fixedAssetRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), unit.getPostedFixedAssetId()))
            .flatMap(Optional::stream)
            .toList();
        if (postedAssets.stream().anyMatch(asset -> !isCleanReversibleAsset(asset, acceptance))) {
            throw business("Asset acceptance reversal has downstream fixed asset dependencies", "ASSET_REVERSAL_HAS_DEPENDENCIES");
        }
        List<AssetAcceptanceLine> acceptanceLines = lines(acceptance.getId());
        reversePurchaseOrderFulfillment(acceptance, acceptanceLines);
        postedAssets.forEach(asset -> reverseCleanAsset(asset, acceptance, request == null ? null : request.reason()));
        postedUnits.forEach(unit -> {
            unit.setPostedFixedAssetId(null);
            unitRepository.save(unit);
        });
        acceptance.setPostingStatus(AssetAcceptancePostingStatus.CANCELLED);
        return toResponse(repository.save(acceptance));
    }

    @Transactional
    public AssetAcceptanceResponse cancel(UUID id, VersionedAssetCommand request) {
        return cancel(id, request, null);
    }

    @Transactional
    public AssetAcceptanceResponse cancel(UUID id, VersionedAssetCommand request, String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            return idempotencyService.execute(
                "ASSET_ACCEPTANCE_CANCEL", id, idempotencyKey, request, AssetAcceptanceResponse.class,
                () -> {
                    requireFixedAssetsEnabled();
                    find(id);
                },
                () -> cancelNow(id, request)
            );
        }
        return cancelNow(id, request);
    }

    private AssetAcceptanceResponse cancelNow(UUID id, VersionedAssetCommand request) {
        requireFixedAssetsEnabled();
        AssetAcceptance acceptance = find(id);
        requireVersion(acceptance, request == null ? null : request.expectedVersion());
        if (acceptance.getPostingStatus() == AssetAcceptancePostingStatus.POSTED) {
            throw business("Posted asset acceptance requires restricted reversal", "ASSET_INVALID_TRANSITION");
        }
        acceptance.setPostingStatus(AssetAcceptancePostingStatus.CANCELLED);
        acceptance.setStatus("CANCELLED");
        return toResponse(repository.save(acceptance));
    }

    public AssetAcceptance findForApproval(UUID id) {
        return find(id);
    }

    public void markSubmittedFromApproval(UUID id) {
        AssetAcceptance acceptance = find(id);
        if (acceptance.getApprovalStatus() == AssetAcceptanceApprovalStatus.SUBMITTED) {
            return;
        }
        if (acceptance.getApprovalStatus() != AssetAcceptanceApprovalStatus.DRAFT) {
            throw business("Asset acceptance cannot be submitted from status " + acceptance.getApprovalStatus(), "ASSET_INVALID_TRANSITION");
        }
        acceptance.setApprovalStatus(AssetAcceptanceApprovalStatus.SUBMITTED);
        repository.save(acceptance);
    }

    public void markApprovedFromApproval(UUID id) {
        AssetAcceptance acceptance = find(id);
        if (acceptance.getApprovalStatus() == AssetAcceptanceApprovalStatus.APPROVED) {
            return;
        }
        if (acceptance.getApprovalStatus() != AssetAcceptanceApprovalStatus.SUBMITTED) {
            throw business("Asset acceptance must be submitted before approval", "ASSET_INVALID_TRANSITION");
        }
        acceptance.setApprovalStatus(AssetAcceptanceApprovalStatus.APPROVED);
        repository.save(acceptance);
    }

    public void markRejectedFromApproval(UUID id) {
        AssetAcceptance acceptance = find(id);
        if (acceptance.getApprovalStatus() != AssetAcceptanceApprovalStatus.SUBMITTED) {
            throw business("Asset acceptance must be submitted before rejection", "ASSET_INVALID_TRANSITION");
        }
        acceptance.setApprovalStatus(AssetAcceptanceApprovalStatus.REJECTED);
        repository.save(acceptance);
    }

    public boolean hasAcceptedUnpostedUnit(UUID id) {
        return lines(id).stream()
            .flatMap(line -> units(line.getId()).stream())
            .anyMatch(unit -> unit.getQualityDecision() == AssetAcceptanceUnitQualityDecision.ACCEPTED
                && unit.getPostedFixedAssetId() == null);
    }

    public boolean allUnitsResolved(UUID id) {
        List<AssetAcceptanceUnit> acceptanceUnits = lines(id).stream()
            .flatMap(line -> units(line.getId()).stream())
            .toList();
        return !acceptanceUnits.isEmpty()
            && acceptanceUnits.stream().noneMatch(unit ->
                unit.getQualityDecision() == AssetAcceptanceUnitQualityDecision.PENDING
                    || unit.getQualityDecision() == AssetAcceptanceUnitQualityDecision.HOLD);
    }

    private void requireAllUnitsResolved(UUID id) {
        List<AssetAcceptanceUnit> acceptanceUnits = lines(id).stream()
            .flatMap(line -> units(line.getId()).stream())
            .toList();
        if (acceptanceUnits.isEmpty()
            || acceptanceUnits.stream().anyMatch(unit ->
                unit.getQualityDecision() == AssetAcceptanceUnitQualityDecision.PENDING
                    || unit.getQualityDecision() == AssetAcceptanceUnitQualityDecision.HOLD)) {
            throw business(
                "All asset acceptance units must be accepted or rejected before approval and posting",
                "ASSET_ACCEPTANCE_UNRESOLVED_UNITS"
            );
        }
        AssetManagementSettings settings = settingsService.requireConfigured();
        if (settings != null && settings.isRequireSerialNumber()
            && acceptanceUnits.stream().anyMatch(unit ->
                unit.getQualityDecision() == AssetAcceptanceUnitQualityDecision.ACCEPTED
                    && trimToNull(unit.getManufacturerSerialNumber()) == null)) {
            throw business(
                "Accepted fixed asset units require manufacturer serial number",
                "ASSET_SERIAL_NUMBER_REQUIRED"
            );
        }
    }

    private AssetAcceptance find(UUID id) {
        return repository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), id)
            .orElseThrow(() -> new NotFoundException("Asset acceptance not found: " + id));
    }

    private PurchaseOrder findPurchaseOrderForUpdate(UUID id) {
        if (id == null) {
            throw bad("purchaseOrderId is required", "ASSET_EVIDENCE_REQUIRED");
        }
        return purchaseOrderRepository.findByIdForUpdate(id)
            .filter(order -> TenantContext.currentTenant().equals(order.getTenantId()))
            .filter(order -> CompanyContext.currentCompany().equals(order.getCompanyId()))
            .filter(order -> !order.isDeleted())
            .orElseThrow(() -> new NotFoundException("Purchase order not found: " + id));
    }

    private List<PurchaseOrderItem> orderItems(UUID purchaseOrderId) {
        return purchaseOrderItemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(
                TenantContext.currentTenant(), purchaseOrderId)
            .stream()
            .filter(item -> CompanyContext.currentCompany().equals(item.getCompanyId()))
            .toList();
    }

    private PurchaseOrderItem validateAssetPurchaseOrderLine(
        AssetAcceptanceLineRequest request,
        Map<UUID, PurchaseOrderItem> itemById
    ) {
        if (request == null || request.purchaseOrderItemId() == null) {
            throw bad("purchaseOrderItemId is required", "ASSET_EVIDENCE_REQUIRED");
        }
        PurchaseOrderItem item = itemById.get(request.purchaseOrderItemId());
        if (item == null) {
            throw bad("Asset acceptance line must belong to the purchase order", "ASSET_SCOPE_DENIED");
        }
        validateAssetLineSnapshot(item, request.materialId());
        if (!"FIXED_ASSET".equals(normalized(request.materialTypeSnapshot()))) {
            throw business("Asset acceptance line must carry FIXED_ASSET material type snapshot", "ASSET_SCOPE_DENIED");
        }
        return item;
    }

    private void validateAssetLineSnapshot(PurchaseOrderItem item, UUID materialId) {
        if (item.getLineType() != ProcurementLineType.MATERIAL
            || item.getMaterialTypeSnapshot() != MaterialType.FIXED_ASSET
            || item.getMaterialId() == null
            || !Objects.equals(item.getMaterialId(), materialId)) {
            throw business("Only FIXED_ASSET purchase order lines can be accepted as assets", "FIXED_ASSET_GOODS_RECEIPT_FORBIDDEN");
        }
    }

    private void validateManualAcceptanceLineContract(AssetAcceptance acceptance, AssetAcceptanceLine line) {
        if (acceptance.getContractId() == null) {
            throw bad("Contract is required for equipment receipt", "ASSET_SCOPE_DENIED");
        }
        Contract contract = contractRepository.findByIdAndIsDeletedFalse(acceptance.getContractId())
            .filter(c -> TenantContext.currentTenant().equals(c.getTenantId())
                && CompanyContext.currentCompany().equals(c.getCompanyId()))
            .orElseThrow(() -> notFound("Contract not found in your company"));
        LocalDate today = LocalDate.now();
        if (!"ACTIVE".equals(contract.getStatus())
            || (contract.getStartDate() != null && contract.getStartDate().isAfter(today))
            || (contract.getEndDate() != null && contract.getEndDate().isBefore(today))) {
            throw business("Contract must be active on the operation date", "ASSET_SCOPE_DENIED");
        }
        if (line.getContractSpecificationId() == null) {
            throw bad("Contract specification is required for equipment receipt", "ASSET_SCOPE_DENIED");
        }
        ContractSpecification spec = contractSpecificationRepository.findByIdAndIsDeletedFalse(line.getContractSpecificationId())
            .filter(s -> contract.getId().equals(s.getContractId())
                && TenantContext.currentTenant().equals(s.getTenantId())
                && CompanyContext.currentCompany().equals(s.getCompanyId()))
            .orElseThrow(() -> notFound("Contract specification does not belong to this contract"));
        if (spec.getLineType() != ProcurementLineType.MATERIAL || !line.getMaterialId().equals(spec.getMaterialId())) {
            throw business("Contract specification material does not match the selected equipment", "ASSET_SCOPE_DENIED");
        }
        BigDecimal totalQty = nvl(line.getAcceptedQuantity()).compareTo(BigDecimal.ZERO) > 0
            ? line.getAcceptedQuantity()
            : nvl(line.getDeliveredQuantity());
        if (spec.getQuantityLimit() != null && spec.getQuantityLimit().signum() > 0
            && totalQty.compareTo(spec.getQuantityLimit()) > 0) {
            throw business("Quantity exceeds contracted limit", "ASSET_SCOPE_DENIED");
        }
    }

    private AdvanceShippingNotice validateAcceptanceAsn(
        UUID asnId,
        PurchaseOrder purchaseOrder,
        UUID supplierId,
        List<ValidatedAcceptanceLine> validatedLines
    ) {
        if (asnId == null) {
            return null;
        }
        if (asnRepository == null || asnItemRepository == null) {
            throw business("ASN validation is not available", "ASSET_ACCEPTANCE_ASN_NOT_AVAILABLE");
        }
        AdvanceShippingNotice asn = asnRepository.findByIdAndIsDeletedFalse(asnId)
            .filter(candidate -> TenantContext.currentTenant().equals(candidate.getTenantId()))
            .filter(candidate -> CompanyContext.currentCompany().equals(candidate.getCompanyId()))
            .orElseThrow(() -> bad("ASN not found for asset acceptance", "ASSET_ACCEPTANCE_ASN_NOT_FOUND"));
        if (!Objects.equals(asn.getPurchaseOrderId(), purchaseOrder.getId())) {
            throw bad("ASN must belong to the selected purchase order", "ASSET_ACCEPTANCE_ASN_PO_MISMATCH");
        }
        if (!Objects.equals(asn.getSupplierId(), supplierId)) {
            throw bad("ASN supplier must match the asset acceptance supplier", "ASSET_ACCEPTANCE_ASN_SUPPLIER_MISMATCH");
        }
        if (!isAsnReceivingEligible(asn)) {
            throw business("ASN is not eligible for fixed asset receiving", "ASSET_ACCEPTANCE_ASN_NOT_ELIGIBLE");
        }
        Map<UUID, List<AdvanceShippingNoticeItem>> asnItemsByPoItem = asnItemRepository
            .findByTenantIdAndCompanyIdAndAsn_IdAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), asn.getId())
            .stream()
            .collect(Collectors.groupingBy(AdvanceShippingNoticeItem::getPurchaseOrderItemId));
        for (ValidatedAcceptanceLine validatedLine : validatedLines) {
            PurchaseOrderItem item = validatedLine.purchaseOrderItem();
            if (item == null) {
                throw bad("Asset acceptance line references a missing purchase order line", "ASSET_SCOPE_DENIED");
            }
            List<AdvanceShippingNoticeItem> asnItems = asnItemsByPoItem.getOrDefault(item.getId(), List.of());
            if (asnItems.isEmpty()) {
                throw bad("Asset acceptance line is not present in the selected ASN", "ASSET_ACCEPTANCE_ASN_LINE_MISMATCH");
            }
            BigDecimal shippedQuantity = asnItems.stream()
                .filter(asnItem -> Objects.equals(asnItem.getMaterialId(), item.getMaterialId()))
                .map(asnItem -> nvl(asnItem.getQuantity()).subtract(nvl(asnItem.getCancelledQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal consumedByAcceptances = nvl(lineRepository.sumDeliveredQuantityByAsnAndPurchaseOrderItem(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), asn.getId(), item.getId()));
            BigDecimal candidateConsumed = validatedLine.request() == null
                ? consumedByAcceptances
                : consumedByAcceptances.add(nvl(validatedLine.deliveredQuantity()));
            if (candidateConsumed.compareTo(shippedQuantity) > 0) {
                throw business("Asset acceptance quantity exceeds remaining ASN quantity", "ASSET_ACCEPTANCE_ASN_OVER_RECEIPT");
            }
        }
        return asn;
    }

    private boolean isAsnReceivingEligible(AdvanceShippingNotice asn) {
        return AdvanceShippingNoticeStatus.CONFIRMED.name().equals(asn.getStatus())
            || AdvanceShippingNoticeStatus.PARTIALLY_RECEIVED.name().equals(asn.getStatus());
    }

    private void applyAsnShipmentProcessing(AssetAcceptance acceptance) {
        if (acceptance.getAsnId() == null) {
            return;
        }
        if (asnRepository == null || asnItemRepository == null) {
            throw business("ASN validation is not available", "ASSET_ACCEPTANCE_ASN_NOT_AVAILABLE");
        }
        AdvanceShippingNotice asn = asnRepository.findByIdAndIsDeletedFalse(acceptance.getAsnId())
            .orElseThrow(() -> bad("ASN not found for asset acceptance", "ASSET_ACCEPTANCE_ASN_NOT_FOUND"));
        Map<UUID, List<AdvanceShippingNoticeItem>> asnItemsByPoItem = asnItemRepository
            .findByTenantIdAndCompanyIdAndAsn_IdAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), asn.getId())
            .stream()
            .collect(Collectors.groupingBy(AdvanceShippingNoticeItem::getPurchaseOrderItemId));
        for (AssetAcceptanceLine line : lines(acceptance.getId())) {
            BigDecimal remainingToApply = nvl(line.getDeliveredQuantity());
            for (AdvanceShippingNoticeItem asnItem : asnItemsByPoItem.getOrDefault(line.getPurchaseOrderItemId(), List.of())) {
                if (remainingToApply.signum() <= 0) {
                    break;
                }
                BigDecimal lineRemaining = asnLineRemainingQuantity(asnItem);
                if (lineRemaining.signum() <= 0) {
                    continue;
                }
                BigDecimal applied = lineRemaining.min(remainingToApply);
                asnItem.setReceivedQuantity(nvl(asnItem.getReceivedQuantity()).add(applied));
                asnItemRepository.save(asnItem);
                remainingToApply = remainingToApply.subtract(applied);
            }
            if (remainingToApply.signum() > 0) {
                throw business("Asset acceptance quantity exceeds remaining ASN quantity", "ASSET_ACCEPTANCE_ASN_OVER_RECEIPT");
            }
        }
        List<AdvanceShippingNoticeItem> asnItems = asnItemsByPoItem.values().stream().flatMap(List::stream).toList();
        boolean anyReceived = asnItems.stream().anyMatch(item -> nvl(item.getReceivedQuantity()).signum() > 0);
        boolean allProcessed = !asnItems.isEmpty() && asnItems.stream().allMatch(item -> asnLineRemainingQuantity(item).signum() <= 0);
        if (allProcessed) {
            asn.setStatus(AdvanceShippingNoticeStatus.RECEIVED.name());
            asn.setReceivedAt(LocalDateTime.now());
        } else if (anyReceived) {
            asn.setStatus(AdvanceShippingNoticeStatus.PARTIALLY_RECEIVED.name());
        }
        asnRepository.save(asn);
    }

    private BigDecimal asnLineRemainingQuantity(AdvanceShippingNoticeItem asnItem) {
        return nvl(asnItem.getQuantity())
            .subtract(nvl(asnItem.getReceivedQuantity()))
            .subtract(nvl(asnItem.getCancelledQuantity()))
            .max(BigDecimal.ZERO);
    }

    private BigDecimal fixedAssetOpenSupplierObligation(PurchaseOrderItem item) {
        return nvl(item.getQuantity()).subtract(nvl(item.getAcceptedQuantity())).max(BigDecimal.ZERO);
    }

    private void updatePurchaseOrderReceivingStatus(PurchaseOrder purchaseOrder, List<PurchaseOrderItem> items) {
        boolean anyAccepted = items.stream()
            .filter(this::isFixedAssetLine)
            .anyMatch(item -> nvl(item.getAcceptedQuantity()).signum() > 0);
        boolean allFixedAssetsAccepted = items.stream()
            .filter(this::isFixedAssetLine)
            .allMatch(item -> fixedAssetOpenSupplierObligation(item).signum() <= 0);
        if (allFixedAssetsAccepted) {
            purchaseOrder.setStatus(PurchaseOrderStatus.RECEIVED.name());
        } else if (anyAccepted) {
            purchaseOrder.setStatus(PurchaseOrderStatus.PARTIALLY_RECEIVED.name());
        }
        purchaseOrderRepository.save(purchaseOrder);
    }

    private boolean isFixedAssetLine(PurchaseOrderItem item) {
        ProcurementLineType lineType = item.getLineType() == null ? ProcurementLineType.MATERIAL : item.getLineType();
        return lineType == ProcurementLineType.MATERIAL && item.getMaterialTypeSnapshot() == MaterialType.FIXED_ASSET;
    }

    private void validateCentralWarehouse(UUID warehouseId, UUID storageLocationId, AssetManagementSettings settings) {
        if (warehouseId == null || !warehouseId.equals(settings.getCentralWarehouseId())) {
            throw bad("Fixed assets must be received into the configured central warehouse", "ASSET_SCOPE_DENIED");
        }
        if (storageLocationId == null) {
            return;
        }
        storageLocationRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), storageLocationId)
            .filter(location -> warehouseId.equals(location.getWarehouseId()))
            .orElseThrow(() -> bad("Storage location must belong to the central warehouse", "ASSET_SCOPE_DENIED"));
    }

    private void validateEmployee(UUID employeeId, String field) {
        if (employeeId == null) {
            return;
        }
        List<Employee> employees = employeeRepository.findByTenantIdAndCompanyIdAndIdInAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), List.of(employeeId));
        if (employees.size() != 1) {
            throw bad(field + " is outside the current company", "ASSET_SCOPE_DENIED");
        }
    }

    private void validateUnits(List<AssetAcceptanceUnitRequest> units, BigDecimal deliveredQuantity) {
        if (units == null || units.size() != deliveredQuantity.intValueExact()) {
            throw bad("One physical asset unit is required per delivered quantity", "ASSET_QUANTITY_MUST_BE_WHOLE");
        }
        HashSet<String> sourceKeys = new HashSet<>();
        for (AssetAcceptanceUnitRequest unit : units) {
            String key = firstNonBlank(unit.manufacturerSerialNumber(), unit.serialNumber(), String.valueOf(unit.lineSequence()));
            if (key == null || !sourceKeys.add(key.trim().toUpperCase(Locale.ROOT))) {
                throw conflict("Duplicate source asset unit in acceptance", "ASSET_IDEMPOTENCY_CONFLICT");
            }
        }
    }

    private String nextReceivingUnitNumber() {
        if (entityManager == null) {
            return "FA-RCV-" + LocalDate.now().getYear() + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        }
        Number next = (Number) entityManager
            .createNativeQuery("select nextval('asset_acceptance_receiving_unit_number_seq')")
            .getSingleResult();
        return "FA-RCV-" + LocalDate.now().getYear() + "-" + String.format(Locale.ROOT, "%06d", next.longValue());
    }

    private BigDecimal wholePositive(BigDecimal value, String field) {
        BigDecimal normalized = nvl(value);
        if (normalized.signum() <= 0) {
            throw bad(field + " must be greater than zero", "ASSET_QUANTITY_MUST_BE_WHOLE");
        }
        try {
            normalized.intValueExact();
        } catch (ArithmeticException exception) {
            throw bad(field + " must be a whole asset quantity", "ASSET_QUANTITY_MUST_BE_WHOLE");
        }
        return normalized;
    }

    private List<AssetAcceptanceLine> lines(UUID acceptanceId) {
        return lineRepository.findByTenantIdAndCompanyIdAndAssetAcceptanceIdAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), acceptanceId);
    }

    private List<AssetAcceptanceUnit> units(UUID lineId) {
        return unitRepository.findByTenantIdAndCompanyIdAndAssetAcceptanceLineIdAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), lineId);
    }

    private void refreshLineQuality(AssetAcceptanceLine line, List<AssetAcceptanceUnit> units) {
        long accepted = count(units, AssetAcceptanceUnitQualityDecision.ACCEPTED);
        long rejected = count(units, AssetAcceptanceUnitQualityDecision.REJECTED);
        long hold = count(units, AssetAcceptanceUnitQualityDecision.HOLD);
        long pending = count(units, AssetAcceptanceUnitQualityDecision.PENDING);
        line.setAcceptedQuantity(BigDecimal.valueOf(accepted));
        line.setRejectedQuantity(BigDecimal.valueOf(rejected));
        line.setHoldQuantity(BigDecimal.valueOf(hold));
        line.setPendingQuantity(BigDecimal.valueOf(pending));
        line.setQualityDecision(lineDecision(accepted, rejected, hold, pending));
    }

    private AssetAcceptanceLineQualityDecision lineDecision(long accepted, long rejected, long hold, long pending) {
        if (pending > 0) {
            return AssetAcceptanceLineQualityDecision.PENDING;
        }
        if (hold > 0) {
            return AssetAcceptanceLineQualityDecision.HOLD;
        }
        if (accepted > 0 && rejected == 0) {
            return AssetAcceptanceLineQualityDecision.ACCEPTED;
        }
        return AssetAcceptanceLineQualityDecision.REJECTED;
    }

    private long count(List<AssetAcceptanceUnit> units, AssetAcceptanceUnitQualityDecision decision) {
        return units.stream().filter(unit -> unit.getQualityDecision() == decision).count();
    }

    private FixedAsset createFixedAsset(
        AssetAcceptance acceptance,
        AssetAcceptanceLine line,
        AssetAcceptanceUnit unit,
        BigDecimal acquisitionValue
    ) {
        FixedAsset asset = new FixedAsset();
        asset.setCode("FA-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        asset.setName("Fixed asset " + firstNonBlank(unit.getManufacturerSerialNumber(), unit.getReceivingUnitNumber(), "received unit"));
        asset.setStatus("ACTIVE");
        asset.setMaterialId(unit.getMaterialId());
        asset.setSourceAcceptanceUnitId(unit.getId());
        asset.setAssetCode(asset.getCode());
        asset.setSerialNumber(unit.getManufacturerSerialNumber());
        asset.setLifecycleStatus(FixedAssetLifecycleStatus.IN_WAREHOUSE);
        asset.setCustodyKind(FixedAssetCustodyKind.WAREHOUSE);
        asset.setWarehouseId(acceptance.getWarehouseId());
        asset.setStorageLocationId(acceptance.getStorageLocationId());
        asset.setAcquisitionValue(acquisitionValue);
        asset.setAcquisitionCurrencyCode(firstNonBlank(line.getAcceptedCurrencyCode(), acceptance.getCurrencyCode()));
        asset.setAcquisitionExchangeRate(acceptance.getExchangeRate());
        asset.setAcquisitionEvidenceType(APPROVAL_MODULE);
        asset.setAcquisitionEvidenceId(acceptance.getId());
        asset = fixedAssetRepository.save(asset);
        return fixedAssetService == null
            ? asset
            : fixedAssetService.assignGeneratedInventoryNumber(asset, "Asset acceptance " + acceptance.getCode());
    }

    private void appendAcceptancePostedEvent(FixedAsset asset, AssetAcceptance acceptance, String reason, String idempotencyKey) {
        AssetEvent event = new AssetEvent();
        event.setCode("AE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        event.setName("Asset acceptance posted");
        event.setStatus("POSTED");
        event.setFixedAssetId(asset.getId());
        event.setEventType("ASSET_ACCEPTANCE_POSTED");
        event.setIdempotencyKey(trimToNull(idempotencyKey));
        event.setAfterLifecycleStatus(asset.getLifecycleStatus());
        event.setAfterCustodyKind(asset.getCustodyKind());
        event.setEffectiveDate(acceptance.getReceiptDate());
        event.setRecordedAt(LocalDateTime.now());
        event.setActorUsername(currentActor());
        event.setReason(trimToNull(reason));
        event.setApprovalInstanceId(acceptance.getApprovalInstanceId());
        event.setSourceDocumentType(APPROVAL_MODULE);
        event.setSourceDocumentId(acceptance.getId());
        event.setAggregateVersion(asset.getVersion());
        assetEventRepository.save(event);
        publishIntegrationEvent(
            asset,
            "ASSET_ACCEPTANCE_POSTED",
            null,
            null,
            APPROVAL_MODULE,
            acceptance.getId(),
            acceptance.getReceiptDate(),
            currentActor(),
            idempotencyKey
        );
    }

    private void recordPurchaseOrderFulfillment(AssetAcceptanceLine line, PurchaseOrderItem item) {
        BigDecimal receivedDelta = nvl(line.getDeliveredQuantity()).subtract(nvl(line.getPoReceivedQuantityRecorded()));
        BigDecimal acceptedDelta = nvl(line.getAcceptedQuantity()).subtract(nvl(line.getPoAcceptedQuantityRecorded()));
        BigDecimal rejectedDelta = nvl(line.getRejectedQuantity()).subtract(nvl(line.getPoRejectedQuantityRecorded()));
        if (receivedDelta.signum() < 0 || acceptedDelta.signum() < 0 || rejectedDelta.signum() < 0) {
            throw conflict("Asset acceptance fulfillment evidence cannot be reversed by posting", "ASSET_VERSION_CONFLICT");
        }
        if (acceptedDelta.compareTo(fixedAssetOpenSupplierObligation(item)) > 0) {
            throw conflict("Accepted fixed asset quantity exceeds open purchase order obligation", "ASSET_ACCEPTANCE_OVER_RECEIPT");
        }
        item.setReceivedQuantity(nvl(item.getReceivedQuantity()).add(receivedDelta));
        item.setAcceptedQuantity(nvl(item.getAcceptedQuantity()).add(acceptedDelta));
        item.setRejectedQuantity(nvl(item.getRejectedQuantity()).add(rejectedDelta));
        line.setPoReceivedQuantityRecorded(nvl(line.getDeliveredQuantity()));
        line.setPoAcceptedQuantityRecorded(nvl(line.getAcceptedQuantity()));
        line.setPoRejectedQuantityRecorded(nvl(line.getRejectedQuantity()));
        if (line.getPoFulfillmentRecordedAt() == null && (receivedDelta.signum() > 0 || acceptedDelta.signum() > 0 || rejectedDelta.signum() > 0)) {
            line.setPoFulfillmentRecordedAt(LocalDateTime.now());
        }
    }

    private void reversePurchaseOrderFulfillment(AssetAcceptance acceptance, List<AssetAcceptanceLine> acceptanceLines) {
        if (acceptance.getPurchaseOrderId() == null || acceptanceLines.isEmpty()) {
            return;
        }
        purchaseOrderRepository.findByIdForUpdate(acceptance.getPurchaseOrderId())
            .orElseThrow(() -> new NotFoundException("Purchase order not found: " + acceptance.getPurchaseOrderId()));
        Map<UUID, PurchaseOrderItem> items = purchaseOrderItemRepository
            .findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(
                TenantContext.currentTenant(), acceptance.getPurchaseOrderId())
            .stream()
            .collect(Collectors.toMap(PurchaseOrderItem::getId, Function.identity()));
        for (AssetAcceptanceLine line : acceptanceLines) {
            PurchaseOrderItem item = items.get(line.getPurchaseOrderItemId());
            if (item == null) {
                throw new NotFoundException("Purchase order item not found: " + line.getPurchaseOrderItemId());
            }
            item.setReceivedQuantity(reverseFulfillmentQuantity(item.getReceivedQuantity(), line.getPoReceivedQuantityRecorded()));
            item.setAcceptedQuantity(reverseFulfillmentQuantity(item.getAcceptedQuantity(), line.getPoAcceptedQuantityRecorded()));
            item.setRejectedQuantity(reverseFulfillmentQuantity(item.getRejectedQuantity(), line.getPoRejectedQuantityRecorded()));
            line.setPoReceivedQuantityRecorded(BigDecimal.ZERO);
            line.setPoAcceptedQuantityRecorded(BigDecimal.ZERO);
            line.setPoRejectedQuantityRecorded(BigDecimal.ZERO);
            line.setPoFulfillmentRecordedAt(null);
            purchaseOrderItemRepository.save(item);
            lineRepository.save(line);
        }
    }

    private BigDecimal reverseFulfillmentQuantity(BigDecimal current, BigDecimal recorded) {
        BigDecimal reversed = nvl(current).subtract(nvl(recorded));
        if (reversed.signum() < 0) {
            throw conflict("Asset acceptance fulfillment evidence cannot be reversed below zero", "ASSET_VERSION_CONFLICT");
        }
        return reversed;
    }

    private boolean hasCommercialDependencies(AssetAcceptance acceptance) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        return evidenceSnapshotRepository.existsByTenantIdAndCompanyIdAndSourceDocumentTypeAndSourceDocumentIdAndIsDeletedFalse(
            tenantId, companyId, ProcurementSourceDocumentType.ASSET_ACCEPTANCE, acceptance.getId())
            || !contractPaymentRepository.findByTenantIdAndSourceDocumentTypeAndSourceDocumentIdAndIsDeletedFalseOrderByCreatedAtDesc(
                tenantId, ProcurementSourceDocumentType.ASSET_ACCEPTANCE.name(), acceptance.getId()).isEmpty();
    }

    private boolean isCleanReversibleAsset(FixedAsset asset, AssetAcceptance acceptance) {
        return asset.getLifecycleStatus() == FixedAssetLifecycleStatus.IN_WAREHOUSE
            && asset.getCustodyKind() == FixedAssetCustodyKind.WAREHOUSE
            && Objects.equals(asset.getWarehouseId(), acceptance.getWarehouseId())
            && Objects.equals(asset.getStorageLocationId(), acceptance.getStorageLocationId())
            && asset.getActiveTransferId() == null
            && asset.getEquipmentId() == null
            && trimToNull(asset.getInventoryNumber()) == null
            && asset.getFirstCommissionedOn() == null
            && asset.getFirstCommissionedAt() == null
            && asset.getDisposedAt() == null
            && Objects.equals(asset.getAcquisitionEvidenceId(), acceptance.getId())
            && APPROVAL_MODULE.equals(asset.getAcquisitionEvidenceType());
    }

    private void reverseCleanAsset(FixedAsset asset, AssetAcceptance acceptance, String reason) {
        FixedAssetLifecycleStatus beforeLifecycle = asset.getLifecycleStatus();
        FixedAssetCustodyKind beforeCustody = asset.getCustodyKind();
        asset.setLifecycleStatus(FixedAssetLifecycleStatus.CANCELLED);
        asset.setCustodyKind(FixedAssetCustodyKind.NONE);
        asset.setWarehouseId(null);
        asset.setStorageLocationId(null);
        asset.setStatus("CANCELLED");
        appendAcceptanceReversedEvent(asset, acceptance, beforeLifecycle, beforeCustody, reason);
        asset.softDelete();
        fixedAssetRepository.save(asset);
    }

    private void appendAcceptanceReversedEvent(
        FixedAsset asset,
        AssetAcceptance acceptance,
        FixedAssetLifecycleStatus beforeLifecycle,
        FixedAssetCustodyKind beforeCustody,
        String reason
    ) {
        AssetEvent event = new AssetEvent();
        event.setCode("AE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        event.setName("Asset acceptance reversed");
        event.setStatus("POSTED");
        event.setFixedAssetId(asset.getId());
        event.setEventType("ASSET_ACCEPTANCE_REVERSED");
        event.setBeforeLifecycleStatus(beforeLifecycle);
        event.setAfterLifecycleStatus(asset.getLifecycleStatus());
        event.setBeforeCustodyKind(beforeCustody);
        event.setAfterCustodyKind(asset.getCustodyKind());
        event.setEffectiveDate(acceptance.getReceiptDate());
        event.setRecordedAt(LocalDateTime.now());
        event.setActorUsername(currentActor());
        event.setReason(trimToNull(reason));
        event.setApprovalInstanceId(acceptance.getApprovalInstanceId());
        event.setSourceDocumentType(APPROVAL_MODULE);
        event.setSourceDocumentId(acceptance.getId());
        event.setAggregateVersion(asset.getVersion());
        assetEventRepository.save(event);
        publishIntegrationEvent(
            asset,
            "ASSET_ACCEPTANCE_REVERSED",
            beforeLifecycle,
            beforeCustody,
            APPROVAL_MODULE,
            acceptance.getId(),
            acceptance.getReceiptDate(),
            currentActor(),
            null
        );
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

    private List<BigDecimal> allocatedUnitValues(AssetAcceptanceLine line, int acceptedUnitCount) {
        if (acceptedUnitCount <= 0) {
            return List.of();
        }
        BigDecimal lineAmount = acceptedBatchValue(line, acceptedUnitCount);
        if (lineAmount == null) {
            return java.util.Collections.nCopies(acceptedUnitCount, null);
        }
        BigDecimal unitAmount = lineAmount.divide(BigDecimal.valueOf(acceptedUnitCount), 4, RoundingMode.DOWN);
        List<BigDecimal> values = new ArrayList<>(acceptedUnitCount);
        BigDecimal allocated = BigDecimal.ZERO;
        for (int index = 0; index < acceptedUnitCount; index++) {
            BigDecimal value = index == acceptedUnitCount - 1 ? lineAmount.subtract(allocated) : unitAmount;
            values.add(value);
            allocated = allocated.add(value);
        }
        return values;
    }

    private BigDecimal acceptedBatchValue(AssetAcceptanceLine line, int acceptedUnitCount) {
        BigDecimal deliveredQuantity = nvl(line.getDeliveredQuantity());
        if (deliveredQuantity.signum() <= 0 || line.getAcceptedValue() == null) {
            return null;
        }
        return line.getAcceptedValue()
            .multiply(BigDecimal.valueOf(acceptedUnitCount))
            .divide(deliveredQuantity, 4, RoundingMode.HALF_UP);
    }

    private AssetAcceptanceUnitQualityDecision parseUnitQuality(String value) {
        try {
            return AssetAcceptanceUnitQualityDecision.valueOf(normalized(value));
        } catch (IllegalArgumentException exception) {
            throw bad("Unsupported asset quality decision: " + value, "ASSET_QUALITY_HOLD");
        }
    }

    private void requireVersion(AssetAcceptance acceptance, Long expectedVersion) {
        if (expectedVersion == null) {
            throw bad("expectedVersion is required", "ASSET_VERSION_CONFLICT");
        }
        if (!Objects.equals(acceptance.getVersion(), expectedVersion)) {
            throw conflict("Asset acceptance version conflict", "ASSET_VERSION_CONFLICT");
        }
    }

    private AssetAcceptanceResponse toResponse(AssetAcceptance acceptance) {
        List<AssetAcceptanceLineResponse> lines = lineRepository
            .findByTenantIdAndCompanyIdAndAssetAcceptanceIdAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), acceptance.getId())
            .stream()
            .map(line -> mapper.toResponse(line, unitRepository
                .findByTenantIdAndCompanyIdAndAssetAcceptanceLineIdAndIsDeletedFalse(
                    TenantContext.currentTenant(), CompanyContext.currentCompany(), line.getId())
                .stream()
                .map(mapper::toResponse)
                .toList()))
            .toList();
        return mapper.toResponse(acceptance, lines);
    }

    private record ValidatedAcceptanceLine(
        AssetAcceptanceLineRequest request,
        PurchaseOrderItem purchaseOrderItem,
        BigDecimal deliveredQuantity
    ) {
    }

    private void requireFixedAssetsEnabled() {
        if (!fixedAssetsEnabled) {
            throw new BusinessException(
                "Fixed assets are disabled",
                "ASSET_SCOPE_DENIED"
            );
        }
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String trimmed = trimToNull(value);
            if (trimmed != null) {
                return trimmed;
            }
        }
        return null;
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String normalized(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String currentActor() {
        return "system";
    }

    private BadRequestException bad(String message, String code) {
        return new BadRequestException(message, code);
    }

    private NotFoundException notFound(String message) {
        return new NotFoundException(message);
    }

    private BusinessException business(String message, String code) {
        return new BusinessException(message, code);
    }

    private ConflictException conflict(String message, String code) {
        return new ConflictException(message, code);
    }
}
