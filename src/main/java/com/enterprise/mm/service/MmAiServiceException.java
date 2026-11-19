package com.enterprise.mm.service;

public class MmAiServiceException extends RuntimeException {
    private final int httpStatus;
    private final String errorCode;
    private final String upstreamRequestId;
    private final Integer retryAfterSeconds;

    public MmAiServiceException(int httpStatus, String errorCode, String message, String upstreamRequestId) {
        this(httpStatus, errorCode, message, upstreamRequestId, null);
    }

    public MmAiServiceException(int httpStatus, String errorCode, String message, String upstreamRequestId,
        Integer retryAfterSeconds) {
        super(message);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode == null || errorCode.isBlank() ? "MM_AI_SERVICE_ERROR" : errorCode;
        this.upstreamRequestId = upstreamRequestId;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public Integer retryAfterSeconds() { return retryAfterSeconds; }

    public int httpStatus() {
        return httpStatus;
    }

    public String errorCode() {
        return errorCode;
    }

    public String upstreamRequestId() {
        return upstreamRequestId;
    }

    public String failureMessage() {
        StringBuilder message = new StringBuilder();
        if (httpStatus > 0) {
            message.append("HTTP ").append(httpStatus).append(": ");
        }
        message.append(getMessage() == null || getMessage().isBlank() ? errorCode : getMessage());
        if (upstreamRequestId != null && !upstreamRequestId.isBlank()) {
            message.append(" (requestId=").append(upstreamRequestId).append(")");
        }
        return message.toString();
    }
}
