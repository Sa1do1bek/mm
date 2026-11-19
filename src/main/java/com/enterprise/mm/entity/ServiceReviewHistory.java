package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "service_review_history")
public class ServiceReviewHistory extends BusinessEntity {
    @Column(name = "service_request_id")
    private UUID serviceRequestId;

    @Column(name = "service_act_id")
    private UUID serviceActId;

    @Column(name = "service_claim_id")
    private UUID serviceClaimId;

    @Column(name = "claim_origin_type", length = 30)
    private String claimOriginType;

    @Column(name = "claim_origin_id")
    private UUID claimOriginId;

    @Column(name = "review_type", nullable = false, length = 60)
    private String reviewType;

    @Column(nullable = false, length = 60)
    private String decision;

    @Column(name = "actor", length = 120)
    private String actor;

    @Column(name = "acted_at", nullable = false)
    private LocalDateTime actedAt;

    @Column(columnDefinition = "text")
    private String commentText;
}
