package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ServiceExecutionCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    private String description;
    private UUID serviceRequestId;
    private UUID serviceOrderId;
    private LocalDate plannedStartDate;
    private LocalDate plannedEndDate;
    private LocalDate actualStartDate;
    private LocalDate actualEndDate;
    private BigDecimal progressPercent;
    private UUID responsibleUserId;
    private String remarks;
    private String executionReport;
}
