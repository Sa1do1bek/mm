package com.enterprise.mm.warehouse.posting;

import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

public final class PostingRuleSelector {
    private PostingRuleSelector() {
    }

    public static PostingRule select(List<PostingRule> candidates, String book, String operation,
                                     String contextKey, LocalDate postingDate) {
        return select(candidates, book, operation, contextKey, postingDate, null);
    }

    public static PostingRule select(List<PostingRule> candidates, String book, String operation,
                                     String contextKey, LocalDate postingDate, String accountCode) {
        if (candidates == null || postingDate == null) {
            throw new BadRequestException("Posting rules and date are required");
        }
        var matching = candidates.stream()
            .filter(rule -> rule.approved()
                && Objects.equals(rule.book(), book)
                && Objects.equals(rule.operation(), operation)
                && Objects.equals(rule.contextKey(), contextKey)
                && !rule.effectiveFrom().isAfter(postingDate)
                && (rule.effectiveThrough() == null || !rule.effectiveThrough().isBefore(postingDate)))
            .toList();
        if (matching.isEmpty()) {
            throw new BadRequestException("No approved posting rule for this context and date");
        }

        if (accountCode != null && !accountCode.isBlank()) {
            String trimmed = accountCode.trim();
            var exactMatching = matching.stream()
                .filter(rule -> Objects.equals(rule.accountCode(), trimmed))
                .toList();
            if (exactMatching.size() == 1) {
                return exactMatching.getFirst();
            }
            if (exactMatching.size() > 1) {
                throw new ConflictException("Ambiguous approved posting rules for account " + trimmed);
            }
            // Fallback to generic rules (rule.accountCode is null)
            var genericMatching = matching.stream()
                .filter(rule -> rule.accountCode() == null || rule.accountCode().isBlank())
                .toList();
            if (genericMatching.size() == 1) {
                return genericMatching.getFirst();
            }
            if (genericMatching.size() > 1) {
                throw new ConflictException("Ambiguous approved generic posting rules for this context and date");
            }
            throw new BadRequestException("No approved posting rule for account " + trimmed + " or generic context");
        }

        if (matching.size() > 1) {
            throw new ConflictException("Ambiguous approved posting rules for this context and date");
        }
        return matching.getFirst();
    }
}
