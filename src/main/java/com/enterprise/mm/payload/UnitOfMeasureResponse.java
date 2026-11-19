package com.enterprise.mm.payload;

import lombok.*;

@Getter
@Setter
public class UnitOfMeasureResponse extends BaseRecordDto {
  private String measurementNameShort;
  private String measurementNameLong;
  private String unitType;
  private String unitTypeLabel;
  private Integer stockQuantityScale;
  private int quantityScale;
  @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
  public String getShortName() { return com.enterprise.mm.common.UnitOfMeasureLabels.shortLabel(measurementNameShort); }
}
