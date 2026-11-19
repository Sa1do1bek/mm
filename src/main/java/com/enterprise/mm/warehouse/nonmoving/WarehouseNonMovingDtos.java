package com.enterprise.mm.warehouse.nonmoving;

import com.enterprise.mm.payload.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public final class WarehouseNonMovingDtos {
    private WarehouseNonMovingDtos() {}

    public record PreferenceRequest(
        @NotNull @Min(1) @Max(1200) Integer thresholdMonths,
        @NotNull @Min(0) Long expectedVersion
    ) {}

    /** Version zero means that the user has not saved a personal preference yet. */
    public record Preference(int thresholdMonths, String source, long version, LocalDateTime updatedAt) {}

    public enum SortBy { LAST_MOVEMENT_DATE, MATERIAL_CODE, MATERIAL_NAME, QUANTITY, BOOK_VALUE }

    public record Filter(String search, UUID materialGroupId, boolean includeUnknownHistory,
                         int page, int size, SortBy sortBy, org.springframework.data.domain.Sort.Direction direction) {
        public Filter(String search, UUID materialGroupId, boolean includeUnknownHistory, int page, int size) {
            this(search, materialGroupId, includeUnknownHistory, page, size, SortBy.LAST_MOVEMENT_DATE,
                org.springframework.data.domain.Sort.Direction.ASC);
        }
    }

    public record Candidate(
        UUID materialId, String materialCode, String materialName,
        UUID materialGroupId, String materialGroupName, UUID warehouseId,
        String unitCode, String unitName,
        BigDecimal quantity, BigDecimal availableQuantity, BigDecimal reservedQuantity,
        BigDecimal quarantineQuantity, BigDecimal bookValue, String currencyCode,
        LocalDate lastMovementDate, LocalDate ageReferenceDate, String ageBasis,
        Integer monthsWithoutMovement, LocalDate eligibleSince,
        String historyStatus, UUID activeCaseId, String activeCaseNo, String activeCaseStatus
    ) {
        @com.fasterxml.jackson.annotation.JsonProperty(value = "unitShortName", access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        public String unitShortName() { return com.enterprise.mm.common.UnitOfMeasureLabels.shortLabel(unitName); }
}

    public record Candidates(int effectiveThresholdMonths, String preferenceSource,
                             LocalDate asOfDate, LocalDateTime calculatedAt,
                             PageResponse<Candidate> positions) {}
}
