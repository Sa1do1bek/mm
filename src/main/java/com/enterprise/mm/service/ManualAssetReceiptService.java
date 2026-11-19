package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.integration.faktura.FakturaReceiptEvidenceService;
import com.enterprise.mm.payload.*;
import com.enterprise.mm.repository.*;
import com.enterprise.mm.security.pilot.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Receives physical equipment into the existing quality/approval/posting workflow. */
@Service
@RequiredArgsConstructor
@PilotCapabilityBoundary(PilotCapability.FIXED_ASSET_EXECUTION)
public class ManualAssetReceiptService {
    private final RoleDataScopeService scope;
    private final WarehouseRepository warehouses;
    private final MaterialCatalogGuard catalog;
    private final AssetManagementSettingsService settings;
    private final AssetAcceptanceRepository acceptances;
    private final AssetAcceptanceLineRepository lines;
    private final AssetAcceptanceUnitRepository units;
    private final AssetAcceptanceService workflow;
    private final IdempotencyService idempotency;
    private final ExactOpeningImportGuard openingGuard;
    private final ContractRepository contracts;
    private final ContractSpecificationRepository contractSpecifications;
    private final FakturaReceiptEvidenceService fakturaEvidence;

    @org.springframework.beans.factory.annotation.Value("${app.features.fixed-assets.enabled:${FIXED_ASSETS_ENABLED:false}}")
    private boolean fixedAssetsEnabled;

