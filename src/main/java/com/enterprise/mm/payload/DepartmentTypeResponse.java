package com.enterprise.mm.payload;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DepartmentTypeResponse extends BaseRecordDto {
    private String levelCode;
    private Integer hierarchyLevel;
    private boolean systemType;
}
