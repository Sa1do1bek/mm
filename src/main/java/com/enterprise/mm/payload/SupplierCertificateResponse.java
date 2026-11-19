package com.enterprise.mm.payload;

import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SupplierCertificateResponse extends BaseRecordDto {
    private UUID supplierId;
    private String supplierCode;
    private String supplierName;
    private String certificateNumber;
    private String issuer;
    private LocalDate validFrom;
    private LocalDate validTo;
}