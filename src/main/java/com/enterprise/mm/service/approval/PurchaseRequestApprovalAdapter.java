package com.enterprise.mm.service.approval;

import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.entity.ApprovalFlowStep;
import com.enterprise.mm.enums.PurchaseRequestStatus;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalDetailItem;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalTaskDecisionRequest;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.PurchaseRequestItemRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.service.AuditService;
import com.enterprise.mm.service.BudgetItemService;
import com.enterprise.mm.service.IntegrationEventService;
import com.enterprise.mm.service.SegregationOfDutyRuleService;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PurchaseRequestApprovalAdapter implements ApprovalModuleAdapter {
    public static final String MODULE_KEY = "PURCHASE_REQUEST";

    private final PurchaseRequestRepository requestRepository;
    private final PurchaseRequestItemRepository itemRepository;
    private final MaterialRepository materialRepository;
    private final IntegrationEventService integrationEventService;
    private final SegregationOfDutyRuleService segregationOfDutyRuleService;
    private final AuditService auditService;
    private final BudgetItemService budgetItemService;



    @Override
    public String moduleKey() {
        return MODULE_KEY;
    }

    @Override
    public UUID companyId(UUID entityId) {
        return find(entityId).getCompanyId();
    }

    @Override
    public UUID departmentId(UUID entityId) {
        return find(entityId).getDepartmentId();
    }

    @Override
    public boolean usesRequestType() {
        return true;
    }

    @Override
    public RequestType requestType(UUID entityId) {
        PurchaseRequest request = find(entityId);
        return request.getRequestType() == null ? RequestType.GOODS : request.getRequestType();
    }

    @Override
    public String displayName(UUID entityId) {
        PurchaseRequest request = find(entityId);
        return request.getName();
    }

    @Override
    public List<ApprovalDetailItem> documentLines(UUID entityId) {
        find(entityId);
        return itemRepository.findByTenantIdAndPurchaseRequest_IdAndIsDeletedFalse(TenantContext.currentTenant(), entityId)
            .stream()
            .map(this::documentLine)
            .toList();
    }

    private ApprovalDetailItem documentLine(PurchaseRequestItem item) {
        Material material = item.getMaterialId() == null
            ? null
            : materialRepository.findByIdAndIsDeletedFalse(item.getMaterialId()).orElse(null);
        String label;
        if (material != null) {
            label = material.getCode() == null || material.getCode().isBlank()
                ? material.getName()
                : material.getCode() + " — " + material.getName();
        } else if (item.getServiceDescription() != null && !item.getServiceDescription().isBlank()) {
            label = item.getServiceDescription();
        } else {
            label = item.getName() == null || item.getName().isBlank() ? "Позиция" : item.getName();
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("lineType", item.getLineType() == null ? null : item.getLineType().name());
        values.put("materialId", item.getMaterialId());
        values.put("materialCode", material == null ? null : material.getCode());
        values.put("materialName", material == null ? null : material.getName());
        values.put("quantity", item.getQuantity());
        values.put("estimatedPrice", item.getEstimatedPrice());
        values.put("unit", item.getUnit());
        values.put("serviceDescription", item.getServiceDescription());
        values.put("serviceCategory", item.getServiceCategory());
        values.entrySet().removeIf(entry -> entry.getValue() == null);
        return new ApprovalDetailItem(
            item.getId() == null ? null : item.getId().toString(),
            label,
            values
        );
    }

    @Override
    public void validateCanSubmit(UUID entityId) {
        PurchaseRequest request = find(entityId);
        if (!PurchaseRequestStatus.DRAFT.name().equals(request.getStatus())
                && !PurchaseRequestStatus.RETURNED.name().equals(request.getStatus())) {
            throw new BusinessException("Purchase request must be DRAFT or RETURNED, current status is " + request.getStatus());
        }
        if (itemRepository.findByTenantIdAndPurchaseRequest_IdAndIsDeletedFalse(TenantContext.currentTenant(), entityId).isEmpty()) {
            throw new BusinessException("Purchase request must contain at least one item");
        }
        if (request.getTotalAmount() != null && request.getTotalAmount().signum() < 0) {
            throw new BusinessException("Budget check failed: total amount cannot be negative");
        }
        itemRepository.findByTenantIdAndPurchaseRequest_IdAndIsDeletedFalse(TenantContext.currentTenant(), entityId)
            .forEach(item -> {
                var result = budgetItemService.checkAvailability(
                    item.getBudgetItemId(),
                    amount(item.getEstimatedPrice()).multiply(amount(item.getQuantity()))
                );
                if (!result.approved()) {
                    throw new BusinessException("Budget check failed for line " + item.getCode() + ": " + result.message());
                }
            });
    }

    @Override
    public void markSubmitted(UUID entityId) {
        PurchaseRequest request = find(entityId);
        if (PurchaseRequestStatus.APPROVAL_PENDING.name().equals(request.getStatus())) {
            return;
        }
        request.setStatus(PurchaseRequestStatus.APPROVAL_PENDING.name());
        requestRepository.save(request);
        integrationEventService.emit("BUDGETING.PR_SUBMITTED", "BUDGETING", request.getId(), Map.of(
            "purchaseRequestId", request.getId(),
            "amount", amount(request),
            "currency", currency(request)
        ));
        auditService.log("PurchaseRequest", entityId, "SUBMIT", request.getCode(), null, null);
    }

    @Override
    public void beforeApproveTask(UUID entityId, ApprovalFlowStep step, ApprovalTaskDecisionRequest request) {
        if (segregationOfDutyRuleService != null) {
            segregationOfDutyRuleService.validateApproval("PurchaseRequest", find(entityId), request == null ? null : request.comment());
        }
    }

    @Override
    public void markApproved(UUID entityId, String comment) {
        PurchaseRequest request = find(entityId);
        if (PurchaseRequestStatus.APPROVED.name().equals(request.getStatus())) {
            return;
        }
        if (!PurchaseRequestStatus.APPROVAL_PENDING.name().equals(request.getStatus())) {
            throw new BusinessException("Purchase request must be APPROVAL_PENDING, current status is " + request.getStatus());
        }
        if (segregationOfDutyRuleService != null) {
            segregationOfDutyRuleService.validateApproval("PurchaseRequest", request, comment);
        }
        request.setStatus(PurchaseRequestStatus.APPROVED.name());
        requestRepository.save(request);
        itemRepository.findByTenantIdAndPurchaseRequest_IdAndIsDeletedFalse(TenantContext.currentTenant(), entityId)
            .forEach(item -> budgetItemService.reserveIdempotent(
                item.getBudgetItemId(),
                amount(item.getEstimatedPrice()).multiply(amount(item.getQuantity())),
                "PurchaseRequest " + request.getCode(),
                "PurchaseRequest",
                request.getId(),
                item.getId(),
                "PurchaseRequestItem:" + item.getId(),
                "RESERVE_APPROVED"
            ));
        integrationEventService.emit("BUDGETING.PR_APPROVED", "BUDGETING", request.getId(), Map.of(
            "purchaseRequestId", request.getId(),
            "amount", amount(request),
            "currency", currency(request)
        ));
        auditService.log("PurchaseRequest", entityId, "APPROVE", comment, null, null);
    }

    @Override
    public void markRejected(UUID entityId, String comment) {
        PurchaseRequest request = find(entityId);
        if (!PurchaseRequestStatus.APPROVAL_PENDING.name().equals(request.getStatus())) {
            throw new BusinessException("Purchase request must be APPROVAL_PENDING, current status is " + request.getStatus());
        }
        request.setStatus(PurchaseRequestStatus.REJECTED.name());
        requestRepository.save(request);
        auditService.log("PurchaseRequest", entityId, "REJECT", comment, null, null);
    }

    @Override
    public void onReturned(UUID entityId, String comment) {
        PurchaseRequest request = find(entityId);
        if (PurchaseRequestStatus.RETURNED.name().equals(request.getStatus())) {
            return;
        }
        if (!PurchaseRequestStatus.APPROVAL_PENDING.name().equals(request.getStatus())) {
            throw new BusinessException("Purchase request cannot be returned from status " + request.getStatus());
        }
        request.setStatus(PurchaseRequestStatus.RETURNED.name());
        requestRepository.save(request);
        auditService.log("PurchaseRequest", entityId, "RETURN", comment, null, null);
    }

    private PurchaseRequest find(UUID id) {
        return requestRepository.findByIdAndIsDeletedFalse(id)
            .filter(request -> TenantContext.currentTenant().equals(request.getTenantId()))
            .orElseThrow(() -> new NotFoundException("PurchaseRequest not found: " + id));
    }

    private BigDecimal amount(PurchaseRequest request) {
        return request.getTotalAmount() == null ? BigDecimal.ZERO : request.getTotalAmount();
    }

    private BigDecimal amount(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String currency(PurchaseRequest request) {
        return request.getCurrencyCode() == null || request.getCurrencyCode().isBlank() ? "UZS" : request.getCurrencyCode();
    }
}
