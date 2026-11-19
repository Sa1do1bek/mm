package com.enterprise.mm.payload;

import java.util.UUID;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DepartmentResponse extends BaseRecordDto {
    private String externalId;
    private UUID parentDepartmentId;
    private String departmentType;
    private UUID managerEmployeeId;
    private String costCenter;
    private String hierarchyPath;
    private Integer sortOrder;
    private Set<UUID> warehouseIds;
}
