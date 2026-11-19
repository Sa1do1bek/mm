package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class IntegrationEventCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private String eventType;
    private String aggregateType;
    private UUID aggregateId;
    private String payload;
    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private String idempotencyKey;
    private String lastError;
    private String externalId;
}
