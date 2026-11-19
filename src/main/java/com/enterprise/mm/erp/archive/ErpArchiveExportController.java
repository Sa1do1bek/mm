package com.enterprise.mm.erp.archive;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController @RequestMapping("/api/erp-history-archive")
@PreAuthorize("hasRole('ADMIN')")
public class ErpArchiveExportController {
    private final ErpArchiveExportService service;
    public ErpArchiveExportController(ErpArchiveExportService service) { this.service = service; }
    @GetMapping(value = "/export", produces = "application/x-ndjson")
    public ResponseEntity<StreamingResponseBody> export() {
        String tenant = TenantContext.currentTenant(); var company = CompanyContext.currentCompany();
        StreamingResponseBody body = out -> service.write(out, tenant, company);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/x-ndjson"))
            .header("Cache-Control", "no-store").body(body);
    }
}
