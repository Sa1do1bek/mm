package com.enterprise.mm.payload;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SupplierAccreditationApplicationCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    @NotNull
    private UUID supplierId;
    @NotNull
    @Size(max = 80)
    private String accreditationType;
    @Size(max = 500)
    private String requestedScope;
    private List<@NotNull UUID> requestedMaterialGroupIds;
    private LocalDateTime submittedAt;
    private LocalDateTime reviewStartedAt;
    private LocalDate validFrom;
    private LocalDate validTo;
    private BigDecimal riskScore;
    @Size(max = 1000)
    private String reviewComment;
    @Size(max = 50)
    private String complianceRecommendation;
    private String complianceSnapshotJson;
    private UUID parentApplicationId;
}
