package com.enterprise.mm.payload;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ServiceReviewHistoryResponse extends BaseRecordDto {
    private UUID serviceRequestId;
    private UUID serviceActId;
    private UUID serviceClaimId;
    private String claimOriginType;
    private UUID claimOriginId;
    private String reviewType;
    private String decision;
    private String actor;
    private LocalDateTime actedAt;
    private String commentText;
}
