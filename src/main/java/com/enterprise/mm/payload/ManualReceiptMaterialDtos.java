package com.enterprise.mm.payload;

import com.enterprise.mm.enums.BatchTrackingPolicy;
import com.enterprise.mm.enums.MaterialSubtype;
import com.enterprise.mm.enums.SerialTrackingPolicy;
import com.enterprise.mm.enums.TrackingMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public final class ManualReceiptMaterialDtos {
    private ManualReceiptMaterialDtos() {}

    public record CreateRequest(
        @NotNull UUID warehouseId,
        @Size(max = 240) String name,
        UUID materialGroupId,
        UUID unitOfMeasureId,
        @Size(max = 80) String code,
        Long mxikId,
        @Size(max = 40) String mxikCode,
        MaterialSubtype materialSubtype,
        @Size(max = 240) String manufacturer,
        UUID manufacturerId,
        @Size(max = 40) String article,
        @Size(max = 120) String barcode,
        TrackingMode trackingMode,
        BatchTrackingPolicy batchTrackingPolicy,
        SerialTrackingPolicy serialTrackingPolicy,
        Boolean incomingInspectionRequired,
        List<@Valid MaterialAttributeRequest> attributes
    ) {}

    public record AttributeTemplate(String key, boolean required, String dataType, String allowedValues) {}

    public record CreateContext(
        boolean canCreate,
        String materialType,
        String defaultSubtype,
        String defaultTrackingMode,
        boolean mxikOptional,
        UUID materialGroupId,
        List<AttributeTemplate> requiredAndOptionalAttributes
    ) {}

    public record CodeAvailability(String normalizedCode, String status) {}
}
