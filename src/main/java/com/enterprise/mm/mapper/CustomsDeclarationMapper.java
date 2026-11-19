package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.CustomsDeclarationResponse;
import com.enterprise.mm.payload.CustomsDeclarationUpdateRequest;
import com.enterprise.mm.payload.CustomsDeclarationCreateRequest;
import com.enterprise.mm.entity.CustomsDeclaration;
import org.springframework.stereotype.Component;

@Component
public class CustomsDeclarationMapper {
    public CustomsDeclarationResponse toResponse(CustomsDeclaration entity) {
        if (entity == null) {
            return null;
        }
        CustomsDeclarationResponse dto = new CustomsDeclarationResponse();
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
        dto.setImportDeliveryId(entity.getImportDeliveryId());
        dto.setImportDeliveryCode(BaseMapper.code(entity.getImportDelivery()));
        dto.setImportDeliveryName(BaseMapper.name(entity.getImportDelivery()));
        dto.setAsnId(entity.getAsnId());
        dto.setAsnCode(BaseMapper.code(entity.getAsn()));
        dto.setAsnName(BaseMapper.name(entity.getAsn()));
        dto.setPurchaseOrderId(entity.getPurchaseOrderId());
        dto.setPurchaseOrderCode(BaseMapper.code(entity.getPurchaseOrder()));
        dto.setPurchaseOrderName(BaseMapper.name(entity.getPurchaseOrder()));
        dto.setSupplierId(entity.getSupplierId());
        dto.setSupplierCode(BaseMapper.supplierCode(entity.getSupplier()));
        dto.setSupplierName(BaseMapper.supplierName(entity.getSupplier()));
        dto.setDeclarationNumber(entity.getDeclarationNumber());
        dto.setDeclarationDate(entity.getDeclarationDate());
        dto.setCustomsPostCode(entity.getCustomsPostCode());
        dto.setCustomsRegime(entity.getCustomsRegime());
        dto.setBrokerName(entity.getBrokerName());
        dto.setTnvedCodeId(entity.getTnvedCodeId());
        dto.setTnvedCodeCode(BaseMapper.code(entity.getTnvedCode()));
        dto.setTnvedCodeName(BaseMapper.name(entity.getTnvedCode()));
        dto.setCountryOfOrigin(entity.getCountryOfOrigin());
        dto.setCustomsValue(entity.getCustomsValue());
        dto.setDutyAmount(entity.getDutyAmount());
        dto.setVatAmount(entity.getVatAmount());
        dto.setExciseAmount(entity.getExciseAmount());
        dto.setFeesAmount(entity.getFeesAmount());
        dto.setTotalCustomsAmount(entity.getTotalCustomsAmount());
        dto.setCurrencyCode(entity.getCurrencyCode());
        dto.setSubmittedAt(entity.getSubmittedAt());
        dto.setReleasedAt(entity.getReleasedAt());
        dto.setAttachmentId(entity.getAttachmentId());
        dto.setRemarks(entity.getRemarks());
        return dto;
    }

    public CustomsDeclaration toEntity(CustomsDeclarationCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        CustomsDeclaration entity = new CustomsDeclaration();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setImportDeliveryId(dto.getImportDeliveryId());
        entity.setAsnId(dto.getAsnId());
        entity.setPurchaseOrderId(dto.getPurchaseOrderId());
        entity.setSupplierId(dto.getSupplierId());
        entity.setDeclarationNumber(dto.getDeclarationNumber());
        entity.setDeclarationDate(dto.getDeclarationDate());
        entity.setCustomsPostCode(dto.getCustomsPostCode());
        entity.setCustomsRegime(dto.getCustomsRegime());
        entity.setBrokerName(dto.getBrokerName());
        entity.setTnvedCodeId(dto.getTnvedCodeId());
        entity.setCountryOfOrigin(dto.getCountryOfOrigin());
        entity.setCustomsValue(dto.getCustomsValue());
        entity.setDutyAmount(dto.getDutyAmount());
        entity.setVatAmount(dto.getVatAmount());
        entity.setExciseAmount(dto.getExciseAmount());
        entity.setFeesAmount(dto.getFeesAmount());
        entity.setTotalCustomsAmount(dto.getTotalCustomsAmount());
        entity.setCurrencyCode(dto.getCurrencyCode());
        entity.setSubmittedAt(dto.getSubmittedAt());
        entity.setReleasedAt(dto.getReleasedAt());
        entity.setAttachmentId(dto.getAttachmentId());
        entity.setRemarks(dto.getRemarks());
        return entity;
    }

    public void updateEntity(CustomsDeclaration entity, CustomsDeclarationUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setImportDeliveryId(dto.getImportDeliveryId());
        entity.setAsnId(dto.getAsnId());
        entity.setPurchaseOrderId(dto.getPurchaseOrderId());
        entity.setSupplierId(dto.getSupplierId());
        entity.setDeclarationNumber(dto.getDeclarationNumber());
        entity.setDeclarationDate(dto.getDeclarationDate());
        entity.setCustomsPostCode(dto.getCustomsPostCode());
        entity.setCustomsRegime(dto.getCustomsRegime());
        entity.setBrokerName(dto.getBrokerName());
        entity.setTnvedCodeId(dto.getTnvedCodeId());
        entity.setCountryOfOrigin(dto.getCountryOfOrigin());
        entity.setCustomsValue(dto.getCustomsValue());
        entity.setDutyAmount(dto.getDutyAmount());
        entity.setVatAmount(dto.getVatAmount());
        entity.setExciseAmount(dto.getExciseAmount());
        entity.setFeesAmount(dto.getFeesAmount());
        entity.setCurrencyCode(dto.getCurrencyCode());
        entity.setAttachmentId(dto.getAttachmentId());
        entity.setRemarks(dto.getRemarks());
    }
}
