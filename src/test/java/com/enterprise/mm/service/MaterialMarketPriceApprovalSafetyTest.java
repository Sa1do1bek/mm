package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.MaterialMarketPrice;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.mapper.MaterialMarketPriceMapper;
import com.enterprise.mm.payload.MaterialMarketPriceCreateRequest;
import com.enterprise.mm.payload.MaterialMarketPriceUpdateRequest;
import com.enterprise.mm.repository.MaterialMarketPriceRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.Query;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class MaterialMarketPriceApprovalSafetyTest {
    private static final String TENANT = "market-price-safety";
    private static final String CONFLICT_MESSAGE =
        "An approved market price already exists for the same material, warehouse scope, currency, and effective date";

    @Mock MaterialMarketPriceRepository repository;
    @Mock MaterialMarketPriceMapper mapper;
    @Mock MaterialCatalogGuard materialCatalogGuard;
    @Mock AuditService auditService;
    @Mock MaterialCostObservationService materialCostObservationService;

    private final MaterialMarketPriceMapper realMapper = new MaterialMarketPriceMapper();
    private UUID companyId;
    private MaterialMarketPriceService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(TENANT);
        companyId = UUID.randomUUID();
        CompanyContext.setCompany(companyId);
        authenticate("finance.controller");
        service = new MaterialMarketPriceService(
            repository,
            mapper,
            materialCatalogGuard,
            auditService,
            materialCostObservationService
        );
        lenient().when(mapper.toResponse(any()))
            .thenAnswer(invocation -> realMapper.toResponse(invocation.getArgument(0)));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void requestDtosDoNotExposeWorkflowOrApprovalEvidence() {
        assertNoWorkflowProperties(MaterialMarketPriceCreateRequest.class);
        assertNoWorkflowProperties(MaterialMarketPriceUpdateRequest.class);
    }

    @Test
    void applicationDuplicateQueryUsesTheSameTrimmedCurrencyKeyAsTheIndex() throws Exception {
        Query query = MaterialMarketPriceRepository.class
            .getMethod(
                "existsApprovedForScopeAndEffectiveDate",
                String.class,
                UUID.class,
                UUID.class,
                UUID.class,
                String.class,
                LocalDate.class,
                UUID.class
            )
            .getAnnotation(Query.class);

        assertThat(query.value().replaceAll("\\s+", " ").toLowerCase())
            .contains("upper(trim(p.currencycode)) = upper(trim(:currencycode))");
    }

    @Test
    void createForcesDraftAndClearsMaliciousMapperStateBeforeFlushAndAudit() {
        MaterialMarketPriceCreateRequest request = validCreateRequest();
        MaterialMarketPrice entity = price("APPROVED", false);
        entity.setApprovedBy("payload-user");
        entity.setApprovedAt(LocalDateTime.of(2026, 7, 1, 10, 30));
        when(mapper.toEntity(request)).thenReturn(entity);
        when(repository.saveAndFlush(entity)).thenReturn(entity);

        var response = service.create(request);

        assertThat(response.getStatus()).isEqualTo("DRAFT");
        assertThat(response.getApprovedBy()).isNull();
        assertThat(response.getApprovedAt()).isNull();
        InOrder order = inOrder(repository, auditService);
        order.verify(repository).saveAndFlush(entity);
        order.verify(auditService).log("MaterialMarketPrice", entity.getId(), "CREATE", entity.getCode(), null, entity);
    }

    @Test
    void realMapperUpdatesBusinessFieldsButPreservesWorkflowEvidence() {
        MaterialMarketPrice entity = price("APPROVED", false);
        LocalDateTime approvedAt = LocalDateTime.of(2026, 7, 1, 10, 30);
        entity.setApprovedBy("finance.controller");
        entity.setApprovedAt(approvedAt);
        MaterialMarketPriceUpdateRequest request = new MaterialMarketPriceUpdateRequest();
        request.setName("updated business name");
        request.setMaterialId(entity.getMaterialId());
        request.setMarketUnitPrice(BigDecimal.TEN);

        realMapper.updateEntity(entity, request);

        assertThat(entity.getName()).isEqualTo("updated business name");
        assertThat(entity.getStatus()).isEqualTo("APPROVED");
        assertThat(entity.getApprovedBy()).isEqualTo("finance.controller");
        assertThat(entity.getApprovedAt()).isEqualTo(approvedAt);
    }

    @Test
    void approveUsesAuthenticatedActorAndFlushesBeforeAudit() {
        MaterialMarketPrice entity = price("DRAFT", false);
        entity.setApprovedBy("spoofed-user");
        entity.setApprovedAt(LocalDateTime.of(2025, 1, 1, 0, 0));
        when(repository.findByIdAndIsDeletedFalse(entity.getId())).thenReturn(Optional.of(entity));
        when(repository.existsApprovedForScopeAndEffectiveDate(
            TENANT, companyId, entity.getMaterialId(), entity.getWarehouseId(),
            entity.getCurrencyCode(), entity.getEffectiveDate(), entity.getId()
        )).thenReturn(false);
        when(repository.saveAndFlush(entity)).thenReturn(entity);
        LocalDateTime before = LocalDateTime.now();

        var response = service.approve(entity.getId());

        assertThat(response.getStatus()).isEqualTo("APPROVED");
        assertThat(response.getApprovedBy()).isEqualTo("finance.controller");
        assertThat(response.getApprovedAt()).isAfterOrEqualTo(before);
        verify(repository).existsApprovedForScopeAndEffectiveDate(
            TENANT, companyId, entity.getMaterialId(), entity.getWarehouseId(),
            "UZS", entity.getEffectiveDate(), entity.getId()
        );
        InOrder order = inOrder(repository, auditService);
        order.verify(repository).saveAndFlush(entity);
        order.verify(auditService).log("MaterialMarketPrice", entity.getId(), "APPROVE", entity.getCode(), null, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "REJECTED"})
    void approveRejectsEveryNonDraftStatusBeforeLookupOrMutation(String status) {
        MaterialMarketPrice entity = price(status, false);
        when(repository.findByIdAndIsDeletedFalse(entity.getId())).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.approve(entity.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Only DRAFT market prices can be approved");

        assertThat(entity.getStatus()).isEqualTo(status);
        assertThat(entity.getApprovedBy()).isNull();
        assertThat(entity.getApprovedAt()).isNull();
        verify(repository, never()).existsApprovedForScopeAndEffectiveDate(any(), any(), any(), any(), any(), any(), any());
        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void approveRejectsUnauthenticatedActorBeforeMutation() {
        SecurityContextHolder.clearContext();
        MaterialMarketPrice entity = price("DRAFT", false);
        when(repository.findByIdAndIsDeletedFalse(entity.getId())).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.approve(entity.getId()))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessage("Authenticated user is required to approve a market price");

        assertThat(entity.getStatus()).isEqualTo("DRAFT");
        assertThat(entity.getApprovedBy()).isNull();
        assertThat(entity.getApprovedAt()).isNull();
        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void approveRejectsDuplicateExactEffectiveScopeWithoutMutationOrAudit() {
        MaterialMarketPrice entity = price("DRAFT", false);
        entity.setWarehouseId(null);
        when(repository.findByIdAndIsDeletedFalse(entity.getId())).thenReturn(Optional.of(entity));
        when(repository.existsApprovedForScopeAndEffectiveDate(
            TENANT, companyId, entity.getMaterialId(), null, "UZS", entity.getEffectiveDate(), entity.getId()
        )).thenReturn(true);

        assertThatThrownBy(() -> service.approve(entity.getId()))
            .isInstanceOf(ConflictException.class)
            .hasMessage(CONFLICT_MESSAGE);

        assertThat(entity.getStatus()).isEqualTo("DRAFT");
        assertThat(entity.getApprovedBy()).isNull();
        assertThat(entity.getApprovedAt()).isNull();
        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void lowercaseApprovedPriceCannotBeUpdatedBeforeMapper() {
        MaterialMarketPrice entity = price("approved", false);
        when(repository.findByIdAndIsDeletedFalse(entity.getId())).thenReturn(Optional.of(entity));

        assertImmutable(() -> service.update(entity.getId(), new MaterialMarketPriceUpdateRequest()));

        verify(mapper, never()).updateEntity(any(), any());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void lowercaseApprovedPriceCannotBeDeleted() {
        MaterialMarketPrice entity = price("approved", false);
        when(repository.findByIdAndIsDeletedFalse(entity.getId())).thenReturn(Optional.of(entity));

        assertImmutable(() -> service.softDelete(entity.getId()));

        assertThat(entity.isDeleted()).isFalse();
        verify(repository, never()).saveAndFlush(any());
        verify(auditService).snapshot(entity);
    }

    @Test
    void lowercaseApprovedPriceCannotBeRestoredAfterScopeConcealment() {
        MaterialMarketPrice entity = price("approved", true);
        when(repository.findAnyById(entity.getId())).thenReturn(Optional.of(entity));

        assertImmutable(() -> service.restore(entity.getId()));

        assertThat(entity.isDeleted()).isTrue();
        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void namedApprovedScopeConstraintBecomesDomainConflictBeforeAudit() {
        MaterialMarketPriceCreateRequest request = validCreateRequest();
        MaterialMarketPrice entity = price("DRAFT", false);
        when(mapper.toEntity(request)).thenReturn(entity);
        when(repository.saveAndFlush(entity)).thenThrow(new DataIntegrityViolationException(
            "duplicate key violates unique index uk_material_market_prices_approved_scope"
        ));

        assertThatThrownBy(() -> service.create(request))
            .isInstanceOf(ConflictException.class)
            .hasMessage(CONFLICT_MESSAGE);

        verifyNoInteractions(auditService);
    }

    @Test
    void unrelatedIntegrityFailureIsRethrownUnchangedAndNotAudited() {
        MaterialMarketPriceCreateRequest request = validCreateRequest();
        MaterialMarketPrice entity = price("DRAFT", false);
        DataIntegrityViolationException failure = new DataIntegrityViolationException("unrelated foreign key");
        when(mapper.toEntity(request)).thenReturn(entity);
        when(repository.saveAndFlush(entity)).thenThrow(failure);

        assertThatThrownBy(() -> service.create(request)).isSameAs(failure);

        verifyNoInteractions(auditService);
    }

    private void assertImmutable(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
            .isInstanceOf(BusinessException.class)
            .hasMessage("APPROVED market price is immutable; create a new effective-dated draft");
    }

    private void assertNoWorkflowProperties(Class<?> requestType) {
        List<String> forbidden = List.of(
            "getStatus", "setStatus", "getApprovedBy", "setApprovedBy", "getApprovedAt", "setApprovedAt"
        );
        assertThat(Arrays.stream(requestType.getMethods()).map(method -> method.getName()))
            .doesNotContainAnyElementsOf(forbidden);
    }

    private MaterialMarketPriceCreateRequest validCreateRequest() {
        MaterialMarketPriceCreateRequest request = new MaterialMarketPriceCreateRequest();
        request.setCode("MKT-TEST");
        request.setName("Market price");
        request.setMaterialId(UUID.randomUUID());
        request.setMarketUnitPrice(BigDecimal.TEN);
        request.setCurrencyCode("UZS");
        request.setEffectiveDate(LocalDate.of(2026, 7, 15));
        return request;
    }

    private MaterialMarketPrice price(String status, boolean deleted) {
        MaterialMarketPrice entity = new MaterialMarketPrice();
        entity.setId(UUID.randomUUID());
        entity.setTenantId(TENANT);
        entity.setCompanyId(companyId);
        entity.setCode("MKT-" + UUID.randomUUID().toString().substring(0, 8));
        entity.setName("Market price");
        entity.setStatus(status);
        entity.setMaterialId(UUID.randomUUID());
        entity.setWarehouseId(UUID.randomUUID());
        entity.setMarketUnitPrice(BigDecimal.TEN);
        entity.setNetRealizableUnitPrice(BigDecimal.ZERO);
        entity.setCurrencyCode("UZS");
        entity.setEffectiveDate(LocalDate.of(2026, 7, 15));
        if (deleted) {
            entity.softDelete();
        }
        return entity;
    }

    private void authenticate(String username) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                username,
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_FINANCE_CONTROLLER"))
            )
        );
    }
}
