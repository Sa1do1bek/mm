package com.enterprise.mm.service.approval;

import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.ApprovalFlowStep;
import com.enterprise.mm.enums.PurchaseOrderStatus;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalDetailItem;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalTaskDecisionRequest;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.service.AuditService;
import com.enterprise.mm.service.SegregationOfDutyRuleService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PurchaseOrderApprovalAdapter implements ApprovalModuleAdapter {
    public static final String MODULE_KEY = "PROCUREMENT_PURCHASE_ORDER";

    private final PurchaseOrderRepository repository;
    private final PurchaseOrderItemRepository itemRepository;
    private final MaterialRepository materialRepository;
    private final AuditService auditService;
    private final SegregationOfDutyRuleService segregationOfDutyRuleService;


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
        PurchaseOrder order = find(entityId);
        return order.getPurchaseRequest() == null ? null : order.getPurchaseRequest().getDepartmentId();
    }

    @Override
    public boolean usesRequestType() {
        return true;
    }

    @Override
    public RequestType requestType(UUID entityId) {
        PurchaseOrder order = find(entityId);
        if (order.getRequestType() != null) {
            return order.getRequestType();
        }
        if (order.getPurchaseRequest() != null && order.getPurchaseRequest().getRequestType() != null) {
            return order.getPurchaseRequest().getRequestType();
        }
        return RequestType.GOODS;
    }

    @Override
    public String displayName(UUID entityId) {
        PurchaseOrder order = find(entityId);
        return order.getName();
    }

    @Override
    public List<ApprovalDetailItem> documentLines(UUID entityId) {
        find(entityId);
        return itemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(TenantContext.currentTenant(), entityId)
            .stream()
            .map(this::documentLine)
            .toList();
    }

    private ApprovalDetailItem documentLine(PurchaseOrderItem item) {
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
        values.put("unitPrice", item.getUnitPrice());
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
        PurchaseOrder order = find(entityId);
        if (!PurchaseOrderStatus.DRAFT.name().equals(order.getStatus())
                && !PurchaseOrderStatus.RETURNED.name().equals(order.getStatus())) {
            throw new BusinessException("Purchase order must be DRAFT or RETURNED, current status is " + order.getStatus());
        }
    }

    @Override
    public void markSubmitted(UUID entityId) {
        PurchaseOrder order = find(entityId);
        if (PurchaseOrderStatus.APPROVAL_PENDING.name().equals(order.getStatus())) {
            return;
        }
        order.setStatus(PurchaseOrderStatus.APPROVAL_PENDING.name());
        repository.save(order);
        auditService.log("PurchaseOrder", entityId, "SUBMIT_FOR_APPROVAL", order.getCode(), null, null);
    }

    @Override
    public void beforeApproveTask(UUID entityId, ApprovalFlowStep step, ApprovalTaskDecisionRequest request) {
        if (segregationOfDutyRuleService != null) {
            segregationOfDutyRuleService.validateApproval("PurchaseOrder", find(entityId), request == null ? null : request.comment());
        }
    }

    @Override
    public void markApproved(UUID entityId, String comment) {
        PurchaseOrder order = find(entityId);
        if (PurchaseOrderStatus.APPROVED.name().equals(order.getStatus())) {
            return;
        }
        if (!PurchaseOrderStatus.APPROVAL_PENDING.name().equals(order.getStatus())) {
            throw new BusinessException("Purchase order must be APPROVAL_PENDING, current status is " + order.getStatus());
        }
        if (segregationOfDutyRuleService != null) {
            segregationOfDutyRuleService.validateApproval("PurchaseOrder", order, comment);
        }
        order.setStatus(PurchaseOrderStatus.APPROVED.name());
        repository.save(order);
        auditService.log("PurchaseOrder", entityId, "APPROVE", comment, null, null);
    }

    @Override
    public void markRejected(UUID entityId, String comment) {
        PurchaseOrder order = find(entityId);
        if (!PurchaseOrderStatus.APPROVAL_PENDING.name().equals(order.getStatus())) {
            throw new BusinessException("Purchase order must be APPROVAL_PENDING, current status is " + order.getStatus());
        }
        order.setStatus(PurchaseOrderStatus.REJECTED.name());
        repository.save(order);
        auditService.log("PurchaseOrder", entityId, "REJECT", comment, null, null);
    }

    @Override
    public void onReturned(UUID entityId, String comment) {
        PurchaseOrder order = find(entityId);
        if (PurchaseOrderStatus.RETURNED.name().equals(order.getStatus())) {
            return;
        }
        if (!PurchaseOrderStatus.APPROVAL_PENDING.name().equals(order.getStatus())) {
            throw new BusinessException("Purchase order cannot be returned from status " + order.getStatus());
        }
        order.setStatus(PurchaseOrderStatus.RETURNED.name());
        repository.save(order);
        auditService.log("PurchaseOrder", entityId, "RETURN", comment, null, null);
    }

    private PurchaseOrder find(UUID id) {
        return repository.findByIdAndIsDeletedFalse(id)
            .filter(order -> TenantContext.currentTenant().equals(order.getTenantId()))
            .orElseThrow(() -> new NotFoundException("PurchaseOrder not found: " + id));
    }
}
