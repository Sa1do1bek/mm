package com.enterprise.mm.payload;

public interface CountryCodeProjection {

    Long getId();
    String getCode();
    String getShortName();
    String getLongName();
    String getAlpha2();
    String getAlpha3();
}
