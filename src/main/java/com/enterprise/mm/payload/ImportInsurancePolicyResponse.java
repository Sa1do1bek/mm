package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ImportInsurancePolicyResponse extends BaseRecordDto {
    private String policyNumber;
    private UUID importDeliveryId;
    private String importDeliveryCode;
    private String importDeliveryName;
    private UUID insurerSupplierId;
    private String insurerSupplierCode;
    private String insurerSupplierName;
    private String insurerName;
    private String coverageType;
    private BigDecimal insuredAmount;
    private BigDecimal premiumAmount;
    private BigDecimal deductibleAmount;
    private String currencyCode;
    private String allocationMethod;
    private boolean includedInInventoryCost;
    private LocalDate validFrom;
    private LocalDate validTo;
    private UUID policyAttachmentId;
    private UUID landedCostComponentId;
    private String landedCostComponentCode;
    private String landedCostComponentName;
    private LocalDateTime activatedAt;
    private LocalDateTime claimedAt;
    private LocalDateTime closedAt;
}