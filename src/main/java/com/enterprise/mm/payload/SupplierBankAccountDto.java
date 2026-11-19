package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SupplierBankAccountDto {
    @Size(max = 240)
    private String bankName;
    @Size(max = 500)
    private String bankMfo;
    @Size(max = 500)
    private String bankAccount;
    @Size(max = 500)
    private String bankAddress;
    private Boolean primaryAccount;
}
