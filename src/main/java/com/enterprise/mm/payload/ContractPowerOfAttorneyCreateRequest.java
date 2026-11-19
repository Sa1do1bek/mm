package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ContractPowerOfAttorneyCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private UUID contractId;
    private UUID supplierId;
    private String attorneyNumber;
    private String issuedTo;
    private LocalDate issueDate;
    private LocalDate validTo;
    private BigDecimal amountLimit;
}
