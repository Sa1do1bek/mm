package com.enterprise.mm.treasury;

import com.enterprise.mm.common.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Component;

/** Deployment-pinned machine credential. Human authorities never authorize native financial evidence. */
@Component
public class TreasuryNativeAccess {
    private final boolean enabled,localHttp;
    private final String key,tenant,company,source;
    @Value("${mm.treasury.native-organization-id:}") private String organization="";
    @Value("${mm.treasury.allowed-operation-types:ОплатаПоставщику}") private String operations="ОплатаПоставщику";
    public TreasuryNativeAccess(@Value("${mm.treasury.native-enabled:false}") boolean enabled,
        @Value("${mm.treasury.key:}") String key,@Value("${mm.treasury.tenant:}") String tenant,
        @Value("${mm.treasury.company:}") String company,@Value("${mm.treasury.source-base-id:}") String source,
        @Value("${mm.treasury.allow-local-http:false}") boolean localHttp) {
        this.enabled=enabled;this.key=key;this.tenant=tenant;this.company=company;this.source=source;this.localHttp=localHttp;
    }
    public boolean nativeEnabled(){return enabled;}
    public String source(){return source;}
    public String organization(){return organization;}
    public void requireBusinessScope(String requestedOrganization,String operation) {
        if(organization.isBlank()||!organization.equalsIgnoreCase(requestedOrganization)
            ||java.util.Arrays.stream(operations.split(",")).map(String::trim).noneMatch(operation::equals))
            throw new BadCredentialsException("Treasury organization or supplier-operation scope mismatch");
    }
    public void authenticate(String supplied,String requestedSource,boolean secure) {
        requireScope(requestedSource);
        if(!secure&&!localHttp)throw new BadCredentialsException("Treasury machine API requires TLS");
        if(supplied==null||!MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))
            throw new BadCredentialsException("Invalid Treasury machine credential");
    }
    public void requireScope(String requestedSource) {
        if(!enabled||key.length()<32||tenant.isBlank()||company.isBlank()||source.isBlank()
            ||!tenant.equals(TenantContext.currentTenant())||!company.equals(String.valueOf(CompanyContext.currentCompany()))||!source.equals(requestedSource))
            throw new BadCredentialsException("Treasury native API disabled or credential scope mismatch");
    }
}
