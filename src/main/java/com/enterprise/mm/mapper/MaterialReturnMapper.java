package com.enterprise.mm.mapper;

import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.MaterialReturnDocument;
import com.enterprise.mm.entity.MaterialReturnLine;
import com.enterprise.mm.payload.MaterialReturnLineResponse;
import com.enterprise.mm.payload.MaterialReturnResponse;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class MaterialReturnMapper {
    public MaterialReturnResponse toResponse(MaterialReturnDocument entity, List<MaterialReturnLine> lines) {
        MaterialReturnResponse response = new MaterialReturnResponse();
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
        response.setIssueRequestId(entity.getIssueRequestId());
        response.setIssueRequestCode(entity.getIssueRequest() == null ? null : entity.getIssueRequest().getCode());
        response.setIssueRequestName(entity.getIssueRequest() == null ? null : entity.getIssueRequest().getName());
        response.setWarehouseId(entity.getWarehouseId());
        response.setWarehouseName(entity.getWarehouse() == null ? null : entity.getWarehouse().getName());
        response.setReason(entity.getReason());
        response.setAcceptanceStatus(entity.getAcceptanceStatus());
        response.setStockMovementDocumentId(entity.getStockMovementDocumentId());
        response.setOriginType(entity.getOriginType());
        response.setHistoricalDocumentNumber(entity.getHistoricalDocumentNumber());
        response.setHistoricalDocumentDate(entity.getHistoricalDocumentDate());
        response.setHistoricalSourceSystem(entity.getHistoricalSourceSystem());
        response.setOwnerId(entity.getOwnerId());
        response.setLines(lines.stream().map(this::toLineResponse).toList());
        response.setAllowedActions(allowedActions(entity.getStatus()));
        return response;
    }

    private MaterialReturnLineResponse toLineResponse(MaterialReturnLine entity) {
        MaterialReturnLineResponse response = new MaterialReturnLineResponse();
        response.setId(entity.getId());
        response.setIssueRequestLineId(entity.getIssueRequestLineId());
        response.setMaterialId(entity.getMaterialId());
        response.setSerialNumber(entity.getSerialNumber());
        response.setOwnerId(entity.getOwnerId());
        var material = entity.getMaterial();
        response.setMaterialName(material == null ? null : material.getName());
        response.setBatchTrackingPolicy(material == null ? null : material.getBatchTrackingPolicy());
        response.setSerialTrackingPolicy(material == null ? null : material.getSerialTrackingPolicy());
        response.setUnitOfMeasure(unitName(material));
        response.setIssuedQuantity(entity.getIssuedQuantity());
        response.setPreviouslyReturnedQuantity(entity.getPreviouslyReturnedQuantity());
        response.setReturnableQuantity(
            nvl(entity.getIssuedQuantity())
                .subtract(nvl(entity.getPreviouslyReturnedQuantity()))
                .subtract(nvl(entity.getAcceptedQuantity()))
                .max(BigDecimal.ZERO)
        );
        response.setReturnQuantity(entity.getReturnQuantity());
        response.setAcceptedQuantity(entity.getAcceptedQuantity());
        response.setRejectedQuantity(entity.getRejectedQuantity());
        response.setCondition(entity.getCondition());
        response.setTargetStockStatus(entity.getTargetStockStatus());
        response.setOriginalUnitCost(entity.getOriginalUnitCost());
        BigDecimal valuedQuantity = nvl(entity.getAcceptedQuantity()).signum() > 0
            ? entity.getAcceptedQuantity()
            : entity.getReturnQuantity();
        response.setTotalReturnValue(nvl(valuedQuantity).multiply(nvl(entity.getOriginalUnitCost())));
        response.setComment(entity.getLineComment());
        response.setInspectionNote(entity.getInspectionNote());
        return response;
    }

    private List<String> allowedActions(String status) {
        if ("DRAFT".equals(status)) {
            return List.of("submit", "cancel");
        }
        if ("SUBMITTED".equals(status) || "INSPECTION".equals(status)) {
            return List.of("accept", "cancel");
        }
        return List.of();
    }

    private String unitName(Material material) {
        if (material == null || material.getUnitOfMeasure() == null) {
            return null;
        }
        var unit = material.getUnitOfMeasure();
        for (String value : List.of(
            value(unit.getMeasurementNameShort()),
            value(unit.getMeasurementNameShortRu()),
            value(unit.getName())
        )) {
            if (!value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
