package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SupplierLetterCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private UUID supplierId;
    private String letterNumber;
    private LocalDate letterDate;
    private String direction;
    private String subject;
    private String channel;
}
