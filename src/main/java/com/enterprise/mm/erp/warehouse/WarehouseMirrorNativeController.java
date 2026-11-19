package com.enterprise.mm.erp.warehouse;

import com.enterprise.mm.erp.OneCApiAccess;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value="/api/integrations/one-c/warehouse",produces="application/json")
public class WarehouseMirrorNativeController {
    private final OneCApiAccess access;private final WarehouseMirrorService service;
    public WarehouseMirrorNativeController(OneCApiAccess access,WarehouseMirrorService service){this.access=access;this.service=service;}
    @PostMapping("/snapshots") public Map<String,Object> snapshot(@RequestHeader(value="X-MM-API-Key",required=false) String key,@RequestHeader(value="Idempotency-Key",required=false) String idempotency,@RequestBody JsonNode request){access.authenticate(key);if(idempotency==null||!idempotency.equals(request.path("eventId").asText()))throw new com.enterprise.mm.error.BadRequestException("Idempotency-Key must equal eventId");return service.receive(request);}
    @PostMapping("/history/poll") public Map<String,Object> poll(@RequestHeader(value="X-MM-API-Key",required=false) String key){access.authenticate(key);return service.poll();}
    @PostMapping("/history/pages") public Map<String,Object> page(@RequestHeader(value="X-MM-API-Key",required=false) String key,@RequestBody JsonNode request){access.authenticate(key);return service.page(request);}
    @PostMapping("/history/failure") public Map<String,Object> failure(@RequestHeader(value="X-MM-API-Key",required=false) String key,@RequestBody JsonNode request){access.authenticate(key);return service.historyFailure(request);}
}
