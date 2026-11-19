package com.enterprise.mm.mapper;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.entity.AssetAcceptance;
import com.enterprise.mm.entity.AssetAcceptanceLine;
import com.enterprise.mm.entity.AssetAcceptanceUnit;
import com.enterprise.mm.entity.AssetEvent;
import com.enterprise.mm.entity.AssetHandover;
import com.enterprise.mm.entity.AssetHandoverLine;
import com.enterprise.mm.entity.AssetManagementSettings;
import com.enterprise.mm.entity.AssetOpeningImport;
import com.enterprise.mm.entity.AssetVerification;
import com.enterprise.mm.entity.AssetVerificationLine;
import com.enterprise.mm.entity.FixedAsset;
import com.enterprise.mm.payload.AssetAcceptanceLineResponse;
import com.enterprise.mm.payload.AssetAcceptanceResponse;
import com.enterprise.mm.payload.AssetAcceptanceUnitResponse;
import com.enterprise.mm.payload.AssetEventResponse;
import com.enterprise.mm.payload.AssetHandoverLineResponse;
import com.enterprise.mm.payload.AssetHandoverResponse;
import com.enterprise.mm.payload.AssetOpeningImportResponse;
import com.enterprise.mm.payload.AssetSettingsResponse;
import com.enterprise.mm.payload.AssetVerificationLineResponse;
import com.enterprise.mm.payload.AssetVerificationResponse;
import com.enterprise.mm.payload.BaseRecordDto;
import com.enterprise.mm.payload.FixedAssetResponse;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
@lombok.RequiredArgsConstructor
public class AssetFoundationMapper {
    private final com.enterprise.mm.service.UnitOfMeasureDisplayService unitDisplay;
    public AssetSettingsResponse toResponse(AssetManagementSettings entity) {
        if (entity == null) {
            return null;
        }
        AssetSettingsResponse response = copyBase(entity, new AssetSettingsResponse());
        response.setCentralWarehouseId(entity.getCentralWarehouseId());
        response.setCentralStorageLocationId(entity.getCentralStorageLocationId());
        response.setInventoryNumberPrefix(entity.getInventoryNumberPrefix());
        response.setInventoryNumberNextValue(entity.getInventoryNumberNextValue());
        response.setInventoryNumberPadding(entity.getInventoryNumberPadding());
        response.setAutoNumberAtReceipt(entity.isAutoNumberAtReceipt());
        response.setRequireSerialNumber(entity.isRequireSerialNumber());
        response.setCentralWarehouseConfigured(entity.getCentralWarehouseId() != null);
        return response;
    }

    public AssetAcceptanceResponse toResponse(
        AssetAcceptance entity,
        List<AssetAcceptanceLineResponse> lines
    ) {
        if (entity == null) {
            return null;
        }
        AssetAcceptanceResponse response = copyBase(entity, new AssetAcceptanceResponse());
        response.setContractId(entity.getContractId());
        response.setContractNumber(entity.getContract() != null ? entity.getContract().getContractNumber() : null);
        response.setContractName(entity.getContract() != null ? entity.getContract().getName() : null);
        response.setPurchaseOrderId(entity.getPurchaseOrderId());
        response.setPurchaseOrderName(entity.getPurchaseOrder() != null ? entity.getPurchaseOrder().getName() : null);
        response.setSupplierId(entity.getSupplierId());
        response.setSupplierName(entity.getSupplier() != null ? entity.getSupplier().getName() : null);
        response.setAsnId(entity.getAsnId());
        response.setDeliveryReference(entity.getDeliveryReference());
        response.setWarehouseId(entity.getWarehouseId());
        response.setWarehouseName(entity.getWarehouse() != null ? entity.getWarehouse().getName() : null);
        response.setStorageLocationId(entity.getStorageLocationId());
        response.setReceivedByEmployeeId(entity.getReceivedByEmployeeId());
        response.setReceiptDate(entity.getReceiptDate());
        response.setReceivedAt(entity.getReceivedAt());
        response.setCurrencyCode(entity.getCurrencyCode());
        response.setExchangeRateSource(entity.getExchangeRateSource());
        response.setSourceEvidence(entity.getSourceEvidence());
        response.setApprovalStatus(enumName(entity.getApprovalStatus()));
        response.setPostingStatus(enumName(entity.getPostingStatus()));
        response.setApprovalInstanceId(entity.getApprovalInstanceId());
        response.setPostedAt(entity.getPostedAt());
        response.setPostedBy(entity.getPostedBy());
        response.setLines(lines == null ? List.of() : lines);
        return response;
    }

