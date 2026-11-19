package com.enterprise.mm.mapper;

import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.payload.UnitOfMeasureCreateRequest;
import com.enterprise.mm.payload.UnitOfMeasureResponse;
import com.enterprise.mm.payload.UnitOfMeasureUpdateRequest;
import com.enterprise.mm.service.UnitTypeLabelResolver;
import com.enterprise.mm.service.StockUomPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
@RequiredArgsConstructor
public class UnitOfMeasureMapper {
    private final UnitTypeLabelResolver unitTypeLabelResolver;

    public UnitOfMeasureResponse toResponse(UnitOfMeasure entity, Locale locale) {
        String lang = locale == null ? "en" : locale.getLanguage();

        String shortName = switch (lang) {
            case "ru" -> firstNonBlank(entity.getMeasurementNameShortRu(), entity.getMeasurementNameShort());
            case "uz" -> firstNonBlank(entity.getMeasurementNameShortUz(), entity.getMeasurementNameShort());
            default -> entity.getMeasurementNameShort();
        };

        String longName = switch (lang) {
            case "ru" -> firstNonBlank(entity.getMeasurementNameLongRu(), entity.getMeasurementNameLong());
            case "uz" -> firstNonBlank(entity.getMeasurementNameLongUz(), entity.getMeasurementNameLong());
            default -> entity.getMeasurementNameLong();
        };
        UnitOfMeasureResponse unitOfMeasureResponse = new UnitOfMeasureResponse();
        unitOfMeasureResponse.setId(entity.getId());
        unitOfMeasureResponse.setTenantId(entity.getTenantId());
        unitOfMeasureResponse.setCompanyId(entity.getCompanyId());
        unitOfMeasureResponse.setCode(entity.getCode());
        unitOfMeasureResponse.setName(entity.getName());
        unitOfMeasureResponse.setStatus(entity.getStatus());
        unitOfMeasureResponse.setDescription(entity.getDescription());
        unitOfMeasureResponse.setCreatedAt(entity.getCreatedAt());
        unitOfMeasureResponse.setUpdatedAt(entity.getUpdatedAt());
        unitOfMeasureResponse.setCreatedBy(entity.getCreatedBy());
        unitOfMeasureResponse.setUpdatedBy(entity.getUpdatedBy());
        unitOfMeasureResponse.setVersion(entity.getVersion());
        unitOfMeasureResponse.setMeasurementNameShort(shortName);
        unitOfMeasureResponse.setMeasurementNameLong(longName);
        unitOfMeasureResponse.setUnitType(entity.getUnitType() != null ? entity.getUnitType().name() : null);
        unitOfMeasureResponse.setUnitTypeLabel(unitTypeLabelResolver.resolve(entity.getUnitType(), locale));
        unitOfMeasureResponse.setStockQuantityScale(entity.getStockQuantityScale());
        unitOfMeasureResponse.setQuantityScale(StockUomPolicy.effectiveScale(entity));
        unitOfMeasureResponse.setDeleted(entity.isDeleted());
        return unitOfMeasureResponse;
    }

    private String firstNonBlank(String preferred, String fallback) {
        return (preferred != null && !preferred.isBlank()) ? preferred : fallback;
    }

    public UnitOfMeasure toEntity(UnitOfMeasureCreateRequest request) {
        if (request == null) {
            return null;
        }
        UnitOfMeasure entity = new UnitOfMeasure();
        entity.setCode(request.getCode());
        entity.setName(request.getName());
        entity.setMeasurementNameShort(request.getMeasurementNameShort());
        entity.setMeasurementNameLong(request.getMeasurementNameLong());
        entity.setMeasurementNameShortRu(request.getMeasurementNameShortRu());
        entity.setMeasurementNameLongRu(request.getMeasurementNameLongRu());
        entity.setMeasurementNameShortUz(request.getMeasurementNameShortUz());
        entity.setMeasurementNameLongUz(request.getMeasurementNameLongUz());
        entity.setUnitType(request.getUnitType());
        entity.setStockQuantityScale(request.getStockQuantityScale());
        return entity;
    }

    public void updateEntity(UnitOfMeasure entity, UnitOfMeasureUpdateRequest request) {
        if (entity == null || request == null) {
            return;
        }
        entity.setCode(request.getCode());
        entity.setName(request.getName());
        entity.setMeasurementNameShort(request.getMeasurementNameShort());
        entity.setMeasurementNameLong(request.getMeasurementNameLong());
        entity.setMeasurementNameShortRu(request.getMeasurementNameShortRu());
        entity.setMeasurementNameLongRu(request.getMeasurementNameLongRu());
        entity.setMeasurementNameShortUz(request.getMeasurementNameShortUz());
        entity.setMeasurementNameLongUz(request.getMeasurementNameLongUz());
        entity.setUnitType(request.getUnitType());
        // Omitted precision in existing clients preserves an approved override.
        if (request.getStockQuantityScale() != null) entity.setStockQuantityScale(request.getStockQuantityScale());
    }
}
