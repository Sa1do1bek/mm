package com.enterprise.mm.erp;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

class OneCPullProtocolTest {
    private final ObjectMapper mapper = new ObjectMapper();
    @Test void pullModeDisablesScheduledHttpBeforeTouchingDatabase() {
        var jdbc=mock(JdbcTemplate.class); var tx=mock(TransactionTemplate.class);
        var worker=new ErpDeliveryWorker(jdbc,mapper,tx);
        ReflectionTestUtils.setField(worker,"transport",new OneCTransport(true));
        worker.deliver(); verifyNoInteractions(jdbc,tx);
    }
    @Test void resultChecksProtocolLeaseBooleanAndBoundedPayload() throws Exception {
        UUID token=UUID.randomUUID();
        assertThatThrownBy(()->OneCPullExchangeService.validateResult(mapper.readTree("{}"),token)).isInstanceOf(RuntimeException.class);
        var value=mapper.createObjectNode().put("protocolVersion",1).put("leaseToken",token.toString()).put("success",true);
        assertThatThrownBy(()->OneCPullExchangeService.validateResult(value,token)).isInstanceOf(RuntimeException.class);
        value.set("result",mapper.createObjectNode());
        OneCPullExchangeService.validateResult(value,token);
        assertThatThrownBy(()->OneCPullExchangeService.validateResult(value,UUID.randomUUID())).isInstanceOf(RuntimeException.class);
        value.put("protocolVersion",2);
        assertThatThrownBy(()->OneCPullExchangeService.validateResult(value,token)).isInstanceOf(RuntimeException.class);
    }
    @Test void canonicalResultHashIsIndependentOfObjectFieldOrder() throws Exception {
        assertThat(OneCPullExchangeService.hash(mapper.readTree("{\"a\":1,\"b\":2}")))
            .isEqualTo(OneCPullExchangeService.hash(mapper.readTree("{\"b\":2,\"a\":1}")));
    }
    @Test void canonicalHashDoesNotConfuseFieldNamesWithStructuralDelimiters() throws Exception {
        assertThat(OneCPullExchangeService.hash(mapper.readTree("{\"a\":1,\"b\":2}")))
            .isNotEqualTo(OneCPullExchangeService.hash(mapper.readTree("{\"a=1, b\":2}")));
    }
    @Test void inboundAuthenticationDoesNotRequireNativePublicationUrlInPullMode() {
        UUID company=UUID.randomUUID();
        var api=new OneCApiAccess("","x".repeat(32),"tenant",company.toString());
        ReflectionTestUtils.setField(api,"transport",new OneCTransport(true));
        com.enterprise.mm.common.TenantContext.setTenant("tenant");
        com.enterprise.mm.common.CompanyContext.setCompany(company);
        try { api.authenticate("x".repeat(32));
            com.enterprise.mm.common.CompanyContext.setCompany(UUID.randomUUID());
            assertThatThrownBy(()->api.authenticate("x".repeat(32))).isInstanceOf(org.springframework.security.authentication.BadCredentialsException.class);
        } finally { com.enterprise.mm.common.TenantContext.clear(); com.enterprise.mm.common.CompanyContext.clear(); }
    }
    @Test void genericProviderStatusCannotStartOneCHttpInPullMode() {
        var service=mock(com.enterprise.mm.service.IntegrationSettingService.class,CALLS_REAL_METHODS);
        var endpoint=new com.enterprise.mm.service.IntegrationSettingService.RuntimeEndpoint("ONE_C","1C",true,false,"http://127.0.0.1:1",null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null);
        ReflectionTestUtils.setField(service,"oneCTransport",new OneCTransport(true));
        assertThatThrownBy(()->ReflectionTestUtils.invokeMethod(service,"live",endpoint)).isInstanceOf(com.enterprise.mm.error.BusinessException.class);
    }

}
