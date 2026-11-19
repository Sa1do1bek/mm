package com.enterprise.mm.warehouse.history;

import com.enterprise.mm.payload.ApiResponse;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/warehouse-source") @RequiredArgsConstructor
public class WarehouseSourceProfileController {
    private final WarehouseSourceProfileService service;
    private final WarehouseSourceVerification verification;
    @GetMapping("/verification") public ApiResponse<Map<String,Object>> verification(){return ApiResponse.ok(verification.verify());}
    private final WarehouseSourceProfileExport exporter;
    @GetMapping("/summary") public ApiResponse<Map<String,Object>> summary(){return ApiResponse.ok(service.summary());}
    @GetMapping public ApiResponse<Map<String,Object>> records(@RequestParam String category,@RequestParam(required=false) UUID targetId,
        @RequestParam(required=false) String search,@RequestParam(required=false) String parentSourceId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size){return ApiResponse.ok(service.records(category,targetId,search,parentSourceId,page,size,false));}
    @GetMapping("/{id}") public ApiResponse<Map<String,Object>> detail(@PathVariable UUID id){return ApiResponse.ok(service.detail(id));}
    @GetMapping("/export") public ResponseEntity<byte[]> export(@RequestParam String category,@RequestParam(required=false) UUID targetId,
        @RequestParam(required=false) String search,@RequestParam(required=false) String parentSourceId,@RequestParam(defaultValue="xlsx") String format,@RequestParam(defaultValue="ru") String language){
        byte[] data=exporter.export(service.records(category,targetId,search,parentSourceId,0,200,true),category,format,language);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(format.equals("csv")?"text/csv;charset=UTF-8":"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(exporter.title(category,language)+"."+format,java.nio.charset.StandardCharsets.UTF_8).build().toString()).body(data);
    }
}
