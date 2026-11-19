package com.enterprise.mm.erp.warehouse.outbound;

import com.enterprise.mm.erp.OneCApiAccess;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value="/api/integrations/one-c/warehouse/operations",produces="application/json")
public class WarehouseOutboundNativeController {
    private final OneCApiAccess access;private final WarehouseOutboundService service;
    public WarehouseOutboundNativeController(OneCApiAccess access,WarehouseOutboundService service){this.access=access;this.service=service;}
    @PostMapping("/poll") public Map<String,Object> poll(@RequestHeader(value="X-MM-API-Key",required=false) String key,@RequestBody JsonNode request){access.authenticate(key);return service.poll(request);}
    @PostMapping("/{id}/result") public Map<String,Object> result(@RequestHeader(value="X-MM-API-Key",required=false) String key,@PathVariable UUID id,@RequestBody JsonNode request){access.authenticate(key);return service.result(id,request);}
}
