package com.enterprise.mm.error;

import java.util.Map;

public class BusinessException extends RuntimeException implements LocalizedException {
    private final String messageKey;
    private final Object[] messageArgs;
    private final Map<String, Object> context;

    public BusinessException(String message) {
        super(message);
        this.messageKey = null;
        this.messageArgs = new Object[0];
        this.context = Map.of();
    }

    public BusinessException(String message, String messageKey, Object... messageArgs) {
        super(message);
        this.messageKey = messageKey;
        this.messageArgs = messageArgs == null ? new Object[0] : messageArgs.clone();
        this.context = Map.of();
    }

    /**
     * UX-LIVE-01: carries structured context (e.g. purchaseOrderId/supplierId) alongside the
     * localized message so API consumers can keep the originating document visible when an
     * error interrupts a workflow (goods receipt, accreditation checks, etc).
     */
    public BusinessException(String message, String messageKey, Map<String, Object> context, Object... messageArgs) {
        super(message);
        this.messageKey = messageKey;
        this.messageArgs = messageArgs == null ? new Object[0] : messageArgs.clone();
        this.context = context == null || context.isEmpty() ? Map.of() : Map.copyOf(context);
    }

    @Override
    public String getMessageKey() {
        return messageKey;
    }

    @Override
    public Object[] getMessageArgs() {
        return messageArgs.clone();
    }

    public Map<String, Object> getContext() {
        return context;
    }
}
