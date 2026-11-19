package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TnvedCodeCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private String tnvedCode;
    private String parentCode;
    private String sectionCode;
    private String unitCode;
    private BigDecimal dutyRate;
    private BigDecimal vatRate;
    private String sourceSystem;
    private String externalId;
    private LocalDateTime lastSyncedAt;
}
