package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ProcurementFramework;
import com.enterprise.mm.entity.ProcurementFrameworkLedgerEntry;
import com.enterprise.mm.entity.ProcurementAwardOutcome;
import com.enterprise.mm.entity.ProcurementProcedure;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.enums.ProcurementExecutionPath;
import com.enterprise.mm.enums.ProcurementAwardSourceType;
import com.enterprise.mm.enums.ProcurementFrameworkLedgerType;
import com.enterprise.mm.enums.ProcurementFrameworkStatus;
import com.enterprise.mm.enums.ProcurementMethodType;
import com.enterprise.mm.enums.ProcurementProcedureStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.ContractCreateRequest;
import com.enterprise.mm.payload.ContractSpecificationCreateRequest;
import com.enterprise.mm.payload.ContractUpdateRequest;
import com.enterprise.mm.payload.ProcurementFrameworkDtos.CallOffRequest;
import com.enterprise.mm.payload.ProcurementFrameworkDtos.CreateRequest;
import com.enterprise.mm.payload.ProcurementFrameworkDtos.LedgerResponse;
import com.enterprise.mm.payload.ProcurementFrameworkDtos.Readiness;
import com.enterprise.mm.payload.ProcurementFrameworkDtos.Response;
import com.enterprise.mm.payload.ProcurementFrameworkDtos.ReversalRequest;
import com.enterprise.mm.payload.ProcurementProcedureExecutionDtos.FrameworkAwardRequest;
import com.enterprise.mm.payload.ProcurementProcedureExecutionDtos.FrameworkCallOffRequest;
import com.enterprise.mm.payload.PurchaseOrderResponse;
import com.enterprise.mm.repository.ProcurementFrameworkLedgerRepository;
import com.enterprise.mm.repository.ProcurementFrameworkRepository;
import com.enterprise.mm.repository.ProcurementAwardOutcomeRepository;
import com.enterprise.mm.repository.ProcurementProcedureRepository;
import com.enterprise.mm.repository.PurchaseRequestItemRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import com.enterprise.mm.service.approval.ProcurementFrameworkApprovalAdapter;
import com.enterprise.mm.util.BusinessNameGenerator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProcurementFrameworkService {
    private static final Set<ProcurementFrameworkStatus> NO_CALLOFF_STATUSES = Set.of(
        ProcurementFrameworkStatus.DRAFT,
        ProcurementFrameworkStatus.APPROVAL_PENDING,
        ProcurementFrameworkStatus.APPROVED,
        ProcurementFrameworkStatus.SUSPENDED,
        ProcurementFrameworkStatus.EXPIRED,
        ProcurementFrameworkStatus.CLOSED,
        ProcurementFrameworkStatus.CANCELLED
    );

    private final ProcurementFrameworkRepository repository;
    private final ProcurementFrameworkLedgerRepository ledgerRepository;
    private final ProcurementAwardOutcomeRepository outcomeRepository;
    private final ProcurementProcedureRepository procedureRepository;
    private final PurchaseRequestRepository purchaseRequestRepository;
    private final PurchaseRequestItemRepository purchaseRequestItemRepository;
    private final SupplierRepository supplierRepository;
    private final ProcurementMethodPolicy methodPolicy;
    private final ProcurementMethodCapabilityService capabilityService;
    private final ProcurementAwardPurchaseOrderService awardPurchaseOrderService;
    private final PurchaseOrderService purchaseOrderService;
    private final ContractService contractService;
    private final ContractSpecificationService contractSpecificationService;
    private final ApprovalEngineService approvalEngineService;
    private final ProcurementAwardOutcomeLineService outcomeLineService;
    private final AuditService auditService;
    private final IntegrationEventService integrationEventService;
    private final SupplierEligibilityService supplierEligibilityService;

    @Transactional
    public Response create(CreateRequest request) {
        if (request == null) {
            throw new BusinessException("Framework create request is required");
        }
        ProcurementProcedure procedure = requireFrameworkProcedure(request.procedureId());
        return response(createForProcedure(procedure, request));
    }

    @Transactional
    public Response createFromProcedure(UUID procedureId, FrameworkAwardRequest request) {
        ProcurementProcedure procedure = requireFrameworkProcedure(procedureId);
        if (request == null) {
            throw new BusinessException("Framework award request is required");
        }
        CreateRequest canonical = new CreateRequest(
            procedureId,
            procedure.getMethodDecisionId(),
            procedure.getPurchaseRequestId(),
            request.supplierId(),
            null,
            "SINGLE_SUPPLIER",
            null,
            request.startDate() == null ? LocalDate.now() : request.startDate(),
            request.endDate(),
            request.currencyCode(),
            request.limitAmount(),
            null,
            request.awardReference(),
            request.contractNumber()
        );
        return response(createForProcedure(procedure, canonical));
    }

    @Transactional
    public Response submit(UUID id) {
        ProcurementFramework framework = requireFramework(id);
        if (framework.frameworkStatus() != ProcurementFrameworkStatus.DRAFT) {
            if (framework.frameworkStatus() == ProcurementFrameworkStatus.APPROVAL_PENDING) {
                return response(framework);
            }
            throw new BusinessException("Framework must be DRAFT before approval submission");
        }
        approvalEngineService.start(ProcurementFrameworkApprovalAdapter.MODULE_KEY, framework.getId());
        return response(requireFramework(framework.getId()));
    }

    @Transactional
    public Response activate(UUID id) {
        ProcurementFramework framework = requireFrameworkForUpdate(id);
        ProcurementFrameworkStatus status = canonicalStatus(framework);
        if (status != ProcurementFrameworkStatus.APPROVED) {
            throw new BusinessException("Framework must be APPROVED before activation");
        }
        validateCapability(framework);
        validateDates(framework.getValidFrom(), framework.getValidTo());
        requireActiveSupplier(framework.getSupplierId());
        if (framework.getContractId() == null) {
            throw new BusinessException("Framework contract is required before activation");
        }
        contractService.activate(framework.getContractId());
        framework.setFrameworkStatus(ProcurementFrameworkStatus.ACTIVE);
        ProcurementFramework saved = repository.save(framework);
        ProcurementProcedure procedure = framework.getProcedureId() == null ? null : requireFrameworkProcedure(framework.getProcedureId());
        if (procedure != null) {
            procedure.setStatus(ProcurementProcedureStatus.FRAMEWORK_ACTIVE.name());
            procedureRepository.save(procedure);
        }
        integrationEventService.emit("PROCUREMENT.FRAMEWORK_ACTIVATED", "ProcurementFramework", saved.getId(), Map.of(
            "frameworkId", saved.getId(),
            "procedureId", value(saved.getProcedureId()),
            "contractId", value(saved.getContractId())
        ));
        auditService.log("ProcurementFramework", saved.getId(), "ACTIVATE", saved.getAwardReference(), null, null);
        return response(saved);
    }

    @Transactional
    public Response suspend(UUID id) {
        ProcurementFramework framework = requireFrameworkForUpdate(id);
        if (canonicalStatus(framework) != ProcurementFrameworkStatus.ACTIVE) {
            throw new BusinessException("Only ACTIVE framework can be suspended");
        }
        framework.setFrameworkStatus(ProcurementFrameworkStatus.SUSPENDED);
        auditService.log("ProcurementFramework", id, "SUSPEND", framework.getAwardReference(), null, null);
        return response(repository.save(framework));
    }

    @Transactional
    public Response close(UUID id) {
        ProcurementFramework framework = requireFrameworkForUpdate(id);
        ProcurementFrameworkStatus status = canonicalStatus(framework);
        if (status != ProcurementFrameworkStatus.ACTIVE
            && status != ProcurementFrameworkStatus.SUSPENDED
            && status != ProcurementFrameworkStatus.EXPIRED) {
            throw new BusinessException("Only ACTIVE, SUSPENDED or EXPIRED framework can be closed");
        }
        framework.setFrameworkStatus(ProcurementFrameworkStatus.CLOSED);
        auditService.log("ProcurementFramework", id, "CLOSE", framework.getAwardReference(), null, null);
        return response(repository.save(framework));
    }

    @Transactional
    public Response cancel(UUID id) {
        ProcurementFramework framework = requireFrameworkForUpdate(id);
        if (framework.frameworkStatus() == ProcurementFrameworkStatus.ACTIVE
            || framework.frameworkStatus() == ProcurementFrameworkStatus.CLOSED) {
            throw new BusinessException("Active or closed framework cannot be cancelled");
        }
        framework.setFrameworkStatus(ProcurementFrameworkStatus.CANCELLED);
        auditService.log("ProcurementFramework", id, "CANCEL", framework.getAwardReference(), null, null);
        return response(repository.save(framework));
    }

    @Transactional
    public PurchaseOrderResponse callOff(UUID id, CallOffRequest request) {
        ProcurementFramework framework = requireFrameworkForUpdate(id);
        return createCallOff(framework, request);
    }

    @Transactional
    public PurchaseOrderResponse callOffFromProcedure(UUID procedureId, FrameworkCallOffRequest request) {
        ProcurementFramework framework = repository.lockScopedByProcedureId(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), procedureId)
            .orElseThrow(() -> new NotFoundException("ProcurementFramework not found for procedure: " + procedureId));
        BigDecimal amount = request == null ? null : request.callOffAmount();
        CallOffRequest canonical = new CallOffRequest(
            amount,
            framework.getCurrencyCode(),
            "LEGACY-" + procedureId,
            "LEGACY-" + procedureId,
            framework.getSupplierId(),
            framework.getPurchaseRequestId(),
            framework.getProcedureId(),
            null,
            request == null ? null : request.orderDate(),
            request == null ? null : request.expectedDeliveryDate(),
            framework.getAllocationBasis()
        );
        return createCallOff(framework, canonical);
    }

    @Transactional
    public Response reverseCallOff(UUID frameworkId, ReversalRequest request) {
        ProcurementFramework framework = requireFrameworkForUpdate(frameworkId);
        String key = requireText(request == null ? null : request.idempotencyKey(), "idempotencyKey");
        List<ProcurementFrameworkLedgerEntry> entries = entries(framework.getId(), key);
        ProcurementFrameworkLedgerEntry commit = entries.stream()
            .filter(entry -> entry.getTransactionType() == ProcurementFrameworkLedgerType.COMMIT)
            .findFirst()
            .orElseThrow(() -> new BusinessException("Call-off commitment not found for idempotencyKey"));
        ProcurementFrameworkLedgerEntry consume = entries.stream()
            .filter(entry -> entry.getTransactionType() == ProcurementFrameworkLedgerType.CONSUME)
            .findFirst()
            .orElse(null);
        List<ProcurementFrameworkLedgerEntry> allEntries = entries(framework.getId());
        boolean commitAlreadyReversed = allEntries.stream()
            .anyMatch(entry -> entry.getTransactionType() == ProcurementFrameworkLedgerType.COMMIT_REVERSAL
                && commit.getId().equals(entry.getReversalReference()));
        boolean consumeAlreadyReversed = consume == null || allEntries.stream()
            .anyMatch(entry -> entry.getTransactionType() == ProcurementFrameworkLedgerType.CONSUME_REVERSAL
                && consume.getId().equals(entry.getReversalReference()));
        if (commitAlreadyReversed && consumeAlreadyReversed) {
            return response(framework);
        }
        String reversalKey = "REVERSAL-" + key;
        if (!commitAlreadyReversed) {
            record(framework, ProcurementFrameworkLedgerType.COMMIT_REVERSAL, commit.getAmount(), commit.getCurrencyCode(),
                reversalKey, commit.getCallOffReference(), commit.getPurchaseOrderId(), commit.getSourcePurchaseRequestId(),
                commit.getSourceProcedureId(), commit.getId());
        }
        if (consume != null && !consumeAlreadyReversed) {
            record(framework, ProcurementFrameworkLedgerType.CONSUME_REVERSAL, consume.getAmount(), consume.getCurrencyCode(),
                reversalKey, consume.getCallOffReference(), consume.getPurchaseOrderId(), consume.getSourcePurchaseRequestId(),
                consume.getSourceProcedureId(), consume.getId());
        }
        updateSnapshots(framework);
        auditService.log("ProcurementFramework", frameworkId, "REVERSE_CALLOFF", request == null ? null : request.reversalReference(), null, null);
        return response(repository.save(framework));
    }

    @Transactional(readOnly = true)
    public Response get(UUID id) {
        ProcurementFramework framework = requireFramework(id);
        return responseForRoute(framework, id);
    }

    @Transactional(readOnly = true)
    public Response getByProcedure(UUID procedureId) {
        ProcurementFramework framework = resolveFramework(procedureId)
            .orElseThrow(() -> new NotFoundException("ProcurementFramework not found for procedure: " + procedureId));
        return responseForRoute(framework, procedureId);
    }

    @Transactional(readOnly = true)
    public Readiness readiness(UUID id) {
        ProcurementFramework framework = requireFramework(id);
        ProcurementFrameworkStatus status = canonicalStatus(framework);
        String reason = null;
        boolean ready = status == ProcurementFrameworkStatus.ACTIVE;
        if (!ready) {
            reason = "Framework status is " + status;
        } else if (remaining(framework).signum() <= 0) {
            ready = false;
            reason = "Framework remaining capacity is exhausted";
        }
        return new Readiness(framework.getId(), ready, status, remaining(framework), reason);
    }

    @Transactional(readOnly = true)
    public BigDecimal remainingCapacity(UUID id) {
        return remaining(requireFramework(id));
    }

    @Transactional(readOnly = true)
    public List<LedgerResponse> ledger(UUID id) {
        ProcurementFramework framework = requireFramework(id);
        return entries(framework.getId()).stream().map(this::ledgerResponse).toList();
    }

    @Transactional
    public void markSubmitted(UUID id) {
        ProcurementFramework framework = requireFrameworkForUpdate(id);
        if (framework.frameworkStatus() == ProcurementFrameworkStatus.APPROVAL_PENDING) {
            return;
        }
        if (framework.frameworkStatus() != ProcurementFrameworkStatus.DRAFT) {
            throw new BusinessException("Framework must be DRAFT before approval submission");
        }
        framework.setFrameworkStatus(ProcurementFrameworkStatus.APPROVAL_PENDING);
        repository.save(framework);
    }

    @Transactional
    public void markApproved(UUID id, String comment, String actor) {
        ProcurementFramework framework = requireFrameworkForUpdate(id);
        if (framework.frameworkStatus() == ProcurementFrameworkStatus.APPROVED) {
            return;
        }
        if (framework.frameworkStatus() != ProcurementFrameworkStatus.APPROVAL_PENDING) {
            throw new BusinessException("Framework must be APPROVAL_PENDING before approval");
        }
        validateCapability(framework);
        framework.setFrameworkStatus(ProcurementFrameworkStatus.APPROVED);
        framework.setApprovalReference(comment);
        framework.setApprovedAt(LocalDateTime.now());
        framework.setApprovedBy(actor == null || actor.isBlank() ? "system" : actor);
        ProcurementFramework saved = repository.save(framework);
        ProcurementProcedure procedure = requireFrameworkProcedure(saved.getProcedureId());
        procedure.setStatus(ProcurementProcedureStatus.FRAMEWORK_AWARDED.name());
        procedure.setContractId(saved.getContractId());
        procedure.setAwardedSupplierId(saved.getSupplierId());
        procedure.setAwardReference(saved.getAwardReference());
        procedure.setFinalizedAmount(saved.getCeilingAmount());
        procedure.setFinalizedCurrencyCode(saved.getCurrencyCode());
        procedureRepository.save(procedure);
    }

    @Transactional
    public void markRejected(UUID id) {
        ProcurementFramework framework = requireFrameworkForUpdate(id);
        if (framework.frameworkStatus() != ProcurementFrameworkStatus.APPROVAL_PENDING) {
            throw new BusinessException("Framework must be APPROVAL_PENDING before rejection");
        }
        framework.setFrameworkStatus(ProcurementFrameworkStatus.DRAFT);
        repository.save(framework);
    }

    private ProcurementFramework createForProcedure(ProcurementProcedure procedure, CreateRequest request) {
        ProcurementFramework existing = repository.lockScopedByProcedureId(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), procedure.getId())
            .orElse(null);
        if (existing != null) {
            return updateDraftAward(existing, procedure, request);
        }
        if (!ProcurementProcedureStatus.FRAMEWORK_DRAFT.name().equals(procedure.getStatus())) {
            throw new BusinessException("Procurement procedure must be FRAMEWORK_DRAFT before framework creation");
        }
        requireActiveSupplier(request.supplierId());
        BigDecimal ceiling = positive(request.ceilingAmount(), "ceilingAmount");
        String currency = currency(request.currencyCode(), defaultCurrency(procedure.getPurchaseRequestId()));
        validateDates(request.validFrom(), request.validTo());
        validateCapability(procedure, ceiling, currency);
        requireRequestHasLines(procedure.getPurchaseRequestId());
        String supplierModel = textOr(request.supplierModel(), "SINGLE_SUPPLIER").toUpperCase(Locale.ROOT);
        String allocationBasis = trimToNull(request.allocationBasis());
        if (!Set.of("SINGLE_SUPPLIER", "MULTI_SUPPLIER").contains(supplierModel)) {
            throw new BusinessException("supplierModel must be SINGLE_SUPPLIER or MULTI_SUPPLIER");
        }
        if (!"SINGLE_SUPPLIER".equals(supplierModel) && !"RANKED_SUPPLIERS".equals(allocationBasis)) {
            throw new BusinessException("Multi-supplier frameworks require allocationBasis RANKED_SUPPLIERS");
        }
        List<UUID> supplierIds = supplierIds(request, supplierModel);
        supplierIds.forEach(this::requireActiveSupplier);
        ProcurementFramework framework = new ProcurementFramework();
        framework.setCode("FW-" + shortId());
        framework.setName(BusinessNameGenerator.workflowDocumentName("ProcurementFramework", procedure.getName()));
        framework.setFrameworkStatus(ProcurementFrameworkStatus.DRAFT);
        framework.setProcedureId(procedure.getId());
        framework.setMethodDecisionId(procedure.getMethodDecisionId());
        framework.setPurchaseRequestId(procedure.getPurchaseRequestId());
        framework.setSupplierId(request.supplierId());
        framework.setSupplierIds(serializeSupplierIds(supplierIds));
        framework.setSupplierModel(supplierModel);
        framework.setAllocationBasis(allocationBasis);
        framework.setValidFrom(request.validFrom());
        framework.setValidTo(request.validTo());
        framework.setCurrencyCode(currency);
        framework.setCeilingAmount(ceiling);
        framework.setCommittedAmount(BigDecimal.ZERO);
        framework.setConsumedAmount(BigDecimal.ZERO);
        framework.setCallOffPolicy(request.callOffPolicy());
        framework.setAwardReference(requireText(request.awardReference(), "awardReference"));
        framework.setContractId(createContract(procedure, request, ceiling, currency));
        ProcurementFramework saved = repository.save(framework);
        syncProcedureAwardFields(procedure, saved);
        procedureRepository.save(procedure);
        auditService.log("ProcurementFramework", saved.getId(), "CREATE", procedure.getProcedureNumber(), null, saved);
        return saved;
    }

    private ProcurementFramework updateDraftAward(
        ProcurementFramework existing,
        ProcurementProcedure procedure,
        CreateRequest request
    ) {
        ProcurementFrameworkStatus status = canonicalStatus(existing);
        if (status != ProcurementFrameworkStatus.DRAFT) {
            throw new BusinessException(
                "Framework award can only be updated while DRAFT; current status: " + status
            );
        }
        if (!entries(existing.getId()).isEmpty()) {
            throw new BusinessException("Framework award cannot be changed after call-off commitments exist");
        }
        requireActiveSupplier(request.supplierId());
        BigDecimal ceiling = positive(request.ceilingAmount(), "ceilingAmount");
        String currency = currency(request.currencyCode(), existing.getCurrencyCode());
        LocalDate validFrom = request.validFrom() == null ? existing.getValidFrom() : request.validFrom();
        LocalDate validTo = request.validTo();
        validateDates(validFrom, validTo);
        validateCapability(procedure, ceiling, currency);
        String supplierModel = textOr(request.supplierModel(), existing.getSupplierModel()).toUpperCase(Locale.ROOT);
        String allocationBasis = request.allocationBasis() == null
            ? existing.getAllocationBasis()
            : trimToNull(request.allocationBasis());
        if (!Set.of("SINGLE_SUPPLIER", "MULTI_SUPPLIER").contains(supplierModel)) {
            throw new BusinessException("supplierModel must be SINGLE_SUPPLIER or MULTI_SUPPLIER");
        }
        if (!"SINGLE_SUPPLIER".equals(supplierModel) && !"RANKED_SUPPLIERS".equals(allocationBasis)) {
            throw new BusinessException("Multi-supplier frameworks require allocationBasis RANKED_SUPPLIERS");
        }
        List<UUID> supplierIds = supplierIds(request, supplierModel);
        if (supplierIds.isEmpty()) {
            supplierIds = supplierIds(existing);
        }
        supplierIds.forEach(this::requireActiveSupplier);

        existing.setSupplierId(request.supplierId());
        existing.setSupplierIds(serializeSupplierIds(supplierIds));
        existing.setSupplierModel(supplierModel);
        existing.setAllocationBasis(allocationBasis);
        existing.setValidFrom(validFrom);
        existing.setValidTo(validTo);
        existing.setCurrencyCode(currency);
        existing.setCeilingAmount(ceiling);
        if (request.callOffPolicy() != null) {
            existing.setCallOffPolicy(request.callOffPolicy());
        }
        existing.setAwardReference(requireText(
            textOr(request.awardReference(), existing.getAwardReference()),
            "awardReference"
        ));
        syncLinkedContract(existing, request, ceiling, currency, validFrom, validTo);
        ProcurementFramework saved = repository.save(existing);
        syncProcedureAwardFields(procedure, saved);
        procedureRepository.save(procedure);
        auditService.log("ProcurementFramework", saved.getId(), "UPDATE_AWARD", saved.getAwardReference(), null, saved);
        return saved;
    }

    private void syncProcedureAwardFields(ProcurementProcedure procedure, ProcurementFramework framework) {
        procedure.setContractId(framework.getContractId());
        procedure.setAwardedSupplierId(framework.getSupplierId());
        procedure.setAwardReference(framework.getAwardReference());
        procedure.setFinalizedAmount(framework.getCeilingAmount());
        procedure.setFinalizedCurrencyCode(framework.getCurrencyCode());
    }

    private void syncLinkedContract(
        ProcurementFramework framework,
        CreateRequest request,
        BigDecimal ceiling,
        String currency,
        LocalDate validFrom,
        LocalDate validTo
    ) {
        if (framework.getContractId() == null) {
            return;
        }
        var current = contractService.get(framework.getContractId());
        ContractUpdateRequest update = new ContractUpdateRequest();
        update.setCode(current.getCode());
        update.setName(current.getName());
        update.setStatus(current.getStatus());
        update.setDescription(current.getDescription());
        update.setSupplierId(request.supplierId());
        update.setContractNumber(textOr(request.contractNumber(), current.getContractNumber()));
        update.setStartDate(validFrom);
        update.setEndDate(validTo);
        update.setLimitAmount(ceiling);
        update.setUsedAmount(current.getUsedAmount());
        update.setServiceUsedAmount(current.getServiceUsedAmount());
        update.setCurrencyCode(currency);
        contractService.update(framework.getContractId(), update);
    }

    private PurchaseOrderResponse createCallOff(ProcurementFramework framework, CallOffRequest request) {
        if (request == null) {
            throw new BusinessException("Framework call-off request is required");
        }
        ProcurementFrameworkStatus status = canonicalStatus(framework);
        if (NO_CALLOFF_STATUSES.contains(status)) {
            throw new BusinessException("Framework status does not allow new call-offs: " + status);
        }
        requireActiveSupplier(request.supplierId() == null ? framework.getSupplierId() : request.supplierId());
        UUID callOffSupplierId = request.supplierId() == null ? framework.getSupplierId() : request.supplierId();
        if (!supplierIds(framework).contains(callOffSupplierId)) {
            throw new BusinessException("Call-off supplier is not a member of this framework");
        }
        if (!"SINGLE_SUPPLIER".equals(framework.getSupplierModel())
            && !"RANKED_SUPPLIERS".equals(request.allocationBasis())) {
            throw new BusinessException("Multi-supplier call-off requires recorded RANKED_SUPPLIERS allocation basis");
        }
        validateInValidityWindow(framework, request.orderDate());
        BigDecimal amount = positive(request.amount(), "amount");
        String currency = currency(request.currencyCode(), framework.getCurrencyCode());
        if (!framework.getCurrencyCode().equals(currency)) {
            throw new BusinessException("Call-off currency must match framework currency");
        }
        if (request.purchaseRequestId() != null && !request.purchaseRequestId().equals(framework.getPurchaseRequestId())) {
            throw new BusinessException("Call-off purchase request lineage must match framework source");
        }
        if (request.procedureId() != null && !request.procedureId().equals(framework.getProcedureId())) {
            throw new BusinessException("Call-off procedure lineage must match framework source");
        }
        String key = idempotencyKey(framework, request);
        List<ProcurementFrameworkLedgerEntry> existing = entries(framework.getId(), key);
        if (!existing.isEmpty()) {
            UUID existingPo = existing.stream()
                .map(ProcurementFrameworkLedgerEntry::getPurchaseOrderId)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElseThrow(() -> new BusinessException("Idempotent call-off exists without purchase order evidence"));
            return purchaseOrderService.get(existingPo);
        }
        if (remaining(framework).compareTo(amount) < 0) {
            throw new BusinessException("Framework ceiling exceeded");
        }
        ProcurementProcedure procedure = requireFrameworkProcedure(framework.getProcedureId());
        PurchaseOrderResponse purchaseOrder = awardPurchaseOrderService.create(
            procedure,
            callOffSupplierId,
            amount,
            framework.getCurrencyCode(),
            framework.getContractId(),
            request.orderDate(),
            request.expectedDeliveryDate(),
            "Framework agreement call-off " + framework.getAwardReference()
        );
        String reference = textOr(request.callOffReference(), "CALLOFF-" + purchaseOrder.getId());
        record(framework, ProcurementFrameworkLedgerType.COMMIT, amount, currency, key, reference, purchaseOrder.getId(),
            framework.getPurchaseRequestId(), framework.getProcedureId(), null);
        record(framework, ProcurementFrameworkLedgerType.CONSUME, amount, currency, key, reference, purchaseOrder.getId(),
            framework.getPurchaseRequestId(), framework.getProcedureId(), null);
        recordCallOffOutcome(framework, procedure, purchaseOrder.getId(), callOffSupplierId, amount, reference, key);
        updateSnapshots(framework);
        repository.save(framework);
        auditService.log("ProcurementFramework", framework.getId(), "CALLOFF", reference, null, null);
        return purchaseOrder;
    }

    private UUID createContract(ProcurementProcedure procedure, CreateRequest request, BigDecimal ceiling, String currency) {
        ContractCreateRequest contractRequest = new ContractCreateRequest();
        contractRequest.setCode("FA-" + procedure.getProcedureNumber());
        contractRequest.setName(BusinessNameGenerator.workflowDocumentName("Contract", procedure.getName()));
        contractRequest.setDescription("Framework award reference: " + request.awardReference());
        contractRequest.setSupplierId(request.supplierId());
        contractRequest.setContractNumber(request.contractNumber());
        contractRequest.setStartDate(request.validFrom());
        contractRequest.setEndDate(request.validTo());
        contractRequest.setLimitAmount(ceiling);
        contractRequest.setUsedAmount(BigDecimal.ZERO);
        contractRequest.setServiceUsedAmount(BigDecimal.ZERO);
        contractRequest.setCurrencyCode(currency);
        var contract = contractService.create(contractRequest);
        createFrameworkSpecifications(procedure, contract.getId());
        return contract.getId();
    }

    private void createFrameworkSpecifications(ProcurementProcedure procedure, UUID contractId) {
        List<PurchaseRequestItem> items = requestItems(procedure.getPurchaseRequestId());
        if (items.isEmpty()) {
            throw new BusinessException("Purchase request has no lines for framework specification");
        }
        for (PurchaseRequestItem item : items) {
            ContractSpecificationCreateRequest specification = new ContractSpecificationCreateRequest();
            specification.setName(item.getName());
            specification.setContractId(contractId);
            specification.setLineType(item.getLineType() == null ? "MATERIAL" : item.getLineType().name());
            specification.setMaterialId(item.getMaterialId());
            specification.setMaterialTypeSnapshot(item.getMaterialTypeSnapshot());
            specification.setServiceDescription(item.getServiceDescription());
            specification.setServiceCategory(item.getServiceCategory());
            specification.setUnit(item.getUnit());
            specification.setPeriod(item.getPeriod());
            specification.setStage(item.getStage());
            specification.setQuantityLimit(item.getQuantity());
            specification.setServiceLimit(item.getQuantity() == null || item.getEstimatedPrice() == null
                ? BigDecimal.ZERO
                : item.getQuantity().multiply(item.getEstimatedPrice()));
            specification.setPrice(item.getEstimatedPrice());
            contractSpecificationService.createWithSourcePurchaseRequestItem(specification, item.getId());
        }
    }

    private ProcurementProcedure requireFrameworkProcedure(UUID id) {
        if (id == null) {
            throw new BusinessException("procedureId is required");
        }
        ProcurementProcedure procedure = procedureRepository.lockScopedById(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), id)
            .orElseThrow(() -> new NotFoundException("ProcurementProcedure not found: " + id));
        if (methodPolicy.require(procedure.getMethod()).executionPath() != ProcurementExecutionPath.FRAMEWORK_AGREEMENT) {
            throw new BusinessException("Procurement procedure does not use FRAMEWORK_AGREEMENT execution");
        }
        return procedure;
    }

    private ProcurementFramework requireFramework(UUID id) {
        return resolveFramework(id)
            .orElseThrow(() -> new NotFoundException("ProcurementFramework not found: " + id));
    }

    private ProcurementFramework requireFrameworkForUpdate(UUID id) {
        return resolveFrameworkForUpdate(id)
            .orElseThrow(() -> new NotFoundException("ProcurementFramework not found: " + id));
    }

    /**
     * UI routes use procedureId in the path; accept either framework id or procedure id.
     */
    private java.util.Optional<ProcurementFramework> resolveFramework(UUID id) {
        if (id == null) {
            return java.util.Optional.empty();
        }
        String tenant = TenantContext.currentTenant();
        UUID company = CompanyContext.currentCompany();
        return repository.findScopedById(tenant, company, id)
            .or(() -> repository.findScopedByProcedureId(tenant, company, id))
            .or(() -> resolveFrameworkViaProcedureCompany(tenant, id));
    }

    private java.util.Optional<ProcurementFramework> resolveFrameworkForUpdate(UUID id) {
        if (id == null) {
            return java.util.Optional.empty();
        }
        String tenant = TenantContext.currentTenant();
        UUID company = CompanyContext.currentCompany();
        return repository.lockScoped(tenant, company, id)
            .or(() -> repository.lockScopedByProcedureId(tenant, company, id))
            .or(() -> resolveFrameworkViaProcedureCompany(tenant, id)
                .flatMap(found -> repository.lockScoped(tenant, found.getCompanyId(), found.getId())));
    }

    private java.util.Optional<ProcurementFramework> resolveFrameworkViaProcedureCompany(String tenant, UUID procedureOrFrameworkId) {
        return procedureRepository.findByIdAndIsDeletedFalse(procedureOrFrameworkId)
            .filter(procedure -> tenant.equals(procedure.getTenantId()))
            .filter(procedure -> com.enterprise.mm.security.AccessScope.canAccessCompany(procedure.getCompanyId()))
            .flatMap(procedure -> repository.findScopedByProcedureId(tenant, procedure.getCompanyId(), procedure.getId())
                .or(() -> repository.findScopedById(tenant, procedure.getCompanyId(), procedureOrFrameworkId)));
    }

    private Supplier requireActiveSupplier(UUID id) {
        if (id == null) {
            throw new BusinessException("supplierId is required");
        }
        Supplier supplier = supplierRepository.findByIdAndIsDeletedFalse(id)
            .filter(this::scoped)
            .orElseThrow(() -> new BusinessException("Supplier not found: " + id));
        supplierEligibilityService.requireNewBusinessAllowed(supplier.getId(), null, "FRAMEWORK_AWARD");
        return supplier;
    }

    private void validateCapability(ProcurementFramework framework) {
        validateCapability(requireFrameworkProcedure(framework.getProcedureId()), framework.getCeilingAmount(), framework.getCurrencyCode());
    }

    private void validateCapability(ProcurementProcedure procedure, BigDecimal amount, String currency) {
        ProcurementMethodType method = methodPolicy.require(procedure.getMethod());
        List<String> categories = categories(procedure.getPurchaseRequestId());
        if (categories.isEmpty()) {
            capabilityService.requireAvailableForCompany(method.name(), amount, currency,
                requestType(procedure.getPurchaseRequestId()), null, procedure.getCompanyId());
            return;
        }
        for (String category : categories) {
            capabilityService.requireAvailableForCompany(method.name(), amount, currency,
                requestType(procedure.getPurchaseRequestId()), category, procedure.getCompanyId());
        }
    }

    private String requestType(UUID purchaseRequestId) {
        if (purchaseRequestId == null) {
            return null;
        }
        return purchaseRequestRepository.findByIdAndIsDeletedFalse(purchaseRequestId)
            .filter(this::scoped)
            .map(PurchaseRequest::getRequestType)
            .map(Enum::name)
            .orElse(null);
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

    private void requireRequestHasLines(UUID purchaseRequestId) {
        if (requestItems(purchaseRequestId).isEmpty()) {
            throw new BusinessException("Purchase request has no lines for framework");
        }
    }

    private List<PurchaseRequestItem> requestItems(UUID purchaseRequestId) {
        if (purchaseRequestId == null) {
            return List.of();
        }
        return purchaseRequestItemRepository.findByTenantIdAndPurchaseRequest_IdAndIsDeletedFalse(
                TenantContext.currentTenant(), purchaseRequestId)
            .stream()
            .filter(this::scoped)
            .toList();
    }

    private List<String> categories(UUID purchaseRequestId) {
        return requestItems(purchaseRequestId).stream()
            .map(item -> item.getMaterial() == null
                ? item.getServiceCategory()
                : item.getMaterial().getMaterialGroupId() == null ? item.getServiceCategory() : item.getMaterial().getMaterialGroupId().toString())
            .filter(value -> value != null && !value.isBlank())
            .distinct()
            .toList();
    }

    private void validateDates(LocalDate validFrom, LocalDate validTo) {
        if (validFrom == null) {
            throw new BusinessException("validFrom is required");
        }
        if (validTo != null && validTo.isBefore(validFrom)) {
            throw new BusinessException("validTo must not be before validFrom");
        }
    }

    private void validateInValidityWindow(ProcurementFramework framework, LocalDate orderDate) {
        LocalDate effectiveDate = orderDate == null ? LocalDate.now() : orderDate;
        if (effectiveDate.isBefore(framework.getValidFrom())) {
            throw new BusinessException("Call-off date is before framework validity");
        }
        if (framework.getValidTo() != null && effectiveDate.isAfter(framework.getValidTo())) {
            throw new BusinessException("Call-off date is after framework validity");
        }
    }

    private ProcurementFrameworkStatus canonicalStatus(ProcurementFramework framework) {
        ProcurementFrameworkStatus status = framework.frameworkStatus();
        if ((status == ProcurementFrameworkStatus.ACTIVE || status == ProcurementFrameworkStatus.APPROVED)
            && framework.getValidTo() != null
            && LocalDate.now().isAfter(framework.getValidTo())) {
            return ProcurementFrameworkStatus.EXPIRED;
        }
        return status;
    }

    private BigDecimal remaining(ProcurementFramework framework) {
        return framework.getCeilingAmount().subtract(net(framework, ProcurementFrameworkLedgerType.COMMIT, ProcurementFrameworkLedgerType.COMMIT_REVERSAL));
    }

    private void updateSnapshots(ProcurementFramework framework) {
        framework.setCommittedAmount(net(framework, ProcurementFrameworkLedgerType.COMMIT, ProcurementFrameworkLedgerType.COMMIT_REVERSAL));
        framework.setConsumedAmount(net(framework, ProcurementFrameworkLedgerType.CONSUME, ProcurementFrameworkLedgerType.CONSUME_REVERSAL));
    }

    private BigDecimal net(ProcurementFramework framework, ProcurementFrameworkLedgerType positive, ProcurementFrameworkLedgerType reversal) {
        return entries(framework.getId()).stream()
            .map(entry -> {
                if (entry.getTransactionType() == positive) return entry.getAmount();
                if (entry.getTransactionType() == reversal) return entry.getAmount().negate();
                return BigDecimal.ZERO;
            })
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private ProcurementFrameworkLedgerEntry record(
        ProcurementFramework framework,
        ProcurementFrameworkLedgerType type,
        BigDecimal amount,
        String currency,
        String idempotencyKey,
        String callOffReference,
        UUID purchaseOrderId,
        UUID purchaseRequestId,
        UUID procedureId,
        UUID reversalReference
    ) {
        ProcurementFrameworkLedgerEntry entry = new ProcurementFrameworkLedgerEntry();
        entry.setCode("FWL-" + shortId());
        entry.setName(type.name() + " " + callOffReference);
        entry.setStatus("POSTED");
        entry.setFrameworkId(framework.getId());
        entry.setTransactionType(type);
        entry.setAmount(amount);
        entry.setCurrencyCode(currency);
        entry.setIdempotencyKey(idempotencyKey);
        entry.setCallOffReference(callOffReference);
        entry.setPurchaseOrderId(purchaseOrderId);
        entry.setSourcePurchaseRequestId(purchaseRequestId);
        entry.setSourceProcedureId(procedureId);
        entry.setReversalReference(reversalReference);
        entry.setOccurredAt(LocalDateTime.now());
        return ledgerRepository.save(entry);
    }

    private List<ProcurementFrameworkLedgerEntry> entries(UUID frameworkId) {
        return ledgerRepository.findByTenantIdAndCompanyIdAndFrameworkIdAndIsDeletedFalseOrderByOccurredAtAsc(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), frameworkId);
    }

    private List<ProcurementFrameworkLedgerEntry> entries(UUID frameworkId, String idempotencyKey) {
        return ledgerRepository.findByTenantIdAndCompanyIdAndFrameworkIdAndIdempotencyKeyAndIsDeletedFalseOrderByOccurredAtAsc(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), frameworkId, idempotencyKey);
    }

    private Response response(ProcurementFramework framework) {
        return responseForRoute(framework, framework.getId());
    }

    /**
     * UI routes use procedureId in the path and often assert {@code response.id === routeId}.
     * When the route key is the procedure id, expose that as {@code id} so the page can load;
     * mutate endpoints still resolve by procedure id via {@link #resolveFrameworkForUpdate}.
     */
    private Response responseForRoute(ProcurementFramework framework, UUID routeId) {
        updateSnapshots(framework);
        UUID publicId = routeId != null && routeId.equals(framework.getProcedureId())
            ? routeId
            : framework.getId();
        return new Response(
            publicId,
            framework.getProcedureId(),
            framework.getMethodDecisionId(),
            framework.getPurchaseRequestId(),
            framework.getContractId(),
            framework.getAwardReference(),
            framework.getSupplierId(),
            supplierIds(framework),
            framework.getSupplierModel(),
            framework.getAllocationBasis(),
            framework.getValidFrom(),
            framework.getValidTo(),
            framework.getCurrencyCode(),
            framework.getCeilingAmount(),
            framework.getCommittedAmount(),
            framework.getConsumedAmount(),
            remaining(framework),
            framework.getCallOffPolicy(),
            canonicalStatus(framework),
            framework.getApprovalReference(),
            framework.getApprovedAt(),
            framework.getApprovedBy(),
            framework.getCreatedAt()
        );
    }

    private LedgerResponse ledgerResponse(ProcurementFrameworkLedgerEntry entry) {
        return new LedgerResponse(entry.getId(), entry.getFrameworkId(), entry.getTransactionType(),
            entry.getCallOffReference(), entry.getPurchaseOrderId(), entry.getSourcePurchaseRequestId(),
            entry.getSourceProcedureId(), entry.getAmount(), entry.getCurrencyCode(), entry.getIdempotencyKey(),
            entry.getReversalReference(), entry.getOccurredAt());
    }

    private void recordCallOffOutcome(
        ProcurementFramework framework,
        ProcurementProcedure procedure,
        UUID purchaseOrderId,
        UUID supplierId,
        BigDecimal amount,
        String reference,
        String idempotencyKey
    ) {
        ProcurementAwardOutcome existing = outcomeRepository
            .findByTenantIdAndCompanyIdAndProcurementProcedureIdAndSourceTypeAndIdempotencyKeyAndIsDeletedFalse(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                procedure.getId(),
                ProcurementAwardSourceType.FRAMEWORK_CALLOFF,
                idempotencyKey
            ).orElse(null);
        if (existing != null) {
            outcomeLineService.recordLines(existing);
            return;
        }
        ProcurementAwardOutcome outcome = new ProcurementAwardOutcome();
        outcome.setCode("PAO-" + shortId());
        outcome.setName(BusinessNameGenerator.workflowDocumentName("ProcurementAwardOutcome", framework.getName()));
        outcome.setStatus("FINALIZED");
        outcome.setProcurementProcedureId(procedure.getId());
        outcome.setSourceType(ProcurementAwardSourceType.FRAMEWORK_CALLOFF);
        outcome.setSourceId(purchaseOrderId);
        outcome.setAwardedSupplierId(supplierId);
        outcome.setPurchaseOrderId(purchaseOrderId);
        outcome.setContractId(framework.getContractId());
        outcome.setAwardAmount(amount);
        outcome.setCurrencyCode(framework.getCurrencyCode());
        outcome.setAwardReference(reference);
        outcome.setIdempotencyKey(idempotencyKey);
        outcome.setFinalizedAt(LocalDateTime.now());
        ProcurementAwardOutcome saved = outcomeRepository.save(outcome);
        outcomeLineService.recordLines(saved);
        integrationEventService.emitIdempotentInCurrentTransaction(
            "PROCUREMENT.FRAMEWORK_CALLOFF_CREATED",
            "ProcurementFramework",
            framework.getId(),
            Map.of(
                "frameworkId", framework.getId(),
                "procedureId", procedure.getId(),
                "purchaseOrderId", purchaseOrderId,
                "outcomeId", saved.getId()
            ),
            "FRAMEWORK_CALLOFF:" + idempotencyKey
        );
    }

    private boolean scoped(com.enterprise.mm.common.BusinessEntity entity) {
        return entity != null
            && !entity.isDeleted()
            && TenantContext.currentTenant().equals(entity.getTenantId())
            && CompanyContext.currentCompany().equals(entity.getCompanyId());
    }

    private BigDecimal positive(BigDecimal value, String field) {
        if (value == null || value.signum() <= 0) {
            throw new BusinessException(field + " must be greater than zero");
        }
        return value;
    }

    private String currency(String value, String fallback) {
        String normalized = value == null || value.isBlank() ? fallback : value;
        normalized = normalized == null || normalized.isBlank() ? "UZS" : normalized.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z]{3}")) {
            throw new BusinessException("Currency code must contain three letters");
        }
        return normalized;
    }

    private String idempotencyKey(ProcurementFramework framework, CallOffRequest request) {
        String provided = trimToNull(request.idempotencyKey());
        if (provided != null) {
            return provided;
        }
        String reference = trimToNull(request.callOffReference());
        if (reference != null) {
            return reference;
        }
        return framework.getId() + ":" + request.amount() + ":" + value(request.purchaseRequestId()) + ":" + value(request.procedureId());
    }

    private String requireText(String value, String field) {
        String normalized = trimToNull(value);
        if (normalized == null || normalized.length() < 3) {
            throw new BusinessException(field + " is required");
        }
        return normalized;
    }

    private String textOr(String value, String fallback) {
        String normalized = trimToNull(value);
        return normalized == null ? fallback : normalized;
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String value(Object value) {
        return value == null ? "" : value.toString();
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private List<UUID> supplierIds(CreateRequest request, String supplierModel) {
        List<UUID> ids = request.supplierIds() == null ? List.of() : request.supplierIds();
        java.util.LinkedHashSet<UUID> ordered = new java.util.LinkedHashSet<>(ids);
        ordered.add(request.supplierId());
        if ("MULTI_SUPPLIER".equals(supplierModel) && ordered.size() < 2) {
            throw new BusinessException("Multi-supplier frameworks require at least two supplierIds");
        }
        return ordered.stream().toList();
    }

    private List<UUID> supplierIds(ProcurementFramework framework) {
        if (framework.getSupplierIds() == null || framework.getSupplierIds().isBlank()) {
            return List.of(framework.getSupplierId());
        }
        List<UUID> ids = java.util.Arrays.stream(framework.getSupplierIds().split(","))
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .map(UUID::fromString)
            .collect(Collectors.toCollection(java.util.ArrayList::new));
        if (!ids.contains(framework.getSupplierId())) {
            ids.add(0, framework.getSupplierId());
        }
        return ids;
    }

    private String serializeSupplierIds(List<UUID> supplierIds) {
        return supplierIds.stream().map(UUID::toString).collect(Collectors.joining(","));
    }

}
