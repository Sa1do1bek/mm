package com.enterprise.mm.service;

final class FinishedGoodsOpeningLiveReview {
    record Evidence(boolean mastersValid, boolean uomValid, boolean uncontrolledStock,
                    boolean existingStock, boolean existingMovement, boolean duplicateTarget,
                    boolean openingInProgress) {}
    static String classify(String status, Evidence evidence) {
        if (!"LIVE_RECONCILIATION_REQUIRED".equals(status)) return status;
        if (evidence.openingInProgress()) return "OPENING_IN_PROGRESS";
        if (evidence.duplicateTarget()) return "DUPLICATE_TARGET_REVIEW";
        if (!evidence.mastersValid()) return "MASTER_REVIEW";
        if (!evidence.uomValid()) return "UOM_REVIEW";
        if (!evidence.uncontrolledStock()) return "CONTROLLED_STOCK_REVIEW";
        if (evidence.existingStock()) return "EXISTING_STOCK_REVIEW";
        if (evidence.existingMovement()) return "EXISTING_MOVEMENT_REVIEW";
        return "ELIGIBLE";
    }
}
