package com.enterprise.mm.payload.integration;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CbuCurrencyResponse {
    private Long id;

    @JsonProperty("Code")
    private String code;

    @JsonProperty("Ccy")
    private String ccy;

    @JsonProperty("CcyNm_RU")
    private String ccyNmRu;

    @JsonProperty("CcyNm_UZ")
    private String ccyNmUz;

    @JsonProperty("CcyNm_UZC")
    private String ccyNmUzc;

    @JsonProperty("CcyNm_EN")
    private String ccyNmEn;

    @JsonProperty("Nominal")
    private String nominal;

    @JsonProperty("Rate")
    private String rate;

    @JsonProperty("Diff")
    private String diff;

    @JsonProperty("Date")
    private String date;
}
