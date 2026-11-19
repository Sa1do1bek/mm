package com.enterprise.mm.payload;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class MaterialAttributeTemplateResponse extends BaseRecordDto {
    private UUID materialGroupId;
    private String attributeName;
    private Boolean required;
    private String dataType;
    private String allowedValues;
}
