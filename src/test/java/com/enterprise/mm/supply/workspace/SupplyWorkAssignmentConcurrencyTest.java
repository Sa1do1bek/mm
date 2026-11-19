package com.enterprise.mm.supply.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.entity.Company;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.enums.RoleName;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.repository.CompanyRepository;
import com.enterprise.mm.supply.workspace.domain.SupplyAccessScope;
import com.enterprise.mm.supply.workspace.domain.SupplyCommitmentKey;
import com.enterprise.mm.supply.workspace.domain.SupplyCommitmentKeyCodec;
import com.enterprise.mm.supply.workspace.enums.SupplyCommitmentKind;
import com.enterprise.mm.supply.workspace.error.SupplyAssignmentConflictException;
import com.enterprise.mm.supply.workspace.payload.SupplyAssignmentDtos.ClaimRequest;
import com.enterprise.mm.supply.workspace.repository.SupplyWorkAssignmentRepository;
import com.enterprise.mm.supply.workspace.repository.SupplyCommitmentQueryRepository;
import com.enterprise.mm.supply.workspace.service.SupplyWorkAssignmentService;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
    "app.seed.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:supply_assignment_concurrency;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"
})
@ActiveProfiles("test")
class SupplyWorkAssignmentConcurrencyTest {
    @Autowired private SupplyWorkAssignmentService service;
    @Autowired private SupplyWorkAssignmentRepository assignmentRepository;
    @Autowired private SupplierRepository supplierRepository;
    @Autowired private PurchaseOrderRepository purchaseOrderRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private SupplyCommitmentQueryRepository commitmentQueryRepository;

    @AfterEach
    void clearContexts() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void absentRowClaimHasOneWinnerAndOneConflict() throws Exception {
        String tenant = "tenant-claim-" + UUID.randomUUID();
        TenantContext.setTenant(tenant);
        UUID company = persistCompany(tenant);
        CompanyContext.setCompany(company);
        User specialist = persistUser(tenant, company, "buyer.concurrent");
        PurchaseOrder po = persistPo(tenant, company);
        SupplyCommitmentKey key = new SupplyCommitmentKey(SupplyCommitmentKind.PO, po.getId());
        String encoded = new SupplyCommitmentKeyCodec().encode(key);
        SupplyAccessScope scope = new SupplyAccessScope(tenant, Set.of(company), Set.of(), false, false);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<String> claim = () -> {
            TenantContext.setTenant(tenant);
            CompanyContext.setCompany(company);
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                specialist.getUsername(), "n/a", List.of(new SimpleGrantedAuthority("ROLE_PROCUREMENT_SPECIALIST"))));
            ready.countDown();
            start.await();
            try {
                service.claim(encoded, scope, new ClaimRequest(
                    null, "spoof", "ADMIN", "concurrent claim", null));
                return "SUCCESS";
            } catch (SupplyAssignmentConflictException conflict) {
                return "CONFLICT";
            } finally {
                SecurityContextHolder.clearContext();
                TenantContext.clear();
                CompanyContext.clear();
            }
        };

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(claim);
            var second = executor.submit(claim);
            ready.await();
            start.countDown();
            assertThat(List.of(first.get(), second.get()))
                .containsExactlyInAnyOrder("SUCCESS", "CONFLICT");
        }

        assertThat(assignmentRepository.findByTenantIdAndCompanyIdAndCommitmentKeyAndIsDeletedFalse(
            tenant, company, key.canonical())).isPresent();
        var joinedCommitment = commitmentQueryRepository.findScoped(
            key, scope, java.time.Instant.now()).orElseThrow();
        assertThat(joinedCommitment.isUnassigned()).isFalse();
        assertThat(joinedCommitment.ownerUsername()).isEqualTo("buyer.concurrent");
        assertThat(joinedCommitment.assignmentDueDateSource())
            .isEqualTo(com.enterprise.mm.supply.workspace.enums.SupplyDueDateSource.POLICY);
    }

    @Test
    void activeAssignmentLookupUsesPessimisticWriteLock() throws Exception {
        Lock lock = SupplyWorkAssignmentRepository.class
            .getMethod("findActiveForUpdate", String.class, UUID.class, String.class)
            .getAnnotation(Lock.class);

        assertThat(lock).isNotNull();
        assertThat(lock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }

    private User persistUser(String tenant, UUID company, String username) {
        User user = base(new User(), tenant, company, "USR", username);
        user.setUsername(username);
        user.setPasswordHash("not-used");
        user.setEnabled(true);
        user.setRoles(Set.of(RoleName.PROCUREMENT_SPECIALIST));
        return userRepository.saveAndFlush(user);
    }

    private UUID persistCompany(String tenant) {
        Company company = base(new Company(), tenant, UUID.randomUUID(), "COMP", "Concurrent company");
        return companyRepository.saveAndFlush(company).getId();
    }

    private PurchaseOrder persistPo(String tenant, UUID company) {
        Supplier supplier = base(new Supplier(), tenant, company, "SUP", "Concurrent supplier");
        supplier.setLegalName("Concurrent supplier");
        supplier = supplierRepository.saveAndFlush(supplier);
        PurchaseOrder po = base(new PurchaseOrder(), tenant, company, "PO", "Concurrent PO");
        po.setRequestType(RequestType.GOODS);
        po.setStatus("SENT");
        po.setSupplierId(supplier.getId());
        po.setOrderDate(LocalDate.now().minusDays(3));
        po.setExpectedDeliveryDate(LocalDate.now().plusDays(3));
        po.setCurrencyCode("USD");
        po.setTotalAmount(java.math.BigDecimal.TEN);
        return purchaseOrderRepository.saveAndFlush(po);
    }

    private <T extends BusinessEntity> T base(
        T entity,
        String tenant,
        UUID company,
        String code,
        String name
    ) {
        entity.setTenantId(tenant);
        entity.setCompanyId(company);
        entity.setCode(code + "-" + UUID.randomUUID());
        entity.setName(name);
        entity.setStatus("ACTIVE");
        return entity;
    }
}
