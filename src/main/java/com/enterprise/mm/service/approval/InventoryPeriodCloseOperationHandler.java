package com.enterprise.mm.service.approval;

import com.enterprise.mm.entity.ApprovalOperationRequest;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.InventoryPeriodCloseResponse;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalDetailItem;
import com.enterprise.mm.service.InventoryPeriodCloseService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class InventoryPeriodCloseOperationHandler implements ApprovalOperationHandler {
    public static final String MODULE_KEY = "INVENTORY_PERIOD_CLOSE";
    private final InventoryPeriodCloseService service;

    @Override
    public String moduleKey() { return MODULE_KEY; }

    @Override
    public UUID companyId(UUID targetId) { return service.get(targetId).getCompanyId(); }

    @Override
    public String displayName(UUID targetId) { return service.get(targetId).getName(); }

    @Override
    public void validate(UUID targetId, Long expectedVersion, String payloadJson) {
        InventoryPeriodCloseResponse period = service.get(targetId);
        if (expectedVersion == null || !expectedVersion.equals(period.getVersion())) {
            throw new BusinessException("Inventory period changed; refresh before requesting approval");
        }
        service.validateCloseForApproval(targetId, expectedVersion);
    }

    @Override
    public void apply(ApprovalOperationRequest request) {
        service.close(request.getTargetEntityId(), request.getTargetVersion());
    }

    @Override
    public List<ApprovalDetailItem> reviewItems(ApprovalOperationRequest request) {
        InventoryPeriodCloseResponse period = service.get(request.getTargetEntityId());
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("operation", "Close inventory period");
        values.put("expectedVersion", request.getTargetVersion());
        values.put("currentStatus", period.getStatus());
        if (period.getPeriodStart() != null) values.put("periodStart", period.getPeriodStart());
        if (period.getPeriodEnd() != null) values.put("periodEnd", period.getPeriodEnd());
        if (period.getWarehouseName() != null) values.put("warehouse", period.getWarehouseName());
        if (period.getTotalStockValue() != null) values.put("totalStockValue", period.getTotalStockValue());
        if (period.getAccountingReconciliationStatus() != null)
            values.put("accountingReconciliationStatus", period.getAccountingReconciliationStatus());
        values.put("openMovementCount", period.getOpenMovementCount());
        return List.of(new ApprovalDetailItem(request.getTargetEntityId().toString(), period.getName(), values));
    }
}
