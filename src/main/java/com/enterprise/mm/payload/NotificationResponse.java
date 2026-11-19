package com.enterprise.mm.payload;

import lombok.Getter;
import lombok.Setter;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
public class NotificationResponse extends BaseRecordDto {
    private String recipientUsername;
    private String title;
    private String message;
    private String titleKey;
    private String messageKey;
    private Map<String, Object> templateArgs;
    private String language;
    private String entityType;
    private UUID entityId;
    private boolean read;
}
