package com.enterprise.mm.mapper;

import com.enterprise.mm.entity.IssueRequest;
import com.enterprise.mm.entity.IssueRequestLine;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.payload.IssueRequestCreateRequest;
import com.enterprise.mm.payload.IssueRequestLineResponse;
import com.enterprise.mm.payload.IssueRequestResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class IssueRequestMapper {
    public IssueRequest toEntity(IssueRequestCreateRequest request) {
        if (request == null) {
            return null;
        }
        IssueRequest entity = new IssueRequest();
        entity.setCode(request.getCode());
        entity.setName(request.getName());
        entity.setDescription(request.getDescription());
        entity.setWarehouseId(request.getWarehouseId());
        entity.setRequesterUserId(request.getRequesterUserId());
        entity.setReceiverUserId(request.getReceiverUserId());
        entity.setCostObjectType(request.getCostObjectType());
        entity.setCostObjectId(request.getCostObjectId());
        entity.setRequiredDate(request.getRequiredDate());
        entity.setPurpose(request.getPurpose());
        return entity;
    }

    public IssueRequestLine toLineEntity(UUID issueRequestId, IssueRequestCreateRequest.Line request, int index) {
        IssueRequestLine entity = new IssueRequestLine();
        entity.setIssueRequestId(issueRequestId);
        entity.setCode("LINE-" + (index + 1));
        entity.setName("Issue request line");
        entity.setStatus("ACTIVE");
        entity.setMaterialId(request.getMaterialId());
        entity.setRequestedQuantity(request.getRequestedQuantity());
        entity.setApprovedQuantity(BigDecimal.ZERO);
        entity.setIssuedQuantity(BigDecimal.ZERO);
        entity.setUnitCost(request.getUnitCost());
        entity.setSourceDemandPlanItemId(request.getSourceDemandPlanItemId());
        entity.setLineComment(request.getComment());
        return entity;
    }

    public IssueRequestResponse toResponse(IssueRequest entity, List<IssueRequestLine> lines) {
        if (entity == null) {
            return null;
        }
        IssueRequestResponse response = new IssueRequestResponse();
        response.setId(entity.getId());
        response.setTenantId(entity.getTenantId());
        response.setCompanyId(entity.getCompanyId());
        response.setCode(entity.getCode());
        response.setName(entity.getName());
        response.setStatus(entity.getStatus());
        response.setDescription(entity.getDescription());
        response.setDeleted(entity.isDeleted());
        response.setCreatedAt(entity.getCreatedAt());
        response.setUpdatedAt(entity.getUpdatedAt());
        response.setCreatedBy(entity.getCreatedBy());
        response.setUpdatedBy(entity.getUpdatedBy());
        response.setVersion(entity.getVersion());
        response.setWarehouseId(entity.getWarehouseId());
        response.setWarehouseName(entity.getWarehouse() == null ? null : entity.getWarehouse().getName());
        response.setRequesterUserId(entity.getRequesterUserId());
        response.setRequesterUserName(entity.getRequesterUser() == null ? null : entity.getRequesterUser().getFullName());
        response.setReceiverUserId(entity.getReceiverUserId());
        response.setReceiverUserName(entity.getReceiverUser() == null ? null : entity.getReceiverUser().getFullName());
        response.setCostObjectType(entity.getCostObjectType());
        response.setCostObjectId(entity.getCostObjectId());
        response.setRequiredDate(entity.getRequiredDate());
        response.setIssuedDate(entity.getIssuedDate());
        response.setPurpose(entity.getPurpose());
        response.setApprovalComment(entity.getApprovalComment());
        response.setStockMovementDocumentId(entity.getStockMovementDocumentId());
        response.setLines(lines.stream().map(this::toLineResponse).toList());
        response.setAllowedActions(allowedActions(entity.getStatus()));
        return response;
    }

    public IssueRequestLineResponse toLineResponse(IssueRequestLine entity) {
        IssueRequestLineResponse response = new IssueRequestLineResponse();
        response.setId(entity.getId());
        response.setMaterialId(entity.getMaterialId());
        Material material = entity.getMaterial();
        response.setMaterialCode(material == null ? null : material.getCode());
        response.setMaterialName(material == null ? null : material.getName());
        response.setBatchTrackingPolicy(material == null ? null : material.getBatchTrackingPolicy());
        response.setSerialTrackingPolicy(material == null ? null : material.getSerialTrackingPolicy());
        response.setUnitOfMeasure(unitName(material));
        response.setRequestedQuantity(entity.getRequestedQuantity());
        response.setApprovedQuantity(entity.getApprovedQuantity());
        response.setIssuedQuantity(entity.getIssuedQuantity());
        response.setRemainingQuantity(nvl(entity.getApprovedQuantity()).subtract(nvl(entity.getIssuedQuantity())).max(BigDecimal.ZERO));
        response.setUnitCost(entity.getUnitCost());
        response.setTotalCost(nvl(entity.getIssuedQuantity()).multiply(nvl(entity.getUnitCost())));
        response.setComment(entity.getLineComment());
        response.setReservationId(entity.getReservationId());
        response.setStockBatchId(entity.getStockBatchId());
        response.setStockMovementId(entity.getStockMovementId());
        response.setStorageLocationId(entity.getStorageLocationId());
        response.setSourceDemandPlanItemId(entity.getSourceDemandPlanItemId());
        return response;
    }

    private List<String> allowedActions(String status) {
        if (status == null) {
            return List.of();
        }
        return switch (status.toUpperCase(Locale.ROOT)) {
            case "DRAFT" -> List.of("submit", "cancel");
            case "SUBMITTED" -> List.of("approve", "reject", "cancel");
            case "APPROVED" -> List.of("reserve", "issue", "cancel");
            case "RESERVED" -> List.of("issue", "cancel");
            case "PARTIALLY_ISSUED" -> List.of("issue", "close");
            case "ISSUED" -> List.of("close");
            default -> List.of();
        };
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String unitName(Material material) {
        if (material == null) {
            return null;
        }
        UnitOfMeasure uom = material.getUnitOfMeasure();
        if (uom == null) {
            return null;
        }
        return firstNonBlank(uom.getMeasurementNameShort(), uom.getMeasurementNameShortRu(), uom.getName());
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
