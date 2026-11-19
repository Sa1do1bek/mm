package com.enterprise.mm.erp;

import com.enterprise.mm.payload.ApiResponse;
import java.time.LocalDate;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/erp-exchange/query-jobs")
public class OneCQueryJobController {
    private final OneCQueryJobService service;
    public OneCQueryJobController(OneCQueryJobService service){this.service=service;}
    @PostMapping public ApiResponse<Map<String,Object>> create(@RequestBody Request request){return ApiResponse.ok(service.create(request.type(),request.periodType(),request.date()));}
    @GetMapping public ApiResponse<List<Map<String,Object>>> history(){return ApiResponse.ok(service.history());}
    @GetMapping("/{id}") public ApiResponse<Map<String,Object>> get(@PathVariable UUID id){return ApiResponse.ok(service.get(id));}
    public record Request(String type,String periodType,LocalDate date){}
}
