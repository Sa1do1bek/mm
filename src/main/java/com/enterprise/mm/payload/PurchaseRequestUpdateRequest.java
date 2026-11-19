package com.enterprise.mm.payload;

@lombok.Getter
@lombok.Setter
public class PurchaseRequestUpdateRequest extends PurchaseRequestCreateRequest {
    private Long version;
    private String itemUpdateMode;
    @jakarta.validation.Valid
    private java.util.List<PurchaseRequestItemRemovalRequest> removedItems;
}
