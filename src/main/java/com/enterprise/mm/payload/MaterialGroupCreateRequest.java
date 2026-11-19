package com.enterprise.mm.payload;

import com.enterprise.mm.enums.MaterialGroupStatus;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MaterialGroupCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    private MaterialGroupStatus status;
    private String description;
    @Size(max = 160)
    private String additionalCategory;
    @Size(max = 160)
    private String lowerCategory;
}
