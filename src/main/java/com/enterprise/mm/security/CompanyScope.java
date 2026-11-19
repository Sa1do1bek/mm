package com.enterprise.mm.security;

/**
 * Named, request-local company scopes that a UI drilldown may request.
 *
 * <p>The contract deliberately exposes no company identifier: the only accepted value resolves
 * to the authenticated request's {@code X-Company-Id} context.</p>
 */
public enum CompanyScope {
    CURRENT_COMPANY;

    public static final String PARAMETER = "companyScope";

    public static CompanyScope fromRequestValue(String value) {
        if (value == null) {
            return null;
        }
        return CURRENT_COMPANY.name().equals(value) ? CURRENT_COMPANY : null;
    }

    public static String currentCompanyDrilldown(String url) {
        if (url == null || url.isBlank()) {
            return url;
        }
        String parameter = PARAMETER + "=" + CURRENT_COMPANY.name();
        if (hasCurrentCompanyScope(url)) {
            return url;
        }
        int fragmentIndex = url.indexOf('#');
        String base = fragmentIndex >= 0 ? url.substring(0, fragmentIndex) : url;
        String fragment = fragmentIndex >= 0 ? url.substring(fragmentIndex) : "";
        String separator;
        if (!base.contains("?")) {
            separator = "?";
        } else if (base.endsWith("?") || base.endsWith("&")) {
            separator = "";
        } else {
            separator = "&";
        }
        return base + separator + parameter + fragment;
    }

    private static boolean hasCurrentCompanyScope(String url) {
        int fragmentIndex = url.indexOf('#');
        String base = fragmentIndex >= 0 ? url.substring(0, fragmentIndex) : url;
        int queryIndex = base.indexOf('?');
        if (queryIndex < 0 || queryIndex == base.length() - 1) {
            return false;
        }
        String query = base.substring(queryIndex + 1);
        for (String parameter : query.split("&")) {
            if ((PARAMETER + "=" + CURRENT_COMPANY.name()).equals(parameter)) {
                return true;
            }
        }
        return false;
    }
}
