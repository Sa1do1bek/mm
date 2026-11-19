package com.enterprise.mm.enums;

public enum TrackingMode {
    QUANTITY,
    LOT,
    SERIAL;

    public BatchTrackingPolicy batchTrackingPolicy() {
        return this == LOT ? BatchTrackingPolicy.REQUIRED : BatchTrackingPolicy.NONE;
    }

    public SerialTrackingPolicy serialTrackingPolicy() {
        return this == SERIAL ? SerialTrackingPolicy.REQUIRED : SerialTrackingPolicy.NONE;
    }

    public static BatchTrackingPolicy batchTrackingPolicy(TrackingMode mode) {
        return (mode == null ? QUANTITY : mode).batchTrackingPolicy();
    }

    public static SerialTrackingPolicy serialTrackingPolicy(TrackingMode mode) {
        return (mode == null ? QUANTITY : mode).serialTrackingPolicy();
    }

    public static TrackingMode fromPolicies(BatchTrackingPolicy batchPolicy, SerialTrackingPolicy serialPolicy) {
        if (serialPolicy == SerialTrackingPolicy.REQUIRED) {
            return SERIAL;
        }
        return batchPolicy == BatchTrackingPolicy.REQUIRED ? LOT : QUANTITY;
    }
}
