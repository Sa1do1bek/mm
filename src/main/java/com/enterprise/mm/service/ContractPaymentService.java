package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.entity.Contract;
import com.enterprise.mm.entity.ContractInvoice;
import com.enterprise.mm.entity.ContractPayment;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.enums.CommercialInstrumentType;
import com.enterprise.mm.enums.ContractInvoiceStatus;
import com.enterprise.mm.enums.ContractPaymentStatus;
import com.enterprise.mm.enums.ProcurementSourceDocumentType;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.ContractPaymentMapper;
import com.enterprise.mm.payload.ContractPaymentCreateRequest;
import com.enterprise.mm.payload.ContractPaymentResponse;
import com.enterprise.mm.payload.ContractPaymentSearchRequest;
import com.enterprise.mm.payload.ContractPaymentUpdateRequest;
import com.enterprise.mm.payload.ProcurementPayablesDtos.PaymentMarkPaidRequest;
import com.enterprise.mm.payload.ProcurementPayablesDtos.PaymentSubmitRequest;
import com.enterprise.mm.payload.ProcurementWorkspaceDtos.DraftPaymentFromInvoiceRequest;
import com.enterprise.mm.service.ProcurementInvoiceEvidenceService.EvidenceSummary;
import com.enterprise.mm.repository.ContractInvoiceRepository;
import com.enterprise.mm.repository.ContractPaymentRepository;
import com.enterprise.mm.repository.ContractRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
public class ContractPaymentService {
    private final ContractPaymentRepository repository;
    private final ContractPaymentMapper mapper;
    private final String entityName = "ContractPayment";
    private final AuditService auditService;
    private final ContractRepository contractRepository;
    private final ContractInvoiceRepository contractInvoiceRepository;
    private final ProcurementPaymentReadinessService paymentReadinessService;
    private final ProcurementInvoiceEvidenceService evidenceService;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final TreasuryPaymentAcknowledgementPolicy treasuryPaymentAcknowledgementPolicy;
    private final TreasuryMockPaymentAcknowledgementService treasuryMockPaymentAcknowledgementService;
    private final IntegrationEventService integrationEventService;
    private final CommercialInstrumentTypeResolver commercialInstrumentTypeResolver;
    private final PurchaseOrderActionPolicy purchaseOrderActionPolicy;
    @org.springframework.beans.factory.annotation.Autowired
    private com.enterprise.mm.treasury.TreasuryTaskService treasuryTaskService;
    @org.springframework.beans.factory.annotation.Autowired
    private com.enterprise.mm.treasury.TreasuryNativeAccess treasuryNativeAccess;

