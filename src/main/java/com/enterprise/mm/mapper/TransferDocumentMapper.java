package com.enterprise.mm.mapper;

import com.enterprise.mm.common.UnitOfMeasureLabels;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.TransferDocument;
import com.enterprise.mm.entity.TransferDocumentLine;
import com.enterprise.mm.payload.TransferDocumentLineResponse;
import com.enterprise.mm.payload.TransferDocumentResponse;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class TransferDocumentMapper {
    public TransferDocumentResponse toResponse(TransferDocument entity, List<TransferDocumentLine> lines) {
        TransferDocumentResponse response = new TransferDocumentResponse();
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
        response.setSourceWarehouseId(entity.getSourceWarehouseId());
        response.setSourceWarehouseName(entity.getSourceWarehouse() == null ? null : entity.getSourceWarehouse().getName());
        response.setSourceLocationId(entity.getSourceLocationId());
        response.setDestinationWarehouseId(entity.getDestinationWarehouseId());
        response.setDestinationWarehouseName(entity.getDestinationWarehouse() == null ? null : entity.getDestinationWarehouse().getName());
        response.setDestinationLocationId(entity.getDestinationLocationId());
        response.setPlannedDate(entity.getPlannedDate());
        response.setDispatchedDate(entity.getDispatchedDate());
        response.setReceivedDate(entity.getReceivedDate());
        response.setDispatchMovementDocumentId(entity.getDispatchMovementDocumentId());
        response.setReceiveMovementDocumentId(entity.getReceiveMovementDocumentId());
        response.setLines(lines.stream().map(this::toLineResponse).toList());
        response.setLineCount(lines.size());
        response.setAllowedActions(List.of()); // Lifecycle, quantities and actor scope are projected by the transfer facade.
        return response;
    }

    private TransferDocumentLineResponse toLineResponse(TransferDocumentLine entity) {
        TransferDocumentLineResponse response = new TransferDocumentLineResponse();
        response.setId(entity.getId());
        response.setMaterialId(entity.getMaterialId());
        Material material = entity.getMaterial();
        response.setMaterialName(material == null ? null : material.getName());
        response.setBatchTrackingPolicy(material == null ? null : material.getBatchTrackingPolicy());
        response.setSerialTrackingPolicy(material == null ? null : material.getSerialTrackingPolicy());
        response.setUnitOfMeasure(unitName(material));
        response.setPlannedQuantity(entity.getPlannedQuantity());
        response.setCancelledQuantity(entity.getCancelledQuantity());
        response.setDispatchedQuantity(entity.getDispatchedQuantity());
        response.setInTransitQuantity(entity.getInTransitQuantity());
        response.setReceivedQuantity(entity.getReceivedQuantity());
        response.setDamagedQuantity(entity.getDamagedQuantity());
        response.setMissingQuantity(entity.getMissingQuantity());
        response.setRemainingQuantity(nvl(entity.getPlannedQuantity()).subtract(nvl(entity.getDispatchedQuantity())).subtract(nvl(entity.getCancelledQuantity())).max(BigDecimal.ZERO));
        response.setUnitCost(entity.getUnitCost());
        response.setInTransitValue(nvl(entity.getInTransitQuantity()).multiply(nvl(entity.getUnitCost())));
        response.setSourceStockBatchId(entity.getSourceStockBatchId());
        response.setDestinationStockBatchId(entity.getDestinationStockBatchId());
        response.setSourceStorageLocationId(entity.getSourceStorageLocationId());
        response.setDestinationStorageLocationId(entity.getDestinationStorageLocationId());
        response.setShortageReason(entity.getShortageReason());
        response.setDiscrepancyReason(entity.getDiscrepancyReason());
        response.setDiscrepancyEvidence(entity.getDiscrepancyEvidence());
        return response;
    }

    private String unitName(Material material) {
        if (material == null || material.getUnitOfMeasure() == null) {
            return null;
        }
        return UnitOfMeasureLabels.uzbekShort(material.getUnitOfMeasure());
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
