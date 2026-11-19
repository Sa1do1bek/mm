package com.enterprise.mm.payload;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RoleResponse extends BaseRecordDto {
    private String roleName;
    private Boolean globalRole;
}
