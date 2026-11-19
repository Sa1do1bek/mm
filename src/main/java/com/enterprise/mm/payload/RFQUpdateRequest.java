package com.enterprise.mm.payload;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RFQUpdateRequest extends RFQCreateRequest {
    public boolean changesSupplierFacingFields() {
        return getCode() != null
            || getName() != null
            || getDescription() != null
            || getPurchaseRequestId() != null
            || getDueDate() != null
            || getTenderProcedureType() != null
            || getSelectionRule() != null
            || getMinQuotationsRequired() != null
            || getResponsibleBuyer() != null
            || getResponseInstructions() != null
            || getPackageLanguage() != null
            || getItems() != null
            || getInvitedSupplierIds() != null;
    }
}
