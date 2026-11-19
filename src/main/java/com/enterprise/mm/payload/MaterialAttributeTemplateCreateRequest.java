package com.enterprise.mm.payload;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class MaterialAttributeTemplateCreateRequest {
    private String code;
    private String name;
    private String status;
    private String description;
    private UUID materialGroupId;
    private String attributeName;
    private Boolean required;
    private String dataType;
    private String allowedValues;
}
