package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ContractAddendumResponse extends BaseRecordDto {
    private UUID contractId;
    private String contractCode;
    private String contractName;
    private String addendumNumber;
    private LocalDate addendumDate;
    private String addendumType;
    private LocalDate effectiveDate;
    private LocalDate newEndDate;
    private BigDecimal limitDelta;
    private BigDecimal newLimitAmount;
    private String reason;
    private LocalDateTime postedAt;
}