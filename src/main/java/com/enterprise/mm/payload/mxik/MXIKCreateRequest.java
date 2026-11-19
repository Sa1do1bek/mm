package com.enterprise.mm.payload.mxik;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MXIKCreateRequest {
    @NotBlank(message = "MXIK name is required")
    private String name;
    @NotBlank(message = "MXIK kod is required")
    private String kod;
    @NotNull(message = "type is required")
    private String type;
}
