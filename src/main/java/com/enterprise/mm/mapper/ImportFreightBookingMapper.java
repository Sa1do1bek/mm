package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.ImportFreightBookingResponse;
import com.enterprise.mm.payload.ImportFreightBookingUpdateRequest;
import com.enterprise.mm.payload.ImportFreightBookingCreateRequest;
import com.enterprise.mm.entity.ImportFreightBooking;
import org.springframework.stereotype.Component;

@Component
public class ImportFreightBookingMapper {
    public ImportFreightBookingResponse toResponse(ImportFreightBooking entity) {
        if (entity == null) {
            return null;
        }
        ImportFreightBookingResponse dto = new ImportFreightBookingResponse();
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
        dto.setBookingNumber(entity.getBookingNumber());
        dto.setImportDeliveryId(entity.getImportDeliveryId());
        dto.setImportDeliveryCode(BaseMapper.code(entity.getImportDelivery()));
        dto.setImportDeliveryName(BaseMapper.name(entity.getImportDelivery()));
        dto.setCarrierSupplierId(entity.getCarrierSupplierId());
        dto.setCarrierSupplierCode(BaseMapper.supplierCode(entity.getCarrierSupplier()));
        dto.setCarrierSupplierName(BaseMapper.supplierName(entity.getCarrierSupplier()));
        dto.setForwarderSupplierId(entity.getForwarderSupplierId());
        dto.setForwarderSupplierCode(BaseMapper.supplierCode(entity.getForwarderSupplier()));
        dto.setForwarderSupplierName(BaseMapper.supplierName(entity.getForwarderSupplier()));
        dto.setTransportMode(entity.getTransportMode());
        dto.setCarrierName(entity.getCarrierName());
        dto.setForwarderName(entity.getForwarderName());
        dto.setOriginCountry(entity.getOriginCountry());
        dto.setOriginPort(entity.getOriginPort());
        dto.setDestinationCountry(entity.getDestinationCountry());
        dto.setDestinationPlace(entity.getDestinationPlace());
        dto.setContainerNumber(entity.getContainerNumber());
        dto.setBillOfLadingNumber(entity.getBillOfLadingNumber());
        dto.setWaybillNumber(entity.getWaybillNumber());
        dto.setPlannedPickupDate(entity.getPlannedPickupDate());
        dto.setPlannedDeliveryDate(entity.getPlannedDeliveryDate());
        dto.setActualPickupDate(entity.getActualPickupDate());
        dto.setActualDeliveryDate(entity.getActualDeliveryDate());
        dto.setFreightAmount(entity.getFreightAmount());
        dto.setFuelSurchargeAmount(entity.getFuelSurchargeAmount());
        dto.setOtherChargesAmount(entity.getOtherChargesAmount());
        dto.setTotalFreightAmount(entity.getTotalFreightAmount());
        dto.setCurrencyCode(entity.getCurrencyCode());
        dto.setAllocationMethod(entity.getAllocationMethod());
        dto.setIncludedInInventoryCost(entity.isIncludedInInventoryCost());
        dto.setInvoiceNumber(entity.getInvoiceNumber());
        dto.setInvoiceDate(entity.getInvoiceDate());
        dto.setLandedCostComponentId(entity.getLandedCostComponentId());
        dto.setLandedCostComponentCode(BaseMapper.code(entity.getLandedCostComponent()));
        dto.setLandedCostComponentName(BaseMapper.name(entity.getLandedCostComponent()));
        dto.setPostedAt(entity.getPostedAt());
        return dto;
    }

    public ImportFreightBooking toEntity(ImportFreightBookingCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        ImportFreightBooking entity = new ImportFreightBooking();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setBookingNumber(dto.getBookingNumber());
        entity.setImportDeliveryId(dto.getImportDeliveryId());
        entity.setCarrierSupplierId(dto.getCarrierSupplierId());
        entity.setForwarderSupplierId(dto.getForwarderSupplierId());
        entity.setTransportMode(dto.getTransportMode());
        entity.setCarrierName(dto.getCarrierName());
        entity.setForwarderName(dto.getForwarderName());
        entity.setOriginCountry(dto.getOriginCountry());
        entity.setOriginPort(dto.getOriginPort());
        entity.setDestinationCountry(dto.getDestinationCountry());
        entity.setDestinationPlace(dto.getDestinationPlace());
        entity.setContainerNumber(dto.getContainerNumber());
        entity.setBillOfLadingNumber(dto.getBillOfLadingNumber());
        entity.setWaybillNumber(dto.getWaybillNumber());
        entity.setPlannedPickupDate(dto.getPlannedPickupDate());
        entity.setPlannedDeliveryDate(dto.getPlannedDeliveryDate());
        entity.setActualPickupDate(dto.getActualPickupDate());
        entity.setActualDeliveryDate(dto.getActualDeliveryDate());
        entity.setFreightAmount(dto.getFreightAmount());
        entity.setFuelSurchargeAmount(dto.getFuelSurchargeAmount());
        entity.setOtherChargesAmount(dto.getOtherChargesAmount());
        entity.setTotalFreightAmount(dto.getTotalFreightAmount());
        entity.setCurrencyCode(dto.getCurrencyCode());
        entity.setAllocationMethod(dto.getAllocationMethod());
        entity.setIncludedInInventoryCost(dto.isIncludedInInventoryCost());
        entity.setInvoiceNumber(dto.getInvoiceNumber());
        entity.setInvoiceDate(dto.getInvoiceDate());
        entity.setLandedCostComponentId(dto.getLandedCostComponentId());
        entity.setPostedAt(dto.getPostedAt());
        return entity;
    }

    public void updateEntity(ImportFreightBooking entity, ImportFreightBookingUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setBookingNumber(dto.getBookingNumber());
        entity.setImportDeliveryId(dto.getImportDeliveryId());
        entity.setCarrierSupplierId(dto.getCarrierSupplierId());
        entity.setForwarderSupplierId(dto.getForwarderSupplierId());
        entity.setTransportMode(dto.getTransportMode());
        entity.setCarrierName(dto.getCarrierName());
        entity.setForwarderName(dto.getForwarderName());
        entity.setOriginCountry(dto.getOriginCountry());
        entity.setOriginPort(dto.getOriginPort());
        entity.setDestinationCountry(dto.getDestinationCountry());
        entity.setDestinationPlace(dto.getDestinationPlace());
        entity.setContainerNumber(dto.getContainerNumber());
        entity.setBillOfLadingNumber(dto.getBillOfLadingNumber());
        entity.setWaybillNumber(dto.getWaybillNumber());
        entity.setPlannedPickupDate(dto.getPlannedPickupDate());
        entity.setPlannedDeliveryDate(dto.getPlannedDeliveryDate());
        entity.setFreightAmount(dto.getFreightAmount());
        entity.setFuelSurchargeAmount(dto.getFuelSurchargeAmount());
        entity.setOtherChargesAmount(dto.getOtherChargesAmount());
        entity.setCurrencyCode(dto.getCurrencyCode());
        entity.setAllocationMethod(dto.getAllocationMethod());
        entity.setIncludedInInventoryCost(dto.isIncludedInInventoryCost());
        entity.setInvoiceNumber(dto.getInvoiceNumber());
        entity.setInvoiceDate(dto.getInvoiceDate());
    }
}
