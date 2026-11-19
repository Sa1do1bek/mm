package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TenderBidOpeningProtocolCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    private String description;
    private UUID rfqId;
    private String openedBy;
    private Integer quorumPresent;
    private String notes;
}
