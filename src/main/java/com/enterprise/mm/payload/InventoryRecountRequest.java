package com.enterprise.mm.payload;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class InventoryRecountRequest {
    private Long expectedLineVersion;
    private Long countRevision;
    private String reason;
}
