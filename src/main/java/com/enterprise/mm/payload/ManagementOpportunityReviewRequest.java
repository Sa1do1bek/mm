package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ManagementOpportunityReviewRequest {
    @Size(max = 1000)
    private String comment;

    @Size(max = 1000)
    private String reason;
}
