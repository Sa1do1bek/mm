package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TnvedCodeResponse extends BaseRecordDto {
    private String tnvedCode;
    private String parentCode;
    private String sectionCode;
    private String unitCode;
    private String unitShortName;
    private BigDecimal dutyRate;
    private BigDecimal vatRate;
    private String sourceSystem;
    private String externalId;
    private LocalDateTime lastSyncedAt;
}
