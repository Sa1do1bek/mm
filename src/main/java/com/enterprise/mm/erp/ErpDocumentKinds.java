package com.enterprise.mm.erp;

public final class ErpDocumentKinds {
    public static final String RECEIPT = "RECEIPT";
    public static final String ISSUE = "ISSUE";
    public static final String TRANSFER = "TRANSFER";
    public static final String POST_WHEN_CONFIRMED = "POST_WHEN_CONFIRMED";
    public static final String HOLD_UNTIL_CONFIRMED = "HOLD_UNTIL_CONFIRMED";

    private ErpDocumentKinds() {
    }

    public static String fromContext(String contextKey) {
        if (contextKey == null) {
            return null;
        }
        return switch (contextKey) {
            case "GOODS_RECEIPT" -> RECEIPT;
            case "ISSUE_REQUEST" -> ISSUE;
            case "TRANSFER", "TRANSFER_DISPATCH", "TRANSFER_RECEIPT" -> TRANSFER;
            default -> null;
        };
    }

    public static String requireKind(String kind) {
        if (RECEIPT.equals(kind) || ISSUE.equals(kind) || TRANSFER.equals(kind)) {
            return kind;
        }
        throw new com.enterprise.mm.error.BadRequestException("Document kind must be RECEIPT, ISSUE or TRANSFER");
    }

    public static String requireMode(String mode) {
        if (POST_WHEN_CONFIRMED.equals(mode) || HOLD_UNTIL_CONFIRMED.equals(mode)) {
            return mode;
        }
        throw new com.enterprise.mm.error.BadRequestException("Arrival mode must be POST_WHEN_CONFIRMED or HOLD_UNTIL_CONFIRMED");
    }
}
