package com.enterprise.mm.payload;

import com.enterprise.mm.enums.RfqAggregationMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RFQResponse  {
    private UUID id;
    private String tenantId;
    private UUID companyId;
    private String companyCode;
    private String companyName;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private boolean isDeleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String createdBy;
    private String updatedBy;
    private Long version;
    private UUID purchaseRequestId;
    private String purchaseRequestCode;
    private String purchaseRequestName;
    private String purpose;
    private UUID procurementProcedureId;
    private UUID sourceRfqId;
    private UUID priceBasisCalculationId;
    private LocalDate dueDate;
    private String tenderProcedureType;
    private String selectionRule;
    private Integer minQuotationsRequired;
    private boolean aggregatedTender;
    private RfqAggregationMode aggregationMode;
    private UUID materialGroupId;
    private Integer sourcePurchaseRequestCount;
    private Integer sourceItemCount;
    private String currencyCode;
    private String responsibleBuyer;
    private String responseInstructions;
    private String packageLanguage;
    private UUID activePublishedVersionId;
    private LocalDateTime publishedAt;
    private String publishedBy;
    private LocalDateTime closedAt;
    private List<UUID> invitedSupplierIds;
}
