package com.enterprise.mm.payload;

import com.enterprise.mm.enums.MaterialGroupStatus;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MaterialGroupResponse extends BaseRecordDto {
    private java.util.UUID parentId;
    private MaterialGroupStatus materialGroupStatus;
    private String additionalCategory;
    private String lowerCategory;
}
