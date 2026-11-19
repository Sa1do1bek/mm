package com.enterprise.mm.warehouse.posting;

import com.enterprise.mm.error.BadRequestException;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.UUID;

/** Scope-specific command identity; tenant and company come from the authenticated context. */
public record PostingCommand(
    String book,
    String operation,
    String contextKey,
    UUID resourceId,
    String idempotencyKey,
    LocalDate postingDate,
    long expectedVersion,
    JsonNode payload
) {
    public PostingCommand {
        if (blank(book) || blank(operation) || blank(contextKey) || resourceId == null
            || postingDate == null || expectedVersion < 0 || payload == null || !payload.isObject()) {
            throw new BadRequestException("Posting command is incomplete");
        }
        if (blank(idempotencyKey)) {
            throw new BadRequestException("Idempotency-Key is required for posting");
        }
        idempotencyKey = idempotencyKey.trim();
        if (idempotencyKey.length() > 160) {
            throw new BadRequestException("Idempotency-Key is too long");
        }
        payload = payload.deepCopy();
    }

    @Override
    public JsonNode payload() {
        return payload.deepCopy();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
