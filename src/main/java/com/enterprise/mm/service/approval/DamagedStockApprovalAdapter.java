package com.enterprise.mm.service.approval;

import static com.enterprise.mm.entity.DamagedStockCase.Status.*;

import com.enterprise.mm.entity.DamagedStockCase;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalDetailItem;
import com.enterprise.mm.repository.ApprovalInstanceRepository;
import com.enterprise.mm.repository.AttachmentRepository;
import com.enterprise.mm.service.AuditService;
import com.enterprise.mm.service.DamagedStockService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DamagedStockApprovalAdapter implements ApprovalModuleAdapter {
    private final ObjectProvider<DamagedStockService> damagedStock;
    private final ApprovalInstanceRepository instances;
    private final AttachmentRepository attachments;
    private final AuditService audit;

    @Override public String moduleKey() { return DamagedStockCase.TYPE; }
    @Override public UUID companyId(UUID id) { return damagedStock.getObject().readCase(id).getCompanyId(); }
    @Override public String displayName(UUID id) {
        var stockCase = damagedStock.getObject().readCase(id);
        return stockCase.getCode() + " · " + stockCase.getName();
    }
    @Override public String requesterUsername(UUID id) { return damagedStock.getObject().readCase(id).getCreatedBy(); }
    @Override public void requireDetailRead(UUID id) { damagedStock.getObject().readCase(id); }

    @Override
    public void validateCanSubmit(UUID id) {
        var service = damagedStock.getObject();
        var stockCase = service.lockCase(id);
        service.requireSubmittable(stockCase);
        if (!attachments.existsByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndAttachmentTypeAndIsDeletedFalse(
            stockCase.getTenantId(), stockCase.getCompanyId(), moduleKey(), id, "DOCUMENT"))
            throw new BusinessException("Attach evidence before submitting the write-off", "damagedStock.evidenceRequired");
    }

    @Override
    public void markSubmitted(UUID id) {
        var stockCase = damagedStock.getObject().lockCase(id);
        var instance = instances.findByTenantIdAndCompanyIdAndModuleKeyAndEntityIdAndIsDeletedFalse(
            stockCase.getTenantId(), stockCase.getCompanyId(), moduleKey(), id).orElseThrow();
        stockCase.setApprovalInstanceId(instance.getId());
        transition(stockCase, PENDING_APPROVAL, null);
    }

    @Override
    public void lockApprovalRevision(UUID id, UUID instanceId, UUID taskId) {
        var stockCase = damagedStock.getObject().lockCase(id);
        DamagedStockService.requireStatus(stockCase, Set.of(PENDING_APPROVAL));
        if (!instanceId.equals(stockCase.getApprovalInstanceId()))
            throw new BusinessException("This approval task is stale");
    }

    @Override public void markApproved(UUID id, String comment) { resolve(id, APPROVED, comment); }
    @Override public void markRejected(UUID id, String comment) { resolve(id, REJECTED, comment); }
    @Override public void onReturned(UUID id, String comment) { resolve(id, RETURNED, comment); }
    @Override public void onCancelled(UUID id, String comment) { resolve(id, REJECTED, comment); }

    private void resolve(UUID id, DamagedStockCase.Status next, String comment) {
        var stockCase = damagedStock.getObject().lockCase(id);
        DamagedStockService.requireStatus(stockCase, Set.of(PENDING_APPROVAL));
        transition(stockCase, next, comment);
    }

    private void transition(DamagedStockCase stockCase, DamagedStockCase.Status next, String comment) {
        stockCase.setStatus(next.name());
        stockCase.setResolutionNote(comment);
        audit.logInCurrentTransaction(moduleKey(), stockCase.getId(), next.name(), comment);
    }

    @Override
    public List<ApprovalDetailItem> documentLines(UUID id) {
        var stockCase = damagedStock.getObject().get(id);
        var values = new LinkedHashMap<String, Object>();
        values.put("materialCode", stockCase.materialCode());
        values.put("warehouseName", stockCase.warehouseName());
        values.put("batchNumber", stockCase.batchNumber());
        values.put("storageLocationName", stockCase.locationName());
        values.put("storageBinName", stockCase.binName());
        values.put("quantity", stockCase.quantity());
        values.put("unit", stockCase.unit());
        values.put("condition", stockCase.condition().name());
        values.put("reason", stockCase.reason());
        values.values().removeIf(java.util.Objects::isNull);
        return List.of(new ApprovalDetailItem(id.toString(), stockCase.materialName(), values));
    }
}
