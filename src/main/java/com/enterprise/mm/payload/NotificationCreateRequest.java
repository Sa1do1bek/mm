package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class NotificationCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private String recipientUsername;
    private String title;
    private String message;
    @Size(max = 200)
    private String titleKey;
    @Size(max = 200)
    private String messageKey;
    private Map<String, Object> templateArgs;
    @Size(max = 80)
    private String entityType;
    private UUID entityId;
    private boolean read;
}
