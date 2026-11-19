package com.enterprise.mm.mapper;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.payload.ProcurementCampaignResponse;
import com.enterprise.mm.payload.ProcurementCampaignUpdateRequest;
import com.enterprise.mm.payload.ProcurementCampaignCreateRequest;
import com.enterprise.mm.entity.ProcurementCampaign;
import com.enterprise.mm.repository.MaterialGroupRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.RFQRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ProcurementCampaignMapper {
    private final MaterialGroupRepository materialGroupRepository;
    private final RFQRepository rfqRepository;
    private final PurchaseRequestRepository purchaseRequestRepository;

    public ProcurementCampaignMapper(
        MaterialGroupRepository materialGroupRepository,
        RFQRepository rfqRepository,
        PurchaseRequestRepository purchaseRequestRepository
    ) {
        this.materialGroupRepository = materialGroupRepository;
        this.rfqRepository = rfqRepository;
        this.purchaseRequestRepository = purchaseRequestRepository;
    }

    public ProcurementCampaignResponse toResponse(ProcurementCampaign entity) {
        if (entity == null) {
            return null;
        }
        ProcurementCampaignResponse dto = new ProcurementCampaignResponse();
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
        dto.setCampaignNumber(entity.getCampaignNumber());
        dto.setMaterialGroupId(entity.getMaterialGroupId());
        dto.setMaterialGroupName(materialGroupName(entity.getMaterialGroupId()));
        dto.setRfqId(entity.getRfqId());
        dto.setRfqName(rfqName(entity.getRfqId()));
        dto.setPriceDiscoveryRfqId(entity.getPriceDiscoveryRfqId());
        dto.setPriceBasisCalculationId(entity.getPriceBasisCalculationId());
        dto.setPurchaseRequestId(entity.getPurchaseRequestId());
        dto.setPurchaseRequestName(purchaseRequestName(entity.getPurchaseRequestId()));
        dto.setMethodDecisionId(entity.getMethodDecisionId());
        dto.setProcurementProcedureId(entity.getProcurementProcedureId());
        dto.setCampaignType(entity.getCampaignType());
        dto.setProcedureType(entity.getProcedureType());
        dto.setTargetSupplierCount(entity.getTargetSupplierCount());
        dto.setPlannedSpendAmount(entity.getPlannedSpendAmount());
        dto.setApprovedSpendAmount(entity.getApprovedSpendAmount());
        dto.setSavingsTargetAmount(entity.getSavingsTargetAmount());
        dto.setCurrencyCode(entity.getCurrencyCode());
        dto.setPlannedStartDate(entity.getPlannedStartDate());
        dto.setPlannedEndDate(entity.getPlannedEndDate());
        dto.setLaunchedAt(entity.getLaunchedAt());
        dto.setCompletedAt(entity.getCompletedAt());
        return dto;
    }

    public ProcurementCampaign toEntity(ProcurementCampaignCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        ProcurementCampaign entity = new ProcurementCampaign();
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setProcurementProcedureId(dto.getProcurementProcedureId());
        entity.setTargetSupplierCount(dto.getTargetSupplierCount());
        entity.setPlannedSpendAmount(dto.getPlannedSpendAmount());
        entity.setApprovedSpendAmount(dto.getApprovedSpendAmount());
        entity.setSavingsTargetAmount(dto.getSavingsTargetAmount());
        entity.setCurrencyCode(dto.getCurrencyCode());
        entity.setPlannedStartDate(dto.getPlannedStartDate());
        entity.setPlannedEndDate(dto.getPlannedEndDate());
        return entity;
    }

    public void updateEntity(ProcurementCampaign entity, ProcurementCampaignUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setTargetSupplierCount(dto.getTargetSupplierCount());
        entity.setPlannedSpendAmount(dto.getPlannedSpendAmount());
        entity.setApprovedSpendAmount(dto.getApprovedSpendAmount());
        entity.setSavingsTargetAmount(dto.getSavingsTargetAmount());
        entity.setCurrencyCode(dto.getCurrencyCode());
        entity.setPlannedStartDate(dto.getPlannedStartDate());
        entity.setPlannedEndDate(dto.getPlannedEndDate());
    }

    private String nameOf(BusinessEntity entity) {
        return entity == null ? null : entity.getName();
    }

    private String materialGroupName(UUID id) {
        return id == null ? null : nameOf(materialGroupRepository.findByIdAndIsDeletedFalse(id).orElse(null));
    }

    private String rfqName(UUID id) {
        return id == null ? null : nameOf(rfqRepository.findByIdAndIsDeletedFalse(id).orElse(null));
    }

    private String purchaseRequestName(UUID id) {
        return id == null ? null : nameOf(purchaseRequestRepository.findByIdAndIsDeletedFalse(id).orElse(null));
    }
}
