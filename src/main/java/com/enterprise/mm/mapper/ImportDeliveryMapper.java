package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.ImportDeliveryResponse;
import com.enterprise.mm.payload.ImportDeliveryUpdateRequest;
import com.enterprise.mm.payload.ImportDeliveryCreateRequest;
import com.enterprise.mm.entity.ImportDelivery;
import org.springframework.stereotype.Component;

@Component
public class ImportDeliveryMapper {
    public ImportDeliveryResponse toResponse(ImportDelivery entity) {
        if (entity == null) {
            return null;
        }
        ImportDeliveryResponse dto = new ImportDeliveryResponse();
        dto.setId(entity.getId());
        dto.setTenantId(entity.getTenantId());
        dto.setCompanyId(entity.getCompanyId());
        dto.setCode(entity.getCode());
        dto.setName(entity.getName());
        dto.setStatus(entity.getStatus());
        dto.setDescription(entity.getDescription());
        dto.setDeleted(entity.isDeleted());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        dto.setCreatedBy(entity.getCreatedBy());
        dto.setUpdatedBy(entity.getUpdatedBy());
        dto.setVersion(entity.getVersion());
        dto.setShipmentNumber(entity.getShipmentNumber());
        dto.setSupplierId(entity.getSupplierId());
        dto.setSupplierCode(BaseMapper.supplierCode(entity.getSupplier()));
        dto.setSupplierName(BaseMapper.supplierName(entity.getSupplier()));
        dto.setPurchaseOrderId(entity.getPurchaseOrderId());
        dto.setPurchaseOrderCode(BaseMapper.code(entity.getPurchaseOrder()));
        dto.setPurchaseOrderName(BaseMapper.name(entity.getPurchaseOrder()));
        dto.setAsnId(entity.getAsnId());
        dto.setAsnCode(BaseMapper.code(entity.getAsn()));
        dto.setAsnName(BaseMapper.name(entity.getAsn()));
        dto.setIncotermId(entity.getIncotermId());
        dto.setIncotermCode(BaseMapper.code(entity.getIncoterm()));
        dto.setIncotermName(BaseMapper.name(entity.getIncoterm()));
        dto.setImportFlag(entity.isImportFlag());
        dto.setTransportMode(entity.getTransportMode());
        dto.setCarrierName(entity.getCarrierName());
        dto.setForwarderName(entity.getForwarderName());
        dto.setBrokerName(entity.getBrokerName());
        dto.setOriginCountry(entity.getOriginCountry());
        dto.setOriginPort(entity.getOriginPort());
        dto.setBorderPost(entity.getBorderPost());
        dto.setDestinationCountry(entity.getDestinationCountry());
        dto.setDestinationPlace(entity.getDestinationPlace());
        dto.setDestinationWarehouseId(entity.getDestinationWarehouseId());
        dto.setDestinationWarehouseCode(BaseMapper.code(entity.getDestinationWarehouse()));
        dto.setDestinationWarehouseName(BaseMapper.name(entity.getDestinationWarehouse()));
        dto.setEtd(entity.getEtd());
        dto.setEta(entity.getEta());
        dto.setActualArrivalDate(entity.getActualArrivalDate());
        dto.setCustomsDeclarationId(entity.getCustomsDeclarationId());
        dto.setBaseGoodsAmount(entity.getBaseGoodsAmount());
        dto.setLogisticsCostAmount(entity.getLogisticsCostAmount());
        dto.setCustomsCostAmount(entity.getCustomsCostAmount());
        dto.setLandedCostAmount(entity.getLandedCostAmount());
        dto.setCurrencyCode(entity.getCurrencyCode());
        dto.setCurrentMilestone(entity.getCurrentMilestone());
        dto.setLastEventAt(entity.getLastEventAt());
        dto.setRiskFlag(entity.isRiskFlag());
        dto.setRiskReason(entity.getRiskReason());
        return dto;
    }

    public ImportDelivery toEntity(ImportDeliveryCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        ImportDelivery entity = new ImportDelivery();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setShipmentNumber(dto.getShipmentNumber());
        entity.setSupplierId(dto.getSupplierId());
        entity.setPurchaseOrderId(dto.getPurchaseOrderId());
        entity.setAsnId(dto.getAsnId());
        entity.setIncotermId(dto.getIncotermId());
        entity.setImportFlag(dto.isImportFlag());
        entity.setTransportMode(dto.getTransportMode());
        entity.setCarrierName(dto.getCarrierName());
        entity.setForwarderName(dto.getForwarderName());
        entity.setBrokerName(dto.getBrokerName());
        entity.setOriginCountry(dto.getOriginCountry());
        entity.setOriginPort(dto.getOriginPort());
        entity.setBorderPost(dto.getBorderPost());
        entity.setDestinationCountry(dto.getDestinationCountry());
        entity.setDestinationPlace(dto.getDestinationPlace());
        entity.setDestinationWarehouseId(dto.getDestinationWarehouseId());
        entity.setEtd(dto.getEtd());
        entity.setEta(dto.getEta());
        entity.setActualArrivalDate(dto.getActualArrivalDate());
        entity.setCustomsDeclarationId(dto.getCustomsDeclarationId());
        entity.setBaseGoodsAmount(dto.getBaseGoodsAmount());
        entity.setLogisticsCostAmount(dto.getLogisticsCostAmount());
        entity.setCustomsCostAmount(dto.getCustomsCostAmount());
        entity.setLandedCostAmount(dto.getLandedCostAmount());
        entity.setCurrencyCode(dto.getCurrencyCode());
        entity.setCurrentMilestone(dto.getCurrentMilestone());
        entity.setLastEventAt(dto.getLastEventAt());
        entity.setRiskFlag(dto.isRiskFlag());
        entity.setRiskReason(dto.getRiskReason());
        return entity;
    }

    public void updateEntity(ImportDelivery entity, ImportDeliveryUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setShipmentNumber(dto.getShipmentNumber());
        entity.setSupplierId(dto.getSupplierId());
        entity.setPurchaseOrderId(dto.getPurchaseOrderId());
        entity.setAsnId(dto.getAsnId());
        entity.setIncotermId(dto.getIncotermId());
        entity.setImportFlag(dto.isImportFlag());
        entity.setTransportMode(dto.getTransportMode());
        entity.setCarrierName(dto.getCarrierName());
        entity.setForwarderName(dto.getForwarderName());
        entity.setBrokerName(dto.getBrokerName());
        entity.setOriginCountry(dto.getOriginCountry());
        entity.setOriginPort(dto.getOriginPort());
        entity.setBorderPost(dto.getBorderPost());
        entity.setDestinationCountry(dto.getDestinationCountry());
        entity.setDestinationPlace(dto.getDestinationPlace());
        entity.setDestinationWarehouseId(dto.getDestinationWarehouseId());
        entity.setEtd(dto.getEtd());
        entity.setEta(dto.getEta());
        entity.setBaseGoodsAmount(dto.getBaseGoodsAmount());
        entity.setCurrencyCode(dto.getCurrencyCode());
    }
}