    public AssetAcceptanceLineResponse toResponse(
        AssetAcceptanceLine entity,
        List<AssetAcceptanceUnitResponse> units
    ) {
        if (entity == null) {
            return null;
        }
        AssetAcceptanceLineResponse response = copyBase(entity, new AssetAcceptanceLineResponse());
        response.setAssetAcceptanceId(entity.getAssetAcceptanceId());
        response.setPurchaseOrderItemId(entity.getPurchaseOrderItemId());
        response.setContractSpecificationId(entity.getContractSpecificationId());
        response.setMaterialId(entity.getMaterialId());
        response.setMaterialCode(entity.getMaterial() != null ? entity.getMaterial().getCode() : null);
        response.setMaterialName(entity.getMaterial() != null ? entity.getMaterial().getName() : null);
        response.setMaterialTypeSnapshot(enumName(entity.getMaterialTypeSnapshot()));
        response.setUnit(entity.getUnit());
        response.setUnitShortName(unitDisplay.shortName(entity, entity.getUnit(), entity.getMaterial()));
        response.setDeliveredQuantity(entity.getDeliveredQuantity());
        response.setAcceptedQuantity(entity.getAcceptedQuantity());
        response.setRejectedQuantity(entity.getRejectedQuantity());
        response.setPendingQuantity(entity.getPendingQuantity());
        response.setHoldQuantity(entity.getHoldQuantity());
        response.setQualityDecision(enumName(entity.getQualityDecision()));
        response.setAcceptedValue(entity.getAcceptedValue());
        response.setAcceptedCurrencyCode(entity.getAcceptedCurrencyCode());
        response.setUnits(units == null ? List.of() : units);
        return response;
    }

    public AssetAcceptanceUnitResponse toResponse(AssetAcceptanceUnit entity) {
        if (entity == null) {
            return null;
        }
        AssetAcceptanceUnitResponse response = copyBase(entity, new AssetAcceptanceUnitResponse());
        response.setAssetAcceptanceLineId(entity.getAssetAcceptanceLineId());
        response.setPurchaseOrderItemId(entity.getPurchaseOrderItemId());
        response.setMaterialId(entity.getMaterialId());
        response.setMaterialTypeSnapshot(enumName(entity.getMaterialTypeSnapshot()));
        response.setLineSequence(entity.getLineSequence());
        response.setReceivingUnitNumber(entity.getReceivingUnitNumber());
        response.setManufacturerSerialNumber(entity.getManufacturerSerialNumber());
        response.setSerialNumber(entity.getSerialNumber());
        response.setTemporaryTag(entity.getTemporaryTag());
        response.setCondition(entity.getCondition());
        response.setQualityDecision(enumName(entity.getQualityDecision()));
        response.setQualityDecisionAt(entity.getQualityDecisionAt());
        response.setQualityDecisionBy(entity.getQualityDecisionBy());
        response.setPostedFixedAssetId(entity.getPostedFixedAssetId());
        response.setPostedFixedAssetName(entity.getPostedFixedAsset() != null ? entity.getPostedFixedAsset().getName() : null);
        return response;
    }

