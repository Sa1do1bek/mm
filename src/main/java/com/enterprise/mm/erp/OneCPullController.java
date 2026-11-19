package com.enterprise.mm.erp;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value="/api/integrations/one-c/tasks",produces="application/json")
public class OneCPullController {
    private final OneCApiAccess access;
    private final OneCPullExchangeService service;
    public OneCPullController(OneCApiAccess access,OneCPullExchangeService service){this.access=access;this.service=service;}
    @PostMapping("/poll") public Map<String,Object> poll(@RequestHeader(value="X-MM-API-Key",required=false) String key,@RequestBody JsonNode request){access.authenticate(key);return service.poll(request);}
    @PostMapping("/{id}/result") public Map<String,Object> result(@RequestHeader(value="X-MM-API-Key",required=false) String key,@PathVariable UUID id,@RequestBody JsonNode request){access.authenticate(key);return service.result(id,request);}
}
