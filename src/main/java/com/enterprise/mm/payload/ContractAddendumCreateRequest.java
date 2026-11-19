package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ContractAddendumCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private UUID contractId;
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
