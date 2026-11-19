package com.enterprise.mm.service;

import java.util.UUID;

/** Internal capability, never accepted from a movement request body. */
public record TransferPostingContext(UUID transferId, UUID shipmentId, UUID allocationId, Kind kind, UUID transferLineId, UUID exceptionCaseId) {
    public enum Kind { DISPATCH, QUARANTINE_RETURN_DISPATCH, GOOD_RECEIPT, QUARANTINE_RECEIPT, TRANSIT_RETURN, TRANSIT_LOSS, QUARANTINE_RELEASE, QUARANTINE_WRITE_OFF }
    public TransferPostingContext(UUID transferId, UUID shipmentId, UUID allocationId, Kind kind) {
        this(transferId, shipmentId, allocationId, kind, null, null);
    }
    public TransferPostingContext(UUID transferId, UUID shipmentId, UUID allocationId, Kind kind, UUID transferLineId) {
        this(transferId, shipmentId, allocationId, kind, transferLineId, null);
    }
    public TransferPostingContext {
        if (transferId == null || shipmentId == null || kind == null
            || ((kind == Kind.DISPATCH || kind == Kind.QUARANTINE_RETURN_DISPATCH) ? transferLineId == null : allocationId == null))
            throw new IllegalArgumentException("Transfer posting requires its persisted shipment and line/allocation");
        if (exceptionCaseId != null && kind != Kind.TRANSIT_RETURN)
            throw new IllegalArgumentException("A selected return case requires a transit return");
    }
}
