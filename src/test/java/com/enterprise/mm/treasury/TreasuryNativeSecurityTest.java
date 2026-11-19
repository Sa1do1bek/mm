package com.enterprise.mm.treasury;

import static org.assertj.core.api.Assertions.*;
import com.enterprise.mm.common.*;
import java.util.UUID;
import org.junit.jupiter.api.*;

class TreasuryNativeSecurityTest {
    final UUID company=UUID.randomUUID();
    final String key="treasury-test-key-32-characters-minimum";
    @AfterEach void clear(){TenantContext.clear();CompanyContext.clear();}
    @Test void keysCannotExpandCompanyOrSourceScopeAndHumanRoleIsNotCredential() {
        var access=new TreasuryNativeAccess(true,key,"tenant",company.toString(),"base-a",false);
        TenantContext.setTenant("tenant");CompanyContext.setCompany(company);
        access.authenticate(key,"base-a",true);
        assertThatThrownBy(()->access.authenticate(null,"base-a",true)).hasMessageContaining("credential");
        assertThatThrownBy(()->access.authenticate(key,"base-b",true)).hasMessageContaining("scope");
        CompanyContext.setCompany(UUID.randomUUID());
        assertThatThrownBy(()->access.authenticate(key,"base-a",true)).hasMessageContaining("scope");
    }
    @Test void productionRejectsPlainHttpEvenWithCorrectKey() {
        var access=new TreasuryNativeAccess(true,key,"tenant",company.toString(),"base-a",false);
        TenantContext.setTenant("tenant");CompanyContext.setCompany(company);
        assertThatThrownBy(()->access.authenticate(key,"base-a",false)).hasMessageContaining("TLS");
    }
}
