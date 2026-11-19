package com.enterprise.mm.payload;

import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SupplierLetterResponse extends BaseRecordDto {
    private UUID supplierId;
    private String supplierCode;
    private String supplierName;
    private String letterNumber;
    private LocalDate letterDate;
    private String direction;
    private String subject;
    private String channel;
}