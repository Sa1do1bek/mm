package com.enterprise.mm.config;

import com.enterprise.mm.repository.InventoryAiDraftRepository;
import com.enterprise.mm.service.InventoryAiDraftService;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class InventoryAiDraftCleanup {
    private final InventoryAiDraftRepository drafts;
    private final InventoryAiDraftService service;

    @Scheduled(initialDelay = 3600000, fixedDelay = 3600000)
    public void expireOldDrafts() {
        for (var draft : drafts.findByStatusAndExpiresAtBefore("OPEN", LocalDateTime.now())) {
            try { service.expire(draft.getId()); }
            catch (RuntimeException ex) { log.warn("Could not expire inventory AI draft {}", draft.getId(), ex); }
        }
    }
}