    protected void beforeCreate(ContractPayment entity, ContractPaymentCreateRequest dto) {
        // Treasury submission and acknowledgement are dedicated transitions;
        // caller supplied create state is deliberately ignored.
        entity.setStatus(ContractPaymentStatus.DRAFT.name());
        ContractInvoice invoice = resolveInvoice(entity);
        applyInvoiceLineage(entity, invoice);
        Contract contract = validateContract(entity);
        if (contract == null) {
            validateStandalonePaymentLineage(entity, invoice);
        } else {
            validateAndApplyMatch(entity, contract);
            entity.setContract(contract);
            entity.setSupplier(contract.getSupplier());
        }
        paymentReadinessService.requirePaymentSubmit(invoice, entity.getAmount());
        entity.setContractInvoice(invoice);
        if (isBlank(entity.getCode()) && !isBlank(entity.getPaymentNumber())) {
            entity.setCode(entity.getPaymentNumber());
        }
        if (isBlank(entity.getName()) && !isBlank(entity.getPaymentNumber())) {
            entity.setName(entity.getPaymentNumber());
        }
        if (entity.getAmount() == null) {
            entity.setAmount(BigDecimal.ZERO);
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

    protected void beforeUpdate(ContractPayment entity, ContractPaymentUpdateRequest dto) {
        if (!ContractPaymentStatus.DRAFT.name().equalsIgnoreCase(normalizedStatus(entity.getStatus(), ""))) {
            throw new BusinessException("Only draft payments can be updated");
        }
        ContractInvoice invoice = resolveInvoice(entity);
        applyInvoiceLineage(entity, invoice);
        Contract contract = validateContract(entity);
        if (contract == null) {
            validateStandalonePaymentLineage(entity, invoice);
        } else {
            validateAndApplyMatch(entity, contract);
            entity.setContract(contract);
            entity.setSupplier(contract.getSupplier());
        }
        paymentReadinessService.requirePaymentSubmit(invoice, entity.getAmount());
        entity.setContractInvoice(invoice);
        if (entity.getAmount() == null) {
            entity.setAmount(BigDecimal.ZERO);
        }
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }

    @Transactional
    public ContractPaymentResponse submitToTreasury(UUID id, PaymentSubmitRequest request) {
        ContractPayment entity = findEntity(id);
        if (entity.getPurchaseOrderId() != null) {
            purchaseOrderRepository.findByIdAndIsDeletedFalse(entity.getPurchaseOrderId())
                .ifPresent(po -> purchaseOrderActionPolicy.requireActionAllowed(
                    po, com.enterprise.mm.enums.PurchaseOrderWorkspaceActionKey.SUBMIT_PAYMENT));
        }
        if (!ContractPaymentStatus.DRAFT.name().equalsIgnoreCase(normalizedStatus(entity.getStatus(), ""))) {
            throw new BusinessException("Only draft payments can be submitted to Treasury");
        }
        ContractInvoice invoice = resolveInvoice(entity);
        paymentReadinessService.requirePaymentSubmit(invoice, entity.getAmount(), entity.getId());
        String paymentReference = trim(request == null ? null : request.paymentReference());
        if (paymentReference == null) {
            paymentReference = trim(entity.getTreasuryReference());
        }
        if (paymentReference == null && (treasuryNativeAccess==null||!treasuryNativeAccess.nativeEnabled()) && treasuryMockPaymentAcknowledgementService.isMockMode()) {
            paymentReference = "MOCK-" + id.toString().substring(0, 8).toUpperCase(Locale.ROOT);
        }
        entity.setTreasuryReference(paymentReference);
        entity.setStatus(ContractPaymentStatus.SENT_TO_TREASURY.name());
        ContractPayment saved = repository.save(entity);
        emitTreasuryPaymentRequest(saved, invoice);
        if(treasuryNativeAccess==null||!treasuryNativeAccess.nativeEnabled())
            treasuryMockPaymentAcknowledgementService.acknowledgeIfMockMode(saved.getId(), paymentReference);
        auditService.log(entityName, saved.getId(), "SUBMIT_TO_TREASURY", paymentReference, null, saved);
        return mapper.toResponse(saved);
    }

    @Transactional
    public ContractPaymentResponse markPaid(UUID id, PaymentMarkPaidRequest request) {
        if(treasuryNativeAccess!=null&&treasuryNativeAccess.nativeEnabled())
            throw new BusinessException("Native Treasury payments are calculated only from verified bank executions; manual mark-paid is disabled");
        ContractPayment entity = findEntity(id);
        if (ContractPaymentStatus.PAID.name().equalsIgnoreCase(normalizedStatus(entity.getStatus(), ""))) {
            return mapper.toResponse(entity);
        }
        if (!ContractPaymentStatus.SENT_TO_TREASURY.name().equalsIgnoreCase(normalizedStatus(entity.getStatus(), ""))) {
            throw new BusinessException("Only SENT_TO_TREASURY payments can be marked paid");
        }
        String paymentReference = trim(request == null ? null : request.paymentReference());
        if (paymentReference == null) {
            paymentReference = trim(entity.getTreasuryReference());
        }
        if (paymentReference == null) {
            paymentReference = trim(entity.getBankReference());
        }
        treasuryPaymentAcknowledgementPolicy.requireSuccessfulAcknowledgement(entity.getId(), paymentReference);
        ContractInvoice invoice = lockInvoice(entity.getContractInvoiceId());
        requirePaymentCanBeMarkedPaid(entity, invoice);
        entity.setTreasuryReference(paymentReference);
        entity.setBankReference(paymentReference);
        entity.setStatus(ContractPaymentStatus.PAID.name());
        ContractPayment saved = repository.save(entity);
        refreshInvoicePaymentState(invoice);
        auditService.log(entityName, saved.getId(), "MARK_PAID", paymentReference, null, saved);
        return mapper.toResponse(saved);
    }

    @Transactional
    public ContractPaymentResponse createDraftForSourceDocument(
        UUID contractInvoiceId,
        UUID sourceDocumentId,
        ProcurementSourceDocumentType sourceDocumentType,
        BigDecimal amount,
        String paymentReference
    ) {
        ContractInvoice invoice = loadInvoice(contractInvoiceId);
        ContractPaymentCreateRequest request = new ContractPaymentCreateRequest();
        request.setContractInvoiceId(invoice.getId());
        request.setContractId(invoice.getContractId());
        request.setPurchaseOrderId(invoice.getPurchaseOrderId());
        request.setSupplierId(invoice.getSupplierId());
        request.setSourceDocumentId(sourceDocumentId);
        request.setSourceDocumentType(sourceDocumentType.name());
        request.setAmount(amount);
        request.setCurrencyCode(invoice.getCurrencyCode());
        request.setTreasuryReference(paymentReference);
        request.setPaymentNumber(paymentReference);
        return create(request);
    }

    @Transactional
    public ContractPaymentResponse createDraftFromInvoice(DraftPaymentFromInvoiceRequest request) {
        if (request == null || request.contractInvoiceId() == null) {
            throw new BusinessException("contractInvoiceId is required");
        }
        ContractInvoice invoice = loadInvoice(request.contractInvoiceId());
        BigDecimal amount = request.amount() == null ? paymentReadinessService.remainingInvoiceAmount(invoice) : request.amount();
        paymentReadinessService.requirePaymentSubmit(invoice, amount);
        UUID sourceDocumentId = null;
        ProcurementSourceDocumentType sourceDocumentType = null;
        if (invoice.getPurchaseOrderId() != null) {
            PurchaseOrder purchaseOrder = purchaseOrderRepository.findByIdAndIsDeletedFalse(invoice.getPurchaseOrderId())
                .filter(item -> TenantContext.currentTenant().equals(item.getTenantId()))
                .filter(item -> com.enterprise.mm.security.AccessScope.canAccessCompany(item.getCompanyId()))
                .orElseThrow(() -> new BusinessException("Purchase order not found"));
            EvidenceSummary summary = evidenceService.summarize(purchaseOrder);
            sourceDocumentId = summary.primarySourceDocumentId();
            sourceDocumentType = summary.primarySourceDocumentType();
        }
        if (sourceDocumentId == null || sourceDocumentType == null) {
            throw new BusinessException("Source document evidence is required before payment draft creation");
        }
        ContractPaymentCreateRequest create = new ContractPaymentCreateRequest();
        create.setContractInvoiceId(invoice.getId());
        create.setContractId(invoice.getContractId());
        create.setPurchaseOrderId(invoice.getPurchaseOrderId());
        create.setSupplierId(invoice.getSupplierId());
        create.setCurrencyCode(normalizedCurrency(invoice.getCurrencyCode()));
        create.setAmount(amount);
        create.setPaymentNumber(trim(request.paymentReference()));
        create.setTreasuryReference(trim(request.paymentReference()));
        create.setPaymentDate(request.paymentDate());
        create.setSourceDocumentType(sourceDocumentType.name());
        create.setSourceDocumentId(sourceDocumentId);
        return create(create);
    }

    private void emitTreasuryPaymentRequest(ContractPayment payment, ContractInvoice invoice) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractPaymentId", payment.getId());
        payload.put("contractInvoiceId", invoice.getId());
        payload.put("purchaseOrderId", payment.getPurchaseOrderId());
        if (payment.getCommercialInstrumentType() != null) {
            payload.put("commercialInstrumentType", payment.getCommercialInstrumentType().name());
        }
        payload.put("amount", payment.getAmount());
        payload.put("currency", payment.getCurrencyCode());
        if (payment.getSourceDocumentType() != null) {
            payload.put("sourceDocumentType", payment.getSourceDocumentType());
        }
        if (payment.getSourceDocumentId() != null) {
            payload.put("sourceDocumentId", payment.getSourceDocumentId());
        }
        if (payment.getTreasuryReference() != null) {
            payload.put("paymentReference", payment.getTreasuryReference());
        }
        var event=integrationEventService.emit("TREASURY.PAYMENT_REQUEST", "TREASURY", payment.getId(), payload);
        if(treasuryTaskService!=null&&treasuryNativeAccess!=null&&treasuryNativeAccess.nativeEnabled())
            treasuryTaskService.enqueue(payment.getId(),event.getId(),new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(payload));
    }

    private void refreshInvoicePaymentState(ContractInvoice invoice) {
        BigDecimal paidAmount = repository
            .findByTenantIdAndContractInvoiceIdAndIsDeletedFalseOrderByCreatedAtAsc(invoice.getTenantId(), invoice.getId())
            .stream()
            .filter(payment -> ContractPaymentStatus.PAID.name().equalsIgnoreCase(normalizedStatus(payment.getStatus(), "")))
            .map(payment -> payment.getAmount() == null ? BigDecimal.ZERO : payment.getAmount())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        invoice.setPaidAmount(paidAmount);
        BigDecimal invoiceAmount = invoice.getAmount() == null ? BigDecimal.ZERO : invoice.getAmount();
        if (paidAmount.compareTo(invoiceAmount) >= 0 && invoiceAmount.signum() > 0) {
            invoice.setStatus(ContractInvoiceStatus.PAID.name());
        } else if (paidAmount.signum() > 0) {
            invoice.setStatus(ContractInvoiceStatus.PARTIALLY_PAID.name());
        }
        contractInvoiceRepository.save(invoice);
    }

    private void requirePaymentCanBeMarkedPaid(ContractPayment payment, ContractInvoice invoice) {
        String invoiceStatus = normalizedStatus(invoice.getStatus(), "");
        if (!ContractInvoiceStatus.ISSUED.name().equals(invoiceStatus)
            && !ContractInvoiceStatus.PARTIALLY_PAID.name().equals(invoiceStatus)) {
            throw new BusinessException("Invoice must be ISSUED or PARTIALLY_PAID before payment confirmation");
        }
        BigDecimal amount = payment.getAmount() == null ? BigDecimal.ZERO : payment.getAmount();
        if (amount.signum() <= 0) {
            throw new BusinessException("Payment amount must be greater than zero and not exceed invoice remaining amount");
        }
        BigDecimal alreadyPaid = repository
            .findByTenantIdAndContractInvoiceIdAndIsDeletedFalseOrderByCreatedAtAsc(invoice.getTenantId(), invoice.getId())
            .stream()
            .filter(existing -> !Objects.equals(existing.getId(), payment.getId()))
            .filter(existing -> ContractPaymentStatus.PAID.name().equalsIgnoreCase(normalizedStatus(existing.getStatus(), "")))
            .map(existing -> existing.getAmount() == null ? BigDecimal.ZERO : existing.getAmount())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal invoiceAmount = invoice.getAmount() == null ? BigDecimal.ZERO : invoice.getAmount();
        if (alreadyPaid.add(amount).compareTo(invoiceAmount) > 0) {
            throw new BusinessException("Payment amount must be greater than zero and not exceed invoice remaining amount");
        }
    }

    private ContractInvoice resolveInvoice(ContractPayment entity) {
        if (entity.getContractInvoiceId() == null && entity.getContractInvoice() != null) {
            entity.setContractInvoiceId(entity.getContractInvoice().getId());
        }
        if (entity.getContractInvoiceId() == null) {
            throw new BusinessException("contractInvoiceId is required");
        }
        return loadInvoice(entity.getContractInvoiceId());
    }

    private ContractInvoice loadInvoice(UUID invoiceId) {
        ContractInvoice invoice = contractInvoiceRepository.findByIdAndIsDeletedFalse(invoiceId)
            .filter(item -> TenantContext.currentTenant().equals(item.getTenantId()))
            .filter(item -> com.enterprise.mm.security.AccessScope.canAccessCompany(item.getCompanyId()))
            .orElseThrow(() -> new BusinessException("Contract invoice not found"));
        return invoice;
    }

    private ContractInvoice lockInvoice(UUID invoiceId) {
        if (invoiceId == null) {
            throw new BusinessException("contractInvoiceId is required");
        }
        return contractInvoiceRepository.findByIdForUpdate(invoiceId)
            .filter(item -> TenantContext.currentTenant().equals(item.getTenantId()))
            .filter(item -> com.enterprise.mm.security.AccessScope.canAccessCompany(item.getCompanyId()))
            .orElseThrow(() -> new BusinessException("Contract invoice not found"));
    }

    private void applyInvoiceLineage(ContractPayment entity, ContractInvoice invoice) {
        entity.setContractId(invoice.getContractId());
        entity.setCommercialInstrumentType(resolveCommercialInstrumentType(invoice));
        entity.setPurchaseOrderId(invoice.getPurchaseOrderId());
        entity.setSupplierId(invoice.getSupplierId());
        entity.setCurrencyCode(invoice.getCurrencyCode());
        entity.setContractInvoiceId(invoice.getId());
    }

    private Contract validateContract(ContractPayment entity) {
        if (entity.getContractId() == null) {
            return null;
        }
        return contractRepository.findByIdAndIsDeletedFalse(entity.getContractId())
            .filter(item -> !item.isDeleted() && TenantContext.currentTenant().equals(item.getTenantId()))
            .filter(item -> com.enterprise.mm.security.AccessScope.canAccessCompany(item.getCompanyId()))
            .orElseThrow(() -> new BusinessException("Contract not found"));
    }

    private void validateAndApplyMatch(ContractPayment entity, Contract contract) {
        if (entity.getSupplierId() != null && !entity.getSupplierId().equals(contract.getSupplierId())) {
            throw new BusinessException("Payment supplier must match contract supplier");
        }
        String contractCurrency = isBlank(contract.getCurrencyCode()) ? "UZS" : contract.getCurrencyCode().trim().toUpperCase(Locale.ROOT);
        if (!isBlank(entity.getCurrencyCode()) && !entity.getCurrencyCode().trim().equalsIgnoreCase(contractCurrency)) {
            throw new BusinessException("Payment currency must match contract currency");
        }
        entity.setContractId(contract.getId());
        entity.setSupplierId(contract.getSupplierId());
        entity.setCurrencyCode(contractCurrency);
    }

    private void validateStandalonePaymentLineage(ContractPayment entity, ContractInvoice invoice) {
        if (!CommercialInstrumentType.STANDALONE_PO.equals(resolveCommercialInstrumentType(invoice))) {
            throw new BusinessException("contractId is required");
        }
        if (invoice.getPurchaseOrderId() == null) {
            throw new BusinessException("purchaseOrderId is required for standalone PO payment");
        }
        if (invoice.getSupplierId() == null) {
            throw new BusinessException("Standalone PO payment requires invoice supplier");
        }
        entity.setContract(null);
        entity.setSupplier(null);
        entity.setContractId(null);
        entity.setCommercialInstrumentType(CommercialInstrumentType.STANDALONE_PO);
        entity.setPurchaseOrderId(invoice.getPurchaseOrderId());
        entity.setSupplierId(invoice.getSupplierId());
        entity.setCurrencyCode(normalizedCurrency(invoice.getCurrencyCode()));
    }

    private CommercialInstrumentType resolveCommercialInstrumentType(ContractInvoice invoice) {
        return commercialInstrumentTypeResolver.resolve(
            invoice.getCommercialInstrumentType(),
            invoice.getContractId(),
            invoice.getPurchaseOrderId()
        );
    }

    @Transactional(readOnly = true)
    public Page<ContractPaymentResponse> search(ContractPaymentSearchRequest request, Pageable pageable) {
        SearchCriteria filter = SearchCriteria.of(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        var spec = ProcurementSearchSpecifications.build(
            ContractPayment.class,
            TenantContext.currentTenant(),
            scoped ? CompanyContext.currentCompany() : null,
            filter,
            request
        );
        return repository.findAll(spec, pageable).map(mapper::toResponse);
    }

    @Transactional(readOnly = true)
    public ContractPaymentResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }

    @Transactional
    public ContractPaymentResponse create(ContractPaymentCreateRequest dto) {
        ContractPayment entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        ContractPayment saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }

    @Transactional
    public ContractPaymentResponse update(UUID id, ContractPaymentUpdateRequest dto) {
        ContractPayment entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        UUID contractId = entity.getContractId();
        UUID contractInvoiceId = entity.getContractInvoiceId();
        mapper.updateEntity(entity, dto);
        entity.setContractId(contractId);
        entity.setContractInvoiceId(contractInvoiceId);
        beforeUpdate(entity, dto);
        ContractPayment saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }

    @Transactional
    public void softDelete(UUID id) {
        ContractPayment entity = findEntity(id);
        if(treasuryTaskService!=null&&treasuryNativeAccess!=null&&treasuryNativeAccess.nativeEnabled()&&treasuryTaskService.hasRequest(id))
            throw new BusinessException("A native Treasury request has an immutable payment audit; deletion is disabled");
        if (ContractPaymentStatus.PAID.name().equalsIgnoreCase(normalizedStatus(entity.getStatus(), ""))) {
            throw new BusinessException("Paid payment cannot be deleted");
        }
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }

    @Transactional
    public ContractPaymentResponse restore(UUID id) {
        ContractPayment entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        ContractPayment saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }

    protected ContractPayment findEntity(UUID id) {
        ContractPayment entity = repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        return entity;
    }

    protected boolean companyScoped() {
        return true;
    }

    private String normalizedStatus(String status, String defaultValue) {
        return isBlank(status) ? defaultValue : status.trim().toUpperCase(Locale.ROOT);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String normalizedCurrency(String value) {
        return isBlank(value) ? "UZS" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
