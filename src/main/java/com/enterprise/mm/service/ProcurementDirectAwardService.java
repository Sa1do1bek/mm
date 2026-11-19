package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ProcurementDirectAward;
import com.enterprise.mm.entity.ProcurementProcedure;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.enums.DirectAwardBasisType;
import com.enterprise.mm.enums.ProcurementAwardSourceType;
import com.enterprise.mm.enums.ProcurementExecutionPath;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.ProcurementMethodType;
import com.enterprise.mm.enums.ProcurementProcedureStatus;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.ContractCreateRequest;
import com.enterprise.mm.payload.ProcurementDirectAwardDtos.DirectAwardPurchaseOrderRequest;
import com.enterprise.mm.payload.ProcurementDirectAwardDtos.DirectAwardRequest;
import com.enterprise.mm.payload.ProcurementDirectAwardDtos.DirectAwardResponse;
import com.enterprise.mm.payload.PurchaseOrderCreateRequest;
import com.enterprise.mm.payload.PurchaseOrderItemCreateRequest;
import com.enterprise.mm.payload.PurchaseOrderResponse;
import com.enterprise.mm.service.ProcurementAwardFinalizationOrchestrator.AwardFinalizationCommand;
import com.enterprise.mm.repository.ProcurementDirectAwardRepository;
import com.enterprise.mm.repository.ProcurementProcedureRepository;
import com.enterprise.mm.repository.PurchaseRequestItemRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import com.enterprise.mm.service.approval.ProcurementDirectAwardApprovalAdapter;
import com.enterprise.mm.util.BusinessNameGenerator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProcurementDirectAwardService {
    private final ProcurementDirectAwardRepository repository;
    private final ProcurementProcedureRepository procedureRepository;
    private final PurchaseRequestRepository purchaseRequestRepository;
    private final PurchaseRequestItemRepository purchaseRequestItemRepository;
    private final SupplierRepository supplierRepository;
    private final PurchaseOrderService purchaseOrderService;
    private final ProcurementMethodPolicy methodPolicy;
    private final AuditService auditService;
    private final PurchaseOrderDemandCyclePolicy demandCyclePolicy;
    private final ProcurementAwardFinalizationOrchestrator awardFinalizationOrchestrator;
    private final ContractService contractService;
    private final ApprovalEngineService approvalEngineService;
    private final ProcurementMethodCapabilityService capabilityService;
    private final SupplierAccreditationService supplierAccreditationService;
    private final SupplierEligibilityService supplierEligibilityService;
    private final SupplierAccreditationDemandResolver accreditationDemandResolver;

    @Transactional
    public DirectAwardResponse create(UUID procedureId, DirectAwardRequest request) {
        if (request == null || request.supplierId() == null) {
            throw new BusinessException("supplierId is required for direct award");
        }
        BigDecimal awardAmount = positiveAmount(request.awardAmount(), "awardAmount");
        String awardBasisType = requireAwardBasisType(request);
        String awardBasisExplanation = requireMeaningful(awardBasisExplanation(request, awardBasisType), "awardBasisExplanation");
        String justification = requireMeaningful(request.justification(), "justification");

        ProcurementProcedure procedure = requireDirectAwardProcedure(procedureId);
        ProcurementMethodType method = methodPolicy.require(procedure.getMethod());
        String currencyCode = currency(request.currencyCode(), defaultCurrency(procedure.getPurchaseRequestId()));
        validateMethodGovernance(method, request, awardAmount, awardBasisType);
        requireCapability(procedure, method, awardAmount, currencyCode);
        demandCyclePolicy.requireApprovedCycleForPurchaseRequest(procedure.getPurchaseRequestId());
        if (!ProcurementProcedureStatus.DIRECT_AWARD_DRAFT.name().equals(procedure.getStatus())) {
            throw new BusinessException("Procurement procedure must be DIRECT_AWARD_DRAFT before direct award creation");
        }
        ProcurementDirectAward existing = repository
            .findByTenantIdAndCompanyIdAndProcurementProcedureIdAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), procedure.getId())
            .orElse(null);
        if (existing != null) {
            return response(existing);
        }
        Supplier supplier = requireSupplier(request.supplierId());
        requireSupplierEligible(supplier.getId(), procedure.getPurchaseRequestId());
        ProcurementDirectAward award = new ProcurementDirectAward();
        award.setCode("PDA-" + shortId());
        award.setName(BusinessNameGenerator.workflowDocumentName("DirectAward", procedure.getName()));
        award.setStatus("DRAFT");
        award.setProcurementProcedureId(procedure.getId());
        award.setMethodDecisionId(procedure.getMethodDecisionId());
        award.setPurchaseRequestId(procedure.getPurchaseRequestId());
        award.setSupplierId(supplier.getId());
        award.setMethod(method.name());
        award.setAwardAmount(awardAmount);
        award.setCurrencyCode(currencyCode);
        award.setJustification(justification);
        award.setAwardBasisType(awardBasisType);
        award.setAwardBasis(awardBasisExplanation);
        applyGovernanceFields(award, request);
        ProcurementDirectAward saved = repository.save(award);
        procedure.setDirectAwardId(saved.getId());
        procedureRepository.save(procedure);
        auditService.log("ProcurementDirectAward", saved.getId(), "CREATE", procedure.getProcedureNumber(), null, saved);
        return response(saved);
    }

    @Transactional
    public DirectAwardResponse submitForApproval(UUID awardId) {
        return submit(awardId);
    }

    @Transactional
    public DirectAwardResponse submit(UUID awardId) {
        ProcurementDirectAward award = requireAward(awardId);
        if (award.getProcurementProcedureId() != null && award.getSupplierId() != null) {
            requireDirectAwardProcedure(award.getProcurementProcedureId());
            requireSupplier(award.getSupplierId());
            requireSupplierEligible(award.getSupplierId(), award.getPurchaseRequestId());
        }
        approvalEngineService.start(approvalModuleKey(award), awardId);
        return response(requireAward(awardId));
    }

    @Transactional
    public DirectAwardResponse update(UUID awardId, DirectAwardRequest request) {
        if (request == null || request.supplierId() == null) {
            throw new BusinessException("supplierId is required for direct award");
        }
        ProcurementDirectAward award = requireAwardForUpdate(awardId);
        if ("APPROVED".equals(award.getStatus())) {
            throw new BusinessException("Approved direct award supplier, amount, method and output are immutable");
        }
        ProcurementProcedure procedure = requireDirectAwardProcedure(award.getProcurementProcedureId());
        ProcurementMethodType method = methodPolicy.require(procedure.getMethod());
        if (award.getPurchaseOrderId() != null || ProcurementProcedureStatus.COMPLETED.name().equals(procedure.getStatus())) {
            throw new BusinessException("Approved direct award supplier, amount, method and output are immutable");
        }
        BigDecimal awardAmount = positiveAmount(request.awardAmount(), "awardAmount");
        String awardBasisType = requireAwardBasisType(request);
        String awardBasisExplanation = requireMeaningful(
            awardBasisExplanation(request, awardBasisType), "awardBasisExplanation");
        String justification = requireMeaningful(request.justification(), "justification");
        String currencyCode = currency(request.currencyCode(), defaultCurrency(procedure.getPurchaseRequestId()));
        validateMethodGovernance(method, request, awardAmount, awardBasisType);
        requireCapability(procedure, method, awardAmount, currencyCode);
        Supplier supplier = requireSupplier(request.supplierId());
        requireSupplierEligible(supplier.getId(), procedure.getPurchaseRequestId());
        boolean changed = materialChanged(award, request, method, awardAmount, currencyCode,
            justification, awardBasisType, awardBasisExplanation);
        if (!changed) {
            return response(award);
        }
        if (!List.of("DRAFT", "RETURNED", "REJECTED", "APPROVAL_PENDING").contains(award.getStatus())) {
            throw new BusinessException("Direct award cannot be edited from status " + award.getStatus());
        }
        if ("APPROVAL_PENDING".equals(award.getStatus()) || "REJECTED".equals(award.getStatus())) {
            approvalEngineService.resetForResubmission(
                approvalModuleKey(award), awardId, "Direct award material terms changed");
        }
        award.setStatus("DRAFT");
        award.setSupplierId(supplier.getId());
        award.setAwardAmount(awardAmount);
        award.setCurrencyCode(currencyCode);
        award.setJustification(justification);
        award.setAwardBasisType(awardBasisType);
        award.setAwardBasis(awardBasisExplanation);
        applyGovernanceFields(award, request);
        clearApprovalSnapshot(award);
        ProcurementDirectAward saved = repository.save(award);
        auditService.log("ProcurementDirectAward", awardId, "UPDATE", "Material terms changed", null, saved);
        return response(saved);
    }

    @Transactional
    public DirectAwardResponse approve(UUID awardId) {
        throw new BusinessException("Direct award approval must be completed through approval task endpoints");
    }

    @Transactional
    public PurchaseOrderResponse createPurchaseOrder(UUID awardId, DirectAwardPurchaseOrderRequest request) {
        ProcurementDirectAward award = requireAwardForUpdate(awardId);
        if (!"APPROVED".equals(award.getStatus())) {
            throw new BusinessException("Direct award must be APPROVED before purchase order creation");
        }
        requireApprovedSnapshot(award);
        if (award.getPurchaseOrderId() != null) {
            ProcurementProcedure procedure = requireDirectAwardProcedure(award.getProcurementProcedureId());
            finalizePurchaseOrderAward(procedure, award, award.getPurchaseOrderId());
            return purchaseOrderService.get(award.getPurchaseOrderId());
        }
        ProcurementProcedure procedure = requireDirectAwardProcedure(award.getProcurementProcedureId());
        PurchaseRequest purchaseRequest = requirePurchaseRequest(award.getPurchaseRequestId());
        List<PurchaseRequestItem> requestItems = purchaseRequestItems(purchaseRequest.getId());
        if (requestItems.isEmpty()) {
            throw new BusinessException("Purchase request has no lines");
        }
        requireSupplier(award.getSupplierId());
        requireSupplierEligible(award.getSupplierId(), purchaseRequest, requestItems);
        PurchaseOrderCreateRequest po = new PurchaseOrderCreateRequest();
        po.setCode("PO-" + award.getCode());
        po.setName(BusinessNameGenerator.workflowDocumentName("PurchaseOrder", purchaseRequest.getName()));
        po.setDescription(award.getJustification());
        po.setSupplierId(award.getSupplierId());
        po.setPurchaseRequestId(purchaseRequest.getId());
        LocalDate orderDate = request == null || request.orderDate() == null ? LocalDate.now() : request.orderDate();
        po.setOrderDate(orderDate);
        po.setExpectedDeliveryDate(
            request == null || request.expectedDeliveryDate() == null
                ? orderDate.plusDays(14)
                : request.expectedDeliveryDate()
        );
        po.setCurrencyCode(award.getCurrencyCode());

        po.setItems(poLines(purchaseRequest, requestItems, award.getAwardAmount()));
        PurchaseOrderResponse response = purchaseOrderService.create(po);
        award.setPurchaseOrderId(response.getId());
        repository.save(award);
        finalizePurchaseOrderAward(procedure, award, response.getId());
        auditService.log("ProcurementDirectAward", award.getId(), "CREATE_PURCHASE_ORDER", response.getId().toString(), null, null);
        return response;
    }

    private void finalizePurchaseOrderAward(
        ProcurementProcedure procedure,
        ProcurementDirectAward award,
        UUID purchaseOrderId
    ) {
        procedure.setDirectAwardId(award.getId());
        awardFinalizationOrchestrator.finalizeAward(new AwardFinalizationCommand(
            procedure.getId(),
            ProcurementAwardSourceType.DIRECT_AWARD,
            award.getId(),
            award.getSupplierId(),
            purchaseOrderId,
            null,
            award.getAwardAmount(),
            award.getCurrencyCode(),
            award.getCode(),
            ProcurementProcedureStatus.COMPLETED,
            "PROCUREMENT.DIRECT_AWARD_FINALIZED",
            "DIRECT_AWARD_FINALIZE"
        ));
    }

    @Transactional(readOnly = true)
    public DirectAwardResponse get(UUID id) {
        return response(requireAward(id));
    }

    private ProcurementProcedure requireDirectAwardProcedure(UUID id) {
        ProcurementProcedure procedure = procedureRepository.lockScopedById(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), id
        ).orElseThrow(() -> new NotFoundException("ProcurementProcedure not found: " + id));
        ProcurementMethodType method = methodPolicy.require(procedure.getMethod());
        if (method.executionPath() != ProcurementExecutionPath.DIRECT_AWARD) {
            throw new BusinessException("Procurement method does not use direct award execution: " + method.name());
        }
        return procedure;
    }

    private ProcurementDirectAward requireAwardForUpdate(UUID id) {
        return repository.lockScopedById(TenantContext.currentTenant(), CompanyContext.currentCompany(), id)
            .orElseThrow(() -> new NotFoundException("ProcurementDirectAward not found: " + id));
    }

    private ProcurementDirectAward requireAward(UUID id) {
        return repository.findByIdAndIsDeletedFalse(id)
            .filter(this::scoped)
            .orElseThrow(() -> new NotFoundException("ProcurementDirectAward not found: " + id));
    }

    private Supplier requireSupplier(UUID id) {
        Supplier supplier = supplierRepository.findByIdAndIsDeletedFalse(id)
            .filter(this::scoped)
            .orElseThrow(() -> new BusinessException("Supplier not found: " + id));
        if (!List.of("ACTIVE", "LIMITED").contains(supplier.getStatus())) {
            String reason = trim(supplier.getBlockedReason());
            throw new BusinessException(
                "Direct award supplier must be eligible for new business; current status is " + supplier.getStatus()
                    + (reason == null ? "" : ": " + reason)
            );
        }
        return supplier;
    }

    private PurchaseRequest requirePurchaseRequest(UUID id) {
        if (id == null) {
            throw new BusinessException("purchaseRequestId is required for direct award purchase order");
        }
        return purchaseRequestRepository.findByIdAndIsDeletedFalse(id)
            .filter(this::scoped)
            .orElseThrow(() -> new BusinessException("Purchase request not found: " + id));
    }

    private List<PurchaseRequestItem> purchaseRequestItems(UUID purchaseRequestId) {
        return purchaseRequestItemRepository
            .findByTenantIdAndPurchaseRequest_IdAndIsDeletedFalse(TenantContext.currentTenant(), purchaseRequestId)
            .stream()
            .filter(this::scoped)
            .toList();
    }

    private List<PurchaseOrderItemCreateRequest> poLines(
        PurchaseRequest purchaseRequest,
        List<PurchaseRequestItem> items,
        BigDecimal awardAmount
    ) {
        BigDecimal estimatedTotal = items.stream()
            .map(item -> amount(item.getQuantity()).multiply(amount(item.getEstimatedPrice())))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (estimatedTotal.signum() > 0) {
            BigDecimal multiplier = amount(awardAmount).divide(estimatedTotal, 8, RoundingMode.HALF_UP);
            return items.stream()
                .map(item -> poLine(
                    purchaseRequest,
                    item,
                    amount(item.getEstimatedPrice()).multiply(multiplier).setScale(4, RoundingMode.HALF_UP)
                ))
                .toList();
        }
        BigDecimal totalQuantity = items.stream()
            .map(PurchaseRequestItem::getQuantity)
            .map(this::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal fallbackUnitPrice = totalQuantity.signum() > 0
            ? amount(awardAmount).divide(totalQuantity, 4, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;
        return items.stream()
            .map(item -> poLine(purchaseRequest, item, fallbackUnitPrice))
            .toList();
    }

    private PurchaseOrderItemCreateRequest poLine(
        PurchaseRequest purchaseRequest,
        PurchaseRequestItem item,
        BigDecimal unitPrice
    ) {
        PurchaseOrderItemCreateRequest line = new PurchaseOrderItemCreateRequest();
        line.setName(item.getName());
        line.setPurchaseRequestItemId(item.getId());
        String lineType = resolveLineType(purchaseRequest, item);
        line.setLineType(lineType);
        line.setMaterialTypeSnapshot(item.getMaterialTypeSnapshot());
        if (ProcurementLineType.MATERIAL.name().equals(lineType)) {
            if (item.getMaterialId() == null) {
                throw new BusinessException(ProcurementLineValidationService.GOODS_PO_ITEM_REQUIRES_MATERIAL);
            }
            line.setMaterialId(item.getMaterialId());
        }
        line.setServiceRequestId(item.getServiceRequestId());
        line.setServiceDescription(firstText(item.getServiceDescription(), item.getName()));
        line.setServiceCategory(item.getServiceCategory());
        line.setUnit(item.getUnit());
        line.setPeriod(item.getPeriod());
        line.setStage(item.getStage());
        line.setBudgetItemId(item.getBudgetItemId());
        line.setQuantity(amount(item.getQuantity()));
        line.setUnitPrice(amount(unitPrice));
        return line;
    }

    private String resolveLineType(PurchaseRequest purchaseRequest, PurchaseRequestItem item) {
        if (purchaseRequest.getRequestType() != null && purchaseRequest.getRequestType().isServiceStyle()) {
            return com.enterprise.mm.enums.ProcurementLineType.SERVICE.name();
        }

        if (item.getLineType() != null) {
            return item.getLineType().name();
        }
        return com.enterprise.mm.enums.ProcurementLineType.MATERIAL.name();
    }

    private String firstText(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback;
    }

    private UUID provisionWorksContract(ProcurementDirectAward award, PurchaseRequest purchaseRequest) {
        ContractCreateRequest contractRequest = new ContractCreateRequest();
        contractRequest.setCode("DA-WKS-" + award.getCode());
        contractRequest.setName(BusinessNameGenerator.workflowDocumentName("Contract", purchaseRequest.getName()));
        contractRequest.setSupplierId(award.getSupplierId());
        contractRequest.setContractNumber("DA-WKS-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        contractRequest.setStartDate(LocalDate.now());
        contractRequest.setEndDate(LocalDate.now().plusYears(1));
        contractRequest.setLimitAmount(positiveAmount(award.getAwardAmount(), "awardAmount"));
        contractRequest.setUsedAmount(BigDecimal.ZERO);
        contractRequest.setServiceUsedAmount(BigDecimal.ZERO);
        contractRequest.setCurrencyCode(award.getCurrencyCode());
        return contractService.activate(contractService.create(contractRequest).getId()).getId();
    }

    private DirectAwardResponse response(ProcurementDirectAward award) {
        PurchaseOrderDemandCyclePolicy.Readiness readiness =
            demandCyclePolicy.readinessForPurchaseRequest(award.getPurchaseRequestId());
        return new DirectAwardResponse(
            award.getId(),
            award.getProcurementProcedureId(),
            award.getMethodDecisionId(),
            award.getPurchaseRequestId(),
            award.getSupplierId(),
            award.getAwardAmount(),
            award.getCurrencyCode(),
            award.getJustification(),
            award.getAwardBasisType(),
            award.getAwardBasisType(),
            award.getAwardBasis(),
            award.getAwardBasis(),
            award.getMethod(),
            award.getEvidenceReference(),
            award.getMarketPriceAmount(),
            award.getMarketPriceEvidenceReference(),
            award.getNegotiationSummary(),
            award.getConflictOfInterestDeclaration(),
            award.isConflictOfInterestConfirmed(),
            award.getStatus(),
            award.getPurchaseOrderId(),
            award.getApprovedAt(),
            award.getApprovedBy(),
            readiness.ready(),
            readiness.reason()
        );
    }

    private boolean scoped(com.enterprise.mm.common.BusinessEntity entity) {
        return entity != null
            && !entity.isDeleted()
            && TenantContext.currentTenant().equals(entity.getTenantId())
            && CompanyContext.currentCompany().equals(entity.getCompanyId());
    }

    private BigDecimal positiveAmount(BigDecimal value, String field) {
        BigDecimal amount = amount(value);
        if (amount.signum() <= 0) {
            throw new BusinessException(field + " must be greater than zero");
        }
        return amount;
    }

    private BigDecimal amount(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String defaultCurrency(UUID purchaseRequestId) {
        if (purchaseRequestId == null) {
            return "UZS";
        }
        return purchaseRequestRepository.findByIdAndIsDeletedFalse(purchaseRequestId)
            .filter(this::scoped)
            .map(PurchaseRequest::getCurrencyCode)
            .filter(value -> value != null && !value.isBlank())
            .orElse("UZS");
    }

    private String currency(String value, String fallback) {
        String normalized = value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT);
        normalized = normalized == null || normalized.isBlank() ? "UZS" : normalized.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z]{3}")) {
            throw new BusinessException("Currency code must contain three letters");
        }
        return normalized;
    }

    private String requireMeaningful(String value, String field) {
        String trimmed = trim(value);
        if (trimmed == null || trimmed.length() < 10) {
            throw new BusinessException(field + " must contain a meaningful explanation");
        }
        return trimmed;
    }

    private String requireAwardBasisType(DirectAwardRequest request) {
        String candidate = firstNonBlank(request.awardBasisType(), request.basisCode());
        if (candidate == null && isAwardBasisType(request.awardBasis())) {
            candidate = request.awardBasis();
        }
        if (candidate == null) {
            throw new BusinessException("awardBasisType is required");
        }
        return normalizeAwardBasisType(candidate);
    }

    private String awardBasisExplanation(DirectAwardRequest request, String awardBasisType) {
        String explanation = trim(request.awardBasisExplanation());
        if (explanation != null) {
            return explanation;
        }
        String legacyAwardBasis = trim(request.awardBasis());
        if (legacyAwardBasis == null || normalizeToken(legacyAwardBasis).equals(awardBasisType) || isAwardBasisType(legacyAwardBasis)) {
            return null;
        }
        return legacyAwardBasis;
    }

    private boolean isAwardBasisType(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            DirectAwardBasisType.valueOf(normalizeToken(value));
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private String normalizeAwardBasisType(String value) {
        try {
            return DirectAwardBasisType.valueOf(normalizeToken(value)).name();
        } catch (IllegalArgumentException exception) {
            throw new BusinessException("Unsupported awardBasisType: " + value);
        }
    }

    private String normalizeToken(String value) {
        return value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private String firstNonBlank(String first, String second) {
        String trimmed = trim(first);
        return trimmed == null ? trim(second) : trimmed;
    }

    private String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void validateMethodGovernance(
        ProcurementMethodType method,
        DirectAwardRequest request,
        BigDecimal awardAmount,
        String awardBasisType
    ) {
        requirePresent(request.evidenceReference(), "evidenceReference is required for " + method.name());
        requireMeaningful(request.conflictOfInterestDeclaration(), "conflictOfInterestDeclaration");
        if (!Boolean.TRUE.equals(request.conflictOfInterestConfirmed())) {
            throw new BusinessException("conflictOfInterestConfirmed must be true before direct award submission");
        }
        BigDecimal marketPrice = positiveAmount(request.marketPriceAmount(), "marketPriceAmount");
        requirePresent(request.marketPriceEvidenceReference(),
            "marketPriceEvidenceReference is required for " + method.name());
        if (awardAmount.compareTo(marketPrice) > 0) {
            throw new BusinessException("awardAmount cannot exceed the documented marketPriceAmount");
        }
        if (method == ProcurementMethodType.DIRECT_PURCHASE) {
            if (!DirectAwardBasisType.MARKET_RESEARCH.name().equals(awardBasisType)) {
                throw new BusinessException("DIRECT_PURCHASE requires MARKET_RESEARCH awardBasisType");
            }
            return;
        }
        if (method == ProcurementMethodType.SINGLE_SOURCE) {
            if (DirectAwardBasisType.MARKET_RESEARCH.name().equals(awardBasisType)) {
                throw new BusinessException("SINGLE_SOURCE requires EXCLUSIVITY, URGENCY or APPROVED_EXCEPTION awardBasisType");
            }
            requireMeaningful(request.negotiationSummary(), "negotiationSummary");
            return;
        }
        throw new BusinessException("Procurement method does not use governed direct award execution: " + method.name());
    }

    private void requireCapability(
        ProcurementProcedure procedure,
        ProcurementMethodType method,
        BigDecimal awardAmount,
        String currencyCode
    ) {
        String requestType = purchaseRequestRepository.findByIdAndIsDeletedFalse(procedure.getPurchaseRequestId())
            .map(PurchaseRequest::getRequestType)
            .map(Enum::name)
            .orElse(null);
        capabilityService.requireAvailableForCompany(
            method.name(), awardAmount, currencyCode, requestType, null, procedure.getCompanyId());
    }

    private void requireSupplierEligible(UUID supplierId, UUID purchaseRequestId) {
        PurchaseRequest purchaseRequest = purchaseRequestRepository.findByIdAndIsDeletedFalse(purchaseRequestId)
            .filter(this::scoped)
            .orElse(null);
        List<PurchaseRequestItem> items = purchaseRequestItems(purchaseRequestId);
        requireSupplierEligible(supplierId, purchaseRequest, items);
    }

    private void requireSupplierEligible(
        UUID supplierId,
        PurchaseRequest purchaseRequest,
        List<PurchaseRequestItem> items
    ) {
        if (purchaseRequest == null || items == null || items.isEmpty()) {
            return;
        }
        var demand = accreditationDemandResolver.forPurchaseRequestItems(purchaseRequest.getRequestType(), items);
        if (supplierEligibilityService != null) {
            supplierEligibilityService.requireNewBusinessAllowed(supplierId, demand, "DIRECT_AWARD");
            return;
        }
        supplierAccreditationService.assertSupplierEligibleForDemand(
            supplierId,
            demand,
            "direct award supplier selection"
        );
    }

    private void applyGovernanceFields(ProcurementDirectAward award, DirectAwardRequest request) {
        award.setEvidenceReference(trim(request.evidenceReference()));
        award.setMarketPriceAmount(positiveAmount(request.marketPriceAmount(), "marketPriceAmount"));
        award.setMarketPriceEvidenceReference(trim(request.marketPriceEvidenceReference()));
        award.setNegotiationSummary(trim(request.negotiationSummary()));
        award.setConflictOfInterestDeclaration(trim(request.conflictOfInterestDeclaration()));
        award.setConflictOfInterestConfirmed(Boolean.TRUE.equals(request.conflictOfInterestConfirmed()));
    }

    private boolean materialChanged(
        ProcurementDirectAward award,
        DirectAwardRequest request,
        ProcurementMethodType method,
        BigDecimal awardAmount,
        String currencyCode,
        String justification,
        String awardBasisType,
        String awardBasisExplanation
    ) {
        return !Objects.equals(award.getSupplierId(), request.supplierId())
            || amount(award.getAwardAmount()).compareTo(awardAmount) != 0
            || !Objects.equals(award.getMethod(), method.name())
            || !Objects.equals(award.getCurrencyCode(), currencyCode)
            || !Objects.equals(award.getJustification(), justification)
            || !Objects.equals(award.getAwardBasisType(), awardBasisType)
            || !Objects.equals(award.getAwardBasis(), awardBasisExplanation)
            || !Objects.equals(award.getEvidenceReference(), trim(request.evidenceReference()))
            || amount(award.getMarketPriceAmount()).compareTo(amount(request.marketPriceAmount())) != 0
            || !Objects.equals(award.getMarketPriceEvidenceReference(), trim(request.marketPriceEvidenceReference()))
            || !Objects.equals(award.getNegotiationSummary(), trim(request.negotiationSummary()))
            || !Objects.equals(award.getConflictOfInterestDeclaration(), trim(request.conflictOfInterestDeclaration()))
            || award.isConflictOfInterestConfirmed() != Boolean.TRUE.equals(request.conflictOfInterestConfirmed());
    }

    private void clearApprovalSnapshot(ProcurementDirectAward award) {
        award.setApprovedAt(null);
        award.setApprovedBy(null);
        award.setApprovedSupplierId(null);
        award.setApprovedAwardAmount(null);
        award.setApprovedMethod(null);
    }

    private void requireApprovedSnapshot(ProcurementDirectAward award) {
        if (award.getApprovedSupplierId() == null
            && award.getApprovedAwardAmount() == null
            && award.getApprovedMethod() == null) {
            return;
        }
        if (!Objects.equals(award.getSupplierId(), award.getApprovedSupplierId())
            || amount(award.getAwardAmount()).compareTo(amount(award.getApprovedAwardAmount())) != 0
            || !Objects.equals(award.getMethod(), award.getApprovedMethod())) {
            throw new BusinessException("Direct award material terms changed after approval; restart approval");
        }
    }

    private String approvalModuleKey(ProcurementDirectAward award) {
        if (ProcurementMethodType.SINGLE_SOURCE.name().equals(award.getMethod())) {
            return ProcurementDirectAwardApprovalAdapter.SINGLE_SOURCE_MODULE_KEY;
        }
        return ProcurementDirectAwardApprovalAdapter.MODULE_KEY;
    }

    private String requirePresent(String value, String message) {
        String normalized = trim(value);
        if (normalized == null) {
            throw new BusinessException(message);
        }
        return normalized;
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

}
