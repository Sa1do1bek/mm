package com.enterprise.mm.service.approval;

import com.enterprise.mm.entity.ApprovalOperationRequest;
import com.enterprise.mm.enums.TransferStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.TransferApproveRequest;
import com.enterprise.mm.payload.TransferDocumentResponse;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalDetailItem;
import com.enterprise.mm.service.TransferDocumentService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TransferApproveOperationHandler implements ApprovalOperationHandler {
    public static final String MODULE_KEY = "TRANSFER_DOCUMENT_APPROVE";
    private final TransferDocumentService service;

    @Override
    public String moduleKey() { return MODULE_KEY; }

    @Override
    public UUID companyId(UUID targetId) { return service.get(targetId).getCompanyId(); }

    @Override
    public void requireDetailRead(UUID targetId) { service.getForApprovalReview(targetId); }

    @Override
    public String displayName(UUID targetId) { return service.get(targetId).getName(); }

    @Override
    public void validate(UUID targetId, Long expectedVersion, String payloadJson) {
        TransferDocumentResponse transfer = service.get(targetId);
        if (expectedVersion == null || !expectedVersion.equals(transfer.getVersion())) {
            throw new BusinessException("Transfer changed; refresh before requesting approval");
        }
        if (!TransferStatus.SUBMITTED.name().equals(transfer.getStatus())) {
            throw new BusinessException("Only a submitted transfer can be approved");
        }
        service.validateApproveForApproval(targetId, expectedVersion);
    }

    @Override
    public void apply(ApprovalOperationRequest request) {
        service.approve(request.getTargetEntityId(), new TransferApproveRequest(request.getTargetVersion()));
    }

    @Override
    public List<ApprovalDetailItem> reviewItems(ApprovalOperationRequest request) {
        TransferDocumentResponse transfer = service.getForApprovalReview(request.getTargetEntityId());
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("operation", "Approve transfer");
        summary.put("targetEntityId", request.getTargetEntityId());
        summary.put("expectedVersion", request.getTargetVersion());
        summary.put("sourceWarehouse", Objects.toString(transfer.getSourceWarehouseName(), ""));
        summary.put("destinationWarehouse", Objects.toString(transfer.getDestinationWarehouseName(), ""));
        summary.put("currentStatus", transfer.getStatus());
        List<ApprovalDetailItem> items = new ArrayList<>();
        items.add(new ApprovalDetailItem(request.getTargetEntityId().toString(), transfer.getName(), summary));
        if (transfer.getLines() != null) {
            transfer.getLines().forEach(line -> {
                Map<String, Object> values = new LinkedHashMap<>();
                if (line.getMaterialId() != null) values.put("materialId", line.getMaterialId());
                if (line.getPlannedQuantity() != null) values.put("plannedQuantity", line.getPlannedQuantity());
                if (line.getUnitOfMeasure() != null) values.put("unitOfMeasure", line.getUnitOfMeasure());
                items.add(new ApprovalDetailItem(
                    line.getId() == null ? null : line.getId().toString(),
                    Objects.toString(line.getMaterialName(), "Material"), values
                ));
            });
        }
        return items;
    }
}
