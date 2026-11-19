package com.enterprise.mm.enums;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum FcmDevicePlatform {
    WEB, ANDROID, IOS;

    @JsonCreator
    public static FcmDevicePlatform from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return FcmDevicePlatform.valueOf(value.trim().toUpperCase());
    }
}
