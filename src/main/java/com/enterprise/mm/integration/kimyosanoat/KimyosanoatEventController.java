package com.enterprise.mm.integration.kimyosanoat;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.payload.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/integrations/kimyosanoat/events")
public class KimyosanoatEventController {
    private final KimyosanoatEventInbox inbox;
    private final KimyosanoatWaybillInbox waybills;
    private final ObjectMapper json;
    private final String username;
    private final String password;
    private final String tenantId;
    private final String companyId;

    public KimyosanoatEventController(KimyosanoatEventInbox inbox, KimyosanoatWaybillInbox waybills,
            ObjectMapper json,
            @Value("${app.kimyosanoat.webhook.username:}") String username,
            @Value("${app.kimyosanoat.webhook.password:}") String password,
            @Value("${app.kimyosanoat.webhook.tenant-id:}") String tenantId,
            @Value("${app.kimyosanoat.webhook.company-id:}") String companyId) {
        this.inbox = inbox;
        this.waybills = waybills;
        this.json = json;
        this.username = username;
        this.password = password;
        this.tenantId = tenantId;
        this.companyId = companyId;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<?>> receive(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Transfer-Id", required = false) Long transferId,
            @RequestHeader(value = "X-Warehouse-1C-Id", required = false) UUID warehouse1cId,
            @RequestBody byte[] body) {
        if (!configured())
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error("Kimyosanoat event receiver is not configured", "KIMYOSANOAT_WEBHOOK_DISABLED"));
        if (!authenticated(authorization))
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"Kimyosanoat MM events\"")
                .body(ApiResponse.error("Authentication required", "AUTHENTICATION_REQUIRED"));
        JsonNode root;
        try { root = json.readTree(body); }
        catch (IOException ex) { throw new BadRequestException("Invalid Kimyosanoat JSON"); }
        if (root != null && root.has("local_waybills")) {
            var receipt = waybills.accept(body, transferId, warehouse1cId);
            return ResponseEntity.status(receipt.duplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .body(ApiResponse.ok(receipt));
        }
        KimyosanoatEventInbox.Event event;
        try { event = json.treeToValue(root, KimyosanoatEventInbox.Event.class); }
        catch (Exception ex) { throw new BadRequestException("Invalid Kimyosanoat event JSON"); }
        var receipt = inbox.accept(event);
        return ResponseEntity.status(receipt.duplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED)
            .body(ApiResponse.ok(receipt));
    }

    private boolean authenticated(String authorization) {
        if (authorization == null || !authorization.startsWith("Basic ") || authorization.length() > 2048) return false;
        try {
            byte[] supplied = Base64.getDecoder().decode(authorization.substring(6));
            byte[] expected = (username + ":" + password).getBytes(StandardCharsets.UTF_8);
            return MessageDigest.isEqual(expected, supplied);
        } catch (IllegalArgumentException ex) { return false; }
    }

    private boolean configured() {
        if (username.isBlank() || username.contains(":") || password.isBlank()
                || !tenantId.matches("[A-Za-z0-9_.-]{1,80}")) return false;
        try { UUID.fromString(companyId); return true; }
        catch (IllegalArgumentException ex) { return false; }
    }

    @GetMapping("/review")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER')")
    public ApiResponse<java.util.List<KimyosanoatEventInbox.ReviewItem>> reviewQueue() {
        return ApiResponse.ok(inbox.reviewQueue());
    }

    @PostMapping("/review/{eventId}/retry")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER')")
    public ApiResponse<Void> retry(@PathVariable UUID eventId) {
        inbox.retry(eventId);
        return ApiResponse.message("Event queued for retry");
    }

    @GetMapping("/waybills/review")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER')")
    public ApiResponse<java.util.List<KimyosanoatWaybillInbox.Review>> waybillReview() {
        return ApiResponse.ok(waybills.review(TenantContext.currentTenant(), CompanyContext.currentCompany()));
    }

    @GetMapping(value = "/waybills/{receiptId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER')")
    public ResponseEntity<byte[]> waybill(@PathVariable UUID receiptId) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
            .body(waybills.raw(receiptId, TenantContext.currentTenant(), CompanyContext.currentCompany()));
    }
}
