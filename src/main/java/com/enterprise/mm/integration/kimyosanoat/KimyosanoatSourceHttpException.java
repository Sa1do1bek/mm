package com.enterprise.mm.integration.kimyosanoat;

import com.enterprise.mm.error.BusinessException;

public class KimyosanoatSourceHttpException extends BusinessException {
    private final int statusCode;

    public KimyosanoatSourceHttpException(int statusCode) {
        super(statusCode == 401 ? "Kimyosanoat rejected the account credentials"
            : "Kimyosanoat returned HTTP " + statusCode);
        this.statusCode = statusCode;
    }

    public int statusCode() { return statusCode; }
}
