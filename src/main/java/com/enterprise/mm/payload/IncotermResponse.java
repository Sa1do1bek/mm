package com.enterprise.mm.payload;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class IncotermResponse extends BaseRecordDto {
    private Integer versionYear;
    private String modeScope;
    private String riskTransferPoint;
    private String sellerCostResponsibility;
    private String buyerCostResponsibility;
    private boolean namedPlaceRequired;
}
