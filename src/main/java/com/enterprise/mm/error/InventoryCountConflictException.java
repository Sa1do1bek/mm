package com.enterprise.mm.error;

import com.enterprise.mm.payload.InventoryCountBatchDtos.RowResult;

/** The current observation is already projected for the authenticated counter. */
public class InventoryCountConflictException extends ConflictException {
    private final RowResult result;
    public InventoryCountConflictException(RowResult result) {
        super(result.message(), result.errorCode());
        this.result = result;
    }
    public RowResult result() { return result; }
}
