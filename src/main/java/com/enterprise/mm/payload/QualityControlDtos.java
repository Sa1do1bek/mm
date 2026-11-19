package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.util.UUID;

public final class QualityControlDtos {
    private QualityControlDtos() {
    }

    public record QualityDecisionRequest(BigDecimal acceptedQuantity, BigDecimal rejectedQuantity, String comment) {
    }

    public record QualityQuarantineDecisionRequest(BigDecimal quantity, String disposition, String comment) {
    }

    public record LaboratoryResultRequest(Boolean passed, String measuredValue, String result, UUID protocolAttachmentId, String comment) {
    }

    public record LimsLaboratoryResultCallback(UUID laboratoryTestId, String limsSampleId, String sampleNumber, String limsStatus, Boolean passed, String measuredValue, String result, UUID protocolAttachmentId, String comment) {
    }

    public record QualityActionRequest(String comment) {
    }
}