package com.enterprise.mm.controller;

import com.enterprise.mm.common.PaginationUtils;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.IntegrationHealthCommandDtos.OperatorCommand;
import com.enterprise.mm.payload.IntegrationHealthWorkspaceDtos.CommandResult;
import com.enterprise.mm.payload.IntegrationHealthWorkspaceDtos.EventDetail;
import com.enterprise.mm.payload.IntegrationHealthWorkspaceDtos.EventListItem;
import com.enterprise.mm.payload.IntegrationHealthWorkspaceDtos.QueueDescriptor;
import com.enterprise.mm.payload.IntegrationHealthWorkspaceDtos.Summary;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.service.IntegrationHealthCommandService;
import com.enterprise.mm.service.IntegrationHealthWorkspaceService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspaces/integration-health")
@RequiredArgsConstructor
public class IntegrationHealthWorkspaceController {
    private static final String READ_ROLES = "hasAnyRole('ADMIN','MM_MANAGER','FINANCE_CONTROLLER','AUDITOR','VIEWER','DEPARTMENT_HEAD','DEPARTMENT_WORKER')";
    private static final String WRITE_ROLES = "hasAnyRole('ADMIN','MM_MANAGER','FINANCE_CONTROLLER')";

    private final IntegrationHealthWorkspaceService service;
    private final IntegrationHealthCommandService commandService;

    @GetMapping("/summary")
    @PreAuthorize(READ_ROLES)
    public ApiResponse<Summary> summary() {
        return ApiResponse.ok(service.summary());
    }

    @GetMapping("/queues")
    @PreAuthorize(READ_ROLES)
    public ApiResponse<List<QueueDescriptor>> queues() {
        return ApiResponse.ok(service.queues());
    }

    @GetMapping("/events")
    @PreAuthorize(READ_ROLES)
    public ApiResponse<PageResponse<EventListItem>> events(
        @RequestParam(defaultValue = "FAILED") String queue,
        @RequestParam(required = false) String search,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(page, size, Sort.by("createdAt").descending());
        return ApiResponse.ok(PageResponse.from(service.events(queue, search, pageable)));
    }

    @GetMapping("/events/{eventId}")
    @PreAuthorize(READ_ROLES)
    public ApiResponse<EventDetail> detail(@PathVariable UUID eventId) {
        return ApiResponse.ok(service.detail(eventId));
    }

    @PostMapping("/events/{eventId}/retry")
    @PreAuthorize(WRITE_ROLES)
    public ApiResponse<CommandResult> retry(
        @PathVariable UUID eventId,
        @Valid @RequestBody OperatorCommand command,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return ApiResponse.ok(commandService.retry(eventId, command, idempotencyKey));
    }

    @PostMapping("/events/{eventId}/replay")
    @PreAuthorize(WRITE_ROLES)
    public ApiResponse<CommandResult> replay(
        @PathVariable UUID eventId,
        @Valid @RequestBody OperatorCommand command,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return ApiResponse.ok(commandService.replay(eventId, command, idempotencyKey));
    }

    @PostMapping("/events/{eventId}/cancel")
    @PreAuthorize(WRITE_ROLES)
    public ApiResponse<CommandResult> cancel(
        @PathVariable UUID eventId,
        @Valid @RequestBody OperatorCommand command,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return ApiResponse.ok(commandService.cancel(eventId, command, idempotencyKey));
    }
}
