package com.enterprise.mm.error;

import java.util.Map;

public class TransferReceiptConflictException extends ConflictException {
    private final Map<String, Object> context;

    public TransferReceiptConflictException(Long currentVersion) {
        super("Transfer changed; refresh the receipt table before retrying", "transfers.receipt.staleVersion");
        context = currentVersion == null ? Map.of() : Map.of("currentVersion", currentVersion);
    }

    public Map<String, Object> getContext() { return context; }
}
