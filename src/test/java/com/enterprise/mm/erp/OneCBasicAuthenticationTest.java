package com.enterprise.mm.erp;

import com.enterprise.mm.erp.warehouse.WarehouseMirrorNativeController;
import com.enterprise.mm.erp.warehouse.WarehouseMirrorService;
import com.enterprise.mm.erp.warehouse.outbound.WarehouseOutboundNativeController;
import com.enterprise.mm.erp.warehouse.outbound.WarehouseOutboundService;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.security.*;
import com.enterprise.mm.treasury.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes=OneCBasicAuthenticationTest.Config.class)
@TestPropertySource(properties={
    "mm.one-c-api.base-url=http://127.0.0.1:15442/test/hs/mmapi",
    "mm.one-c-api.key=test-only-legacy-native-key-with-at-least-32-characters",
    "mm.one-c-api.username=test-one-c", "mm.one-c-api.password=test-only-basic-password",
    "mm.one-c-api.tenant=basic-test", "mm.one-c-api.company=10000000-0000-0000-0000-000000000001",
    "app.cors.allowed-origins=http://localhost", "mm.one-c-api.pull-enabled=false"
})
class OneCBasicAuthenticationTest {
    private static final String COMPANY="10000000-0000-0000-0000-000000000001";
    private static final String KEY="test-only-legacy-native-key-with-at-least-32-characters";
    private static final String TREASURY_KEY="test-only-independent-treasury-key-at-least-32-characters";
    private static final String CHALLENGE="Basic realm=\"MM 1C API\", charset=\"UTF-8\"";
    @Autowired WebApplicationContext context;
    @Autowired OneCApiAccess access;
    @Autowired OneCInboundService inbound;
    @Autowired OneCPullExchangeService pull;
    @Autowired WarehouseMirrorService mirror;
    @Autowired WarehouseOutboundService outbound;
    @Autowired TreasuryTaskService treasuryTasks;
    MockMvc mvc;

