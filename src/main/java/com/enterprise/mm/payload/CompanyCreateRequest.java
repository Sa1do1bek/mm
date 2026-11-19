package com.enterprise.mm.payload;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CompanyCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private String legalName;
    private String shortName;
    @Pattern(regexp = "^$|^\\d{9}$", message = "must contain exactly 9 digits")
    private String taxId;
    @Pattern(regexp = "^$|^\\d{5}$", message = "must contain exactly 5 digits")
    private String okedCode;
    private String registrationNumber;
    private LocalDate registrationDate;
    private String legalAddress;
    private String phone;
    @Email
    @Size(max = 160)
    private String email;
    private Integer nonMovingStockThresholdMonths;
    private Boolean nonMovingStockAutoDetectEnabled;
}
