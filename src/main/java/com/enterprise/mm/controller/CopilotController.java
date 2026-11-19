package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.service.CopilotChatService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import java.util.UUID;
import java.util.List;
import io.swagger.v3.oas.annotations.Operation;

@RestController
@RequestMapping("/api/mm/ai/copilot")
@RequiredArgsConstructor
public class CopilotController {
    private final CopilotChatService chats;

    public record CreateConversation(String title) {}
    public record UpdateConversation(String title, Boolean archived) {}

    @PostMapping("/messages")
    public ApiResponse<JsonNode> message(@RequestBody JsonNode request) {
        return ApiResponse.ok(chats.send(request));
    }

    @PostMapping("/conversations")
    public ApiResponse<CopilotChatService.Conversation> create(@RequestBody(required = false) CreateConversation request) {
        return ApiResponse.ok(chats.create(request == null ? null : request.title()));
    }

    @GetMapping("/conversations")
    public ApiResponse<CopilotChatService.Page<CopilotChatService.Conversation>> list(
        @RequestParam(defaultValue = "false") boolean archived,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(chats.list(archived, page, size));
    }

    @GetMapping("/conversations/{id}")
    public ApiResponse<CopilotChatService.Conversation> get(@PathVariable UUID id) {
        return ApiResponse.ok(chats.get(id));
    }

    @PatchMapping("/conversations/{id}")
    public ApiResponse<CopilotChatService.Conversation> update(@PathVariable UUID id, @RequestBody UpdateConversation request) {
        return ApiResponse.ok(chats.update(id, request.title(), request.archived()));
    }

    @GetMapping("/conversations/{id}/messages")
    public ApiResponse<CopilotChatService.Page<CopilotChatService.Message>> messages(@PathVariable UUID id,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.ok(chats.messages(id, page, size));
    }

    @GetMapping("/conversations/{id}/pending-runs")
    public ApiResponse<List<CopilotChatService.PendingRun>> pending(@PathVariable UUID id) {
        return ApiResponse.ok(chats.pending(id));
    }

    @PostMapping("/conversations/{id}/runs/{runId}/poll")
    public ApiResponse<JsonNode> poll(@PathVariable UUID id, @PathVariable String runId) {
        return ApiResponse.ok(chats.resume(id, runId));
    }

    @Operation(summary = "Cancel a Copilot run", description = "Idempotently marks a queued or running run CANCELLED. A cancellation received before submission reserves the run ID as cancelled. Already completed runs return a conflict. Requires access to the conversation's tenant, company and owner scope.")
    @PostMapping("/conversations/{id}/runs/{runId}/cancel")
    public ApiResponse<CopilotChatService.CancelledRun> cancel(@PathVariable UUID id, @PathVariable String runId) {
        return ApiResponse.ok(chats.cancel(id, runId));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public org.springframework.http.ResponseEntity<ApiResponse<Void>> storageUnavailable() {
        return org.springframework.http.ResponseEntity.status(503).body(
            ApiResponse.error("Chat storage is unavailable; resume the same run", "COPILOT_STORAGE_UNAVAILABLE"));
    }
}
