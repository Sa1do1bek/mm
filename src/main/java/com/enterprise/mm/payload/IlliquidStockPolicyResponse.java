package com.enterprise.mm.payload;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class IlliquidStockPolicyResponse extends BaseRecordDto {
    private String legalEntityCode;
    private UUID warehouseId;
    private String materialCategory;
    private String materialType;
    private Integer agingDays;
    private Integer excessDays;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private boolean active;
    private LocalDateTime activatedAt;
    private String activatedBy;
}
