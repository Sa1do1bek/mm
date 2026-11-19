package com.enterprise.mm.payload;

import com.enterprise.mm.enums.EmployeeStatus;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class EmployeeResponse extends BaseRecordDto {
    private UUID userId;
    private String externalId;
    private String personnelNumber;
    private UUID departmentId;
    private UUID warehouseId;
    private String warehouseName;
    private UUID positionId;
    private UUID managerEmployeeId;
    private String email;
    private String phone;
    private EmployeeStatus employmentStatus;
    private LocalDate hireDate;
    private LocalDate dismissalDate;
    private Set<String> roles;
}
