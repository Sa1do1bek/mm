package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.RfqMailCallbackDtos.RfqMailEvent;
import com.enterprise.mm.service.rfq.RfqDeliveryService;
import com.enterprise.mm.service.rfq.RfqMailCallbackVerifier;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class RfqMailCallbackController {
    private final RfqMailCallbackVerifier callbackVerifier;
    private final RfqDeliveryService deliveryService;

    @PostMapping("/api/integrations/callbacks/rfq-mail/events")
    public ApiResponse<Void> callback(
        @RequestHeader("X-RFQ-Mail-Signature") String signature,
        @Valid @RequestBody RfqMailEvent event
    ) {
        callbackVerifier.requireValid(signature, event);
        deliveryService.applyProviderEvent(event);
        return ApiResponse.ok(null);
    }
}
