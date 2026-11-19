package com.enterprise.mm.payload;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LandedLogisticsEventCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String status;
    private String description;
    private UUID importDeliveryId;
    private String eventType;
    private LocalDateTime eventDate;
    private String location;
    private String carrierStatus;
    private String externalSystem;
    private String externalEventId;
    private boolean exceptionFlag;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime resolvedAt;
}
