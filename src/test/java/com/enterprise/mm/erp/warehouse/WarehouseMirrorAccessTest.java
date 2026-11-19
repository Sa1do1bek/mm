package com.enterprise.mm.erp.warehouse;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.enterprise.mm.service.RoleDataScopeService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class WarehouseMirrorAccessTest {
    @Configuration @EnableMethodSecurity(proxyTargetClass=true)
    static class Config {
        @Bean WarehouseMirrorService service(){return mock(WarehouseMirrorService.class);}
        @Bean RoleDataScopeService roleDataScopeService(){return mock(RoleDataScopeService.class);}
        @Bean WarehouseMirrorController controller(WarehouseMirrorService service){return new WarehouseMirrorController(service);}
        @Bean com.enterprise.mm.erp.warehouse.outbound.WarehouseOutboundService outboundService(){return mock(com.enterprise.mm.erp.warehouse.outbound.WarehouseOutboundService.class);}
        @Bean com.enterprise.mm.erp.warehouse.outbound.WarehouseOutboundController outboundController(com.enterprise.mm.erp.warehouse.outbound.WarehouseOutboundService service){return new com.enterprise.mm.erp.warehouse.outbound.WarehouseOutboundController(service);}
    }
    void role(String name){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("operator",null,List.of(new SimpleGrantedAuthority("ROLE_"+name))));}
    @Test void companywideFactsRequireCompanywideRoleAndScopeThroughRealMethodSecurityProxy(){
        try(var context=new AnnotationConfigApplicationContext(Config.class)){
            var access=context.getBean(RoleDataScopeService.class);when(access.currentScope()).thenReturn(RoleDataScopeService.DataScope.unrestricted());
            var controller=context.getBean(WarehouseMirrorController.class);role("ACCOUNTANT");assertThatCode(controller::config).doesNotThrowAnyException();
            when(access.currentScope()).thenReturn(RoleDataScopeService.DataScope.restricted(false,true));assertThatThrownBy(controller::config).isInstanceOf(AccessDeniedException.class);
            when(access.currentScope()).thenReturn(RoleDataScopeService.DataScope.unrestricted());role("WAREHOUSE_MANAGER");assertThatThrownBy(controller::config).isInstanceOf(AccessDeniedException.class);
            role("AUDITOR");assertThatCode(controller::config).doesNotThrowAnyException();assertThatThrownBy(()->controller.create("key",null)).isInstanceOf(AccessDeniedException.class);assertThatThrownBy(()->controller.retry(java.util.UUID.randomUUID())).isInstanceOf(AccessDeniedException.class);assertThatThrownBy(()->controller.cancel(java.util.UUID.randomUUID())).isInstanceOf(AccessDeniedException.class);
            var outbound=context.getBean(com.enterprise.mm.erp.warehouse.outbound.WarehouseOutboundController.class);assertThatCode(outbound::coverage).doesNotThrowAnyException();assertThatThrownBy(()->outbound.reconcile(null)).isInstanceOf(AccessDeniedException.class);assertThatThrownBy(()->outbound.approve(java.util.UUID.randomUUID(),"key",null)).isInstanceOf(AccessDeniedException.class);role("ACCOUNTANT");assertThatThrownBy(()->outbound.approve(java.util.UUID.randomUUID(),"key",null)).isInstanceOf(AccessDeniedException.class);when(access.currentScope()).thenReturn(RoleDataScopeService.DataScope.restricted(false,true));assertThatThrownBy(outbound::coverage).isInstanceOf(AccessDeniedException.class);
            role("FINANCE_CONTROLLER");assertThatThrownBy(()->outbound.approve(java.util.UUID.randomUUID(),"key",null)).isInstanceOf(AccessDeniedException.class);when(access.currentScope()).thenReturn(RoleDataScopeService.DataScope.unrestricted());assertThatCode(()->outbound.approve(java.util.UUID.randomUUID(),"key",null)).doesNotThrowAnyException();assertThatThrownBy(()->outbound.reconcile(null)).isInstanceOf(AccessDeniedException.class);role("ADMIN");assertThatCode(()->outbound.reconcile(null)).doesNotThrowAnyException();
        }finally{SecurityContextHolder.clearContext();}
    }
}
