package com.enterprise.mm.treasury;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value="/api/treasury",produces="application/json")
@PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST','FINANCE_CONTROLLER','ACCOUNTANT','BUDGET_CONTROLLER','AUDITOR')")
public class TreasuryOperatorController {
    private final TreasuryTaskService tasks;
    public TreasuryOperatorController(TreasuryTaskService tasks){this.tasks=tasks;}
    @GetMapping("/config") public Map<String,Object> config(){return tasks.config();}
    @GetMapping("/requests") public List<Map<String,Object>> list(@RequestParam(required=false)UUID mmPaymentId,@RequestParam(defaultValue="false")boolean externalOnly){return tasks.requests(mmPaymentId,externalOnly);}
    @GetMapping("/requests/{id}") public Map<String,Object> request(@PathVariable UUID id){return tasks.request(id);}
    @GetMapping("/readiness/{id}") public Map<String,Object> readiness(@PathVariable UUID id){return tasks.readiness(id);}
    @GetMapping("/reconciliation") public Map<String,Object> reconciliation(){return tasks.reconciliation();}
    @GetMapping("/mappings") public List<Map<String,Object>> mappings(@RequestParam(required=false)UUID mmPaymentId){return tasks.mappings(mmPaymentId);}
    @PutMapping("/mappings/{id}") @PreAuthorize("hasRole('ADMIN')")
    public Map<String,Object> mapping(@PathVariable UUID id,@RequestBody JsonNode body){return tasks.saveMapping(id,body);}
    @PostMapping("/requests/{id}/link") @PreAuthorize("hasAnyRole('ADMIN','FINANCE_CONTROLLER')")
    public Map<String,Object> link(@PathVariable UUID id,@RequestBody JsonNode body){return tasks.link(id,TreasuryTaskService.uuid(body.path("mmPaymentId").asText(),"mmPaymentId"));}
    @PostMapping("/tasks/{id}/retry") @PreAuthorize("hasAnyRole('ADMIN','FINANCE_CONTROLLER')")
    public Map<String,Object> retry(@PathVariable UUID id){return tasks.retry(id);}
}
