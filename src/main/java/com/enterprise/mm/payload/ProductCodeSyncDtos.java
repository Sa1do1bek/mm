package com.enterprise.mm.payload;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class ProductCodeSyncDtos {
    private ProductCodeSyncDtos() {
    }

    public record ProductCodeSyncRequest(
        String sourcePath,
        List<Map<String, Object>> records,
        Boolean fullSync,
        Integer pageSize,
        Integer startPage,
        Integer maxPages,
        String pageParam,
        String sizeParam,
        Boolean requireLive
    ) {
    }

    public record ProductCodeSyncResult(
        String dictionary,
        String sourceSystem,
        String mode,
        int received,
        int created,
        int updated,
        int pagesFetched,
        boolean complete,
        String message,
        LocalDateTime syncedAt
    ) {
    }
}