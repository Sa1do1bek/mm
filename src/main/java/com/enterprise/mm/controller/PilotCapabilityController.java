package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.security.pilot.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/pilot-capabilities")
@RequiredArgsConstructor
public class PilotCapabilityController {
    private final PilotCapabilityPolicy policy;

    @GetMapping
    public ApiResponse<Map<String, Object>> get() {
        return ApiResponse.ok(Map.of("policyVersion", "deployment-allowlist-v1",
            "enabledCapabilities", policy.enabledCapabilities(),
            "disabledCapabilities", Arrays.stream(PilotCapability.values()).filter(c -> !policy.enabled(c)).toList()));
    }
}
