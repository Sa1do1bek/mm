package com.enterprise.mm.payload;

import com.enterprise.mm.enums.EmployeeStatus;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class EmployeeCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private UUID userId;
    private String externalId;
    private String personnelNumber;
    private UUID departmentId;
    private UUID warehouseId;
    @JsonIgnore
    private boolean warehouseAssignmentProvided;
    private UUID positionId;
    private UUID managerEmployeeId;
    private String email;
    private String phone;
    private EmployeeStatus employmentStatus;
    private LocalDate hireDate;
    private LocalDate dismissalDate;
    private Set<String> roles;

    public void setWarehouseId(UUID warehouseId) {
        this.warehouseId = warehouseId;
        this.warehouseAssignmentProvided = true;
    }
}
