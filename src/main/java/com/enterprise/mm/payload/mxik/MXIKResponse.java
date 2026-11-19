package com.enterprise.mm.payload.mxik;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MXIKResponse {
    private Long id;
    private String name;
    private String nameRu;
    private String kod;
    private String type;
    private String attributeName;
    private String attributeNameRu;
    private String attributeNameCyril;
}
