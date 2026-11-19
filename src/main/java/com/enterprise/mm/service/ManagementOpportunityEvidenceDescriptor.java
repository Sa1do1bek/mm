package com.enterprise.mm.service;

import com.enterprise.mm.enums.ManagementOpportunityEvidenceSourceEntityType;
import java.util.UUID;

public record ManagementOpportunityEvidenceDescriptor(
    String type,
    ManagementOpportunityEvidenceSourceEntityType sourceType,
    UUID sourceId,
    String snapshotJson
) {
}
