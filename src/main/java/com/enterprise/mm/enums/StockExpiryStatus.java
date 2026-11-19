package com.enterprise.mm.enums;

/** Date-based stock information, independent of movement eligibility and material lifecycle. */
public enum StockExpiryStatus {
    EXPIRED, EXPIRES_TODAY, EXPIRING_SOON, VALID, UNKNOWN
}
