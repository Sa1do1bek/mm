package com.enterprise.mm.erp.warehouse;

import com.enterprise.mm.error.BadRequestException;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class WarehouseMirrorSettings {
    private final boolean enabled; private final String source,organization;
    public WarehouseMirrorSettings(@Value("${mm.one-c-warehouse.enabled:false}") boolean enabled,
        @Value("${mm.one-c-warehouse.source-instance-id:}") String source,
        @Value("${mm.one-c-warehouse.organization-id:}") String organization){this.enabled=enabled;this.source=source;this.organization=organization;}
    public void requireEnabled(){if(!enabled||source.isBlank()||organization.isBlank())throw new BadRequestException("Warehouse v2 is disabled or native source/organization is not pinned");}
    public void validate(String source,String organization){requireEnabled();if(!this.source.equals(source)||!this.organization.equals(organization))throw new BadRequestException("Native warehouse source or organization differs from configured scope");}
    public String source(){return source;} public String organization(){return organization;}
    public Map<String,Object> config(){return Map.of("enabled",enabled&&!source.isBlank()&&!organization.isBlank(),"sourceInstanceId",source,"organizationId",organization,"operationalApplication","NOT_APPLIED","valuationPolicy","PRESERVE_MM_NATIVE");}
}
