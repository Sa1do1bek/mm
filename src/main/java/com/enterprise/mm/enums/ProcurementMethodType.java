package com.enterprise.mm.enums;

public enum ProcurementMethodType {
    OPEN_TENDER(true, ProcurementExecutionPath.TENDER_WITH_PRICE_DISCOVERY, false),
    CLOSED_TENDER(true, ProcurementExecutionPath.TENDER_WITH_PRICE_DISCOVERY, false),
    OPEN_COMPETITION(true, ProcurementExecutionPath.RFQ, false),
    CLOSED_COMPETITION(true, ProcurementExecutionPath.RFQ, false),
    REQUEST_FOR_QUOTATION(true, ProcurementExecutionPath.RFQ, false),
    E_AUCTION(true, ProcurementExecutionPath.AUCTION, false),
    DIRECT_PURCHASE(false, ProcurementExecutionPath.DIRECT_AWARD, true),
    SINGLE_SOURCE(false, ProcurementExecutionPath.DIRECT_AWARD, true),
    FRAME_AGREEMENT(false, ProcurementExecutionPath.FRAMEWORK_AGREEMENT, false);

    private final boolean competitive;
    private final ProcurementExecutionPath executionPath;
    private final boolean exceptionReason;

    ProcurementMethodType(boolean competitive, ProcurementExecutionPath executionPath, boolean exceptionReason) {
        this.competitive = competitive;
        this.executionPath = executionPath;
        this.exceptionReason = exceptionReason;
    }

    public boolean isCompetitive() {
        return competitive;
    }

    public boolean usesRfqEngine() {
        return executionPath == ProcurementExecutionPath.RFQ
            || executionPath == ProcurementExecutionPath.TENDER_WITH_PRICE_DISCOVERY;
    }

    public ProcurementExecutionPath executionPath() {
        return executionPath;
    }

    public boolean requiresPriceDiscovery() {
        return executionPath == ProcurementExecutionPath.TENDER_WITH_PRICE_DISCOVERY;
    }

    public boolean requiresExceptionReason() {
        return exceptionReason;
    }
}
