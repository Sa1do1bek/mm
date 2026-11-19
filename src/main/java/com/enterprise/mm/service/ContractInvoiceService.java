package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.entity.Contract;
import com.enterprise.mm.entity.ContractInvoice;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.enums.CommercialInstrumentType;
import com.enterprise.mm.enums.ContractInvoiceStatus;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.ContractInvoiceMapper;
import com.enterprise.mm.payload.ContractInvoiceCreateRequest;
import com.enterprise.mm.payload.ContractInvoiceResponse;
import com.enterprise.mm.payload.ContractInvoiceSearchRequest;
import com.enterprise.mm.payload.ContractInvoiceUpdateRequest;
import com.enterprise.mm.payload.ProcurementWorkspaceDtos.DraftInvoiceFromPurchaseOrderRequest;
import com.enterprise.mm.repository.ContractInvoiceRepository;
import com.enterprise.mm.repository.ContractRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
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
@com.enterprise.mm.security.pilot.PilotCapabilityBoundary(value = com.enterprise.mm.security.pilot.PilotCapability.MM_FINANCE_EXECUTION, reads = {"search", "get"})
public class ContractInvoiceService {
    private final ContractInvoiceRepository repository;
    private final ContractInvoiceMapper mapper;
    private final String entityName = "ContractInvoice";
    private final PurchaseOrderActionPolicy purchaseOrderActionPolicy;
    private final AuditService auditService;
    private final ContractRepository contractRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final ProcurementInvoiceEvidenceService evidenceService;
    private final ProcurementPayablesNotificationService payablesNotificationService;
    private final PurchaseOrderCommercialReadinessService purchaseOrderCommercialReadinessService;
    private final CommercialInstrumentTypeResolver commercialInstrumentTypeResolver;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final BudgetItemService budgetItemService;

