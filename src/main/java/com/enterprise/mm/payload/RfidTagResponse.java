package com.enterprise.mm.payload;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RfidTagResponse extends BaseRecordDto {
    private String tagValue;
    private String entityType;
    private UUID entityId;
    private String deviceId;
    private LocalDateTime lastSeenAt;
}