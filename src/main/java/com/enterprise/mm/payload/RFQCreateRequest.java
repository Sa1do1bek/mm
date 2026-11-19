package com.enterprise.mm.payload;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RFQCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private UUID purchaseRequestId;
    private String purpose;
    private UUID procurementProcedureId;
    private UUID sourceRfqId;
    private LocalDate dueDate;
    private String tenderProcedureType;
    private String selectionRule;
    private Integer minQuotationsRequired;
    @Size(max = 120)
    private String responsibleBuyer;
    private String responseInstructions;
    @Size(max = 5)
    private String packageLanguage;
    private List<UUID> invitedSupplierIds;
    @Valid
    private List<RFQItemCreateRequest> items;
}