    protected void beforeCreate(ContractInvoice entity, ContractInvoiceCreateRequest dto) {
        // Lifecycle is command-owned. A create payload must never be able to
        // bypass evidence validation and the dedicated issue command.
        entity.setStatus(ContractInvoiceStatus.DRAFT.name());
        entity.setCommercialInstrumentType(resolveCommercialInstrumentType(entity));
        if (isStandalonePo(entity)) {
            validateAndApplyStandalonePurchaseOrder(entity);
            entity.setContract(null);
        } else {
            Contract contract = validateContract(entity);
            validateAndApplyMatch(entity, contract);
            validateAndApplyPurchaseOrder(entity, contract, false);
            entity.setContract(contract);
            entity.setSupplier(contract.getSupplier());
        }
        if (isBlank(entity.getCode()) && !isBlank(entity.getInvoiceNumber())) {
            entity.setCode(entity.getInvoiceNumber());
        }
        if (isBlank(entity.getName()) && !isBlank(entity.getInvoiceNumber())) {
            entity.setName(entity.getInvoiceNumber());
        }
        if (entity.getAmount() == null) {
            entity.setAmount(BigDecimal.ZERO);
        }
        if (entity.getPaidAmount() == null) {
            entity.setPaidAmount(BigDecimal.ZERO);
        }
        if (entity.getCompanyId() == null) {
            entity.setCompanyId(CompanyContext.currentCompany());
        }
        rejectDuplicateDraftForPurchaseOrder(entity);
        if (entity.getCode() == null || entity.getCode().isBlank()) {
            entity.setCode(entityName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_") + "-" + UUID.randomUUID().toString().substring(0, 8));
        }
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
        if (isIssued(entity)) {
            issueValidation(entity);
        }
    }

    protected void beforeUpdate(ContractInvoice entity, ContractInvoiceUpdateRequest dto) {
        entity.setCommercialInstrumentType(resolveCommercialInstrumentType(entity));
        if (isStandalonePo(entity)) {
            validateAndApplyStandalonePurchaseOrder(entity);
            entity.setContract(null);
        } else {
            Contract contract = validateContract(entity);
            validateAndApplyMatch(entity, contract);
            validateAndApplyPurchaseOrder(entity, contract, isIssued(entity));
            entity.setContract(contract);
            entity.setSupplier(contract.getSupplier());
        }
        if (entity.getAmount() == null) {
            entity.setAmount(BigDecimal.ZERO);
        }
        if (entity.getPaidAmount() == null) {
            entity.setPaidAmount(BigDecimal.ZERO);
        }
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }

    @Transactional
    public ContractInvoiceResponse issue(UUID id) {
        ContractInvoice entity = findEntity(id);
        if (ContractInvoiceStatus.PAID.name().equalsIgnoreCase(entity.getStatus())
            || ContractInvoiceStatus.CANCELLED.name().equalsIgnoreCase(entity.getStatus())) {
            throw new BusinessException("Invoice cannot be issued from current status");
        }
        issueValidation(entity);
        String oldSnapshot = auditService.snapshot(entity);
        entity.setStatus(ContractInvoiceStatus.ISSUED.name());
        ContractInvoice saved = repository.save(entity);
        consumeGoodsBudgetOnIssue(saved);
        recordEvidenceSnapshot(saved);
        auditService.log(entityName, saved.getId(), "ISSUE", saved.getCode(), oldSnapshot, saved);
        payablesNotificationService.notifyPaymentRequired(saved);
        return mapper.toResponse(saved);
    }

    private void issueValidation(ContractInvoice entity) {
        if (entity.getPurchaseOrderId() == null) {
            throw new BusinessException("purchaseOrderId is required before invoice issuance");
        }
        PurchaseOrder purchaseOrder = loadPurchaseOrder(entity.getPurchaseOrderId());
        if (isStandalonePo(entity)) {
            purchaseOrderCommercialReadinessService.requireStandalonePoInvoiceable(
                purchaseOrder,
                entity.getSupplierId(),
                entity.getCurrencyCode()
            );
            evidenceService.validateInvoiceAmount(purchaseOrder, entity.getAmount(), entity.getId());
            return;
        }
        if (!Objects.equals(purchaseOrder.getContractId(), entity.getContractId())) {
            throw new BusinessException("Purchase order must belong to invoice contract");
        }
        if (!Objects.equals(purchaseOrder.getSupplierId(), entity.getSupplierId())) {
            throw new BusinessException("Purchase order supplier must match invoice supplier");
        }
        evidenceService.validateInvoiceAmount(purchaseOrder, entity.getAmount(), entity.getId());
    }

    private Contract validateContract(ContractInvoice entity) {
        if (entity.getContract() == null && entity.getContractId() == null) {
            throw new BusinessException("contractId is required");
        }
        UUID contractId = entity.getContract() != null ? entity.getContract().getId() : entity.getContractId();
        Contract contract = contractRepository.findByIdAndIsDeletedFalse(contractId)
            .filter(item -> !item.isDeleted() && TenantContext.currentTenant().equals(item.getTenantId()))
            .filter(item -> com.enterprise.mm.security.AccessScope.canAccessCompany(item.getCompanyId()))
            .orElseThrow(() -> new BusinessException("Contract not found"));
        if ("CLOSED".equals(contract.getStatus())) {
            throw new BusinessException("Cannot attach invoice to closed contract");
        }
        return contract;
    }

    private void validateAndApplyStandalonePurchaseOrder(ContractInvoice entity) {
        if (entity.getContractId() != null || entity.getContract() != null) {
            throw new BusinessException("Standalone PO invoice cannot reference a contract");
        }
        UUID purchaseOrderId = entity.getPurchaseOrderId();
        if (purchaseOrderId == null) {
            throw new BusinessException("purchaseOrderId is required for standalone PO invoice");
        }
        PurchaseOrder purchaseOrder = loadPurchaseOrder(purchaseOrderId);
        purchaseOrderCommercialReadinessService.requireStandalonePoInvoiceable(
            purchaseOrder,
            entity.getSupplierId(),
            entity.getCurrencyCode()
        );
        entity.setContractId(null);
        entity.setContract(null);
        entity.setSupplierId(purchaseOrder.getSupplierId());
        entity.setCurrencyCode(purchaseOrderCommercialReadinessService.normalizedCurrency(purchaseOrder.getCurrencyCode()));
        entity.setPurchaseOrderId(purchaseOrder.getId());
        entity.setPurchaseOrder(purchaseOrder);
    }

    private void validateAndApplyMatch(ContractInvoice entity, Contract contract) {
        if (entity.getSupplierId() != null && !entity.getSupplierId().equals(contract.getSupplierId())) {
            throw new BusinessException("Invoice supplier must match contract supplier");
        }
        String contractCurrency = isBlank(contract.getCurrencyCode()) ? "UZS" : contract.getCurrencyCode().trim().toUpperCase(Locale.ROOT);
        if (!isBlank(entity.getCurrencyCode()) && !entity.getCurrencyCode().trim().equalsIgnoreCase(contractCurrency)) {
            throw new BusinessException("Invoice currency must match contract currency");
        }
        entity.setContractId(contract.getId());
        entity.setSupplierId(contract.getSupplierId());
        entity.setCurrencyCode(contractCurrency);
    }

    private void validateAndApplyPurchaseOrder(ContractInvoice entity, Contract contract, boolean locked) {
        UUID purchaseOrderId = entity.getPurchaseOrderId();
        if (purchaseOrderId == null) {
            if (isIssued(entity)) {
                throw new BusinessException("purchaseOrderId is required before invoice issuance");
            }
            entity.setPurchaseOrder(null);
            return;
        }
        PurchaseOrder purchaseOrder = loadPurchaseOrder(purchaseOrderId);
        if (!Objects.equals(purchaseOrder.getContractId(), contract.getId())) {
            throw new BusinessException("Purchase order must belong to invoice contract");
        }
        if (!Objects.equals(purchaseOrder.getSupplierId(), entity.getSupplierId())) {
            throw new BusinessException("Purchase order supplier must match invoice supplier");
        }
        String purchaseOrderCurrency = normalizedCurrency(purchaseOrder.getCurrencyCode());
        if (!purchaseOrderCurrency.equalsIgnoreCase(normalizedCurrency(entity.getCurrencyCode()))) {
            throw new BusinessException("Purchase order currency must match invoice currency");
        }
        if (locked && entity.getPurchaseOrderId() != null && !Objects.equals(entity.getPurchaseOrderId(), purchaseOrderId)) {
            throw new BusinessException("Contract invoice cannot be moved to another purchase order");
        }
        entity.setPurchaseOrderId(purchaseOrder.getId());
        entity.setPurchaseOrder(purchaseOrder);
    }

    private PurchaseOrder loadPurchaseOrder(UUID purchaseOrderId) {
        return purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)
            .filter(item -> TenantContext.currentTenant().equals(item.getTenantId()))
            .filter(item -> com.enterprise.mm.security.AccessScope.canAccessCompany(item.getCompanyId()))
            .orElseThrow(() -> new BusinessException("Purchase order not found"));
    }