    public FixedAssetResponse toResponse(FixedAsset entity) {
        if (entity == null) {
            return null;
        }
        FixedAssetResponse response = copyBase(entity, new FixedAssetResponse());
        response.setMaterialId(entity.getMaterialId());
        response.setMaterialName(entity.getMaterial() != null ? entity.getMaterial().getName() : null);
        if (entity.getMaterial() != null && entity.getMaterial().getMxik() != null) {
            response.setMaterialMxikName(BaseMapper.firstNonBlank(
                entity.getMaterial().getMxik().getName(),
                entity.getMaterial().getMxik().getAttributeName(),
                entity.getMaterial().getMxik().getAttributeNameRu(),
                entity.getMaterial().getMxik().getAttributeNameCyril()
            ));
        }
        response.setEquipmentId(entity.getEquipmentId());
        response.setSourceAcceptanceUnitId(entity.getSourceAcceptanceUnitId());
        response.setOpeningSourceType(entity.getOpeningSourceType());
        response.setOpeningSourceId(entity.getOpeningSourceId());
        response.setAssetCode(entity.getAssetCode());
        response.setInventoryNumber(entity.getInventoryNumber());
        response.setSerialNumber(entity.getSerialNumber());
        response.setLifecycleStatus(enumName(entity.getLifecycleStatus()));
        response.setCustodyKind(enumName(entity.getCustodyKind()));
        response.setCustodianEmployeeId(entity.getCustodianEmployeeId());
        response.setCustodianEmployeeName(entity.getCustodianEmployee() != null ? entity.getCustodianEmployee().getName() : null);
        response.setDepartmentId(entity.getDepartmentId());
        response.setDepartmentName(entity.getDepartment() != null ? entity.getDepartment().getName() : null);
        response.setWarehouseId(entity.getWarehouseId());
        response.setWarehouseName(entity.getWarehouse() != null ? entity.getWarehouse().getName() : null);
        response.setStorageLocationId(entity.getStorageLocationId());
        response.setOperatingLocation(entity.getOperatingLocation());
        response.setActiveTransferId(entity.getActiveTransferId());
        response.setAcquisitionValue(entity.getAcquisitionValue());
        response.setAcquisitionCurrencyCode(entity.getAcquisitionCurrencyCode());
        response.setAcquisitionExchangeRate(entity.getAcquisitionExchangeRate());
        response.setAcquisitionEvidenceType(entity.getAcquisitionEvidenceType());
        response.setAcquisitionEvidenceId(entity.getAcquisitionEvidenceId());
        response.setFirstCommissionedOn(entity.getFirstCommissionedOn());
        response.setFirstCommissionedAt(entity.getFirstCommissionedAt());
        response.setFirstCommissionedBy(entity.getFirstCommissionedBy());
        response.setDisposedAt(entity.getDisposedAt());
        response.setCategory(enumName(entity.getCategory()));
        response.setDepreciationMethod(enumName(entity.getDepreciationMethod()));
        response.setUsefulLifeMonths(entity.getUsefulLifeMonths());
        response.setSalvageValue(entity.getSalvageValue());
        response.setAccumulatedDepreciation(entity.getAccumulatedDepreciation());
        response.setCurrentBookValue(entity.getCurrentBookValue() != null ? entity.getCurrentBookValue() : (entity.getAcquisitionValue() != null ? entity.getAcquisitionValue().subtract(entity.getAccumulatedDepreciation() != null ? entity.getAccumulatedDepreciation() : java.math.BigDecimal.ZERO) : null));
        response.setDepreciationStartDate(entity.getDepreciationStartDate());
        response.setLastDepreciationDate(entity.getLastDepreciationDate());
        response.setDepreciationRate(entity.getDepreciationRate());
        return response;
    }

    public com.enterprise.mm.payload.AssetDepreciationEntryResponse toResponse(com.enterprise.mm.entity.AssetDepreciationEntry entity) {
        if (entity == null) {
            return null;
        }
        com.enterprise.mm.payload.AssetDepreciationEntryResponse response = copyBase(entity, new com.enterprise.mm.payload.AssetDepreciationEntryResponse());
        response.setFixedAssetId(entity.getFixedAssetId());
        response.setFixedAssetName(entity.getFixedAsset() != null ? entity.getFixedAsset().getName() : null);
        response.setAssetCode(entity.getFixedAsset() != null ? entity.getFixedAsset().getAssetCode() : null);
        response.setInventoryNumber(entity.getFixedAsset() != null ? entity.getFixedAsset().getInventoryNumber() : null);
        response.setPeriodYear(entity.getPeriodYear());
        response.setPeriodMonth(entity.getPeriodMonth());
        response.setPeriodDate(entity.getPeriodDate());
        response.setOpeningBookValue(entity.getOpeningBookValue());
        response.setDepreciationAmount(entity.getDepreciationAmount());
        response.setClosingBookValue(entity.getClosingBookValue());
        response.setAccumulatedDepreciation(entity.getAccumulatedDepreciation());
        response.setCalculationMethod(enumName(entity.getCalculationMethod()));
        response.setPosted(entity.isPosted());
        response.setPostedAt(entity.getPostedAt());
        response.setPostedBy(entity.getPostedBy());
        response.setRunReference(entity.getRunReference());
        return response;
    }

