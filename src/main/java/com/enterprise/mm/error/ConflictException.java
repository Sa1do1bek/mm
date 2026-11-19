package com.enterprise.mm.error;

public class ConflictException extends RuntimeException implements LocalizedException {
    private final String messageKey;
    private final Object[] messageArgs;

    public ConflictException(String message) {
        super(message);
        this.messageKey = null;
        this.messageArgs = new Object[0];
    }

    public ConflictException(String message, String messageKey, Object... messageArgs) {
        super(message);
        this.messageKey = messageKey;
        this.messageArgs = messageArgs == null ? new Object[0] : messageArgs.clone();
    }

    @Override
    public String getMessageKey() {
        return messageKey;
    }

    @Override
    public Object[] getMessageArgs() {
        return messageArgs.clone();
    }
}
