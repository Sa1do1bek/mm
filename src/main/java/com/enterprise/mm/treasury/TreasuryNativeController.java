package com.enterprise.mm.treasury;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value="/api/integrations/treasury",produces="application/json")
public class TreasuryNativeController {
    private final TreasuryNativeAccess access;private final TreasuryTaskService tasks;private final TreasuryEventService events;
    public TreasuryNativeController(TreasuryNativeAccess access,TreasuryTaskService tasks,TreasuryEventService events){this.access=access;this.tasks=tasks;this.events=events;}
    void authenticate(String key,JsonNode body,HttpServletRequest http){boolean secure=http.isSecure();if(!secure&&!Set.of("127.0.0.1","0:0:0:0:0:0:0:1","::1").contains(http.getRemoteAddr()))throw new org.springframework.security.authentication.BadCredentialsException("Treasury machine API requires TLS outside loopback");access.authenticate(key,body.path("sourceBaseId").asText(),secure);}
    @PostMapping("/tasks/poll") public Map<String,Object> poll(@RequestHeader(value="X-MM-Treasury-Key",required=false)String key,@RequestBody JsonNode body,HttpServletRequest http){authenticate(key,body,http);return tasks.poll(body);}
    @PostMapping("/tasks/{id}/result") public Map<String,Object> result(@RequestHeader(value="X-MM-Treasury-Key",required=false)String key,@PathVariable UUID id,@RequestBody JsonNode body,HttpServletRequest http){authenticate(key,body,http);return tasks.result(id,body);}
    @PostMapping("/events") public Map<String,Object> event(@RequestHeader(value="X-MM-Treasury-Key",required=false)String key,@RequestBody JsonNode body,HttpServletRequest http){authenticate(key,body,http);return events.receive(body);}
}
