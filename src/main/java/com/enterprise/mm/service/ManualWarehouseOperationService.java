package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.enums.*;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.integration.faktura.FakturaReceiptEvidenceService;
import com.enterprise.mm.payload.*;
import com.enterprise.mm.warehouse.packaging.ReturnablePackagingService;
import com.enterprise.mm.repository.*;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import com.enterprise.mm.service.approval.StockMovementApprovalAdapter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ManualWarehouseOperationService {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.enterprise.mm.erp.ErpExchangeService erpExchange;
    private final RoleDataScopeService scope;
    private final WarehouseRepository warehouses;
    private final MaterialCatalogGuard catalog;
    private final StockBalanceRepository balances;
    private final StockMovementService movements;
    private final IdempotencyService idempotency;
    private final QualityInspectionService quality;
    private final MaterialUnitEligibilityService eligibility;
    private final MaterialUnitRepository units;
    private final MaterialUnitEventRepository events;
    private final ContractRepository contracts;
    private final SupplierRepository suppliers;
    private final ContractSpecificationRepository contractSpecifications;
    private final StockMovementDocumentRepository stockMovementDocuments;
    private final StockMovementRepository stockMovementRepository;
    private final ContractGroupReceiptService groupReceipts;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<ApprovalEngineService> approvalEngine;
    private final InventoryScopeLockService inventoryLocks;
    private final EntityManager em;
    private final FakturaReceiptEvidenceService fakturaEvidence;
    private final ContractReceiptLimitService receiptLimits;
    private final WarehouseReceiptProfileService receiptProfiles;
    private final WarehouseReceiptEligibilityService receiptEligibility;
    private final ReturnablePackagingService returnablePackaging;

    public record WarehouseOption(UUID id, String code, String name) {}

    /** Names for source/destination evidence only; grants no access to another warehouse's stock. */
    @Transactional(readOnly = true)
    public PageResponse<WarehouseOption> counterpartWarehouses(UUID warehouseId, String search,
        org.springframework.data.domain.Pageable pageable) {
        checkWarehouse(warehouseId);
        String pattern = search == null || search.isBlank() ? "" : "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
        return PageResponse.from(warehouses.searchManualCounterparts(TenantContext.currentTenant(),
            CompanyContext.currentCompany(), warehouseId, pattern, pageable)
            .map(w -> new WarehouseOption(w.getId(), w.getCode(), w.getName())));
    }

    /**
     * Universal contract search for manual issue modal.
     * Matches code, name, contractNumber, supplier name/code/tax ID, and material names/codes in specifications.
     */
    @Transactional(readOnly = true)
    public PageResponse<ManualIssueContractOptionResponse> searchContractsForIssue(
        UUID warehouseId,
        String search,
        String code,
        String contractNumber,
        UUID supplierId,
        Pageable pageable
    ) {
        checkWarehouse(warehouseId);
        String trimmedSearch = (search == null || search.isBlank()) ? "" : search.trim();
        String exactCode = (code == null || code.isBlank()) ? "" : code.trim().toLowerCase(Locale.ROOT);
        String exactContractNumber = (contractNumber == null || contractNumber.isBlank()) ? "" : contractNumber.trim().toLowerCase(Locale.ROOT);

        var page = contracts.searchContractsForManualIssue(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            trimmedSearch,
            exactCode,
            exactContractNumber,
            supplierId,
            supplierIdsMatchingTaxId(trimmedSearch),
            "ACTIVE",
            true,
            LocalDate.now(),
            pageable
        );

        return PageResponse.from(page.map(contract -> {
            var specs = contractSpecifications.findByTenantIdAndContractIdAndIsDeletedFalse(
                TenantContext.currentTenant(), contract.getId());
            long materialCount = specs.stream()
                .filter(s -> s.getLineType() == ProcurementLineType.MATERIAL)
                .count();
            String supplierCode = contract.getSupplier() != null ? contract.getSupplier().getCode() : null;
            String supplierName = contract.getSupplier() != null ?
                (contract.getSupplier().getLegalName() != null ? contract.getSupplier().getLegalName() : contract.getSupplier().getName()) : null;

            return new ManualIssueContractOptionResponse(
                contract.getId(),
                contract.getCode(),
                contract.getContractNumber(),
                contract.getName(),
                contract.getSupplierId(),
                supplierCode,
                supplierName,
                contract.getCurrencyCode(),
                contract.getStartDate(),
                contract.getEndDate(),
                contract.getStatus(),
                (int) materialCount
            );
        }));
    }

    private List<UUID> supplierIdsMatchingTaxId(String search) {
        if (search.isEmpty()) return List.of();
        String normalized = search.toLowerCase(Locale.ROOT);
        List<UUID> matches = new ArrayList<>();
        int page = 0;
        List<Supplier> batch;
        // Tax IDs are encrypted at rest; match the values decoded by the entity converter.
        do {
            batch = suppliers.findByTenantIdAndCompanyIdAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(),
                org.springframework.data.domain.PageRequest.of(page++, 200, org.springframework.data.domain.Sort.by("id")));
            for (Supplier supplier : batch) {
                if (supplier.getTaxId() != null && supplier.getTaxId().toLowerCase(Locale.ROOT).contains(normalized)) {
                    matches.add(supplier.getId());
                }
            }
        } while (batch.size() == 200);
        return matches;
    }

    /**
     * Retrieves full issue context for a selected contract:
     * Material specifications with current warehouse stock balance and available positions.
     */
    @Transactional(readOnly = true)
    public ContractManualIssueContextResponse contractManualIssueContext(UUID warehouseId, UUID contractId) {
        var receiptScope = requireReceiptScope(warehouseId, contractId);
        Warehouse warehouse = receiptScope.warehouse();
        Contract contract = receiptScope.contract();

        var specs = contractSpecifications.findActiveMaterialSpecificationsByContract(
            TenantContext.currentTenant(), contractId);

        return issueContext(warehouseId, warehouse, contract, specs);
    }

    record ReceiptScope(Warehouse warehouse, Contract contract) {}

    ReceiptScope requireReceiptScope(UUID warehouseId, UUID contractId) {
        Warehouse warehouse = checkWarehouse(warehouseId);
        Contract contract = contracts.findByIdAndIsDeletedFalse(contractId)
            .filter(c -> TenantContext.currentTenant().equals(c.getTenantId())
                && CompanyContext.currentCompany().equals(c.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("Contract not found"));

        if (!"ACTIVE".equals(contract.getStatus())) {
            throw new BusinessException("Contract is not in ACTIVE status");
        }
        if (contract.getStartDate() != null && contract.getStartDate().isAfter(LocalDate.now())) {
            throw new BusinessException("Contract has not started");
        }
        if (contract.getEndDate() != null && contract.getEndDate().isBefore(LocalDate.now())) {
            throw new BusinessException("Contract has expired");
        }

        return new ReceiptScope(warehouse, contract);
    }

    private ContractManualIssueContextResponse issueContext(UUID warehouseId, Warehouse warehouse, Contract contract,
        List<ContractSpecificationRepository.ContractMaterialSpecificationProjection> specs) {
        List<UUID> materialIds = specs.stream()
            .map(ContractSpecificationRepository.ContractMaterialSpecificationProjection::getMaterialId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();

        Map<UUID, StockBalanceRepository.WarehouseMaterialBalanceSummaryProjection> balanceMap = new HashMap<>();
        if (!materialIds.isEmpty()) {
            var balanceList = balances.findWarehouseBalancesByMaterialIds(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                warehouseId,
                materialIds
            );
            for (var b : balanceList) {
                balanceMap.put(b.getMaterialId(), b);
            }
        }

        LocalDate today = LocalDate.now();
        List<ContractManualIssueContextResponse.ContractManualIssueLineResponse> lines = new ArrayList<>();

        for (var spec : specs) {
            UUID matId = spec.getMaterialId();
            var balanceProj = matId != null ? balanceMap.get(matId) : null;
            BigDecimal available = balanceProj != null && balanceProj.getTotalAvailable() != null ? balanceProj.getTotalAvailable() : BigDecimal.ZERO;
            BigDecimal onHand = balanceProj != null && balanceProj.getTotalOnHand() != null ? balanceProj.getTotalOnHand() : BigDecimal.ZERO;
            BigDecimal reserved = balanceProj != null && balanceProj.getTotalReserved() != null ? balanceProj.getTotalReserved() : BigDecimal.ZERO;

            List<StockBalance> activePositions = matId != null ?
                balances.findAvailablePositionsForIssue(TenantContext.currentTenant(), CompanyContext.currentCompany(), warehouseId, matId, today)
                : List.of();

            UUID defaultPositionId = activePositions.isEmpty() ? null : activePositions.get(0).getId();

            List<ContractManualIssueContextResponse.AvailableStockPositionDto> positionDtos = activePositions.stream()
                .map(p -> new ContractManualIssueContextResponse.AvailableStockPositionDto(
                    p.getId(),
                    p.getStockBatchId(),
                    p.getStockBatch() != null ? p.getStockBatch().getBatchNumber() : null,
                    p.getStorageLocationId(),
                    p.getStorageLocation() != null ? p.getStorageLocation().getName() : null,
                    p.getStorageBinId(),
                    p.getStorageBin() != null ? p.getStorageBin().getName() : null,
                    p.getAvailableQuantity(),
                    p.getStockBatch() != null ? (p.getStockBatch().getExpiresOn() != null ? p.getStockBatch().getExpiresOn() : p.getStockBatch().getExpirationDate()) : null
                ))
                .toList();

            var stockSummary = new ContractManualIssueContextResponse.WarehouseStockBalanceSummary(
                available,
                onHand,
                reserved,
                available.compareTo(BigDecimal.ZERO) > 0,
                defaultPositionId
            );

            BigDecimal qtyLimit = spec.getQuantityLimit() != null ? spec.getQuantityLimit() : BigDecimal.ZERO;
            BigDecimal price = spec.getPrice() != null ? spec.getPrice() : BigDecimal.ZERO;
            BigDecimal totalAmount = qtyLimit.multiply(price);

            lines.add(new ContractManualIssueContextResponse.ContractManualIssueLineResponse(
                spec.getSpecificationId(),
                matId,
                spec.getMaterialCode(),
                spec.getMaterialName(),
                spec.getUnit(),
                qtyLimit,
                price,
                totalAmount,
                spec.getMaterialTypeSnapshot() != null ? spec.getMaterialTypeSnapshot().name() : "MATERIAL",
                spec.getSerialTrackingPolicy() != null ? spec.getSerialTrackingPolicy().name() : "NONE",
                stockSummary,
                positionDtos
            ));
        }

        String supplierName = contract.getSupplier() != null ?
            (contract.getSupplier().getLegalName() != null ? contract.getSupplier().getLegalName() : contract.getSupplier().getName()) : null;

        return new ContractManualIssueContextResponse(
            contract.getId(),
            contract.getCode(),
            contract.getContractNumber(),
            contract.getName(),
            contract.getSupplierId(),
            supplierName,
            contract.getCurrencyCode(),
            contract.getStartDate(),
            contract.getEndDate(),
            warehouse.getId(),
            warehouse.getName(),
            lines
        );
    }

    @Transactional(readOnly = true)
    public ContractManualIssueContextResponse contractManualReceiptContext(UUID warehouseId, UUID contractId) {
        var context = contractManualIssueContext(warehouseId, contractId);
        var lines = new ArrayList<>(context.lines());
        lines.addAll(groupReceipts.contextLines(contractId));
        return new ContractManualIssueContextResponse(context.contractId(), context.contractCode(), context.contractNumber(),
            context.contractName(), context.supplierId(), context.supplierName(), context.currencyCode(), context.startDate(),
            context.endDate(), context.warehouseId(), context.warehouseName(), receiptEligibility.enrichAdvisory(warehouseId, lines, false));
    }

    @Transactional(readOnly = true)
    public PageResponse<ContractManualIssueContextResponse.ContractManualIssueLineResponse> contractSpecificationMaterials(
        UUID warehouseId, UUID contractId, UUID specificationId, String search, Pageable pageable) {
        // Apply the same warehouse, contract scope, lifecycle and validity checks as the receipt context.
        requireReceiptScope(warehouseId, contractId);
        var result = groupReceipts.members(contractId, specificationId, search, pageable);
        return new PageResponse<>(receiptEligibility.enrichAdvisory(warehouseId, result.content(), true),
            result.page(), result.size(), result.totalElements(), result.totalPages());
    }

    @Transactional
    public ManualWarehouseDocumentResponse receiptBatch(ManualWarehouseDocumentRequest request, String key) {
        checkWarehouse(request.warehouseId());
        return idempotency.executeRequired("MANUAL_WAREHOUSE_RECEIPT_BATCH", request.warehouseId(), key, request,
            ManualWarehouseDocumentResponse.class, () -> submitBatch(request, true).docResponse());
    }

    @Transactional
    public ManualWarehouseDocumentResponse issueBatch(ManualWarehouseDocumentRequest request, String key) {
        checkWarehouse(request.warehouseId());
        return idempotency.executeRequired("MANUAL_WAREHOUSE_ISSUE_BATCH", request.warehouseId(), key, request,
            ManualWarehouseDocumentResponse.class, () -> submitBatch(request, false).docResponse());
    }

    @Transactional
    public StockMovementResponse receipt(ManualWarehouseOperationRequest request, String key) {
        checkWarehouse(request.warehouseId());
        if (request.contractId() == null) throw new BusinessException("Contract is required for manual receipt");
        if (request.contractSpecificationId() == null) throw new BusinessException("Contract specification is required for manual receipt");
        var docRequest = toDocumentRequest(request);
        return idempotency.executeRequired("MANUAL_WAREHOUSE_RECEIPT", request.warehouseId(), key, request,
            StockMovementResponse.class, () -> submitBatch(docRequest, true).movementResponses().get(0));
    }

    @Transactional
    public StockMovementResponse issue(ManualWarehouseOperationRequest request, String key) {
        checkWarehouse(request.warehouseId());
        if (request.contractId() == null) throw new BusinessException("Contract is required for manual issue");
        if (request.contractSpecificationId() == null) throw new BusinessException("Contract specification is required for manual issue");
        var docRequest = toDocumentRequest(request);
        return idempotency.executeRequired("MANUAL_WAREHOUSE_ISSUE", request.warehouseId(), key, request,
            StockMovementResponse.class, () -> submitBatch(docRequest, false).movementResponses().get(0));
    }

    private ManualWarehouseDocumentRequest toDocumentRequest(ManualWarehouseOperationRequest request) {
        var line = new ManualWarehouseDocumentLineRequest(
            request.contractSpecificationId(),
            request.quantity(),
            request.unitCost(),
            request.stockPositionId(),
            request.materialId(),
            request.serialNumbers(),
            request.materialUnitIds()
        );
        return new ManualWarehouseDocumentRequest(
            request.warehouseId(),
            request.contractId(),
            request.movementDate(),
            request.source(),
            request.reference(),
            List.of(line),
            request.fakturaPowerOfAttorneyId(),
            request.fakturaInvoiceId(),
            request.missingFakturaReason(),
            request.packaging()
        );
    }

    private Warehouse checkWarehouse(UUID id) {
        scope.assertWarehouseAccess(id);
        return warehouses.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), id)
            .filter(w -> CompanyContext.currentCompany().equals(w.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("Warehouse not found"));
    }
    private record BatchResult(
        ManualWarehouseDocumentResponse docResponse,
        List<StockMovementResponse> movementResponses
    ) {}

    private BatchResult submitBatch(ManualWarehouseDocumentRequest request, boolean receipt) {
        checkWarehouse(request.warehouseId());
        // Match stock posting: company/opening guard and warehouse scope before specification or stock row locks.
        inventoryLocks.lock(CompanyContext.currentCompany(), List.of(request.warehouseId()));
        if (request.contractId() == null) {
            throw new BusinessException("Contract is required for manual " + (receipt ? "receipt" : "issue"));
        }
        Contract contract = contracts.findByIdAndIsDeletedFalse(request.contractId())
            .filter(c -> TenantContext.currentTenant().equals(c.getTenantId())
                && CompanyContext.currentCompany().equals(c.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("Contract not found in your company"));

        if (!"ACTIVE".equals(contract.getStatus())) {
            throw new BusinessException("Contract is not in ACTIVE status: " + contract.getStatus());
        }
        LocalDate today = LocalDate.now();
        if (contract.getStartDate() != null && contract.getStartDate().isAfter(today)) {
            throw new BusinessException("Contract has not started");
        }
        if (contract.getEndDate() != null && contract.getEndDate().isBefore(today)) {
            throw new BusinessException("Contract has expired");
        }
        if (request.lines() == null || request.lines().isEmpty()) {
            throw new BusinessException("At least one line is required");
        }
        if (request.packaging() != null && !request.packaging().isEmpty()) {
            if (!receipt) throw new BusinessException("Returnable packaging can be received only with content");
            Set<String> packageLines = new HashSet<>();
            for (var packageLine : request.packaging()) {
                if (packageLine == null || packageLine.contentLineIndex() == null || packageLine.contentLineIndex() < 0
                    || packageLine.contentLineIndex() >= request.lines().size()
                    || packageLine.packagingMaterialId() == null
                    || !packageLines.add(packageLine.contentLineIndex() + ":" + packageLine.packagingMaterialId()))
                    throw new BusinessException("Packaging content line is invalid or duplicated");
            }
        }

        Set<UUID> specIds = new HashSet<>();
        Set<String> requestedLines = new HashSet<>();
        for (ManualWarehouseDocumentLineRequest line : request.lines()) {
            if (line.contractSpecificationId() == null) {
                throw new BusinessException("Contract specification ID is required for each line");
            }
            if (!requestedLines.add(line.contractSpecificationId() + ":" + line.materialId())
                || (!specIds.add(line.contractSpecificationId()) && !receipt)) {
                throw new BusinessException("Duplicate contract specification in request: " + line.contractSpecificationId());
            }
        }

        Map<UUID, ContractSpecification> loadedSpecs = new HashMap<>();
        Map<ManualWarehouseDocumentLineRequest, Material> loadedMaterials = new HashMap<>();
        Set<UUID> directSpecIds = new HashSet<>();
        Map<UUID, BigDecimal> requestedGroupQuantities = new HashMap<>();
        Map<UUID, Material> materialsToLock = new TreeMap<>(Comparator.comparing(UUID::toString));
        Set<String> allBatchSerials = new HashSet<>();
        Set<UUID> allBatchUnitIds = new HashSet<>();
        Map<UUID, BigDecimal> totalPerPosition = new HashMap<>();
        Map<UUID, BigDecimal> totalPerMaterial = new HashMap<>();

        // Lock every receipt specification in the same order across concurrent documents.
        List<UUID> orderedSpecIds = specIds.stream().sorted(Comparator.comparing(UUID::toString)).toList();
        for (UUID specId : orderedSpecIds) {
            ContractSpecification spec = (receipt
                ? contractSpecifications.findByIdAndIsDeletedFalseForUpdate(specId)
                : contractSpecifications.findByIdAndIsDeletedFalse(specId))
                .filter(s -> contract.getId().equals(s.getContractId())
                    && TenantContext.currentTenant().equals(s.getTenantId())
                    && CompanyContext.currentCompany().equals(s.getCompanyId()))
                .orElseThrow(() -> new NotFoundException("Contract specification does not belong to this contract"));
            loadedSpecs.put(specId, spec);
        }

        for (ManualWarehouseDocumentLineRequest line : request.lines()) {
            if (line.quantity() == null || line.quantity().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException("Quantity must be greater than zero");
            }
            ContractSpecification spec = loadedSpecs.get(line.contractSpecificationId());
            if (!"ACTIVE".equals(spec.getStatus())) {
                throw new BusinessException("Contract specification is not ACTIVE");
            }
            if (spec.getLineType() != ProcurementLineType.MATERIAL) {
                throw new BusinessException("Contract specification is not a material line: " + spec.getId());
            }
            boolean groupLine = spec.getMaterialGroupId() != null;
            if (!groupLine && !directSpecIds.add(spec.getId())) {
                throw new BusinessException("Duplicate contract specification in request: " + spec.getId());
            }
            if (groupLine && !receipt) {
                throw new BusinessException("Group contract specifications support material receipt only");
            }
            UUID materialId = groupLine ? line.materialId() : spec.getMaterialId();
            if (materialId == null) {
                throw new BusinessException("Material is required for contract specification");
            }
            if (!groupLine && line.materialId() != null && !materialId.equals(line.materialId())) {
                throw new BusinessException("Contract specification material does not match the selected material");
            }

            Material material = catalog.requireActiveTmbMaterial(materialId, "materialId");
            if (material.getMaterialType() == MaterialType.FIXED_ASSET) {
                throw new BusinessException("Material batch commands do not accept fixed asset materials");
            }
            loadedMaterials.put(line, material);
            materialsToLock.put(materialId, material);
        }
        if (receipt && request.packaging() != null && !request.packaging().isEmpty())
            returnablePackaging.validateManualRequest(contract.getSupplierId(), request.packaging(),
                request.lines().stream().map(line -> loadedMaterials.get(line).getId()).toList());

        // Bound-line validation and first balance creation both lock materials. Acquire every
        // direct/group material first, in one order, so mixed batches cannot invert those locks.
        for (Material material : materialsToLock.values()) {
            em.refresh(material, LockModeType.PESSIMISTIC_WRITE);
            catalog.requireActiveTmbMaterial(material.getId(), "materialId");
        }

        for (ManualWarehouseDocumentLineRequest line : request.lines()) {
            ContractSpecification spec = loadedSpecs.get(line.contractSpecificationId());
            Material material = loadedMaterials.get(line);
            UUID materialId = material.getId();
            boolean groupLine = spec.getMaterialGroupId() != null;
            StockUomPolicy.quantity(material, line.quantity(), false);

            if (groupLine) {
                BigDecimal contractQuantity = groupReceipts.validateReceipt(spec, material, line.quantity(),
                    requestedGroupQuantities.getOrDefault(spec.getId(), BigDecimal.ZERO));
                requestedGroupQuantities.merge(spec.getId(), contractQuantity, BigDecimal::add);
            } else if (receipt) {
                receiptLimits.validate(spec, line.quantity(), BigDecimal.ZERO);
            } else if (spec.getQuantityLimit() != null && spec.getQuantityLimit().compareTo(BigDecimal.ZERO) > 0
                && line.quantity().compareTo(spec.getQuantityLimit()) > 0) {
                throw new BusinessException("Quantity (" + line.quantity()
                    + ") exceeds contracted limit (" + spec.getQuantityLimit() + ")");
            }

            boolean serialized = material.getSerialTrackingPolicy() == SerialTrackingPolicy.REQUIRED;

            if (receipt) {
                receiptEligibility.validateMaterial(material);
                if (line.unitCost() == null || line.unitCost().compareTo(BigDecimal.ZERO) < 0) {
                    throw new BusinessException("Receipt unit cost is required");
                }
                if (serialized) {
                    int count;
                    try {
                        count = line.quantity().intValueExact();
                    } catch (ArithmeticException e) {
                        throw new BusinessException("Serialized quantity must be whole");
                    }
                    List<String> serials = line.serialNumbers() == null ? List.of() : line.serialNumbers();
                    if (serials.size() != count) {
                        throw new BusinessException("Enter one serial number per physical unit");
                    }
                    for (String serial : serials) {
                        String normalized = serial.trim().toUpperCase(Locale.ROOT);
                        if (normalized.isBlank() || !allBatchSerials.add(normalized) || units.existsByTenantIdAndCompanyIdAndMaterialIdAndNormalizedSerialNumberAndIsDeletedFalse(
                            TenantContext.currentTenant(), CompanyContext.currentCompany(), materialId, normalized)) {
                            throw new BusinessException("Serial number is empty or already registered: " + serial);
                        }
                    }
                }
            } else {
                if (line.stockPositionId() == null) {
                    throw new BusinessException("Select a stock position");
                }
                totalPerPosition.merge(line.stockPositionId(), line.quantity(), BigDecimal::add);
                totalPerMaterial.merge(materialId, line.quantity(), BigDecimal::add);
                if (serialized) {
                    int count;
                    try {
                        count = line.quantity().intValueExact();
                    } catch (ArithmeticException e) {
                        throw new BusinessException("Serialized quantity must be whole");
                    }
                    List<UUID> unitIds = line.materialUnitIds() == null ? List.of() : line.materialUnitIds();
                    if (unitIds.size() != count) {
                        throw new BusinessException("Select required material units");
                    }
                    for (UUID uId : unitIds) {
                        if (!allBatchUnitIds.add(uId)) {
                            throw new BusinessException("Duplicate serialized material unit across lines: " + uId);
                        }
                    }
                }
            }
        }

        Map<UUID, StockBalance> lockedPositions = new HashMap<>();
        if (!receipt) {
            List<UUID> sortedPositionIds = request.lines().stream()
                .map(ManualWarehouseDocumentLineRequest::stockPositionId)
                .distinct()
                .sorted(Comparator.comparing(UUID::toString))
                .toList();

            for (UUID posId : sortedPositionIds) {
                StockBalance balance = balances.findByIdAndIsDeletedFalseForUpdate(posId)
                    .filter(b -> TenantContext.currentTenant().equals(b.getTenantId())
                        && CompanyContext.currentCompany().equals(b.getCompanyId())
                        && request.warehouseId().equals(b.getWarehouseId()))
                    .orElseThrow(() -> new NotFoundException("Stock position not found in your warehouse"));
                lockedPositions.put(posId, balance);
            }

            for (int i = 0; i < request.lines().size(); i++) {
                ManualWarehouseDocumentLineRequest line = request.lines().get(i);
                ContractSpecification spec = loadedSpecs.get(line.contractSpecificationId());
                Material material = loadedMaterials.get(line);
                StockBalance balance = lockedPositions.get(line.stockPositionId());

                if (!material.getId().equals(balance.getMaterialId())) {
                    throw new BusinessException("Stock position material does not match specification material");
                }
                boolean serialized = material.getSerialTrackingPolicy() == SerialTrackingPolicy.REQUIRED;
                String blocked = StockPositionPolicy.blockedReason(balance, serialized);
                if (blocked != null) {
                    throw new BusinessException("Stock position is unavailable: " + blocked);
                }
                if (balance.getStockBatchId() == null) {
                    throw new BusinessException("Stock position has no batch");
                }
                if (serialized) {
                    List<MaterialUnit> selected = eligibility.lockAndValidate(material.getId(), request.warehouseId(),
                        balance.getStorageLocationId(), balance.getStorageBinId(), line.quantity(), line.materialUnitIds(), MaterialUnitStatus.IN_STOCK);
                    if (selected.stream().anyMatch(u -> !balance.getStockBatchId().equals(u.getStockBatchId()))) {
                        throw new BusinessException("Selected units must belong to the selected stock position");
                    }
                }
            }

            for (Map.Entry<UUID, BigDecimal> entry : totalPerPosition.entrySet()) {
                StockBalance balance = lockedPositions.get(entry.getKey());
                BigDecimal available = balance.getAvailableQuantity() != null ? balance.getAvailableQuantity() : BigDecimal.ZERO;
                if (available.compareTo(entry.getValue()) < 0) {
                    throw new BusinessException("Not enough available stock in this position. Available: "
                        + available + ", requested: " + entry.getValue());
                }
            }
            Map<UUID, BigDecimal> warehouseAvailable = new HashMap<>();
            for (var summary : balances.findWarehouseBalancesByMaterialIds(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), request.warehouseId(),
                new ArrayList<>(totalPerMaterial.keySet()))) {
                warehouseAvailable.put(summary.getMaterialId(),
                    summary.getTotalAvailable() == null ? BigDecimal.ZERO : summary.getTotalAvailable());
            }
            for (Map.Entry<UUID, BigDecimal> entry : totalPerMaterial.entrySet()) {
                if (warehouseAvailable.getOrDefault(entry.getKey(), BigDecimal.ZERO).compareTo(entry.getValue()) < 0) {
                    throw new BusinessException("Not enough available stock for this material in the warehouse");
                }
            }
        }

        StockMovementDocument document = new StockMovementDocument();
        document.setTenantId(TenantContext.currentTenant());
        document.setCompanyId(CompanyContext.currentCompany());
        document.setCode("SMD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        String contractLabel = contract.getContractNumber() != null ? contract.getContractNumber() : contract.getCode();
        document.setName((receipt ? "Kirim" : "Chiqim") + " · " + contractLabel);
        document.setStatus("DRAFT");
        document.setWarehouseId(request.warehouseId());
        document.setMovementType(receipt ? MovementType.RECEIPT : MovementType.ISSUE);
        document.setMovementDate(request.movementDate() == null ? LocalDate.now() : request.movementDate());
        document.setReferenceType("MANUAL_WAREHOUSE");
        document.setReferenceId(contract.getId());
        document.setDescription(java.util.stream.Stream.of(request.source(), request.reference())
            .filter(value -> value != null && !value.isBlank()).map(String::trim)
            .collect(Collectors.joining(" · ")));
        document.setManualOperationPayload(writePayload(request));
        document = stockMovementDocuments.save(document);
        if (receipt) {
            fakturaEvidence.attach(contract.getId(), FakturaReceiptEvidenceService.ReceiptKind.STOCK_MOVEMENT,
                document.getId(), document.getMovementDate(), request.fakturaPowerOfAttorneyId(),
                request.fakturaInvoiceId(), request.missingFakturaReason());
        }

        List<StockMovementResponse> movementResponses = new ArrayList<>();
        List<UUID> movementIds = new ArrayList<>();

        for (int i = 0; i < request.lines().size(); i++) {
            ManualWarehouseDocumentLineRequest line = request.lines().get(i);
            ContractSpecification spec = loadedSpecs.get(line.contractSpecificationId());
            Material material = loadedMaterials.get(line);
            StockMovement movement = new StockMovement();
            movement.setDocumentId(document.getId());
            movement.setCompanyId(CompanyContext.currentCompany());
            movement.setMaterialId(material.getId());
            movement.setWarehouseId(request.warehouseId());
            movement.setMovementType(receipt ? MovementType.RECEIPT : MovementType.ISSUE);
            movement.setQuantity(line.quantity());
            movement.setMovementDate(document.getMovementDate());
            movement.setReferenceType("MANUAL_WAREHOUSE");
            movement.setReferenceId(contract.getId());
            movement.setContractSpecificationId(spec.getId());

            String supplierLabel = contract.getSupplier() != null ?
                (contract.getSupplier().getLegalName() != null ? contract.getSupplier().getLegalName() : contract.getSupplier().getName()) : "";
            String contractDesc = "Shartnoma: " + contractLabel + (supplierLabel == null || supplierLabel.isBlank() ? "" : " · " + supplierLabel);
            String evidenceDesc = document.getDescription();
            movement.setDescription(evidenceDesc == null || evidenceDesc.isBlank()
                ? contractDesc
                : evidenceDesc + " · " + contractDesc);

            if (receipt) {
                movement.setUnitCost(line.unitCost());
            } else {
                StockBalance balance = lockedPositions.get(line.stockPositionId());
                movement.setStockBatchId(balance.getStockBatchId());
                movement.setStorageLocationId(balance.getStorageLocationId());
                movement.setStorageBinId(balance.getStorageBinId());
                movement.setUnitCost(BigDecimal.ZERO);
            }

            StockMovementResponse draft = movements.createManualWarehouseDraft(movement);
            movementResponses.add(draft);
            movementIds.add(draft.getId());
        }

        approvalEngine.getObject().start(StockMovementApprovalAdapter.MODULE_KEY, document.getId());
        // ApprovalEngine invokes the adapter synchronously in this transaction. Keep the
        // response explicit even when a non-JPA test double is used for the engine.
        document.setStatus(StockMovementStatus.PENDING.name());
        movementResponses.forEach(response -> {
            response.setStatus(StockMovementStatus.PENDING.name());
            response.setStockMovementStatus(StockMovementStatus.PENDING);
        });

        return new BatchResult(
            new ManualWarehouseDocumentResponse(
                document.getId(),
                document.getCode(),
                document.getMovementType(),
                document.getStatus(),
                movementIds,
                movementIds.size()
            ),
            movementResponses
        );
    }

    /** Called only by the approval adapter after the final approval decision. */
    @Transactional
    public void postApprovedDocument(UUID documentId) {
        StockMovementDocument document = stockMovementDocuments.findByIdAndIsDeletedFalse(documentId)
            .filter(d -> TenantContext.currentTenant().equals(d.getTenantId())
                && CompanyContext.currentCompany().equals(d.getCompanyId())
                && "MANUAL_WAREHOUSE".equals(d.getReferenceType()))
            .orElseThrow(() -> new NotFoundException("Manual warehouse document not found"));
        if ("POSTED".equals(document.getStatus())) return;
        if (!"APPROVED".equals(document.getStatus()))
            throw new BusinessException("Manual warehouse document must be APPROVED before posting");

        ManualWarehouseDocumentRequest request = readPayload(document.getManualOperationPayload());
        boolean receipt = document.getMovementType() == MovementType.RECEIPT;
        if (receipt) {
            fakturaEvidence.recheck(FakturaReceiptEvidenceService.ReceiptKind.STOCK_MOVEMENT,
                document.getId(), document.getMovementDate());
        }
        checkWarehouse(request.warehouseId());
        Contract contract = contracts.findByIdAndIsDeletedFalse(request.contractId())
            .filter(c -> TenantContext.currentTenant().equals(c.getTenantId())
                && CompanyContext.currentCompany().equals(c.getCompanyId()) && "ACTIVE".equals(c.getStatus()))
            .orElseThrow(() -> new BusinessException("Contract is no longer active"));
        LocalDate today = LocalDate.now();
        if ((contract.getStartDate() != null && contract.getStartDate().isAfter(today))
            || (contract.getEndDate() != null && contract.getEndDate().isBefore(today)))
            throw new BusinessException("Contract is not valid on the approval date");

        List<StockMovement> lines = stockMovementRepository
            .findByTenantIdAndDocumentIdAndIsDeletedFalseOrderByCreatedAtAsc(TenantContext.currentTenant(), documentId);
        if (lines.size() != request.lines().size())
            throw new BusinessException("Manual warehouse approval snapshot does not match its movement lines");

        if (receipt) validateApprovedReceipt(request, contract, lines);

        Map<UUID, List<StockMovement>> linesBySpecification = lines.stream().collect(Collectors.groupingBy(
            StockMovement::getContractSpecificationId,
            LinkedHashMap::new,
            Collectors.toCollection(ArrayList::new)
        ));

        List<StockMovement> postedContentMovements = new ArrayList<>();
        List<com.enterprise.mm.erp.ErpExchangeService.Line> exchangeLines = new ArrayList<>();
        for (ManualWarehouseDocumentLineRequest command : request.lines()) {
            List<StockMovement> candidates = linesBySpecification.getOrDefault(command.contractSpecificationId(), List.of());
            StockMovement movement = candidates.stream()
                .filter(line -> command.materialId() == null || Objects.equals(command.materialId(), line.getMaterialId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException("Manual warehouse approval line changed"));
            candidates.remove(movement);
            if (movement.getQuantity().compareTo(command.quantity()) != 0)
                throw new BusinessException("Manual warehouse approval line quantity changed");
            Material material = catalog.requireActiveTmbMaterial(movement.getMaterialId(), "materialId");
            boolean serialized = material.getSerialTrackingPolicy() == SerialTrackingPolicy.REQUIRED;
            List<MaterialUnit> selected = List.of();
            if (!receipt && serialized) {
                selected = eligibility.lockAndValidate(material.getId(), request.warehouseId(),
                    movement.getStorageLocationId(), movement.getStorageBinId(), movement.getQuantity(),
                    command.materialUnitIds(), MaterialUnitStatus.IN_STOCK);
                if (selected.stream().anyMatch(unit -> !Objects.equals(movement.getStockBatchId(), unit.getStockBatchId())))
                    throw new BusinessException("Selected units must belong to the approved stock position");
            }
            if (receipt && serialized) validateReceiptSerials(material.getId(), movement.getQuantity(), command.serialNumbers());

            StockMovementResponse posted = movements.postManualWarehouseMovement(movement);
            if (erpExchange != null && erpExchange.captureEnabled()) exchangeLines.add(new com.enterprise.mm.erp.ErpExchangeService.Line(posted.getMaterialId(), posted.getWarehouseId(), posted.getQuantity(), posted.getTotalCost()));
            if (receipt) postedContentMovements.add(movement);
            if (receipt && serialized) registerUnitsForLine(material.getId(), request.warehouseId(), posted, command.serialNumbers());
            if (!receipt && serialized) eligibility.markIssued(selected, "MANUAL_WAREHOUSE", posted.getId(), posted.getId(),
                request.warehouseId(), posted.getStorageLocationId(), posted.getStorageBinId());
            if (receipt && material.isIncomingInspectionRequired()) startIncomingInspection(material, posted);
        }
        if (receipt && request.packaging() != null && !request.packaging().isEmpty())
            returnablePackaging.recordManualReceipt(document.getId(), contract.getSupplierId(),
                request.packaging(), postedContentMovements);
        document.setStatus("POSTED");
        document.setPostedAt(LocalDateTime.now());
        stockMovementDocuments.save(document);
        if (!exchangeLines.isEmpty()) com.enterprise.mm.erp.ErpExchangeHooks.accept(erpExchange, receipt ? "RECEIPT" : "ISSUE", document.getId(), document.getVersion(), document.getMovementDate(), receipt ? "RECEIPT" : "ISSUE", receipt ? null : request.warehouseId(), receipt ? request.warehouseId() : null, exchangeLines);
    }

    /** Recheck shared receipt limits and warehouse rules after waiting for approval. */
    private void validateApprovedReceipt(ManualWarehouseDocumentRequest request, Contract contract,
        List<StockMovement> lines) {
        inventoryLocks.lock(CompanyContext.currentCompany(), List.of(request.warehouseId()));
        Map<UUID, ContractSpecification> specifications = new TreeMap<>();
        for (UUID id : request.lines().stream().map(ManualWarehouseDocumentLineRequest::contractSpecificationId).distinct().sorted().toList()) {
            var spec = contractSpecifications.findByIdAndIsDeletedFalseForUpdate(id)
                .filter(value -> contract.getId().equals(value.getContractId())
                    && TenantContext.currentTenant().equals(value.getTenantId())
                    && CompanyContext.currentCompany().equals(value.getCompanyId()))
                .orElseThrow(() -> new BusinessException("Contract specification no longer belongs to this contract"));
            if (!"ACTIVE".equals(spec.getStatus()) || spec.getLineType() != ProcurementLineType.MATERIAL)
                throw new BusinessException("Contract specification is no longer an ACTIVE material line");
            specifications.put(id, spec);
        }
        Map<UUID, Material> materials = new TreeMap<>();
        for (var line : lines) materials.put(line.getMaterialId(), catalog.requireActiveTmbMaterial(line.getMaterialId(), "materialId"));
        for (var material : materials.values()) em.refresh(material, LockModeType.PESSIMISTIC_WRITE);
        Map<UUID, BigDecimal> requested = new HashMap<>();
        for (var line : lines) {
            var spec = specifications.get(line.getContractSpecificationId());
            if (spec == null) throw new BusinessException("Manual warehouse approval specification changed");
            var material = catalog.requireActiveTmbMaterial(line.getMaterialId(), "materialId");
            StockUomPolicy.quantity(material, line.getQuantity(), false);
            receiptEligibility.validateMaterial(material);
            BigDecimal contractQuantity;
            var alreadyRequested = requested.getOrDefault(spec.getId(), BigDecimal.ZERO);
            if (spec.getMaterialGroupId() != null) {
                contractQuantity = groupReceipts.validateReceipt(spec, material, line.getQuantity(), alreadyRequested);
            } else {
                if (!Objects.equals(spec.getMaterialId(), material.getId()))
                    throw new BusinessException("Contract specification material changed before approval");
                receiptLimits.validate(spec, line.getQuantity(), alreadyRequested);
                contractQuantity = line.getQuantity();
            }
            requested.merge(spec.getId(), contractQuantity, BigDecimal::add);
        }
    }

    private void validateReceiptSerials(UUID materialId, BigDecimal quantity, List<String> serialNumbers) {
        int count;
        try { count = quantity.intValueExact(); }
        catch (ArithmeticException e) { throw new BusinessException("Serialized quantity must be whole"); }
        List<String> serials = serialNumbers == null ? List.of() : serialNumbers;
        if (serials.size() != count) throw new BusinessException("Enter one serial number per physical unit");
        Set<String> unique = new HashSet<>();
        for (String serial : serials) {
            String normalized = serial == null ? "" : serial.trim().toUpperCase(Locale.ROOT);
            if (normalized.isBlank() || !unique.add(normalized)
                || units.existsByTenantIdAndCompanyIdAndMaterialIdAndNormalizedSerialNumberAndIsDeletedFalse(
                    TenantContext.currentTenant(), CompanyContext.currentCompany(), materialId, normalized))
                throw new BusinessException("Serial number is empty or already registered: " + serial);
        }
    }

    private void startIncomingInspection(Material material, StockMovementResponse posted) {
        QualityInspectionCreateRequest inspection = new QualityInspectionCreateRequest();
        inspection.setName("Incoming inspection · " + material.getName());
        inspection.setInspectionType("INCOMING");
        inspection.setStockMovementId(posted.getId());
        inspection.setReceivedQuantity(posted.getQuantity());
        inspection.setQuarantinedQuantity(posted.getQuantity());
        inspection.setDueDate(LocalDate.now().plusDays(1));
        quality.startQuarantine(quality.create(inspection).getId());
    }

    private String writePayload(ManualWarehouseDocumentRequest request) {
        try { return objectMapper.writeValueAsString(request); }
        catch (JsonProcessingException e) { throw new BusinessException("Manual warehouse command could not be saved"); }
    }

    private ManualWarehouseDocumentRequest readPayload(String payload) {
        if (payload == null || payload.isBlank()) throw new BusinessException("Manual warehouse approval snapshot is missing");
        try { return objectMapper.readValue(payload, ManualWarehouseDocumentRequest.class); }
        catch (JsonProcessingException e) { throw new BusinessException("Manual warehouse approval snapshot is invalid"); }
    }

    private void registerUnitsForLine(UUID materialId, UUID warehouseId, StockMovementResponse movement, List<String> serialNumbers) {
        if (serialNumbers == null) return;
        for (String serial : serialNumbers) {
            MaterialUnit unit = new MaterialUnit();
            unit.setCode("MU-" + UUID.randomUUID().toString().substring(0, 8));
            unit.setName("Material unit " + serial.trim());
            unit.setStatus("IN_STOCK");
            unit.setMaterialId(materialId);
            unit.setWarehouseId(warehouseId);
            unit.setStockBatchId(movement.getStockBatchId());
            unit.setManufacturerSerialNumber(serial.trim());
            unit.setNormalizedSerialNumber(serial.trim().toUpperCase(Locale.ROOT));
            unit.setManualStockMovementId(movement.getId());
            unit.setReceivedAt(LocalDateTime.now());
            unit = units.save(unit);

            MaterialUnitEvent event = new MaterialUnitEvent();
            event.setCode("MUE-" + UUID.randomUUID().toString().substring(0, 8));
            event.setName("Manual warehouse receipt");
            event.setStatus("POSTED");
            event.setMaterialUnitId(unit.getId());
            event.setEventType(MaterialUnitEventType.RECEIVED);
            event.setSourceType("MANUAL_WAREHOUSE");
            event.setSourceId(movement.getId());
            event.setWarehouseId(warehouseId);
            event.setStockBatchId(movement.getStockBatchId());
            var actor = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            event.setPerformedBy(actor == null ? "system" : actor.getName());
            event.setOccurredAt(LocalDateTime.now());
            events.save(event);
        }
    }
}
