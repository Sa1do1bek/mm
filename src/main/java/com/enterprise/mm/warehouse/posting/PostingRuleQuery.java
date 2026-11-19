package com.enterprise.mm.warehouse.posting;

import com.enterprise.mm.error.BadRequestException;
import java.time.LocalDate;

public record PostingRuleQuery(String book, String operation, String contextKey, LocalDate postingDate, String accountCode) {
    public PostingRuleQuery(String book, String operation, String contextKey, LocalDate postingDate) {
        this(book, operation, contextKey, postingDate, null);
    }

    public PostingRuleQuery {
        if (blank(book) || blank(operation) || blank(contextKey) || postingDate == null) {
            throw new BadRequestException("Posting rule preview needs book, operation, context and date");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
