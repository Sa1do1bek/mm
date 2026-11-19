package com.enterprise.mm.payload;

import java.util.Set;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UserResponse extends BaseRecordDto {
    private UUID employeeId;
    private String username;
    private String password;
    private String fullName;
    private UUID departmentId;
    private String email;
    private boolean enabled;
    private boolean superAdmin;
    private Set<String> roles;
}
