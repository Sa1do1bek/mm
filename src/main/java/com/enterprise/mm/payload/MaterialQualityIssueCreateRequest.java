package com.enterprise.mm.payload;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class MaterialQualityIssueCreateRequest {
    private String code;
    private String name;
    private String status;
    private String description;
    private UUID materialId;
    private String issueType;
    private String severity;
    private String fieldName;
}
