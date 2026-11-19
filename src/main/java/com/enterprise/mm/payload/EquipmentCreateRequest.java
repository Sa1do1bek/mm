package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class EquipmentCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private String externalEquipmentId;
    private String model;
    private UUID manufacturerId;
    private String manufacturer;
    private String serialNumber;
    private UUID departmentId;
    private String location;
}
