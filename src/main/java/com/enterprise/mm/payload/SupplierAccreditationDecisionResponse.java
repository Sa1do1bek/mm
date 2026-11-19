package com.enterprise.mm.payload;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SupplierAccreditationDecisionResponse extends BaseRecordDto {
    private UUID applicationId;
    private String applicationCode;
    private String applicationName;
    private String decision;
    private String approvedScope;
    private List<UUID> allowedMaterialGroupIds;
    private LocalDate validFrom;
    private LocalDate validTo;
    private String conditions;
    private String commissionProtocolNo;
}
