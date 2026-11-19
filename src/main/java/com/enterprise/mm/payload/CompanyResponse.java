package com.enterprise.mm.payload;

import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CompanyResponse extends BaseRecordDto {
    private String legalName;
    private String shortName;
    private String taxId;
    private String okedCode;
    private String registrationNumber;
    private LocalDate registrationDate;
    private String legalAddress;
    private String phone;
    private String email;
    private Integer nonMovingStockThresholdMonths;
    private Boolean nonMovingStockAutoDetectEnabled;
}
