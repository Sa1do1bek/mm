package com.enterprise.mm.payload;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;

public final class WarehouseReceiptProfileDtos {
    private WarehouseReceiptProfileDtos() {}
    public enum Effect { ALLOW, DENY }
    public enum Eligibility { ALLOWED, BLOCKED, REVIEW_REQUIRED }
    public record Rule(@NotNull UUID materialGroupId, @NotNull Effect effect, boolean includeDescendants) {}
    public record UpdateRequest(@PositiveOrZero Long version, @Size(max = 2000) String purpose,
        @NotNull @Size(max = 500) List<@NotNull @Valid Rule> rules) {}
    public record Profile(UUID warehouseId, boolean configured, Long version, String purpose, List<Rule> rules) {}
    public record Selection(Eligibility eligibility, boolean recommendedSelected, String decisionSource,
        String reasonCode, String reasonText, Long policyVersion) {}
}
