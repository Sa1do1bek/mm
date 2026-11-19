package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class IncotermCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private Integer versionYear;
    private String modeScope;
    private String riskTransferPoint;
    private String sellerCostResponsibility;
    private String buyerCostResponsibility;
    private boolean namedPlaceRequired;
}
