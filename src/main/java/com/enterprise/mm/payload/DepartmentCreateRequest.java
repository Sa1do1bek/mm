package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.util.UUID;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DepartmentCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private String externalId;
    private UUID parentDepartmentId;
    private String departmentType;
    private UUID managerEmployeeId;
    private String costCenter;
    private String hierarchyPath;
    private Integer sortOrder;
    private Set<UUID> warehouseIds;
}
