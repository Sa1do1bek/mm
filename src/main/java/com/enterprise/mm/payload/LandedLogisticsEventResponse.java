package com.enterprise.mm.payload;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LandedLogisticsEventResponse extends BaseRecordDto {
    private UUID importDeliveryId;
    private String importDeliveryCode;
    private String importDeliveryName;
    private String eventType;
    private LocalDateTime eventDate;
    private String location;
    private String carrierStatus;
    private String externalSystem;
    private String externalEventId;
    private boolean exceptionFlag;
    private LocalDateTime resolvedAt;
}