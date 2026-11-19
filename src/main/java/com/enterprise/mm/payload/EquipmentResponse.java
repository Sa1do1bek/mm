package com.enterprise.mm.payload;

import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class EquipmentResponse extends BaseRecordDto {
    private String externalEquipmentId;
    private String model;
    private UUID manufacturerId;
    private String manufacturerName;
    private String manufacturer;
    private String serialNumber;
    private UUID departmentId;
    private String location;
}