    private boolean isIssued(ContractInvoice entity) {
        String status = normalizedStatus(entity.getStatus(), "");
        return ContractInvoiceStatus.ISSUED.name().equals(status)
            || ContractInvoiceStatus.PARTIALLY_PAID.name().equals(status)
            || ContractInvoiceStatus.PAID.name().equals(status);
    }

    private boolean isStandalonePo(ContractInvoice entity) {
        return CommercialInstrumentType.STANDALONE_PO.equals(resolveCommercialInstrumentType(entity));
    }

    private CommercialInstrumentType resolveCommercialInstrumentType(ContractInvoice entity) {
        UUID contractId = entity.getContractId() != null
            ? entity.getContractId()
            : (entity.getContract() == null ? null : entity.getContract().getId());
        return commercialInstrumentTypeResolver.resolve(
            entity.getCommercialInstrumentType(),
            contractId,
            entity.getPurchaseOrderId()
        );
    }

    private String normalizedStatus(String status, String defaultValue) {
        return isBlank(status) ? defaultValue : status.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizedCurrency(String value) {
        return isBlank(value) ? "UZS" : value.trim().toUpperCase(Locale.ROOT);
    }

    private void consumeGoodsBudgetOnIssue(ContractInvoice invoice) {
        if (invoice.getPurchaseOrderId() == null || invoice.getId() == null || nvl(invoice.getAmount()).signum() <= 0) {
            return;
        }
        PurchaseOrder purchaseOrder = loadPurchaseOrder(invoice.getPurchaseOrderId());
        if (purchaseOrder.getRequestType() != RequestType.GOODS) {
            return;
        }
        List<PurchaseOrderItem> budgetedLines = purchaseOrderItemRepository
            .findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(TenantContext.currentTenant(), purchaseOrder.getId())
            .stream()
            .filter(item -> item.getBudgetItemId() != null)
            .filter(item -> item.getLineType() == null || item.getLineType() == ProcurementLineType.MATERIAL)
            .filter(item -> lineAmount(item).signum() > 0)
            .toList();
        if (budgetedLines.isEmpty()) {
            return;
        }
        BigDecimal totalBudgetedAmount = budgetedLines.stream()
            .map(this::lineAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalBudgetedAmount.signum() <= 0) {
            return;
        }
        BigDecimal invoiceAmount = nvl(invoice.getAmount());
        BigDecimal remaining = invoiceAmount;
        for (int index = 0; index < budgetedLines.size(); index++) {
            PurchaseOrderItem item = budgetedLines.get(index);
            BigDecimal consumedAmount = index == budgetedLines.size() - 1
                ? remaining
                : invoiceAmount.multiply(lineAmount(item)).divide(totalBudgetedAmount, 4, RoundingMode.HALF_UP);
            consumedAmount = consumedAmount.min(remaining);
            remaining = remaining.subtract(consumedAmount);
            if (consumedAmount.signum() <= 0) {
                continue;
            }
            budgetItemService.consumeIdempotent(
                item.getBudgetItemId(),
                consumedAmount,
                "ContractInvoice " + invoiceLabel(invoice),
                "ContractInvoice",
                invoice.getId(),
                item.getId(),
                "ContractInvoice:" + invoice.getId() + ":PurchaseOrderItem:" + item.getId(),
                "CONSUME_ISSUED"
            );
        }
    }

    private void reverseGoodsBudgetOnCancellation(ContractInvoice invoice) {
        if (invoice.getPurchaseOrderId() == null || invoice.getId() == null || nvl(invoice.getAmount()).signum() <= 0) {
            return;
        }
        PurchaseOrder purchaseOrder = loadPurchaseOrder(invoice.getPurchaseOrderId());
        if (purchaseOrder.getRequestType() != RequestType.GOODS) {
            return;
        }
        List<PurchaseOrderItem> budgetedLines = purchaseOrderItemRepository
            .findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(TenantContext.currentTenant(), purchaseOrder.getId())
            .stream()
            .filter(item -> item.getBudgetItemId() != null)
            .filter(item -> item.getLineType() == null || item.getLineType() == ProcurementLineType.MATERIAL)
            .filter(item -> lineAmount(item).signum() > 0)
            .toList();
        if (budgetedLines.isEmpty()) {
            return;
        }
        BigDecimal totalBudgetedAmount = budgetedLines.stream()
            .map(this::lineAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalBudgetedAmount.signum() <= 0) {
            return;
        }
        BigDecimal invoiceAmount = nvl(invoice.getAmount());
        BigDecimal remaining = invoiceAmount;
        for (int index = 0; index < budgetedLines.size(); index++) {
            PurchaseOrderItem item = budgetedLines.get(index);
            BigDecimal reversedAmount = index == budgetedLines.size() - 1
                ? remaining
                : invoiceAmount.multiply(lineAmount(item)).divide(totalBudgetedAmount, 4, RoundingMode.HALF_UP);
            reversedAmount = reversedAmount.min(remaining);
            remaining = remaining.subtract(reversedAmount);
            if (reversedAmount.signum() <= 0) {
                continue;
            }
            budgetItemService.reverseConsumptionIdempotent(
                item.getBudgetItemId(),
                reversedAmount,
                "ContractInvoice " + invoiceLabel(invoice),
                "ContractInvoice",
                invoice.getId(),
                item.getId(),
                "ContractInvoice:" + invoice.getId() + ":PurchaseOrderItem:" + item.getId(),
                "REVERSE_CANCELLED"
            );
        }
    }

    private void validateIssuedInvoiceMutation(
        String previousStatus,
        String nextStatus,
        BigDecimal previousAmount,
        BigDecimal nextAmount,
        UUID previousPurchaseOrderId,
        UUID nextPurchaseOrderId,
        UUID previousSupplierId,
        UUID nextSupplierId,
        String previousCurrencyCode,
        String nextCurrencyCode
    ) {
        if (!isIssuedStatus(previousStatus)) {
            return;
        }
        String normalizedPrevious = normalizedStatus(previousStatus, "");
        String normalizedNext = normalizedStatus(nextStatus, "");
        if (ContractInvoiceStatus.ISSUED.name().equals(normalizedPrevious)
            && !ContractInvoiceStatus.ISSUED.name().equals(normalizedNext)
            && !ContractInvoiceStatus.CANCELLED.name().equals(normalizedNext)) {
            throw new BusinessException("Issued invoice payment status must be updated through payment flow");
        }
        if (ContractInvoiceStatus.PARTIALLY_PAID.name().equals(normalizedPrevious)
            && !ContractInvoiceStatus.PARTIALLY_PAID.name().equals(normalizedNext)) {
            throw new BusinessException("Paid invoice cannot be cancelled");
        }
        if (nvl(previousAmount).compareTo(nvl(nextAmount)) != 0
            || !Objects.equals(previousPurchaseOrderId, nextPurchaseOrderId)
            || !Objects.equals(previousSupplierId, nextSupplierId)
            || !normalizedCurrency(previousCurrencyCode).equals(normalizedCurrency(nextCurrencyCode))) {
            throw new BusinessException("Issued invoice financial fields cannot be changed; cancel and create a replacement invoice");
        }
    }

    private boolean isIssuedStatus(String status) {
        String normalized = normalizedStatus(status, "");
        return ContractInvoiceStatus.ISSUED.name().equals(normalized)
            || ContractInvoiceStatus.PARTIALLY_PAID.name().equals(normalized)
            || ContractInvoiceStatus.PAID.name().equals(normalized);
    }

    private BigDecimal lineAmount(PurchaseOrderItem item) {
        return nvl(item.getQuantity()).multiply(nvl(item.getUnitPrice()));
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String invoiceLabel(ContractInvoice invoice) {
        if (!isBlank(invoice.getInvoiceNumber())) {
            return invoice.getInvoiceNumber();
        }
        if (!isBlank(invoice.getCode())) {
            return invoice.getCode();
        }
        return invoice.getId().toString();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @Transactional(readOnly = true)
    public Page<ContractInvoiceResponse> search(ContractInvoiceSearchRequest request, Pageable pageable) {
        SearchCriteria filter = SearchCriteria.of(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        var spec = ProcurementSearchSpecifications.build(
            ContractInvoice.class,
            TenantContext.currentTenant(),
            scoped ? CompanyContext.currentCompany() : null,
            filter,
            request
        );
        return repository.findAll(spec, pageable).map(mapper::toResponse);
    }

    @Transactional(readOnly = true)
    public ContractInvoiceResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }

    @Transactional
    public ContractInvoiceResponse create(ContractInvoiceCreateRequest dto) {
        ContractInvoice entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        ContractInvoice saved = repository.save(entity);
        if (isIssued(saved)) {
            consumeGoodsBudgetOnIssue(saved);
            recordEvidenceSnapshot(saved);
        }
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }

    @Transactional
    public ContractInvoiceResponse createDraftFromPurchaseOrder(DraftInvoiceFromPurchaseOrderRequest request) {
        if (request == null || request.purchaseOrderId() == null) {
            throw new BusinessException("purchaseOrderId is required");
        }
        PurchaseOrder purchaseOrder = loadPurchaseOrder(request.purchaseOrderId());
        var existingDraft = repository.findFirstByTenantIdAndCompanyIdAndPurchaseOrderIdAndStatusAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(),
            purchaseOrder.getCompanyId(),
            purchaseOrder.getId(),
            ContractInvoiceStatus.DRAFT.name()
        );
        if (existingDraft != null && existingDraft.isPresent()) {
            return mapper.toResponse(existingDraft.get());
        }
        purchaseOrderActionPolicy.requireActionAllowed(
            purchaseOrder, com.enterprise.mm.enums.PurchaseOrderWorkspaceActionKey.CREATE_INVOICE);
        ProcurementInvoiceEvidenceService.EvidenceSummary summary = evidenceService.summarize(purchaseOrder);
        BigDecimal amount = request.amount() == null ? summary.remainingInvoiceableAmount() : request.amount();
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException("Remaining invoiceable amount must be greater than zero");
        }
        if (amount.compareTo(summary.remainingInvoiceableAmount()) > 0) {
            throw new BusinessException("Invoice amount exceeds remaining invoiceable amount for purchase order");
        }
        ContractInvoiceCreateRequest create = new ContractInvoiceCreateRequest();
        create.setContractId(purchaseOrder.getContractId());
        create.setCommercialInstrumentType(commercialInstrumentTypeResolver.resolve(
            null,
            purchaseOrder.getContractId(),
            purchaseOrder.getId()
        ));
        create.setPurchaseOrderId(purchaseOrder.getId());
        create.setSupplierId(purchaseOrder.getSupplierId());
        create.setCurrencyCode(normalizedCurrency(purchaseOrder.getCurrencyCode()));
        create.setAmount(amount);
        create.setInvoiceNumber(isBlank(request.invoiceNumber())
            ? "INV-" + purchaseOrder.getCode()
            : request.invoiceNumber().trim());
        create.setInvoiceDate(request.invoiceDate() == null ? java.time.LocalDate.now() : request.invoiceDate());
        create.setDueDate(request.dueDate());
        return create(create);
    }

    @Transactional
    public ContractInvoiceResponse update(UUID id, ContractInvoiceUpdateRequest dto) {
        ContractInvoice entity = findEntity(id);
        if (ContractInvoiceStatus.PAID.name().equalsIgnoreCase(entity.getStatus())
            || ContractInvoiceStatus.CANCELLED.name().equalsIgnoreCase(entity.getStatus())) {
            throw new BusinessException("Paid or cancelled invoice cannot be updated");
        }
        String oldSnapshot = auditService.snapshot(entity);
        UUID contractId = entity.getContractId();
        UUID purchaseOrderId = entity.getPurchaseOrderId();
        CommercialInstrumentType commercialInstrumentType = entity.getCommercialInstrumentType();
        String previousStatus = entity.getStatus();
        BigDecimal previousAmount = entity.getAmount();
        UUID previousSupplierId = entity.getSupplierId();
        String previousCurrencyCode = entity.getCurrencyCode();
        mapper.updateEntity(entity, dto);
        if (!Objects.equals(contractId, entity.getContractId())) {
            throw new BusinessException("Contract invoice cannot be moved to another contract");
        }
        if (commercialInstrumentType != null
            && entity.getCommercialInstrumentType() != null
            && !Objects.equals(commercialInstrumentType, entity.getCommercialInstrumentType())) {
            throw new BusinessException("Contract invoice commercial instrument cannot be changed");
        }
        entity.setContractId(contractId);
        if (commercialInstrumentType != null) {
            entity.setCommercialInstrumentType(commercialInstrumentType);
        }
        if (purchaseOrderId != null) {
            entity.setPurchaseOrderId(purchaseOrderId);
        }
        beforeUpdate(entity, dto);
        validateIssuedInvoiceMutation(
            previousStatus,
            entity.getStatus(),
            previousAmount,
            entity.getAmount(),
            purchaseOrderId,
            entity.getPurchaseOrderId(),
            previousSupplierId,
            entity.getSupplierId(),
            previousCurrencyCode,
            entity.getCurrencyCode()
        );
        if (!Objects.equals(previousStatus, entity.getStatus()) && isIssued(entity)) {
            issueValidation(entity);
        }
        ContractInvoice saved = repository.save(entity);
        if (!Objects.equals(previousStatus, saved.getStatus()) && isIssued(saved)) {
            consumeGoodsBudgetOnIssue(saved);
            recordEvidenceSnapshot(saved);
        }
        if (isIssuedStatus(previousStatus) && ContractInvoiceStatus.CANCELLED.name().equals(normalizedStatus(saved.getStatus(), ""))) {
            reverseGoodsBudgetOnCancellation(saved);
        }
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }

    @Transactional
    public void softDelete(UUID id) {
        ContractInvoice entity = findEntity(id);
        if (ContractInvoiceStatus.PAID.name().equalsIgnoreCase(entity.getStatus())
            || ContractInvoiceStatus.PARTIALLY_PAID.name().equalsIgnoreCase(entity.getStatus())) {
            throw new BusinessException("Paid invoice cannot be deleted");
        }
        String oldSnapshot = auditService.snapshot(entity);
        if (ContractInvoiceStatus.ISSUED.name().equals(normalizedStatus(entity.getStatus(), ""))) {
            reverseGoodsBudgetOnCancellation(entity);
        }
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }

    protected ContractInvoice findEntity(UUID id) {
        ContractInvoice entity = repository.findByIdAndIsDeletedFalse(id)
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
        return entity;
    }

    @Transactional
    public ContractInvoiceResponse restore(UUID id) {
        ContractInvoice entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        ContractInvoice saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }

    protected boolean companyScoped() {
        return true;
    }

    private void recordEvidenceSnapshot(ContractInvoice entity) {
        if (entity.getPurchaseOrderId() == null) {
            return;
        }
        evidenceService.recordInvoiceEvidenceSnapshot(entity, loadPurchaseOrder(entity.getPurchaseOrderId()));
    }

    private void rejectDuplicateDraftForPurchaseOrder(ContractInvoice entity) {
        if (entity.getPurchaseOrderId() == null
            || !ContractInvoiceStatus.DRAFT.name().equals(normalizedStatus(entity.getStatus(), ContractInvoiceStatus.DRAFT.name()))) {
            return;
        }
        UUID companyId = entity.getCompanyId() == null ? CompanyContext.currentCompany() : entity.getCompanyId();
        var existingDraft = repository.findFirstByTenantIdAndCompanyIdAndPurchaseOrderIdAndStatusAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(),
                companyId,
                entity.getPurchaseOrderId(),
                ContractInvoiceStatus.DRAFT.name()
            );
        if (existingDraft != null && existingDraft.isPresent()) {
            throw new BusinessException("Active draft invoice already exists for purchase order");
        }
    }
}
