package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ServiceExecutionResponse extends BaseRecordDto {
    private UUID serviceRequestId;
    private String serviceRequestCode;
    private String serviceRequestName;
    private UUID serviceOrderId;
    private String serviceOrderCode;
    private String serviceOrderName;
    private LocalDate plannedStartDate;
    private LocalDate plannedEndDate;
    private LocalDate actualStartDate;
    private LocalDate actualEndDate;
    private BigDecimal progressPercent;
    private UUID responsibleUserId;
    private String responsibleUserCode;
    private String responsibleUserName;
    private String remarks;
    private String executionReport;
}
