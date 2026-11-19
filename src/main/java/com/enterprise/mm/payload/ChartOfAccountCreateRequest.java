package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChartOfAccountCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private String accountCode;
    private String parentCode;
    private String accountType;
    private boolean group;
    private String sourceSystem;
    private String externalId;
    private LocalDateTime lastSyncedAt;
}
