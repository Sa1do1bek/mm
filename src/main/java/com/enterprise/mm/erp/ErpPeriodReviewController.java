package com.enterprise.mm.erp;

import com.enterprise.mm.payload.ApiResponse;
import java.time.LocalDate;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/erp-exchange/period-reviews")
public class ErpPeriodReviewController {
    private final ErpPeriodReviewService service;
    @org.springframework.beans.factory.annotation.Autowired private OneCQueryJobService jobs;
    @org.springframework.beans.factory.annotation.Autowired private OneCTransport transport;
    public ErpPeriodReviewController(ErpPeriodReviewService service){this.service=service;}
    @GetMapping public ApiResponse<List<Map<String,Object>>> history(){return ApiResponse.ok(service.history());}
    @GetMapping("/{id}") public ApiResponse<Map<String,Object>> get(@PathVariable UUID id){return ApiResponse.ok(service.get(id));}
    @PostMapping public ApiResponse<Map<String,Object>> generate(@RequestBody GenerateRequest request){return ApiResponse.ok(transport!=null&&transport.pullEnabled()?jobs.create("PERIOD",request.periodType(),request.date()):service.generate(request.periodType(),request.date()));}
    @PostMapping("/{id}/review") public ApiResponse<Map<String,Object>> review(@PathVariable UUID id,@RequestBody ReviewRequest request){return ApiResponse.ok(service.review(id,request.decision(),request.comment(),request.expectedVersion(),request.fingerprint(),request.nativeEvidenceJobId()));}
    public record GenerateRequest(String periodType,LocalDate date){}
    public record ReviewRequest(String decision,String comment,long expectedVersion,String fingerprint,UUID nativeEvidenceJobId){}
}
