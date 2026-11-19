package com.enterprise.mm.payload;

import com.enterprise.mm.enums.MovementType;
import java.util.List;
import java.util.UUID;

public record ManualWarehouseDocumentResponse(
    UUID documentId,
    String documentCode,
    MovementType movementType,
    String status,
    List<UUID> movementIds,
    int lineCount
) {}
