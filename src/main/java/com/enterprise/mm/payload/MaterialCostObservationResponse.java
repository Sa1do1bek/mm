package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MaterialCostObservationResponse extends BaseRecordDto {
    private UUID materialId;
    private String materialCode;
    private String materialName;
    private String mxikCode;
    private BigDecimal unitCost;
    private String currencyCode;
    private BigDecimal quantity;
    private String sourceType;
    private UUID sourceEntityId;
    private UUID sourceLineId;
    private String documentCode;
    private UUID supplierId;
    private LocalDateTime observedAt;
}
