package com.enterprise.mm.error;

public interface LocalizedException {
    String getMessageKey();

    Object[] getMessageArgs();
}
