package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PositionCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private String externalId;
    private UUID departmentId;
    private String positionCategory;
    private String grade;
    private BigDecimal headcount;
    private BigDecimal occupiedHeadcount;
}
