package com.enterprise.mm.mapper;

import com.enterprise.mm.common.DataQualityGuards;
import com.enterprise.mm.payload.AdvanceShippingNoticeResponse;
import com.enterprise.mm.payload.AdvanceShippingNoticeUpdateRequest;
import com.enterprise.mm.payload.AdvanceShippingNoticeCreateRequest;
import com.enterprise.mm.entity.AdvanceShippingNotice;
import org.springframework.stereotype.Component;

@Component
public class AdvanceShippingNoticeMapper {
    public AdvanceShippingNoticeResponse toResponse(AdvanceShippingNotice entity) {
        if (entity == null) {
            return null;
        }
        AdvanceShippingNoticeResponse dto = new AdvanceShippingNoticeResponse();
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
        dto.setAsnNumber(entity.getAsnNumber());
        dto.setSupplierId(entity.getSupplierId());
        dto.setSupplierCode(BaseMapper.supplierCode(entity.getSupplier()));
        dto.setSupplierName(BaseMapper.supplierName(entity.getSupplier()));
        dto.setPurchaseOrderId(entity.getPurchaseOrderId());
        dto.setPurchaseOrderCode(BaseMapper.code(entity.getPurchaseOrder()));
        dto.setPurchaseOrderName(BaseMapper.name(entity.getPurchaseOrder()));
        dto.setContractId(entity.getContractId());
        dto.setContractCode(BaseMapper.code(entity.getContract()));
        dto.setContractName(BaseMapper.name(entity.getContract()));
        dto.setIncotermId(entity.getIncotermId());
        dto.setIncotermCode(BaseMapper.code(entity.getIncoterm()));
        dto.setIncotermName(BaseMapper.name(entity.getIncoterm()));
        dto.setNoticeDate(entity.getNoticeDate());
        dto.setExpectedShipDate(entity.getExpectedShipDate());
        dto.setExpectedArrivalDate(entity.getExpectedArrivalDate());
        dto.setTransportMode(entity.getTransportMode());
        dto.setCarrierName(entity.getCarrierName());
        dto.setVehicleNumber(entity.getVehicleNumber());
        dto.setContainerNumber(entity.getContainerNumber());
        dto.setTrackingNumber(entity.getTrackingNumber());
        dto.setOriginCountry(entity.getOriginCountry());
        dto.setDestinationCountry(entity.getDestinationCountry());
        dto.setLoadingPoint(entity.getLoadingPoint());
        dto.setUnloadingPoint(entity.getUnloadingPoint());
        dto.setTotalQuantity(entity.getTotalQuantity());
        dto.setTotalAmount(DataQualityGuards.roundMoney(entity.getTotalAmount(), entity.getCurrencyCode()));
        dto.setCurrencyCode(entity.getCurrencyCode());
        dto.setSentAt(entity.getSentAt());
        dto.setConfirmedAt(entity.getConfirmedAt());
        dto.setReceivedAt(entity.getReceivedAt());
        return dto;
    }

    public AdvanceShippingNotice toEntity(AdvanceShippingNoticeCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        AdvanceShippingNotice entity = new AdvanceShippingNotice();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setAsnNumber(dto.getAsnNumber());
        entity.setSupplierId(dto.getSupplierId());
        entity.setPurchaseOrderId(dto.getPurchaseOrderId());
        entity.setContractId(dto.getContractId());
        entity.setIncotermId(dto.getIncotermId());
        entity.setNoticeDate(DataQualityGuards.requireSaneDate(dto.getNoticeDate(), "noticeDate"));
        entity.setExpectedShipDate(DataQualityGuards.requireSaneDate(dto.getExpectedShipDate(), "expectedShipDate"));
        entity.setExpectedArrivalDate(DataQualityGuards.requireSaneDate(dto.getExpectedArrivalDate(), "expectedArrivalDate"));
        DataQualityGuards.requireDateOrder(entity.getExpectedShipDate(), entity.getExpectedArrivalDate(),
            "expectedShipDate", "expectedArrivalDate");
        entity.setTransportMode(dto.getTransportMode());
        entity.setCarrierName(dto.getCarrierName());
        entity.setVehicleNumber(dto.getVehicleNumber());
        entity.setContainerNumber(dto.getContainerNumber());
        entity.setTrackingNumber(dto.getTrackingNumber());
        entity.setOriginCountry(dto.getOriginCountry());
        entity.setDestinationCountry(dto.getDestinationCountry());
        entity.setLoadingPoint(dto.getLoadingPoint());
        entity.setUnloadingPoint(dto.getUnloadingPoint());
        entity.setTotalQuantity(dto.getTotalQuantity());
        entity.setTotalAmount(DataQualityGuards.roundMoney(dto.getTotalAmount(), dto.getCurrencyCode()));
        entity.setCurrencyCode(dto.getCurrencyCode());
        entity.setSentAt(dto.getSentAt());
        entity.setConfirmedAt(dto.getConfirmedAt());
        entity.setReceivedAt(dto.getReceivedAt());
        return entity;
    }

    public void updateEntity(AdvanceShippingNotice entity, AdvanceShippingNoticeUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setAsnNumber(dto.getAsnNumber());
        entity.setSupplierId(dto.getSupplierId());
        entity.setPurchaseOrderId(dto.getPurchaseOrderId());
        entity.setContractId(dto.getContractId());
        entity.setIncotermId(dto.getIncotermId());
        entity.setNoticeDate(DataQualityGuards.requireSaneDate(dto.getNoticeDate(), "noticeDate"));
        entity.setExpectedShipDate(DataQualityGuards.requireSaneDate(dto.getExpectedShipDate(), "expectedShipDate"));
        entity.setExpectedArrivalDate(DataQualityGuards.requireSaneDate(dto.getExpectedArrivalDate(), "expectedArrivalDate"));
        DataQualityGuards.requireDateOrder(entity.getExpectedShipDate(), entity.getExpectedArrivalDate(),
            "expectedShipDate", "expectedArrivalDate");
        entity.setTransportMode(dto.getTransportMode());
        entity.setCarrierName(dto.getCarrierName());
        entity.setVehicleNumber(dto.getVehicleNumber());
        entity.setContainerNumber(dto.getContainerNumber());
        entity.setTrackingNumber(dto.getTrackingNumber());
        entity.setOriginCountry(dto.getOriginCountry());
        entity.setDestinationCountry(dto.getDestinationCountry());
        entity.setLoadingPoint(dto.getLoadingPoint());
        entity.setUnloadingPoint(dto.getUnloadingPoint());
        entity.setTotalQuantity(dto.getTotalQuantity());
        entity.setTotalAmount(DataQualityGuards.roundMoney(dto.getTotalAmount(), dto.getCurrencyCode()));
        entity.setCurrencyCode(dto.getCurrencyCode());
        entity.setSentAt(dto.getSentAt());
        entity.setConfirmedAt(dto.getConfirmedAt());
        entity.setReceivedAt(dto.getReceivedAt());
    }
}
