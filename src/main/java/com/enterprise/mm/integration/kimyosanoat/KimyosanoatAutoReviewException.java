package com.enterprise.mm.integration.kimyosanoat;

public class KimyosanoatAutoReviewException extends RuntimeException {
    private final String reasonCode;

    public KimyosanoatAutoReviewException(String reasonCode, String message) {
        super(message);
        this.reasonCode = reasonCode;
    }

    public String reasonCode() { return reasonCode; }
}
