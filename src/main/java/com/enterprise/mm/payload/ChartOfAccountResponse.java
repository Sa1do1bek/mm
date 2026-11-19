package com.enterprise.mm.payload;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChartOfAccountResponse extends BaseRecordDto {
    private String accountCode;
    private String parentCode;
    private String accountType;
    private boolean group;
    private String sourceSystem;
    private String externalId;
    private LocalDateTime lastSyncedAt;
}
