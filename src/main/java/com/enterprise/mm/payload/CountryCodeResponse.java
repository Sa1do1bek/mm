package com.enterprise.mm.payload;

public record CountryCodeResponse(
            Long id,
            String code,
            String shortName,
            String longName,
            String alpha2,
            String alpha3
    ) {
    }
