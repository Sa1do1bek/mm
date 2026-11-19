package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class IkpuCodeResponse extends BaseRecordDto {
    private String ikpuCode;
    private String packageCode;
    private String unitCode;
    private String unitShortName;
    private String groupCode;
    private BigDecimal vatRate;
    private boolean service;
    private String sourceSystem;
    private String externalId;
    private LocalDateTime lastSyncedAt;
}