    public AssetEventResponse toResponse(AssetEvent entity) {
        if (entity == null) {
            return null;
        }
        AssetEventResponse response = copyBase(entity, new AssetEventResponse());
        response.setFixedAssetId(entity.getFixedAssetId());
        response.setFixedAssetName(entity.getFixedAsset() != null ? entity.getFixedAsset().getName() : null);
        response.setEventType(entity.getEventType());
        response.setCommandId(entity.getCommandId());
        response.setIdempotencyKey(entity.getIdempotencyKey());
        response.setBeforeLifecycleStatus(enumName(entity.getBeforeLifecycleStatus()));
        response.setAfterLifecycleStatus(enumName(entity.getAfterLifecycleStatus()));
        response.setBeforeCustodyKind(enumName(entity.getBeforeCustodyKind()));
        response.setAfterCustodyKind(enumName(entity.getAfterCustodyKind()));
        response.setBeforeCustodyJson(entity.getBeforeCustodyJson());
        response.setAfterCustodyJson(entity.getAfterCustodyJson());
        response.setEffectiveDate(entity.getEffectiveDate());
        response.setRecordedAt(entity.getRecordedAt());
        response.setActorUsername(entity.getActorUsername());
        response.setActorEmployeeId(entity.getActorEmployeeId());
        response.setReason(entity.getReason());
        response.setApprovalInstanceId(entity.getApprovalInstanceId());
        response.setSourceDocumentType(entity.getSourceDocumentType());
        response.setSourceDocumentId(entity.getSourceDocumentId());
        response.setAggregateVersion(entity.getAggregateVersion());
        return response;
    }

    public AssetHandoverResponse toResponse(AssetHandover entity, List<AssetHandoverLineResponse> lines) {
        if (entity == null) {
            return null;
        }
        AssetHandoverResponse response = copyBase(entity, new AssetHandoverResponse());
        response.setContractId(entity.getContractId());
        response.setHandoverType(enumName(entity.getHandoverType()));
        response.setFromCustodyType(enumName(entity.getFromCustodyType()));
        response.setFromEmployeeId(entity.getFromEmployeeId());
        response.setFromEmployeeName(entity.getFromEmployee() != null ? entity.getFromEmployee().getName() : null);
        response.setFromDepartmentId(entity.getFromDepartmentId());
        response.setFromDepartmentName(entity.getFromDepartment() != null ? entity.getFromDepartment().getName() : null);
        response.setFromWarehouseId(entity.getFromWarehouseId());
        response.setFromWarehouseName(entity.getFromWarehouse() != null ? entity.getFromWarehouse().getName() : null);
        response.setToCustodyType(enumName(entity.getToCustodyType()));
        response.setToEmployeeId(entity.getToEmployeeId());
        response.setToEmployeeName(entity.getToEmployee() != null ? entity.getToEmployee().getName() : null);
        response.setToDepartmentId(entity.getToDepartmentId());
        response.setToDepartmentName(entity.getToDepartment() != null ? entity.getToDepartment().getName() : null);
        response.setToWarehouseId(entity.getToWarehouseId());
        response.setToWarehouseName(entity.getToWarehouse() != null ? entity.getToWarehouse().getName() : null);
        response.setStatus(entity.getStatus());
        response.setApprovalInstanceId(entity.getApprovalInstanceId());
        response.setApprovedAt(entity.getApprovedAt());
        response.setDispatchedAt(entity.getDispatchedAt());
        response.setAcknowledgedAt(entity.getAcknowledgedAt());
        response.setExceptionStatus(entity.getExceptionStatus());
        response.setClosedAt(entity.getClosedAt());
        response.setLines(lines == null ? List.of() : lines);
        return response;
    }

