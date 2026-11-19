package com.enterprise.mm.warehouse.posting;

import com.enterprise.mm.error.BadRequestException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PostingReceipt(UUID batchId, UUID ruleId, int ruleVersion, LocalDate postingDate,
                             List<PostingEffect> effects) {
    public PostingReceipt {
        if (batchId == null || ruleId == null || ruleVersion < 1 || postingDate == null
            || effects == null || effects.isEmpty() || effects.stream().anyMatch(effect -> effect == null)) {
            throw new BadRequestException("Posting receipt is incomplete");
        }
        effects = List.copyOf(effects);
    }
}
