package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BudgetItemCreateRequest {
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private BigDecimal annualLimit;
}
