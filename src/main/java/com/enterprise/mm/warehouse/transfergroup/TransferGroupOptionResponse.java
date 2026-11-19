package com.enterprise.mm.warehouse.transfergroup;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** A selector option, deliberately excluding child documents and progress calculations. */
public record TransferGroupOptionResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1) String code,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1) String name
) {}
