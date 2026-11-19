package com.enterprise.mm.warehouse.posting;

import com.enterprise.mm.error.BadRequestException;
import java.time.LocalDate;
import java.util.UUID;

/** Immutable rule version selected for a posting date and exact context. */
public record PostingRule(
    UUID id,
    String book,
    String operation,
    String contextKey,
    int ruleVersion,
    LocalDate effectiveFrom,
    LocalDate effectiveThrough,
    boolean approved,
    String accountCode,
    String currencyCode
) {
    public PostingRule {
        if (id == null || blank(book) || blank(operation) || blank(contextKey) || ruleVersion < 1
            || effectiveFrom == null || blank(accountCode) || blank(currencyCode)
            || (effectiveThrough != null && effectiveThrough.isBefore(effectiveFrom))) {
            throw new BadRequestException("Posting rule is incomplete or has an invalid effective period");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
