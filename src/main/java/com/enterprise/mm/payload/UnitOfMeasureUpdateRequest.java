package com.enterprise.mm.payload;

import com.enterprise.mm.enums.MeasurementUnitType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UnitOfMeasureUpdateRequest {
    private String code;
    private String name;
    private String measurementNameShort;
    private String measurementNameLong;
    private String measurementNameShortRu;
    private String measurementNameLongRu;
    private String measurementNameShortUz;
    private String measurementNameLongUz;
    private MeasurementUnitType unitType;
    @Min(0)
    @Max(7)
    private Integer stockQuantityScale;
}
