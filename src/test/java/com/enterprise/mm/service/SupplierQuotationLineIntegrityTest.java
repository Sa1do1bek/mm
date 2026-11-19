package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.RFQItem;
import com.enterprise.mm.entity.SupplierQuotation;
import com.enterprise.mm.entity.SupplierQuotationItem;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.SupplierQuotationItemMapper;
import com.enterprise.mm.payload.SupplierQuotationItemCreateRequest;
import com.enterprise.mm.repository.RFQItemRepository;
import com.enterprise.mm.repository.SupplierQuotationItemRepository;
import com.enterprise.mm.repository.SupplierQuotationRepository;
import com.enterprise.mm.security.AccessScope;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class SupplierQuotationLineIntegrityTest {
    private static final String TENANT = "quotation-line-integrity";

    @Mock private SupplierQuotationItemRepository repository;
    @Mock private SupplierQuotationItemMapper mapper;
    @Mock private MaterialCatalogGuard materialCatalogGuard;
    @Mock private ProcurementLineValidationService lineValidationService;
    @Mock private SupplierQuotationRepository quotationRepository;
    @Mock private RFQItemRepository rfqItemRepository;
    @Mock private AuditService auditService;
    @Mock private RoleDataScopeService roleDataScopeService;
    @InjectMocks private SupplierQuotationItemService service;

    private UUID companyId;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(companyId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
        AccessScope.clearRequestedCompanyScope();
        SecurityContextHolder.clearContext();
    }

    @Test
    void createRejectsDuplicateActiveRfqLineBeforeWriting() {
        Fixture fixture = fixture(companyId, TENANT);
        when(repository.existsByQuotationIdAndRfqItemIdAndIsDeletedFalse(
            fixture.quotation().getId(), fixture.rfqItem().getId())).thenReturn(true);

        assertThatThrownBy(() -> service.create(fixture.request()))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("already contains RFQ line");

        verify(repository, never()).save(any());
    }

    @Test
    void createTranslatesDatabaseUniquenessRaceToConflict() {
        Fixture fixture = fixture(companyId, TENANT);
        when(repository.existsByQuotationIdAndRfqItemIdAndIsDeletedFalse(
            fixture.quotation().getId(), fixture.rfqItem().getId())).thenReturn(false);
        when(repository.save(fixture.item())).thenReturn(fixture.item());
        org.mockito.Mockito.doThrow(new DataIntegrityViolationException(
            "duplicate key violates unique constraint uk_supplier_quotation_items_active_rfq_line"
        )).when(repository).flush();

        assertThatThrownBy(() -> service.create(fixture.request()))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("already contains RFQ line");
    }

    @Test
    void restoreRejectsWhenAnotherActiveItemAlreadyUsesTheRfqLine() {
        Fixture fixture = fixture(companyId, TENANT);
        fixture.item().softDelete();
        when(repository.findAnyById(fixture.item().getId())).thenReturn(Optional.of(fixture.item()));
        when(repository.existsByQuotationIdAndRfqItemIdAndIsDeletedFalse(
            fixture.quotation().getId(), fixture.rfqItem().getId())).thenReturn(true);

        assertThatThrownBy(() -> service.restore(fixture.item().getId()))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("already contains RFQ line");

        assertThat(fixture.item().isDeleted()).isTrue();
        verify(repository, never()).save(any());
    }

    @Test
    void globalAdministratorCannotAttachRfqLineFromAnotherCompany() {
        authenticateGlobalAdministrator();
        Fixture fixture = fixture(companyId, TENANT);
        fixture.rfqItem().setCompanyId(UUID.randomUUID());

        assertThatThrownBy(() -> service.create(fixture.request()))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("RFQItem not found");

        verify(repository, never()).save(any());
    }

    @Test
    void cannotAttachRfqLineFromAnotherTenant() {
        Fixture fixture = fixture(companyId, TENANT);
        fixture.rfqItem().setTenantId("another-tenant");

        assertThatThrownBy(() -> service.create(fixture.request()))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("RFQItem not found");

        verify(repository, never()).save(any());
    }

    private Fixture fixture(UUID parentCompanyId, String tenantId) {
        UUID quotationId = UUID.randomUUID();
        UUID rfqId = UUID.randomUUID();
        UUID rfqItemId = UUID.randomUUID();

        PurchaseRequest purchaseRequest = new PurchaseRequest();
        purchaseRequest.setId(UUID.randomUUID());
        purchaseRequest.setTenantId(tenantId);
        purchaseRequest.setCompanyId(parentCompanyId);

        RFQ rfq = new RFQ();
        rfq.setId(rfqId);
        rfq.setTenantId(tenantId);
        rfq.setCompanyId(parentCompanyId);
        rfq.setPurchaseRequestId(purchaseRequest.getId());
        rfq.setPurchaseRequest(purchaseRequest);

        SupplierQuotation quotation = new SupplierQuotation();
        quotation.setId(quotationId);
        quotation.setTenantId(tenantId);
        quotation.setCompanyId(parentCompanyId);
        quotation.setStatus("SUBMITTED");
        quotation.setRfqId(rfqId);
        quotation.setRfq(rfq);

        RFQItem rfqItem = new RFQItem();
        rfqItem.setId(rfqItemId);
        rfqItem.setTenantId(tenantId);
        rfqItem.setCompanyId(parentCompanyId);
        rfqItem.setRfqId(rfqId);

        SupplierQuotationItem item = new SupplierQuotationItem();
        item.setId(UUID.randomUUID());
        item.setTenantId(tenantId);
        item.setCompanyId(parentCompanyId);
        item.setQuotationId(quotationId);
        item.setRfqItemId(rfqItemId);
        item.setStatus("ACTIVE");

        SupplierQuotationItemCreateRequest request = new SupplierQuotationItemCreateRequest();
        request.setQuotationId(quotationId);
        request.setRfqItemId(rfqItemId);

        org.mockito.Mockito.lenient().when(mapper.toEntity(request)).thenReturn(item);
        when(quotationRepository.findByIdAndIsDeletedFalse(quotationId)).thenReturn(Optional.of(quotation));
        when(roleDataScopeService.canAccessDocument(null, null, null)).thenReturn(true);
        org.mockito.Mockito.lenient().when(rfqItemRepository.findByIdAndIsDeletedFalse(rfqItemId))
            .thenReturn(Optional.of(rfqItem));
        return new Fixture(request, quotation, rfqItem, item);
    }

    private void authenticateGlobalAdministrator() {
        UsernamePasswordAuthenticationToken authentication =
            new UsernamePasswordAuthenticationToken("global-admin", "n/a");
        authentication.setDetails(new AccessScope.RequestScope(true));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private record Fixture(
        SupplierQuotationItemCreateRequest request,
        SupplierQuotation quotation,
        RFQItem rfqItem,
        SupplierQuotationItem item
    ) {
    }
}
