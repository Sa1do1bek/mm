package com.enterprise.mm.payload;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ManufacturerResponse extends BaseRecordDto {
    private String country;
    private String countryCode;
    private String website;
    private String email;
    private String phone;
}
