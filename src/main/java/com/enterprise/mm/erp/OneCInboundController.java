package com.enterprise.mm.erp;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value="/api/integrations/one-c",produces="application/json")
public class OneCInboundController {
    private final OneCApiAccess access;
    private final OneCInboundService service;
    public OneCInboundController(OneCApiAccess access,OneCInboundService service) { this.access=access; this.service=service; }

    @GetMapping("/health")
    public Map<String,Object> health(@RequestHeader(value="X-MM-API-Key",required=false) String key) {
        access.authenticate(key);
        return Map.of("protocolVersion",1,"connected",true,
            "tenantId",com.enterprise.mm.common.TenantContext.currentTenant(),
            "companyId",com.enterprise.mm.common.CompanyContext.currentCompany());
    }

    @PostMapping("/events")
    public Map<String,Object> receive(@RequestHeader(value="X-MM-API-Key",required=false) String key,
                                      @RequestHeader(value="Idempotency-Key",required=false) String idempotencyKey,
                                      @RequestBody JsonNode event) {
        access.authenticate(key);
        var context=org.springframework.security.core.context.SecurityContextHolder.getContext();
        var previous=context.getAuthentication();
        context.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
            "one-c-api",null,java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_INTEGRATION_OPERATOR"))));
        try { return service.receive(event,idempotencyKey); }
        finally { context.setAuthentication(previous); }
    }
}