    @BeforeEach void setUp() {
        reset(inbound,pull,mirror,outbound,treasuryTasks);
        when(pull.poll(any())).thenReturn(Map.of("tasks",java.util.List.of()));
        when(mirror.poll()).thenReturn(Map.of("tasks",java.util.List.of()));
        when(outbound.poll(any())).thenReturn(Map.of("tasks",java.util.List.of()));
        when(treasuryTasks.poll(any())).thenReturn(Map.of("tasks",java.util.List.of()));
        mvc=webAppContextSetup(context).apply(springSecurity()).build();
    }
    private static String basic(String value) {
        return "Basic "+Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
    @Test void basicAuthenticatesV1AndBothWarehouseFlows() throws Exception {
        String auth=basic("test-one-c:test-only-basic-password");
        mvc.perform(get("/api/integrations/one-c/health").header("Authorization",auth)
            .header("X-Tenant-Id","basic-test").header("X-Company-Id",COMPANY))
            .andExpect(status().isOk()).andExpect(jsonPath("connected").value(true));
        for(String path:java.util.List.of("tasks/poll","warehouse/history/poll","warehouse/operations/poll")) {
            mvc.perform(post("/api/integrations/one-c/"+path).header("Authorization",auth)
                .header("X-Tenant-Id","basic-test").header("X-Company-Id",COMPANY)
                .contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        }
        verify(pull).poll(any());verify(mirror).poll();verify(outbound).poll(any());
    }
    @Test void wrongAndMalformedBasicCannotFallBackToValidLegacyKey() throws Exception {
        for(String auth:java.util.List.of(basic("test-one-c:wrong"),basic("wrong:test-only-basic-password"),
            "Basic !!!",basic("missing-colon"),"Basic", "Basic ", "Basic /w==")) {
            mvc.perform(post("/api/integrations/one-c/tasks/poll").header("Authorization",auth)
                .header("X-MM-API-Key",KEY).header("X-Tenant-Id","basic-test").header("X-Company-Id",COMPANY)
                .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate",CHALLENGE));
        }
        verifyNoInteractions(inbound,pull,mirror,outbound);
    }
    @Test void missingCredentialGetsMachineBasicChallenge() throws Exception {
        mvc.perform(get("/api/integrations/one-c/health")
            .header("X-Tenant-Id","basic-test").header("X-Company-Id",COMPANY))
            .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate",CHALLENGE));
    }
    @Test void duplicateAuthorizationHeadersFailClosed() throws Exception {
        String valid=basic("test-one-c:test-only-basic-password");
        for(String[] headers:new String[][]{{valid,valid},{valid,basic("test-one-c:wrong")}}) {
            mvc.perform(post("/api/integrations/one-c/tasks/poll").header("Authorization",(Object[])headers)
                .header("X-Tenant-Id","basic-test").header("X-Company-Id",COMPANY)
                .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate",CHALLENGE));
        }
        verifyNoInteractions(pull);
    }
    @Test void controlCharactersAreRejectedAsMalformedHttpBeforeAuthentication() throws Exception {
        String valid=basic("test-one-c:test-only-basic-password");
        for(String control:java.util.List.of("\r\n","\u0000")) {
            mvc.perform(post("/api/integrations/one-c/tasks/poll").header("Authorization",valid+control)
                .header("X-Tenant-Id","basic-test").header("X-Company-Id",COMPANY)
                .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest()); // Spring's StrictHttpFirewall rejects these before our filter.
        }
        verifyNoInteractions(pull);
    }
    @Test void basicStillRequiresPinnedTenantAndCompany() throws Exception {
        for(String[] scope:new String[][]{{"other",COMPANY},{"basic-test","20000000-0000-0000-0000-000000000002"}}) {
            mvc.perform(post("/api/integrations/one-c/tasks/poll")
                .header("Authorization",basic("test-one-c:test-only-basic-password"))
                .header("X-Tenant-Id",scope[0]).header("X-Company-Id",scope[1])
                .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate",CHALLENGE));
        }
        verifyNoInteractions(pull);
    }
    @Test void missingScopeHeadersRejectValidBasic() throws Exception {
        for(String missing:java.util.List.of("X-Tenant-Id","X-Company-Id")) {
            var request=get("/api/integrations/one-c/health").header("Authorization",basic("test-one-c:test-only-basic-password"));
            if(!missing.equals("X-Tenant-Id"))request.header("X-Tenant-Id","basic-test");
            if(!missing.equals("X-Company-Id"))request.header("X-Company-Id",COMPANY);
            mvc.perform(request).andExpect(status().isUnauthorized());
        }
    }
    @Test void basicOnlyPullNeedsNeitherLegacyKeyNorNativePublicationUrl() throws Exception {
        try(var isolated=new org.springframework.web.context.support.AnnotationConfigWebApplicationContext()) {
            isolated.setServletContext(new org.springframework.mock.web.MockServletContext());
            org.springframework.test.context.support.TestPropertySourceUtils.addInlinedPropertiesToEnvironment(isolated,
                "mm.one-c-api.key=", "mm.one-c-api.base-url=", "mm.one-c-api.pull-enabled=true",
                "mm.one-c-api.username=test-one-c", "mm.one-c-api.password=test-only-basic-password",
                "mm.one-c-api.tenant=basic-test", "mm.one-c-api.company="+COMPANY,
                "app.cors.allowed-origins=http://localhost");
            isolated.register(Config.class);isolated.refresh();
            var keylessMvc=webAppContextSetup(isolated).apply(springSecurity()).build();
            keylessMvc.perform(get("/api/integrations/one-c/health")
                .header("Authorization",basic("test-one-c:test-only-basic-password"))
                .header("X-Tenant-Id","basic-test").header("X-Company-Id",COMPANY)).andExpect(status().isOk());
        }
    }
    @Test void legacyKeyRemainsAcceptedAndIncorrectKeyFails() throws Exception {
        mvc.perform(get("/api/integrations/one-c/health").header("X-MM-API-Key",KEY)
            .header("X-Tenant-Id","basic-test").header("X-Company-Id",COMPANY)).andExpect(status().isOk());
        mvc.perform(get("/api/integrations/one-c/health").header("X-MM-API-Key","wrong")
            .header("X-Tenant-Id","basic-test").header("X-Company-Id",COMPANY))
            .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate",CHALLENGE));
    }
    @Test void basicDoesNotAuthenticateBrowserOrTreasuryRoutes() throws Exception {
        mvc.perform(get("/api/materials").header("Authorization",basic("test-one-c:test-only-basic-password"))
            .header("X-Tenant-Id","basic-test").header("X-Company-Id",COMPANY))
            .andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("WWW-Authenticate"));
        mvc.perform(post("/api/integrations/treasury/tasks/poll")
            .header("Authorization",basic("test-one-c:test-only-basic-password"))
            .header("X-Tenant-Id","basic-test").header("X-Company-Id",COMPANY)
            .secure(true).contentType("application/json").content("{\"sourceBaseId\":\"test-source\"}"))
            .andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("WWW-Authenticate"));
        verifyNoInteractions(treasuryTasks);
    }
    @Test void treasuryKeepsItsOwnSourceScopedKey() throws Exception {
        mvc.perform(post("/api/integrations/treasury/tasks/poll").header("X-MM-Treasury-Key",KEY)
            .header("X-Tenant-Id","basic-test").header("X-Company-Id",COMPANY)
            .secure(true).contentType("application/json").content("{\"sourceBaseId\":\"test-source\"}"))
            .andExpect(status().isUnauthorized());
        verifyNoInteractions(treasuryTasks);
        mvc.perform(post("/api/integrations/treasury/tasks/poll").header("X-MM-Treasury-Key",TREASURY_KEY)
            .header("X-Tenant-Id","basic-test").header("X-Company-Id",COMPANY)
            .secure(true).contentType("application/json").content("{\"sourceBaseId\":\"test-source\"}"))
            .andExpect(status().isOk());
        verify(treasuryTasks).poll(any());
    }
    @Test void dormantLegacyOutboundPreservesNativeKeyOnlyIngressContract() {
        String base="http://127.0.0.1:15442/test/hs/mmapi";
        var request=HttpRequest.newBuilder(URI.create(base+"/health"));
        access.authorize(request,base,"basic-test",COMPANY);
        assertThat(request.build().headers().firstValue("Authorization")).isEmpty();
        assertThat(request.build().headers().firstValue("X-MM-API-Key")).contains(KEY);
    }
    @Test void basicOnlyConfigurationCannotSendBlankKeyToLegacyNativeIngress() {
        String base="http://127.0.0.1:15442/test/hs/mmapi";
        var basicOnly=new OneCApiAccess(base,"","basic-test",COMPANY,"test-one-c","test-only-basic-password");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> basicOnly.authorize(
            HttpRequest.newBuilder(URI.create(base+"/health")),base,"basic-test",COMPANY))
            .isInstanceOf(org.springframework.security.authentication.BadCredentialsException.class)
            .hasMessageContaining("Legacy outbound");
    }
    @Configuration @EnableWebMvc @EnableWebSecurity
    @Import({SecurityConfig.class,OneCApiAccess.class,OneCTransport.class,OneCInboundController.class,
        OneCPullController.class,WarehouseMirrorNativeController.class,WarehouseOutboundNativeController.class,
        TreasuryNativeController.class,com.enterprise.mm.error.GlobalExceptionHandler.class})
    static class Config {
        @Bean ObjectMapper mapper(){return new ObjectMapper().findAndRegisterModules();}
        @Bean ApiSecurityErrorHandler errors(ObjectMapper mapper){return new ApiSecurityErrorHandler(mapper);}
        @Bean TenantFilter tenant(ApiSecurityErrorHandler errors){return new TenantFilter(errors);}
        @Bean RateLimitFilter rate(ApiSecurityErrorHandler errors){return new RateLimitFilter(0,errors);}
        @Bean JwtAuthenticationFilter jwt(ApiSecurityErrorHandler errors){return new JwtAuthenticationFilter(mock(JwtService.class),errors,mock(UserRepository.class));}
        @Bean UserDetailsService users(){return mock(UserDetailsService.class);}
        @Bean OneCInboundService inbound(){return mock(OneCInboundService.class);}
        @Bean OneCPullExchangeService pull(){return mock(OneCPullExchangeService.class);}
        @Bean WarehouseMirrorService mirror(){return mock(WarehouseMirrorService.class);}
        @Bean WarehouseOutboundService outbound(){return mock(WarehouseOutboundService.class);}
        @Bean TreasuryNativeAccess treasuryAccess(){return new TreasuryNativeAccess(true,TREASURY_KEY,"basic-test",COMPANY,"test-source",false);}
        @Bean TreasuryTaskService treasuryTasks(){return mock(TreasuryTaskService.class);}
        @Bean TreasuryEventService treasuryEvents(){return mock(TreasuryEventService.class);}
    }
}
