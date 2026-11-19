package com.enterprise.mm.payload;

import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MaterialEquipmentCompatibilityResponse extends BaseRecordDto {
    private UUID materialId;
    private UUID equipmentId;
    private String compatibilityType;
    private String manufacturerPartNo;
    private boolean required;
    private LocalDate validFrom;
    private LocalDate validTo;
}