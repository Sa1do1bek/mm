package com.enterprise.mm.erp.warehouse.outbound;

import com.enterprise.mm.common.*;
import com.enterprise.mm.erp.warehouse.WarehouseMirrorSettings;
import com.enterprise.mm.error.BadRequestException;
import java.util.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Component;

@Component
public class WarehouseOutboundSettings {
    private final boolean enabled;private final WarehouseMirrorSettings source;
    private final Set<String> contraAccounts;private final String tenant,company;
    @Autowired public WarehouseOutboundSettings(@Value("${mm.one-c-warehouse.outbound-enabled:false}") boolean enabled,WarehouseMirrorSettings source,
        @Value("${mm.one-c-warehouse.allowed-contra-accounts:}") String contra,
        @Value("${mm.one-c-api.tenant:}") String tenant,@Value("${mm.one-c-api.company:}") String company){
        this.enabled=enabled;this.source=source;this.tenant=tenant;this.company=company;var ids=new HashSet<String>();for(String id:contra.split(",")){if(!id.isBlank()){try{ids.add(UUID.fromString(id.trim()).toString());}catch(RuntimeException ex){throw new IllegalArgumentException("Invalid warehouse allowed-contra-accounts configuration");}}}this.contraAccounts=Set.copyOf(ids);
    }
    public WarehouseOutboundSettings(boolean enabled,WarehouseMirrorSettings source,String contra){this(enabled,source,contra,TenantContext.currentTenant(),String.valueOf(CompanyContext.currentCompany()));}
    public boolean captureEnabled(){return enabled&&Boolean.TRUE.equals(source.config().get("enabled"))&&tenant!=null&&!tenant.isBlank()&&company!=null&&tenant.equals(TenantContext.currentTenant())&&company.equals(String.valueOf(CompanyContext.currentCompany()));}
    public void requireEnabled(){if(!captureEnabled())throw new BadRequestException("Warehouse outbound is disabled or outside pinned company scope");source.requireEnabled();}
    public void validate(String instance,String org){requireEnabled();source.validate(instance,org);}
    public String instance(){return source.source();}public String organization(){return source.organization();}
    public boolean allowsContra(String id){return contraAccounts.contains(id);}
    public Map<String,Object> config(){return Map.of("enabled",captureEnabled(),"sourceInstanceId",instance(),"organizationId",organization(),"allowedContraAccountCount",contraAccounts.size(),"valuationPolicy","PRESERVE_MM_NATIVE");}
}
