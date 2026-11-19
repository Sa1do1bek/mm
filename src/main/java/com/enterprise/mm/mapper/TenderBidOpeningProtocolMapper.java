package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.TenderBidOpeningProtocolResponse;
import com.enterprise.mm.payload.TenderBidOpeningProtocolUpdateRequest;
import com.enterprise.mm.payload.TenderBidOpeningProtocolCreateRequest;
import com.enterprise.mm.entity.TenderBidOpeningProtocol;
import org.springframework.stereotype.Component;

@Component
public class TenderBidOpeningProtocolMapper {
    public TenderBidOpeningProtocolResponse toResponse(TenderBidOpeningProtocol entity) {
        if (entity == null) {
            return null;
        }
        TenderBidOpeningProtocolResponse dto = new TenderBidOpeningProtocolResponse();
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
        dto.setRfqId(entity.getRfqId());
        dto.setRfqCode(BaseMapper.code(entity.getRfq()));
        dto.setRfqName(BaseMapper.name(entity.getRfq()));
        dto.setProtocolNumber(entity.getProtocolNumber());
        dto.setOpenedAt(entity.getOpenedAt());
        dto.setOpenedBy(entity.getOpenedBy());
        dto.setQuorumRequired(entity.getQuorumRequired());
        dto.setQuorumPresent(entity.getQuorumPresent());
        dto.setQuorumConfirmed(entity.isQuorumConfirmed());
        dto.setQuotationCount(entity.getQuotationCount());
        dto.setAcceptedQuotationCount(entity.getAcceptedQuotationCount());
        dto.setRejectedQuotationCount(entity.getRejectedQuotationCount());
        dto.setMinutes(entity.getMinutes());
        dto.setPostedAt(entity.getPostedAt());
        return dto;
    }

    public TenderBidOpeningProtocol toEntity(TenderBidOpeningProtocolCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        TenderBidOpeningProtocol entity = new TenderBidOpeningProtocol();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setRfqId(dto.getRfqId());
        entity.setOpenedBy(dto.getOpenedBy());
        entity.setQuorumPresent(dto.getQuorumPresent());
        entity.setMinutes(dto.getNotes());
        return entity;
    }

    public void updateEntity(TenderBidOpeningProtocol entity, TenderBidOpeningProtocolUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setRfqId(dto.getRfqId());
        entity.setOpenedBy(dto.getOpenedBy());
        entity.setQuorumPresent(dto.getQuorumPresent());
        entity.setMinutes(dto.getNotes());
    }
}
