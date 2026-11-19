package com.enterprise.mm.payload;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AssignableRfqResponse {
    @Schema(description = "RFQ id")
    private UUID id;
    private String code;
    private String name;
    private String status;
    private UUID companyId;
}
