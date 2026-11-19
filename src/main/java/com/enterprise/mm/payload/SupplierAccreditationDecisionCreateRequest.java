package com.enterprise.mm.payload;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SupplierAccreditationDecisionCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    @NotNull
    private UUID applicationId;
    @NotNull
    @Size(max = 80)
    private String decision;
    @Size(max = 500)
    private String approvedScope;
    private List<@NotNull UUID> allowedMaterialGroupIds;
    private LocalDate validFrom;
    private LocalDate validTo;
    @Size(max = 1000)
    private String conditions;
    @Size(max = 120)
    private String commissionProtocolNo;
}
