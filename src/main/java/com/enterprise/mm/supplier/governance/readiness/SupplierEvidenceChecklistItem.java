package com.enterprise.mm.supplier.governance.readiness;

import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessEnums.EvidenceChecklistItemState;
import java.time.LocalDate;
import java.util.UUID;

public record SupplierEvidenceChecklistItem(
    UUID snapshotId,
    UUID applicationId,
    String requirementCode,
    String documentType,
    boolean mandatory,
    boolean reusable,
    UUID linkedDocumentId,
    EvidenceChecklistItemState state,
    LocalDate validTo,
    String ownerRole,
    LocalDate dueDate
) {
}
