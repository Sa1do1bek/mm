package com.enterprise.mm.service.approval;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ApprovalFlowStep;
import com.enterprise.mm.entity.ProcurementAwardOutcome;
import com.enterprise.mm.entity.ProcurementFramework;
import com.enterprise.mm.entity.ProcurementProcedure;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.enums.ProcurementAwardSourceType;
import com.enterprise.mm.enums.ProcurementExecutionPath;
import com.enterprise.mm.enums.ProcurementFrameworkStatus;
import com.enterprise.mm.enums.ProcurementMethodType;
import com.enterprise.mm.enums.ProcurementProcedureStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalDetailItem;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalTaskDecisionRequest;
import com.enterprise.mm.repository.ProcurementAwardOutcomeRepository;
import com.enterprise.mm.repository.ProcurementFrameworkRepository;
import com.enterprise.mm.repository.ProcurementProcedureRepository;
import com.enterprise.mm.repository.PurchaseRequestItemRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.service.AuditService;
import com.enterprise.mm.service.ProcurementAwardOutcomeLineService;
import com.enterprise.mm.service.ProcurementMethodCapabilityService;
import com.enterprise.mm.service.ProcurementMethodPolicy;
import com.enterprise.mm.service.SegregationOfDutyRuleService;
import com.enterprise.mm.service.SupplierEligibilityService;
import com.enterprise.mm.util.BusinessNameGenerator;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProcurementFrameworkApprovalAdapter implements ApprovalModuleAdapter {
    public static final String MODULE_KEY = "PROCUREMENT_FRAMEWORK";

    private final ProcurementFrameworkRepository repository;
    private final ProcurementProcedureRepository procedureRepository;
    private final ProcurementAwardOutcomeRepository outcomeRepository;
    private final ProcurementAwardOutcomeLineService outcomeLineService;
    private final SupplierRepository supplierRepository;
    private final PurchaseRequestRepository purchaseRequestRepository;
    private final PurchaseRequestItemRepository purchaseRequestItemRepository;
    private final ProcurementMethodPolicy methodPolicy;
    private final ProcurementMethodCapabilityService capabilityService;
    private final AuditService auditService;
    private final SegregationOfDutyRuleService segregationOfDutyRuleService;
    private final SupplierEligibilityService supplierEligibilityService;

    @Override
    public String moduleKey() {
        return MODULE_KEY;
    }

    @Override
    public UUID companyId(UUID entityId) {
        return find(entityId).getCompanyId();
    }

    @Override
    public String displayName(UUID entityId) {
        ProcurementFramework framework = find(entityId);
        return framework.getAwardReference() == null ? framework.getName() : framework.getAwardReference();
    }

    @Override
    public List<ApprovalDetailItem> documentLines(UUID entityId) {
        ProcurementFramework framework = find(entityId);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("supplierId", framework.getSupplierId());
        values.put("supplierModel", framework.getSupplierModel());
        values.put("allocationBasis", framework.getAllocationBasis());
        values.put("validFrom", framework.getValidFrom());
        values.put("validTo", framework.getValidTo());
        values.put("ceilingAmount", framework.getCeilingAmount());
        values.put("currencyCode", framework.getCurrencyCode());
        values.put("procedureId", framework.getProcedureId());
        values.entrySet().removeIf(entry -> entry.getValue() == null);
        return List.of(new ApprovalDetailItem(framework.getId().toString(), displayName(entityId), values));
    }

    @Override
    public void validateCanSubmit(UUID entityId) {
        ProcurementFramework framework = find(entityId);
        if (framework.frameworkStatus() != ProcurementFrameworkStatus.DRAFT) {
            throw new BusinessException("Framework must be DRAFT before approval submission");
        }
        if (framework.getSupplierId() == null) {
            throw new BusinessException("supplierId is required for framework approval");
        }
        if (framework.getCeilingAmount() == null || framework.getCeilingAmount().signum() <= 0) {
            throw new BusinessException("ceilingAmount must be greater than zero");
        }
    }

    @Override
    public void markSubmitted(UUID entityId) {
        ProcurementFramework framework = find(entityId);
        if (framework.frameworkStatus() == ProcurementFrameworkStatus.APPROVAL_PENDING) {
            return;
        }
        framework.setFrameworkStatus(ProcurementFrameworkStatus.APPROVAL_PENDING);
        repository.save(framework);
        auditService.log("ProcurementFramework", entityId, "SUBMIT", framework.getAwardReference(), null, null);
    }

    @Override
    public void beforeApproveTask(UUID entityId, ApprovalFlowStep step, ApprovalTaskDecisionRequest request) {
        if (segregationOfDutyRuleService != null) {
            segregationOfDutyRuleService.validateApproval("ProcurementFramework", find(entityId), request == null ? null : request.comment());
        }
    }

    @Override
    public void markApproved(UUID entityId, String comment) {
        ProcurementFramework framework = find(entityId);
        if (framework.frameworkStatus() == ProcurementFrameworkStatus.APPROVED) {
            return;
        }
        if (framework.frameworkStatus() != ProcurementFrameworkStatus.APPROVAL_PENDING) {
            throw new BusinessException("Framework must be APPROVAL_PENDING before approval");
        }
        framework.setFrameworkStatus(ProcurementFrameworkStatus.APPROVED);
        framework.setApprovalReference(comment);
        framework.setApprovedAt(LocalDateTime.now());
        framework.setApprovedBy(actor());
        ProcurementFramework saved = repository.save(framework);
        ProcurementProcedure procedure = procedureRepository.lockScopedById(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), saved.getProcedureId())
            .orElseThrow(() -> new NotFoundException("ProcurementProcedure not found: " + saved.getProcedureId()));
        validateBeforeAward(saved, procedure);
        procedure.setStatus(ProcurementProcedureStatus.FRAMEWORK_AWARDED.name());
        procedure.setContractId(saved.getContractId());
        procedure.setAwardedSupplierId(saved.getSupplierId());
        procedure.setAwardReference(saved.getAwardReference());
        procedure.setFinalizedAmount(saved.getCeilingAmount());
        procedure.setFinalizedCurrencyCode(saved.getCurrencyCode());
        procedureRepository.save(procedure);
        recordAwardOutcome(saved, procedure);
        auditService.log("ProcurementFramework", entityId, "APPROVE", comment, null, null);
    }

    @Override
    public void markRejected(UUID entityId, String comment) {
        ProcurementFramework framework = find(entityId);
        if (framework.frameworkStatus() != ProcurementFrameworkStatus.APPROVAL_PENDING) {
            throw new BusinessException("Framework must be APPROVAL_PENDING before rejection");
        }
        framework.setFrameworkStatus(ProcurementFrameworkStatus.DRAFT);
        repository.save(framework);
        auditService.log("ProcurementFramework", entityId, "REJECT", comment, null, null);
    }

    @Override
    public void onReturned(UUID entityId, String comment) {
        markRejected(entityId, comment);
    }

    private ProcurementFramework find(UUID id) {
        return repository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), id)
            .orElseThrow(() -> new NotFoundException("ProcurementFramework not found: " + id));
    }

    private void validateBeforeAward(ProcurementFramework framework, ProcurementProcedure procedure) {
        if (!framework.getProcedureId().equals(procedure.getId())
            || !framework.getPurchaseRequestId().equals(procedure.getPurchaseRequestId())
            || !framework.getMethodDecisionId().equals(procedure.getMethodDecisionId())) {
            throw new BusinessException("Framework source lineage changed during approval; restart approval");
        }
        if (framework.getValidFrom() == null
            || (framework.getValidTo() != null && framework.getValidTo().isBefore(framework.getValidFrom()))) {
            throw new BusinessException("Framework validity dates are invalid");
        }
        if (framework.getValidTo() != null && java.time.LocalDate.now().isAfter(framework.getValidTo())) {
            throw new BusinessException("Expired framework cannot be approved");
        }
        Supplier supplier = supplierRepository.findByIdAndIsDeletedFalse(framework.getSupplierId())
            .filter(this::scoped)
            .orElseThrow(() -> new BusinessException("Supplier not found: " + framework.getSupplierId()));
        supplierEligibilityService.requireNewBusinessAllowed(supplier.getId(), null, "FRAMEWORK_AWARD");
        ProcurementMethodType method = methodPolicy.require(procedure.getMethod());
        if (method.executionPath() != ProcurementExecutionPath.FRAMEWORK_AGREEMENT) {
            throw new BusinessException("Procurement procedure does not use FRAMEWORK_AGREEMENT execution");
        }
        String requestType = purchaseRequestRepository.findByIdAndIsDeletedFalse(framework.getPurchaseRequestId())
            .filter(this::scoped)
            .map(PurchaseRequest::getRequestType)
            .map(Enum::name)
            .orElse(null);
        List<String> categories = purchaseRequestItemRepository
            .findByTenantIdAndPurchaseRequest_IdAndIsDeletedFalse(TenantContext.currentTenant(), framework.getPurchaseRequestId())
            .stream()
            .filter(this::scoped)
            .map(item -> item.getMaterial() == null
                ? item.getServiceCategory()
                : item.getMaterial().getMaterialGroupId() == null ? item.getServiceCategory() : item.getMaterial().getMaterialGroupId().toString())
            .filter(value -> value != null && !value.isBlank())
            .distinct()
            .toList();
        if (categories.isEmpty()) {
            capabilityService.requireAvailableForCompany(method.name(), framework.getCeilingAmount(), framework.getCurrencyCode(),
                requestType, null, procedure.getCompanyId());
            return;
        }
        for (String category : categories) {
            capabilityService.requireAvailableForCompany(method.name(), framework.getCeilingAmount(), framework.getCurrencyCode(),
                requestType, category, procedure.getCompanyId());
        }
    }

    private void recordAwardOutcome(ProcurementFramework framework, ProcurementProcedure procedure) {
        String idempotencyKey = framework.getContractId() == null ? framework.getId().toString() : framework.getContractId().toString();
        ProcurementAwardOutcome existing = outcomeRepository
            .findByTenantIdAndCompanyIdAndProcurementProcedureIdAndSourceTypeAndIdempotencyKeyAndIsDeletedFalse(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                procedure.getId(),
                ProcurementAwardSourceType.FRAMEWORK_AWARD,
                idempotencyKey
            ).orElse(null);
        if (existing != null) {
            outcomeLineService.recordLines(existing);
            return;
        }
        ProcurementAwardOutcome outcome = new ProcurementAwardOutcome();
        outcome.setCode("PAO-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT));
        outcome.setName(BusinessNameGenerator.workflowDocumentName("ProcurementAwardOutcome", framework.getName()));
        outcome.setStatus("FINALIZED");
        outcome.setProcurementProcedureId(procedure.getId());
        outcome.setSourceType(ProcurementAwardSourceType.FRAMEWORK_AWARD);
        outcome.setSourceId(framework.getContractId());
        outcome.setAwardedSupplierId(framework.getSupplierId());
        outcome.setContractId(framework.getContractId());
        outcome.setAwardAmount(framework.getCeilingAmount());
        outcome.setCurrencyCode(framework.getCurrencyCode());
        outcome.setAwardReference(framework.getAwardReference());
        outcome.setIdempotencyKey(idempotencyKey);
        outcome.setFinalizedAt(LocalDateTime.now());
        ProcurementAwardOutcome saved = outcomeRepository.save(outcome);
        outcomeLineService.recordLines(saved);
    }

    private boolean scoped(com.enterprise.mm.common.BusinessEntity entity) {
        return entity != null
            && !entity.isDeleted()
            && TenantContext.currentTenant().equals(entity.getTenantId())
            && CompanyContext.currentCompany().equals(entity.getCompanyId());
    }

    private String actor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || authentication.getName() == null ? "system" : authentication.getName();
    }
}
