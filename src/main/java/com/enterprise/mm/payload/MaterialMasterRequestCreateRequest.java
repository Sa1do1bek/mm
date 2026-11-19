package com.enterprise.mm.payload;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class MaterialMasterRequestCreateRequest {
    private String code;
    private String name;
    private String status;
    private String description;
    private String requestType;
    private UUID materialId;
    private UUID targetMaterialId;
    private String proposedName;
    private UUID materialGroupId;
    private UUID uomId;
    private String mxikCode;
    private String tnvedCode;
    private String requestedBy;
}
