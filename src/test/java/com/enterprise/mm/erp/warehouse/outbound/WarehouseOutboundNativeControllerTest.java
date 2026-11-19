package com.enterprise.mm.erp.warehouse.outbound;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.enterprise.mm.erp.OneCApiAccess;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WarehouseOutboundNativeControllerTest {
    @Test void nativePollAndResultsRequirePinnedApiCredentialBeforeService(){var access=mock(OneCApiAccess.class);var service=mock(WarehouseOutboundService.class);var controller=new WarehouseOutboundNativeController(access,service);var request=new ObjectMapper().createObjectNode();UUID id=UUID.randomUUID();controller.poll("key",request);controller.result("key",id,request);verify(access,times(2)).authenticate("key");verify(service).poll(request);verify(service).result(id,request);doThrow(new org.springframework.security.authentication.BadCredentialsException("disabled")).when(access).authenticate("bad");assertThatThrownBy(()->controller.poll("bad",request)).isInstanceOf(org.springframework.security.authentication.BadCredentialsException.class);verifyNoMoreInteractions(service);}
    @Test void nativeHttpRoutesEnforceActualCredentialScopeEvenForAuthenticatedAdministrator() throws Exception {
        String key="test-only-warehouse-native-key-at-least-32-characters";UUID company=UUID.randomUUID();var access=new OneCApiAccess("http://127.0.0.1:15442/compact/hs/mmapi",key,"native-test",company.toString());var service=mock(WarehouseOutboundService.class);var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new WarehouseOutboundNativeController(access,service)).setControllerAdvice(new com.enterprise.mm.error.GlobalExceptionHandler()).build();
        com.enterprise.mm.common.TenantContext.setTenant("native-test");com.enterprise.mm.common.CompanyContext.setCompany(company);org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("administrator",null,java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
        try{
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/integrations/one-c/warehouse/operations/poll").contentType("application/json").content("{}")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/integrations/one-c/warehouse/operations/"+UUID.randomUUID()+"/result").header("X-MM-API-Key","wrong").contentType("application/json").content("{}")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());verifyNoInteractions(service);
            com.enterprise.mm.common.CompanyContext.setCompany(UUID.randomUUID());mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/integrations/one-c/warehouse/operations/poll").header("X-MM-API-Key",key).contentType("application/json").content("{}")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());verifyNoInteractions(service);
            com.enterprise.mm.common.CompanyContext.setCompany(company);when(service.poll(any())).thenReturn(java.util.Map.of("operations",java.util.List.of()));mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/integrations/one-c/warehouse/operations/poll").header("X-MM-API-Key",key).contentType("application/json").content("{}")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());verify(service).poll(any());
        }finally{com.enterprise.mm.common.TenantContext.clear();com.enterprise.mm.common.CompanyContext.clear();org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    }
}
