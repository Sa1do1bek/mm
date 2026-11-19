package com.enterprise.mm.payload;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SupplierUpdateRequest extends SupplierCreateRequest {
    @NotNull
    private Long expectedVersion;
}
