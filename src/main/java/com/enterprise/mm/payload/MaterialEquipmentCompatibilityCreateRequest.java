package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MaterialEquipmentCompatibilityCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private UUID materialId;
    private UUID equipmentId;
    private String compatibilityType;
    private String manufacturerPartNo;
    private boolean required;
    private LocalDate validFrom;
    private LocalDate validTo;
}
