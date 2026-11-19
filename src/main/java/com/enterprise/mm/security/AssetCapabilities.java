package com.enterprise.mm.security;

import java.util.List;

public final class AssetCapabilities {
    public static final String ASSET_SETTINGS_MANAGE = "ASSET_SETTINGS_MANAGE";
    public static final String ASSET_RECEIVE = "ASSET_RECEIVE";
    public static final String ASSET_QUALITY_DECIDE = "ASSET_QUALITY_DECIDE";
    public static final String ASSET_NUMBER_ASSIGN = "ASSET_NUMBER_ASSIGN";
    public static final String ASSET_NUMBER_CORRECT = "ASSET_NUMBER_CORRECT";
    public static final String ASSET_HANDOVER_REQUEST = "ASSET_HANDOVER_REQUEST";
    public static final String ASSET_APPROVE = "ASSET_APPROVE";
    public static final String ASSET_DISPATCH = "ASSET_DISPATCH";
    public static final String ASSET_ACKNOWLEDGE = "ASSET_ACKNOWLEDGE";
    public static final String ASSET_COMMISSION = "ASSET_COMMISSION";
    public static final String ASSET_REPAIR = "ASSET_REPAIR";
    public static final String ASSET_DISPOSE = "ASSET_DISPOSE";
    public static final String ASSET_OPENING_IMPORT = "ASSET_OPENING_IMPORT";
    public static final String ASSET_VERIFY = "ASSET_VERIFY";
    public static final String ASSET_READ = "ASSET_READ";
    public static final String ASSET_EXPORT = "ASSET_EXPORT";

    private static final List<String> ALL = List.of(
        ASSET_SETTINGS_MANAGE,
        ASSET_RECEIVE,
        ASSET_QUALITY_DECIDE,
        ASSET_NUMBER_ASSIGN,
        ASSET_NUMBER_CORRECT,
        ASSET_HANDOVER_REQUEST,
        ASSET_APPROVE,
        ASSET_DISPATCH,
        ASSET_ACKNOWLEDGE,
        ASSET_COMMISSION,
        ASSET_REPAIR,
        ASSET_DISPOSE,
        ASSET_OPENING_IMPORT,
        ASSET_VERIFY,
        ASSET_READ,
        ASSET_EXPORT
    );

    private AssetCapabilities() {
    }

    public static List<String> all() {
        return ALL;
    }
}
