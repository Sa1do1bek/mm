package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.util.Set;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UserCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;

    private UUID companyId;
    private UUID departmentId;
    private UUID employeeId;
    private String description;
    private String username;
    private String password;
    private String fullName;
    private String email;
    private boolean enabled = true;
    private Set<String> roles;
}
