package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Contract;
import com.enterprise.mm.entity.ContractSpecification;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.RFQItem;
import com.enterprise.mm.entity.RFQPurchaseRequestSource;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierQuotation;
import com.enterprise.mm.entity.SupplierQuotationItem;
import com.enterprise.mm.enums.ContractStatus;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.ProcurementAwardSourceType;
import com.enterprise.mm.enums.RfqSourceAllocationStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.RfqAggregationDtos.AwardContractResponse;
import com.enterprise.mm.repository.ContractRepository;
import com.enterprise.mm.repository.ContractSpecificationRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.PurchaseRequestItemRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.RFQItemRepository;
import com.enterprise.mm.repository.RFQPurchaseRequestSourceRepository;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.repository.SupplierQuotationItemRepository;
import com.enterprise.mm.repository.SupplierQuotationRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.service.ProcurementAwardFinalizationOrchestrator.AwardFinalizationCommand;
import com.enterprise.mm.util.BusinessNameGenerator;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RfqAwardService {
    private final SupplierQuotationRepository quotationRepository;
    private final SupplierQuotationItemRepository quotationItemRepository;
    private final RFQRepository rfqRepository;
    private final RFQItemRepository rfqItemRepository;
    private final RFQPurchaseRequestSourceRepository sourceRepository;
    private final SupplierRepository supplierRepository;
    private final ContractRepository contractRepository;
    private final ContractSpecificationRepository contractSpecificationRepository;
    private final QuotationSelectionValidationService selectionValidationService;
    private final RfqAggregationUnitResolver unitResolver;
    private final IntegrationEventService integrationEventService;
    private final AuditService auditService;
    private final MaterialRepository materialRepository;
    private final PurchaseRequestRepository purchaseRequestRepository;
    private final PurchaseRequestItemRepository purchaseRequestItemRepository;
    private final SupplierAccreditationService supplierAccreditationService;
    private final SupplierEligibilityService supplierEligibilityService;
    private final ProcurementAwardFinalizationOrchestrator awardFinalizationOrchestrator;

    @Transactional
    public AwardContractResponse award(UUID quotationId, String reason) {
        SupplierQuotation quotation = quotationRepository.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), quotationId)
            .filter(item -> CompanyContext.currentCompany().equals(item.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("Quotation not found: " + quotationId));
        if (quotation.getThreadId() != null && !quotation.isCurrentRevision()) {
            throw new BusinessException("Superseded quotation revision cannot be awarded");
        }
        RFQ rfq = rfqRepository.findByIdForUpdate(quotation.getRfqId())
            .filter(item -> TenantContext.currentTenant().equals(item.getTenantId()))
            .filter(item -> CompanyContext.currentCompany().equals(item.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("RFQ not found: " + quotation.getRfqId()));
        if ("CANCELLED".equals(rfq.getStatus()) || "AWARDED".equals(rfq.getStatus())) {
            return existingAwardOrReject(rfq, quotation);
        }
        Contract existingForRfq = contractRepository
            .findByTenantIdAndCompanyIdAndSourceRfqIdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany(), rfq.getId())
            .orElse(null);
        if (existingForRfq != null) {
            if (quotation.getId().equals(existingForRfq.getSourceQuotationId())) {
                finalizeContractAward(rfq, quotation, existingForRfq);
                return awardResponse(rfq, quotation, existingForRfq);
            }
            throw new BusinessException("RFQ already has a source contract", "rfq.award.contract_exists", rfq.getId());
        }
        Supplier supplier = supplierRepository.findByIdAndIsDeletedFalse(quotation.getSupplierId())
            .filter(item -> TenantContext.currentTenant().equals(item.getTenantId()))
            .filter(item -> CompanyContext.currentCompany().equals(item.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("Supplier not found: " + quotation.getSupplierId()));
        if ("BLOCKED".equals(supplier.getStatus())) {
            throw new BusinessException("Blocked supplier cannot be selected");
        }
        List<SupplierQuotation> quotations = currentSubmitted(rfq.getId())
            .stream()
            .filter(item -> CompanyContext.currentCompany().equals(item.getCompanyId()))
            .toList();
        try {
            selectionValidationService.validateSelection(quotation, rfq, quotations, reason);
        } catch (BusinessException ex) {
            throw new BusinessException(ex.getMessage(), "rfq.award.tender_not_ready");
        }
        List<RFQItem> rfqItems = rfqItemRepository.findByTenantIdAndRfqIdOrderById(TenantContext.currentTenant(), rfq.getId());
        ensureAuditablePurchaseRequestLineage(rfq, rfqItems);
        List<SupplierQuotationItem> quoteItems = quotationItemRepository
            .findByTenantIdAndQuotation_IdAndIsDeletedFalse(TenantContext.currentTenant(), quotation.getId())
            .stream()
            .filter(item -> CompanyContext.currentCompany().equals(item.getCompanyId()))
            .toList();
        validateExactCoverage(rfqItems, quoteItems);
        List<SupplierAccreditationDemandResolver.DemandLine> demandLines = rfqItems.stream()
            .map(item -> new SupplierAccreditationDemandResolver.DemandLine(
                item.getLineType(),
                item.getMaterialId()))
            .toList();
        requireNewBusinessAllowed(
            supplier.getId(),
            SupplierAccreditationDemandResolver.resolve(
                demandLines,
                rfq.getCompanyId(),
                materialRepository),
            "RFQ_AWARD");

        quotations.forEach(candidate -> {
            candidate.setSelected(candidate.getId().equals(quotation.getId()));
            candidate.setStatus(candidate.getId().equals(quotation.getId()) ? "SELECTED" : "REJECTED");
            if (candidate.getId().equals(quotation.getId())) {
                candidate.setSelectionReason(reason);
            }
            quotationRepository.save(candidate);
        });

        Contract contract = new Contract();
        contract.setCode("CON-" + quotation.getCode());
        contract.setName(BusinessNameGenerator.workflowDocumentName("Contract", rfq.getName()));
        contract.setDescription(rfq.getDescription());
        contract.setStatus(ContractStatus.DRAFT.name());
        contract.setSupplierId(supplier.getId());
        contract.setContractNumber("CON-" + quotation.getCode());
        contract.setLimitAmount(amount(quotation.getTotalAmount()));
        contract.setUsedAmount(BigDecimal.ZERO);
        contract.setServiceUsedAmount(BigDecimal.ZERO);
        contract.setCurrencyCode(quotation.getCurrencyCode());
        contract.setSourceRfqId(rfq.getId());
        contract.setSourceQuotationId(quotation.getId());
        contract = contractRepository.save(contract);

        Map<UUID, RFQItem> rfqItemById = rfqItems.stream()
            .collect(Collectors.toMap(RFQItem::getId, item -> item));
        for (SupplierQuotationItem quoteItem : quoteItems) {
            RFQItem rfqItem = rfqItemById.get(quoteItem.getRfqItemId());
            ContractSpecification spec = new ContractSpecification();
            spec.setCode("CS-" + quoteItem.getCode());
            spec.setName(BusinessNameGenerator.workflowDocumentName("ContractSpecification", contract.getName()));
            spec.setStatus("ACTIVE");
            spec.setContractId(contract.getId());
            ProcurementLineType lineType = rfqItem.getLineType() == null ? ProcurementLineType.MATERIAL : rfqItem.getLineType();
            spec.setLineType(lineType);
            spec.setMaterialId(lineType == ProcurementLineType.MATERIAL ? quoteItem.getMaterialId() : null);
            spec.setMaterialTypeSnapshot(quoteItem.getMaterialTypeSnapshot() == null
                ? rfqItem.getMaterialTypeSnapshot()
                : quoteItem.getMaterialTypeSnapshot());
            spec.setServiceDescription(quoteItem.getServiceDescription() == null ? rfqItem.getServiceDescription() : quoteItem.getServiceDescription());
            spec.setServiceCategory(quoteItem.getServiceCategory() == null ? rfqItem.getServiceCategory() : quoteItem.getServiceCategory());
            spec.setUnit(rfqItem.getUnit());
            spec.setPeriod(rfqItem.getPeriod());
            spec.setStage(rfqItem.getStage());
            spec.setQuantityLimit(amount(quoteItem.getQuantity()));
            spec.setPrice(amount(quoteItem.getUnitPrice()));
            spec.setSourceRfqItemId(rfqItem.getId());
            spec.setSourceQuotationItemId(quoteItem.getId());
            contractSpecificationRepository.save(spec);
        }
        rfq.setStatus("AWARDED");
        rfqRepository.save(rfq);
        finalizeContractAward(rfq, quotation, contract);
        Map<String, Object> awardEvent = new LinkedHashMap<>();
        awardEvent.put("rfqId", rfq.getId());
        awardEvent.put("rfqCode", Objects.toString(rfq.getCode(), ""));
        awardEvent.put("rfqName", Objects.toString(rfq.getName(), ""));
        awardEvent.put("quotationId", quotation.getId());
        awardEvent.put("quotationCode", Objects.toString(quotation.getCode(), ""));
        awardEvent.put("contractId", contract.getId());
        awardEvent.put("contractCode", Objects.toString(contract.getCode(), ""));
        awardEvent.put("supplierId", supplier.getId());
        awardEvent.put("supplierName", Objects.toString(supplier.getName(), ""));
        awardEvent.put("totalAmount", amount(quotation.getTotalAmount()));
        awardEvent.put("currency", Objects.toString(quotation.getCurrencyCode(), ""));
        awardEvent.put("awardedAt", LocalDateTime.now().toString());
        integrationEventService.emitInCurrentTransaction(
            "RFQ_CONTRACT_AWARDED", "RFQ", rfq.getId(), awardEvent);
        auditService.logInCurrentTransaction("RFQ", rfq.getId(), "AWARD_CONTRACT", contract.getId().toString(), null, null);
        auditService.logInCurrentTransaction("Contract", contract.getId(), "CREATE_FROM_RFQ", rfq.getId().toString(), null, null);
        return awardResponse(rfq, quotation, contract);
    }

    private List<SupplierQuotation> currentSubmitted(UUID rfqId) {
        List<String> statuses = List.of("ACTIVE", "SUBMITTED", "SELECTED", "REJECTED");
        List<SupplierQuotation> current = quotationRepository.findCurrentSubmittedByRfq(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), rfqId, statuses
        );
        if (!current.isEmpty()) {
            return current;
        }
        return quotationRepository
            .findByTenantIdAndRfq_IdAndIsDeletedFalseOrderByTotalAmountAsc(
                TenantContext.currentTenant(), rfqId
            ).stream()
            .filter(value -> value.getThreadId() == null)
            .filter(value -> value.getStatus() == null || statuses.contains(value.getStatus()))
            .toList();
    }

    private void ensureAuditablePurchaseRequestLineage(RFQ rfq, List<RFQItem> rfqItems) {
        List<RFQPurchaseRequestSource> activeSources = sourceRepository
            .findByTenantIdAndCompanyIdAndRfqIdAndStatusAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), rfq.getId(),
                RfqSourceAllocationStatus.ACTIVE.name());
        if (!activeSources.isEmpty()) {
            return;
        }
        if (rfq.isAggregatedTender()) {
            throw lineageError(rfq, "Aggregated RFQ has no active source allocations");
        }
        if (rfq.getPurchaseRequestId() == null) {
            throw lineageError(rfq, "RFQ has no recoverable purchase request lineage");
        }
        PurchaseRequest purchaseRequest = purchaseRequestRepository.findByIdAndIsDeletedFalse(rfq.getPurchaseRequestId())
            .filter(item -> TenantContext.currentTenant().equals(item.getTenantId()))
            .filter(item -> CompanyContext.currentCompany().equals(item.getCompanyId()))
            .orElseThrow(() -> lineageError(rfq, "RFQ purchase request lineage cannot be resolved"));
        List<PurchaseRequestItem> unmatched = new ArrayList<>(purchaseRequestItemRepository
            .findByTenantIdAndPurchaseRequest_IdAndIsDeletedFalse(TenantContext.currentTenant(), purchaseRequest.getId())
            .stream()
            .filter(item -> CompanyContext.currentCompany().equals(item.getCompanyId()))
            .toList());
        for (RFQItem rfqItem : rfqItems) {
            List<PurchaseRequestItem> matches = unmatched.stream()
                .filter(item -> sameDemandLine(rfqItem, item))
                .toList();
            if (matches.size() != 1) {
                throw lineageError(rfq, "RFQ line cannot be matched uniquely to its purchase request line");
            }
            PurchaseRequestItem requestItem = matches.get(0);
            unmatched.remove(requestItem);
            sourceRepository.save(recoveredSource(rfq, rfqItem, purchaseRequest, requestItem));
        }
        rfq.setSourcePurchaseRequestCount(1);
        rfq.setSourceItemCount(rfqItems.size());
        rfqRepository.save(rfq);
    }

    private boolean sameDemandLine(RFQItem rfqItem, PurchaseRequestItem requestItem) {
        ProcurementLineType rfqType = rfqItem.getLineType() == null ? ProcurementLineType.MATERIAL : rfqItem.getLineType();
        ProcurementLineType requestType = requestItem.getLineType() == null ? ProcurementLineType.MATERIAL : requestItem.getLineType();
        return rfqType == requestType
            && Objects.equals(rfqItem.getMaterialId(), requestItem.getMaterialId())
            && Objects.equals(rfqItem.getServiceRequestId(), requestItem.getServiceRequestId())
            && Objects.equals(rfqItem.getServiceDescription(), requestItem.getServiceDescription())
            && amount(rfqItem.getQuantity()).compareTo(amount(requestItem.getQuantity())) == 0
            && unitResolver.requiredKey(rfqItem.getUnit(), rfqItem.getId())
                .equals(unitResolver.requiredKey(requestItem.getUnit(), requestItem.getId()));
    }

    private RFQPurchaseRequestSource recoveredSource(
        RFQ rfq, RFQItem rfqItem, PurchaseRequest purchaseRequest, PurchaseRequestItem requestItem
    ) {
        RFQPurchaseRequestSource source = new RFQPurchaseRequestSource();
        source.setCode("RFQSRC-" + UUID.randomUUID().toString().substring(0, 8));
        source.setName(BusinessDisplayNamePolicy.requireHumanName("PurchaseRequest", purchaseRequest.getName(), purchaseRequest.getCode()) + " allocation");
        source.setStatus(RfqSourceAllocationStatus.ACTIVE.name());
        source.setCompanyId(CompanyContext.currentCompany());
        source.setRfqId(rfq.getId());
        source.setRfqItemId(rfqItem.getId());
        source.setPurchaseRequestId(purchaseRequest.getId());
        source.setPurchaseRequestItemId(requestItem.getId());
        source.setAllocatedQuantity(requestItem.getQuantity());
        source.setSourceRequiredDate(requestItem.fulfillmentDate(purchaseRequest));
        source.setSourceDepartmentId(purchaseRequest.getDepartmentId());
        source.setSourceBudgetItemId(requestItem.getBudgetItemId());
        source.setSourceCurrencyCode(purchaseRequest.getCurrencyCode());
        source.setSourceUnit(requestItem.getUnit());
        source.setAllocatedAt(LocalDateTime.now());
        return source;
    }

    private BusinessException lineageError(RFQ rfq, String message) {
        return new BusinessException(message, "rfq.award.lineage_unrecoverable", rfq.getId());
    }

    private void requireNewBusinessAllowed(UUID supplierId, com.enterprise.mm.payload.SupplierEligibilityDtos.AccreditationDemand demand, String operation) {
        if (supplierEligibilityService != null) {
            supplierEligibilityService.requireNewBusinessAllowed(supplierId, demand, operation);
            return;
        }
        supplierAccreditationService.assertSupplierEligibleForDemand(supplierId, demand, "supplier quotation award");
    }

    private AwardContractResponse existingAwardOrReject(RFQ rfq, SupplierQuotation quotation) {
        Contract existing = contractRepository
            .findByTenantIdAndCompanyIdAndSourceRfqIdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany(), rfq.getId())
            .orElseThrow(() -> new BusinessException("RFQ cannot be awarded in status " + rfq.getStatus()));
        if (!quotation.getId().equals(existing.getSourceQuotationId())) {
            throw new BusinessException("RFQ already has a source contract", "rfq.award.contract_exists", rfq.getId());
        }
        finalizeContractAward(rfq, quotation, existing);
        return awardResponse(rfq, quotation, existing);
    }

    private void finalizeContractAward(RFQ rfq, SupplierQuotation quotation, Contract contract) {
        if (rfq.getProcurementProcedureId() == null) {
            return;
        }
        awardFinalizationOrchestrator.finalizeAward(new AwardFinalizationCommand(
            rfq.getProcurementProcedureId(),
            ProcurementAwardSourceType.RFQ_CONTRACT,
            quotation.getId(),
            quotation.getSupplierId(),
            null,
            contract.getId(),
            quotation.getTotalAmount(),
            quotation.getCurrencyCode(),
            quotation.getCode(),
            null,
            null,
            null
        ));
    }

    private void validateExactCoverage(List<RFQItem> rfqItems, List<SupplierQuotationItem> quoteItems) {
        if (quoteItems.size() != rfqItems.size()) {
            throw new BusinessException("Quotation does not cover every RFQ item", "rfq.award.coverage_mismatch");
        }
        Map<UUID, RFQItem> rfqItemById = rfqItems.stream()
            .collect(Collectors.toMap(RFQItem::getId, item -> item, (left, right) -> left, LinkedHashMap::new));
        HashSet<UUID> seen = new HashSet<>();
        for (SupplierQuotationItem quoteItem : quoteItems) {
            if (quoteItem.getRfqItemId() == null || !seen.add(quoteItem.getRfqItemId())) {
                throw new BusinessException("Quotation item must reference exactly one RFQ item", "rfq.award.coverage_mismatch");
            }
            RFQItem rfqItem = rfqItemById.get(quoteItem.getRfqItemId());
            if (rfqItem == null
                || !Objects.equals(rfqItem.getMaterialId(), quoteItem.getMaterialId())
                || amount(rfqItem.getQuantity()).compareTo(amount(quoteItem.getQuantity())) != 0
                || !unitKey(rfqItem, quoteItem).equals(unitResolver.requiredKey(rfqItem.getUnit(), rfqItem.getId()))) {
                throw new BusinessException("Quotation item does not match RFQ item", "rfq.award.coverage_mismatch");
            }
        }
    }

    private String unitKey(RFQItem rfqItem, SupplierQuotationItem quoteItem) {
        String rfqUnitKey = unitResolver.requiredKey(rfqItem.getUnit(), rfqItem.getId());
        return unitResolver.normalizeOptional(quoteItem.getUnit())
            .map(unit -> unitResolver.requiredKey(unit, quoteItem.getId()))
            .orElse(rfqUnitKey);
    }

    private AwardContractResponse awardResponse(RFQ rfq, SupplierQuotation quotation, Contract contract) {
        int specificationCount = contractSpecificationRepository
            .findByTenantIdAndContractIdAndIsDeletedFalse(TenantContext.currentTenant(), contract.getId())
            .size();
        return new AwardContractResponse(
            rfq.getId(),
            quotation.getId(),
            quotation.getSupplierId(),
            contract.getId(),
            specificationCount);
    }

    private BigDecimal amount(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
