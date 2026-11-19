package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.payload.PurchaseOrderResponse;
import com.enterprise.mm.payload.PurchaseOrderSearchRequest;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.security.AccessScope;
import com.enterprise.mm.security.CompanyScope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "app.seed.enabled=false")
@ActiveProfiles("test")
@Transactional
class PurchaseOrderCurrentCompanyScopeIntegrationTest {
    @Autowired PurchaseOrderService service;
    @Autowired PurchaseOrderRepository purchaseOrderRepository;
    @Autowired SupplierRepository supplierRepository;

    @AfterEach
    void tearDown() {
        AccessScope.clearRequestedCompanyScope();
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void globalAdminCurrentCompanyRegistryDrilldownIsScopedWhileOrdinaryRegistryStaysTenantWide() {
        UUID currentCompany = UUID.randomUUID();
        TenantContext.setTenant("tenant-po-admin-scope");
        CompanyContext.setCompany(currentCompany);
        Supplier currentSupplier = supplier("SUP-PO-SCOPE-CURRENT", currentCompany);
        Supplier otherSupplier = supplier("SUP-PO-SCOPE-OTHER", UUID.randomUUID());
        purchaseOrder("PO-ADMIN-SCOPE-CURRENT", currentCompany, currentSupplier.getId());
        purchaseOrder("PO-ADMIN-SCOPE-OTHER", otherSupplier.getCompanyId(), otherSupplier.getId());
        purchaseOrderRepository.flush();
        authenticateGlobalAdmin();

        assertThat(service.search(search("po-admin-scope"), PageRequest.of(0, 20)).getTotalElements())
            .isEqualTo(2);

        AccessScope.setRequestedCompanyScope(CompanyScope.CURRENT_COMPANY);
        var scoped = service.search(search("po-admin-scope"), PageRequest.of(0, 20));

        assertThat(scoped.getTotalElements()).isEqualTo(1);
        assertThat(scoped.getContent()).extracting(PurchaseOrderResponse::getCode)
            .containsExactly("PO-ADMIN-SCOPE-CURRENT");
    }

    private Supplier supplier(String code, UUID companyId) {
        Supplier supplier = new Supplier();
        supplier.setTenantId(TenantContext.currentTenant());
        supplier.setCompanyId(companyId);
        supplier.setCode(code);
        supplier.setName(code);
        supplier.setLegalName(code);
        supplier.setStatus("ACTIVE");
        return supplierRepository.save(supplier);
    }

    private PurchaseOrder purchaseOrder(String code, UUID companyId, UUID supplierId) {
        PurchaseOrder order = new PurchaseOrder();
        order.setTenantId(TenantContext.currentTenant());
        order.setCompanyId(companyId);
        order.setSupplierId(supplierId);
        order.setCode(code);
        order.setName(code);
        order.setStatus("SENT");
        order.setRequestType(RequestType.GOODS);
        order.setOrderDate(LocalDate.now());
        order.setExpectedDeliveryDate(LocalDate.now().plusDays(2));
        order.setCurrencyCode("UZS");
        order.setTotalAmount(BigDecimal.TEN);
        return purchaseOrderRepository.save(order);
    }

    private PurchaseOrderSearchRequest search(String value) {
        return new PurchaseOrderSearchRequest(
            value, null, null, null, null, null, false,
            null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null
        );
    }

    private void authenticateGlobalAdmin() {
        var authentication = new UsernamePasswordAuthenticationToken(
            "global-admin",
            null,
            List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );
        authentication.setDetails(new AccessScope.RequestScope(true));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
