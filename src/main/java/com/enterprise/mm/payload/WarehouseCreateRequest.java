package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class WarehouseCreateRequest {
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private String address;
    private List<UUID> responsibleEmployeeIds;
}
