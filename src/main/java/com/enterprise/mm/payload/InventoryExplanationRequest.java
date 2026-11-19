package com.enterprise.mm.payload;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class InventoryExplanationRequest {
    private Long expectedLineVersion;
    private Long countRevision;
    private String requestedFrom;
    private String reason;
    private String explanation;
    private String reviewComment;
}
