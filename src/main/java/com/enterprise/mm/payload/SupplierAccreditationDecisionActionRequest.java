package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record SupplierAccreditationDecisionActionRequest(
    String decision,
    List<@NotNull UUID> allowedMaterialGroupIds,
    LocalDate validFrom,
    LocalDate validTo,
    @Size(max = 1000) String reviewComment
) {
}
