package com.enterprise.mm.erp;

import static org.assertj.core.api.Assertions.*;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;

class OneCApiAccessTest {
    private static final String BASE="http://127.0.0.1:15442/compact/hs/mmapi";
    private static final String KEY="test-only-native-api-key-with-more-than-32-characters";
    private static final UUID COMPANY=UUID.fromString("10000000-0000-0000-0000-000000000001");
    private final OneCApiAccess access=new OneCApiAccess(BASE,KEY,"mm-local",COMPANY.toString());
    @AfterEach void clear() { TenantContext.clear(); CompanyContext.clear(); }
    @Test void nativePublicationGetsCompanyBoundCredential() {
        var builder=HttpRequest.newBuilder(URI.create(BASE+"/health"));
        access.authorize(builder,BASE,"mm-local",COMPANY);
        var headers=builder.GET().build().headers();
        assertThat(headers.firstValue("X-MM-API-Key")).contains(KEY);
        assertThat(headers.firstValue("X-Tenant-Id")).contains("mm-local");
        assertThat(headers.firstValue("X-Company-Id")).contains(COMPANY.toString());
    }
    @Test void credentialCannotBeSentToAnotherEndpoint() {
        assertThatThrownBy(() -> access.authorize(HttpRequest.newBuilder(URI.create("http://127.0.0.1:15441")),
            "http://127.0.0.1:15441","mm-local",COMPANY)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void incomingRequiresBothCredentialAndMatchingScope() {
        TenantContext.setTenant("mm-local"); CompanyContext.setCompany(COMPANY);
        access.authenticate(KEY);
        assertThatThrownBy(() -> access.authenticate("incorrect")).isInstanceOf(BadCredentialsException.class);
        TenantContext.setTenant("other");
        assertThatThrownBy(() -> access.authenticate(KEY)).isInstanceOf(BadCredentialsException.class);
    }
    @Test void missingConfigurationFailsClosed() {
        var disabled=new OneCApiAccess("","","","");
        assertThatThrownBy(() -> disabled.authenticate(null)).isInstanceOf(BadCredentialsException.class);
    }
}
