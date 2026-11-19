package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PositionResponse extends BaseRecordDto {
    private String externalId;
    private UUID departmentId;
    private String positionCategory;
    private String grade;
    private BigDecimal headcount;
    private BigDecimal occupiedHeadcount;
    private BigDecimal availableHeadcount;
}
