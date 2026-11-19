package com.enterprise.mm.payload;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ManagementOpportunityPolicyResolveResponse {
    private boolean matched;
    private String resolutionReason;
    private ManagementOpportunityPolicyResponse policy;
}
