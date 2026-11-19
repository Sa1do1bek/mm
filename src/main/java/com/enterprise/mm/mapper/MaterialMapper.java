package com.enterprise.mm.mapper;

import com.enterprise.mm.entity.MXIK;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.enums.HazardClass;
import com.enterprise.mm.enums.MaterialStatus;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.enums.MaterialSubtype;
import com.enterprise.mm.enums.TrackingMode;
import com.enterprise.mm.enums.ValuationMethod;
import com.enterprise.mm.payload.MaterialCreateRequest;
import com.enterprise.mm.payload.MaterialResponse;
import com.enterprise.mm.payload.MaterialUpdateRequest;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class MaterialMapper {
    public MaterialResponse toResponse(Material entity) {
        if (entity == null) {
            return null;
        }
        MaterialResponse response = new MaterialResponse();
        response.setId(entity.getId());
        response.setSourceData(entity.getSourceData());
        response.setTenantId(entity.getTenantId());
        response.setCompanyId(entity.getCompanyId());
        response.setCode(entity.getCode());
        response.setName(entity.getName());
        response.setStatus(entity.getStatus());
        response.setMaterialStatus(parseEnum(MaterialStatus.class, entity.getStatus()));
        response.setDescription(entity.getDescription());
        response.setDeleted(entity.isDeleted());
        response.setCreatedAt(entity.getCreatedAt());
        response.setUpdatedAt(entity.getUpdatedAt());
        response.setCreatedBy(entity.getCreatedBy());
        response.setUpdatedBy(entity.getUpdatedBy());
        response.setVersion(entity.getVersion());
        response.setMaterialGroupId(entity.getMaterialGroupId());
        response.setMaterialGroupCode(BaseMapper.code(entity.getMaterialGroup()));
        response.setMaterialGroupName(BaseMapper.name(entity.getMaterialGroup()));
        response.setUnitOfMeasureId(entity.getUnitOfMeasureId());
        response.setIncomingInspectionRequired(entity.isIncomingInspectionRequired());
        if (entity.getUnitOfMeasure() != null) {
            response.setUnitOfMeasureCode(entity.getUnitOfMeasure().getCode());
            response.setUnitOfMeasureShortName(com.enterprise.mm.common.UnitOfMeasureLabels.localizedShort(entity.getUnitOfMeasure(), org.springframework.context.i18n.LocaleContextHolder.getLocale()));
        response.setUnitOfMeasureName(firstNonBlank(
                entity.getUnitOfMeasure().getMeasurementNameLong(),
                entity.getUnitOfMeasure().getName()
            ));
        }
        response.setMaterialType(entity.getMaterialType() == null ? MaterialType.TMB : entity.getMaterialType());
        response.setMaterialSubtype(response.getMaterialType() == MaterialType.TMB
            ? (entity.getMaterialSubtype() == null ? MaterialSubtype.MATERIAL : entity.getMaterialSubtype())
            : null);
        response.setTrackingMode(entity.getTrackingMode() == null ? TrackingMode.QUANTITY : entity.getTrackingMode());
        response.setBatchTrackingPolicy(entity.getBatchTrackingPolicy() == null
            ? TrackingMode.batchTrackingPolicy(entity.getTrackingMode())
            : entity.getBatchTrackingPolicy());
        response.setSerialTrackingPolicy(entity.getSerialTrackingPolicy() == null
            ? TrackingMode.serialTrackingPolicy(entity.getTrackingMode())
            : entity.getSerialTrackingPolicy());
        response.setValuationMethod(entity.getValuationMethod());
        response.setMinStock(entity.getMinStock());
        response.setSafetyStock(entity.getSafetyStock());
        response.setReorderPoint(entity.getReorderPoint());
        response.setBarcode(entity.getBarcode());
        response.setQrCode(entity.getQrCode());
        response.setRfidTag(entity.getRfidTag());
        response.setAnalytics1(entity.getAnalytics1());
        response.setAnalytics2(entity.getAnalytics2());
        response.setAnalytics3(entity.getAnalytics3());
        response.setMxikCode(entity.getMxikCode());
        response.setTnvedCodeId(entity.getTnvedCodeId());
        response.setTnvedCode(entity.getTnvedCode());
        response.setTnvedCodeName(BaseMapper.name(entity.getTnvedCodeRef()));
        response.setArticle(entity.getArticle());
        response.setManufacturerId(entity.getManufacturerId());
        response.setManufacturerName(firstNonBlank(
            BaseMapper.name(entity.getManufacturerRef()), entity.getManufacturer()));
        response.setManufacturer(firstNonBlank(BaseMapper.name(entity.getManufacturerRef()), entity.getManufacturer()));
        response.setCountryOfOrigin(entity.getCountryOfOrigin());
        response.setShelfLifeDays(entity.getShelfLifeDays());
        response.setHazardClass(parseEnum(HazardClass.class, entity.getHazardClass()));
        response.setMaxStock(entity.getMaxStock());
        response.setReplenishmentLeadTimeDays(entity.getReplenishmentLeadTimeDays());
        response.setAverageCost(entity.getAverageCost());
        response.setAverageCostCurrencyCode(entity.getAverageCostCurrencyCode());
        response.setAverageCostObservationId(entity.getAverageCostObservationId());
        response.setAverageCostSourceType(entity.getAverageCostSourceType());
        response.setAverageCostSourceEntityId(entity.getAverageCostSourceEntityId());
        response.setAverageCostSourceLineId(entity.getAverageCostSourceLineId());
        response.setAverageCostDocumentCode(entity.getAverageCostDocumentCode());
        response.setAverageCostObservedAt(entity.getAverageCostObservedAt());
        response.setAverageCostSelectedAt(entity.getAverageCostSelectedAt());
        response.setReplacementMaterialId(entity.getReplacementMaterialId());
        response.setReplacementMaterialCode(BaseMapper.materialCode(entity.getReplacementMaterial()));
        response.setReplacementMaterialName(BaseMapper.materialName(entity.getReplacementMaterial()));
        response.setLifecycleReason(entity.getLifecycleReason());
        response.setLifecycleChangedAt(entity.getLifecycleChangedAt());
        if (entity.getMxik() != null) {
            response.setMxikId(entity.getMxik().getId());
            response.setMxikCode(entity.getMxik().getKod());
            response.setMxikType(entity.getMxik().getType());
            response.setMxikAttributeName(firstNonBlank(
                entity.getMxik().getAttributeName(),
                entity.getMxik().getAttributeNameRu(),
                entity.getMxik().getAttributeNameCyril()
            ));
        }
        response.setAttributes(List.of());
        return response;
    }

    public Material toEntity(MaterialCreateRequest request) {
        if (request == null) {
            return null;
        }
        Material entity = new Material();
        entity.setCode(request.getCode());
        entity.setName(request.getName());
        entity.setStatus(enumName(request.getStatus()));
        entity.setDescription(request.getDescription());
        entity.setMaterialGroupId(request.getMaterialGroupId());
        entity.setUnitOfMeasureId(request.getUnitOfMeasureId());
        if (request.getIncomingInspectionRequired() != null) entity.setIncomingInspectionRequired(request.getIncomingInspectionRequired());
        entity.setMaterialType(request.getMaterialType());
        if (request.getMaterialSubtype() != null) entity.setMaterialSubtype(request.getMaterialSubtype());
        entity.setTrackingMode(request.getTrackingMode() == null ? TrackingMode.QUANTITY : request.getTrackingMode());
        entity.setBatchTrackingPolicy(request.getBatchTrackingPolicy());
        entity.setSerialTrackingPolicy(request.getSerialTrackingPolicy());
        entity.setValuationMethod(request.getValuationMethod());
        entity.setMinStock(request.getMinStock());
        entity.setSafetyStock(request.getSafetyStock());
        entity.setReorderPoint(request.getReorderPoint());
        entity.setBarcode(request.getBarcode());
        entity.setQrCode(request.getQrCode());
        entity.setRfidTag(request.getRfidTag());
        entity.setAnalytics1(request.getAnalytics1());
        entity.setAnalytics2(request.getAnalytics2());
        entity.setAnalytics3(request.getAnalytics3());
        entity.setMxikCode(request.getMxikCode());
        entity.setTnvedCodeId(request.getTnvedCodeId());
        entity.setTnvedCode(request.getTnvedCode());
        entity.setArticle(request.getArticle());
        entity.setManufacturerId(request.getManufacturerId());
        entity.setManufacturer(request.getManufacturer());
        entity.setCountryOfOrigin(request.getCountryOfOrigin());
        entity.setShelfLifeDays(request.getShelfLifeDays());
        entity.setHazardClass(enumName(request.getHazardClass()));
        entity.setMaxStock(request.getMaxStock());
        entity.setReplenishmentLeadTimeDays(request.getReplenishmentLeadTimeDays());
        entity.setAverageCostObservationId(request.getAverageCostObservationId());
        entity.setAverageCost(request.getAverageCost());
        entity.setAverageCostCurrencyCode(request.getAverageCostCurrencyCode());
        applyMxikReference(request, entity);
        return entity;
    }

    public void updateEntity(Material entity, MaterialUpdateRequest request) {
        if (entity == null || request == null) {
            return;
        }
        entity.setCode(request.getCode());
        entity.setName(request.getName());
        entity.setStatus(enumName(request.getStatus()));
        entity.setDescription(request.getDescription());
        entity.setMaterialGroupId(request.getMaterialGroupId());
        entity.setUnitOfMeasureId(request.getUnitOfMeasureId());
        if (request.getIncomingInspectionRequired() != null) entity.setIncomingInspectionRequired(request.getIncomingInspectionRequired());
        entity.setMaterialType(request.getMaterialType());
        if (request.getMaterialSubtype() != null) entity.setMaterialSubtype(request.getMaterialSubtype());
        entity.setTrackingMode(request.getTrackingMode() == null ? TrackingMode.QUANTITY : request.getTrackingMode());
        entity.setBatchTrackingPolicy(request.getBatchTrackingPolicy());
        entity.setSerialTrackingPolicy(request.getSerialTrackingPolicy());
        entity.setValuationMethod(request.getValuationMethod());
        entity.setMinStock(request.getMinStock());
        entity.setSafetyStock(request.getSafetyStock());
        entity.setReorderPoint(request.getReorderPoint());
        entity.setBarcode(request.getBarcode());
        entity.setQrCode(request.getQrCode());
        entity.setRfidTag(request.getRfidTag());
        entity.setAnalytics1(request.getAnalytics1());
        entity.setAnalytics2(request.getAnalytics2());
        entity.setAnalytics3(request.getAnalytics3());
        entity.setMxikCode(request.getMxikCode());
        entity.setTnvedCodeId(request.getTnvedCodeId());
        entity.setTnvedCode(request.getTnvedCode());
        entity.setArticle(request.getArticle());
        entity.setManufacturerId(request.getManufacturerId());
        entity.setManufacturer(request.getManufacturer());
        entity.setCountryOfOrigin(request.getCountryOfOrigin());
        entity.setShelfLifeDays(request.getShelfLifeDays());
        entity.setHazardClass(enumName(request.getHazardClass()));
        entity.setMaxStock(request.getMaxStock());
        entity.setReplenishmentLeadTimeDays(request.getReplenishmentLeadTimeDays());
        entity.setAverageCostObservationId(request.getAverageCostObservationId());
        entity.setAverageCost(request.getAverageCost());
        entity.setAverageCostCurrencyCode(request.getAverageCostCurrencyCode());
        applyMxikReference(request, entity);
    }

    private void applyMxikReference(MaterialCreateRequest request, Material entity) {
        if (request.getMxikCode() != null && !request.getMxikCode().isBlank()) {
            entity.setMxikCode(request.getMxikCode().trim());
        }
        if (request.getMxikId() != null) {
            MXIK mxik = new MXIK();
            mxik.setId(request.getMxikId());
            if (request.getMxikCode() != null && !request.getMxikCode().isBlank()) {
                mxik.setKod(request.getMxikCode().trim());
            }
            entity.setMxik(mxik);
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private <T extends Enum<T>> T parseEnum(Class<T> type, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
