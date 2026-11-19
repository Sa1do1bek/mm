package com.enterprise.mm.payload;

import com.enterprise.mm.enums.ManagementOpportunityEvidenceSourceEntityType;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ManagementOpportunityEvidenceResponse extends BaseRecordDto {
    private UUID opportunityId;
    private String evidenceType;
    private ManagementOpportunityEvidenceSourceEntityType sourceEntityType;
    private UUID sourceEntityId;
    private String capturedSnapshot;
}