    @Transactional(readOnly = true)
    public PageResponse<AssetAcceptanceResponse> pending(UUID warehouseId, org.springframework.data.domain.Pageable pageable) {
        scope.assertWarehouseAccess(warehouseId);
        warehouses.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany(), warehouseId)
            .orElseThrow(() -> new NotFoundException("Warehouse not found"));
        return PageResponse.from(acceptances.findByTenantIdAndCompanyIdAndWarehouseIdAndPostingStatusAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), warehouseId,
            com.enterprise.mm.enums.AssetAcceptancePostingStatus.NOT_POSTED, pageable).map(receipt -> workflow.get(receipt.getId())));
    }

    @Transactional
    public AssetAcceptanceResponse create(ManualWarehouseOperationRequest request, String key) {
        if (!fixedAssetsEnabled) throw new BusinessException("Fixed asset operations are disabled");
        if (request.contractId() == null) throw new BusinessException("Contract is required for equipment receipt");
        if (request.contractSpecificationId() == null) throw new BusinessException("Contract specification is required for equipment receipt");
        scope.assertWarehouseAccess(request.warehouseId());
        warehouses.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), request.warehouseId())
            .filter(w -> CompanyContext.currentCompany().equals(w.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("Warehouse not found"));
        openingGuard.assertOperationsAllowed(CompanyContext.currentCompany());
        return idempotency.executeRequired("MANUAL_ASSET_RECEIPT", request.warehouseId(), key, request,
            AssetAcceptanceResponse.class, () -> createNow(request));
    }

    private AssetAcceptanceResponse createNow(ManualWarehouseOperationRequest request) {
        Material material = catalog.requireActiveMaterial(request.materialId(), "materialId");
        if (material.getMaterialType() != MaterialType.FIXED_ASSET)
            throw new BusinessException("Select a fixed-asset material");
        Contract contract = contracts.findByIdAndIsDeletedFalse(request.contractId())
            .filter(c -> TenantContext.currentTenant().equals(c.getTenantId())
                && CompanyContext.currentCompany().equals(c.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("Contract not found in your company"));
        LocalDate today = LocalDate.now();
        if (!"ACTIVE".equals(contract.getStatus()) ||
            (contract.getStartDate() != null && contract.getStartDate().isAfter(today)) ||
            (contract.getEndDate() != null && contract.getEndDate().isBefore(today)))
            throw new BusinessException("Contract must be active on the operation date");
        ContractSpecification specification = contractSpecifications.findByIdAndIsDeletedFalse(request.contractSpecificationId())
            .filter(s -> contract.getId().equals(s.getContractId())
                && TenantContext.currentTenant().equals(s.getTenantId())
                && CompanyContext.currentCompany().equals(s.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("Contract specification does not belong to this contract"));
        if (!"ACTIVE".equals(specification.getStatus()))
            throw new BusinessException("Contract specification must be ACTIVE for equipment receipt");
        if (specification.getMaterialGroupId() != null || specification.getLineType() != ProcurementLineType.MATERIAL
            || !material.getId().equals(specification.getMaterialId()))
            throw new BusinessException("Contract specification material does not match the selected equipment");
        if (specification.getQuantityLimit() != null && specification.getQuantityLimit().signum() > 0
            && request.quantity().compareTo(specification.getQuantityLimit()) > 0)
            throw new BusinessException("Quantity exceeds contracted limit");
        AssetManagementSettings policy = settings.requireConfigured();
        int count;
        try { count = request.quantity().intValueExact(); }
        catch (ArithmeticException exception) { throw new BusinessException("Equipment quantity must be whole"); }
        if (count < 1 || count > 100) throw new BusinessException("Receive between 1 and 100 equipment units per document");
        if (request.unitCost() == null || request.unitCost().signum() < 0)
            throw new BusinessException("Equipment unit value is required");
        List<String> serials = request.serialNumbers() == null ? List.of() : request.serialNumbers();
        if ((!serials.isEmpty() || policy.isRequireSerialNumber()) && serials.size() != count)
            throw new BusinessException("Enter one serial number per equipment unit");
        if (new HashSet<>(serials.stream().map(s -> s.trim().toUpperCase(Locale.ROOT)).toList()).size() != serials.size())
            throw new BusinessException("Equipment serial numbers must be unique within the receipt");
        AssetAcceptance receipt = new AssetAcceptance();
        receipt.setCode(code("AA"));
        receipt.setName("Manual equipment receipt В· " + material.getName());
        receipt.setStatus("DRAFT");
        receipt.setContractId(contract.getId());
        receipt.setWarehouseId(request.warehouseId());
        receipt.setReceiptDate(request.movementDate() == null ? LocalDate.now() : request.movementDate());
        receipt.setReceivedAt(LocalDateTime.now());
        receipt.setCurrencyCode(request.currencyCode() == null ? "UZS" : request.currencyCode());
        receipt.setExchangeRate(BigDecimal.ONE);
        receipt.setSourceEvidence(request.source());
        receipt.setDeliveryReference(request.reference());
        receipt = acceptances.save(receipt);
        fakturaEvidence.attach(contract.getId(), FakturaReceiptEvidenceService.ReceiptKind.ASSET_ACCEPTANCE,
            receipt.getId(), receipt.getReceiptDate(), request.fakturaPowerOfAttorneyId(),
            request.fakturaInvoiceId(), request.missingFakturaReason());
        AssetAcceptanceLine line = new AssetAcceptanceLine();
        line.setCode(code("AAL"));
        line.setName(material.getName());
        line.setStatus("DRAFT");
        line.setAssetAcceptanceId(receipt.getId());
        line.setContractSpecificationId(specification.getId());
        line.setMaterialId(material.getId());
        line.setMaterialTypeSnapshot(MaterialType.FIXED_ASSET);
        line.setDeliveredQuantity(request.quantity());
        line.setPendingQuantity(request.quantity());
        line.setAcceptedValue(request.unitCost().multiply(request.quantity()));
        line.setAcceptedCurrencyCode(receipt.getCurrencyCode());
        line = lines.save(line);
        for (int index = 0; index < count; index++) {
            AssetAcceptanceUnit unit = new AssetAcceptanceUnit();
            unit.setCode(code("AAU"));
            unit.setName("Equipment unit " + (index + 1));
            unit.setStatus("PENDING");
            unit.setAssetAcceptanceLineId(line.getId());
            unit.setMaterialId(material.getId());
            unit.setMaterialTypeSnapshot(MaterialType.FIXED_ASSET);
            unit.setLineSequence(index + 1);
            unit.setReceivingUnitNumber(code("FA-RCV"));
            unit.setTemporaryTag(unit.getReceivingUnitNumber());
            unit.setCondition("NEW");
            if (!serials.isEmpty()) {
                unit.setManufacturerSerialNumber(serials.get(index).trim());
                unit.setSerialNumber(serials.get(index).trim());
            }
            units.save(unit);
        }
        return workflow.get(receipt.getId());
    }

    private String code(String prefix) { return prefix + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT); }
}
