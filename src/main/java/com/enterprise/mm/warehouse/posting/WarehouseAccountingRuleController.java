package com.enterprise.mm.warehouse.posting;

import com.enterprise.mm.payload.ApiResponse;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for previewing and inspecting approved warehouse posting rules and subledger accounts.
 */
@RestController
@RequestMapping("/api/warehouse/accounting-rules")
public class WarehouseAccountingRuleController {

    private final PostingJournalService journalService;

    public WarehouseAccountingRuleController(PostingJournalService journalService) {
        this.journalService = journalService;
    }

    @GetMapping("/preview")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','ACCOUNTANT')")
    public ResponseEntity<ApiResponse<PostingRule>> preview(
        @RequestParam String book,
        @RequestParam String operation,
        @RequestParam String contextKey,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate postingDate
    ) {
        LocalDate date = postingDate != null ? postingDate : LocalDate.now();
        PostingRuleQuery query = new PostingRuleQuery(book, operation, contextKey, date);
        return ResponseEntity.ok(ApiResponse.ok(journalService.preview(query)));
    }
}
