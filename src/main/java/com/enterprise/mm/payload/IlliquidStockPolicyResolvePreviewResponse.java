package com.enterprise.mm.payload;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class IlliquidStockPolicyResolvePreviewResponse {
    private boolean matched;
    private String resolutionReason;
    private IlliquidStockPolicyResponse policy;
}