    public AssetHandoverLineResponse toResponse(AssetHandoverLine entity) {
        if (entity == null) {
            return null;
        }
        AssetHandoverLineResponse response = copyBase(entity, new AssetHandoverLineResponse());
        response.setAssetHandoverId(entity.getAssetHandoverId());
        response.setFixedAssetId(entity.getFixedAssetId());
        response.setFixedAssetName(entity.getFixedAsset() != null ? entity.getFixedAsset().getName() : null);
        response.setAssetAllocationId(entity.getAssetAllocationId());
        response.setAssetAllocationName(entity.getAssetAllocation() != null ? entity.getAssetAllocation().getCode() : null);
        response.setLineStatus(enumName(entity.getLineStatus()));
        return response;
    }

    public AssetVerificationResponse toResponse(
        AssetVerification entity,
        List<AssetVerificationLineResponse> lines
    ) {
        if (entity == null) {
            return null;
        }
        AssetVerificationResponse response = copyBase(entity, new AssetVerificationResponse());
        response.setWarehouseId(entity.getWarehouseId());
        response.setWarehouseName(entity.getWarehouse() != null ? entity.getWarehouse().getName() : null);
        response.setDepartmentId(entity.getDepartmentId());
        response.setDepartmentName(entity.getDepartment() != null ? entity.getDepartment().getName() : null);
        response.setVerificationType(enumName(entity.getVerificationType()));
        response.setStatus(entity.getStatus());
        response.setStartedAt(entity.getStartedAt());
        response.setCompletedAt(entity.getCompletedAt());
        response.setLines(lines == null ? List.of() : lines);
        return response;
    }

    public AssetVerificationLineResponse toResponse(AssetVerificationLine entity) {
        if (entity == null) {
            return null;
        }
        AssetVerificationLineResponse response = copyBase(entity, new AssetVerificationLineResponse());
        response.setAssetVerificationId(entity.getAssetVerificationId());
        response.setAssetVerificationName(entity.getAssetVerification() != null ? entity.getAssetVerification().getCode() : null);
        response.setFixedAssetId(entity.getFixedAssetId());
        response.setFixedAssetName(entity.getFixedAsset() != null ? entity.getFixedAsset().getName() : null);
        response.setExpectedCustodyKind(enumName(entity.getExpectedCustodyKind()));
        response.setExpectedEmployeeId(entity.getExpectedEmployeeId());
        response.setExpectedDepartmentId(entity.getExpectedDepartmentId());
        response.setExpectedWarehouseId(entity.getExpectedWarehouseId());
        response.setObservedCustodyKind(enumName(entity.getObservedCustodyKind()));
        response.setObservedEmployeeId(entity.getObservedEmployeeId());
        response.setObservedDepartmentId(entity.getObservedDepartmentId());
        response.setObservedWarehouseId(entity.getObservedWarehouseId());
        response.setObservedAt(entity.getObservedAt());
        response.setDiscrepancyType(enumName(entity.getDiscrepancyType()));
        response.setResolutionStatus(enumName(entity.getResolutionStatus()));
        return response;
    }

    public AssetOpeningImportResponse toResponse(AssetOpeningImport entity) {
        if (entity == null) {
            return null;
        }
        AssetOpeningImportResponse response = copyBase(entity, new AssetOpeningImportResponse());
        response.setStatus(entity.getStatus());
        response.setSourceFileId(entity.getSourceFileId());
        response.setValidatedAt(entity.getValidatedAt());
        response.setPostedAt(entity.getPostedAt());
        response.setPostedBy(entity.getPostedBy());
        return response;
    }

    private <T extends BaseRecordDto> T copyBase(BusinessEntity entity, T response) {
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
        return response;
    }

    private String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
