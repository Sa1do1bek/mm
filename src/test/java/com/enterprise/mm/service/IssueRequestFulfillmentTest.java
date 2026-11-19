package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.enums.SerialTrackingPolicy;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.mapper.IssueRequestMapper;
import com.enterprise.mm.payload.*;
import com.enterprise.mm.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

class IssueRequestFulfillmentTest {
    private final IssueRequestRepository requests = mock(IssueRequestRepository.class);
    private final IssueRequestLineRepository lines = mock(IssueRequestLineRepository.class);
    private final MaterialRepository materials = mock(MaterialRepository.class);
    private final StockBalanceRepository balances = mock(StockBalanceRepository.class);
    private final StockMovementDocumentService documents = mock(StockMovementDocumentService.class);
    private final MaterialReservationService reservations = mock(MaterialReservationService.class);
    private final RoleDataScopeService scope = mock(RoleDataScopeService.class);
    private final IdempotencyRecordRepository idempotency = mock(IdempotencyRecordRepository.class);
    private final IssueRequestService service = new IssueRequestService(
        requests, lines, new IssueRequestMapper(), mock(WarehouseRepository.class), mock(UserRepository.class),
        materials, reservations, documents, balances,
        new IdempotencyService(idempotency, new ObjectMapper().findAndRegisterModules()),
        mock(AuditService.class), mock(AuditSnapshotSerializer.class), scope,
        mock(MaterialUnitEligibilityService.class), mock(DemandPlanItemRepository.class));
    private IssueRequest request;
    private IssueRequestLine first;
    private IssueRequestLine second;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("issue-fulfillment-test");
        CompanyContext.setCompany(UUID.randomUUID());
        request = new IssueRequest();
        request.setId(UUID.randomUUID());
        request.setTenantId(TenantContext.currentTenant());
        request.setCompanyId(CompanyContext.currentCompany());
        request.setWarehouseId(UUID.randomUUID());
        request.setVersion(4L);
        request.setCode("IR-0042");
        request.setPurpose("Pump repair");
        request.setStatus("APPROVED");
        first = line("10");
        second = line("8");
        when(scope.canAccessWarehouse(request.getWarehouseId())).thenReturn(true);
        when(requests.findByIdAndIsDeletedFalse(request.getId())).thenReturn(Optional.of(request));
        when(requests.lockExecution(request.getId(), request.getTenantId(), request.getCompanyId())).thenReturn(Optional.of(request));
        when(lines.findByTenantIdAndIssueRequestIdAndIsDeletedFalseOrderByCreatedAtAsc(request.getTenantId(), request.getId()))
            .thenReturn(List.of(first, second));
        when(requests.save(any())).thenAnswer(call -> {
            request.setVersion(request.getVersion() + 1);
            return request;
        });
        Map<String, IdempotencyRecord> saved = new HashMap<>();
        when(idempotency.findByTenantIdAndCompanyIdAndOperationAndResourceIdAndIdempotencyKeyAndIsDeletedFalse(
            anyString(), any(), anyString(), any(), anyString())).thenAnswer(call -> Optional.ofNullable(saved.get(call.getArgument(4))));
        when(idempotency.save(any())).thenAnswer(call -> {
            IdempotencyRecord record = call.getArgument(0);
            saved.put(record.getIdempotencyKey(), record);
            return record;
        });
    }

    @AfterEach
    void tearDown() { TenantContext.clear(); CompanyContext.clear(); }

    @Test
    void selectedPartialIssuesHaveSeparateVouchersAndLeaveUnselectedLinesUntouched() {
        prepareStockAndPosting();
        var result = service.issue(request.getId(), issue("2.5000001"), "first-issue");
        assertThat(result.getStatus()).isEqualTo("PARTIALLY_ISSUED");
        assertThat(first.getIssuedQuantity()).isEqualByComparingTo("2.5000001");
        assertThat(second.getIssuedQuantity()).isZero();
        verify(lines, never()).save(second);

        service.issue(request.getId(), issue("1.25"), "second-issue");
        assertThat(first.getIssuedQuantity()).isEqualByComparingTo("3.7500001");
        ArgumentCaptor<StockMovementDocumentCreateRequest> posted = ArgumentCaptor.forClass(StockMovementDocumentCreateRequest.class);
        verify(documents, times(2)).create(posted.capture());
        assertThat(posted.getAllValues()).extracting(StockMovementDocumentCreateRequest::getCode)
            .containsExactly("IR-0042-ISS-5", "IR-0042-ISS-6");
        for (var voucher : posted.getAllValues()) {
            assertThat(voucher.getReferenceType()).isEqualTo("ISSUE_REQUEST");
            assertThat(voucher.getReferenceId()).isEqualTo(request.getId());
            assertThat(voucher.getDescription()).isEqualTo("Pump repair");
            assertThat(voucher.getItems()).hasSize(1);
            assertThat(voucher.getItems().get(0).getMaterialId()).isEqualTo(first.getMaterialId());
        }
    }

    @Test
    void retryWithOriginalVersionReturnsSavedResultWithoutPostingAgain() {
        prepareStockAndPosting();
        var payload = issue("2");
        var original = service.issue(request.getId(), payload, "lost-response");
        var replay = service.issue(request.getId(), payload, "lost-response");
        assertThat(replay.getStockMovementDocumentId()).isEqualTo(original.getStockMovementDocumentId());
        assertThat(first.getIssuedQuantity()).isEqualByComparingTo("2");
        verify(documents).create(any());
        verify(documents).postManagedIssue(any(), any(), anyString());
        payload.getLines().get(0).setIssuedQuantity(BigDecimal.ONE);
        assertThatThrownBy(() -> service.issue(request.getId(), payload, "lost-response")).isInstanceOf(ConflictException.class);
        verify(documents).create(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"empty", "null", "foreign", "duplicate", "zero", "negative", "excess", "missingQuantity", "duplicateUnit"})
    void invalidSelectionFailsBeforeAnyReservationOrStockMutation(String kind) {
        var payload = issue("2");
        var selected = payload.getLines().get(0);
        switch (kind) {
            case "empty" -> payload.setLines(List.of());
            case "null" -> payload.setLines(null);
            case "foreign" -> selected.setIssueRequestLineId(UUID.randomUUID());
            case "duplicate" -> payload.setLines(List.of(selected, selected));
            case "zero" -> selected.setIssuedQuantity(BigDecimal.ZERO);
            case "negative" -> selected.setIssuedQuantity(BigDecimal.ONE.negate());
            case "excess" -> selected.setIssuedQuantity(new BigDecimal("10.0000001"));
            case "missingQuantity" -> selected.setIssuedQuantity(null);
            case "duplicateUnit" -> { UUID unit = UUID.randomUUID(); selected.setMaterialUnitIds(List.of(unit, unit)); }
        }
        assertThatThrownBy(() -> service.issue(request.getId(), payload, "invalid")).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(documents, reservations);
        verify(lines, never()).save(any());
        assertThat(first.getIssuedQuantity()).isZero();
    }

    @Test
    void unapprovedRequestAndMissingIdempotencyKeyCannotIssue() {
        var payload = issue("1");
        assertThatThrownBy(() -> service.issue(request.getId(), payload, null))
            .isInstanceOf(BadRequestException.class).hasMessageContaining("Idempotency-Key");
        request.setStatus("DRAFT");
        assertThatThrownBy(() -> service.issue(request.getId(), payload, "draft"))
            .isInstanceOf(ConflictException.class);
        verifyNoInteractions(documents, reservations);
    }

    @Test
    void historyIsFilteredByRequestWarehouseAndMovementType() {
        var page = PageRequest.of(0, 10);
        when(documents.search(any(), eq(page))).thenReturn(Page.empty(page));
        service.issueDocuments(request.getId(), page);
        ArgumentCaptor<StockMovementDocumentSearchRequest> filter = ArgumentCaptor.forClass(StockMovementDocumentSearchRequest.class);
        verify(documents).search(filter.capture(), eq(page));
        assertThat(filter.getValue().referenceId()).isEqualTo(request.getId());
        assertThat(filter.getValue().warehouseId()).isEqualTo(request.getWarehouseId());
        assertThat(filter.getValue().movementType()).isEqualTo(MovementType.ISSUE);
        assertThat(filter.getValue().includeDeleted()).isFalse();
    }

    @Test
    void historyAndReplayRecheckWarehouseAccess() {
        when(scope.canAccessWarehouse(request.getWarehouseId())).thenReturn(false);
        assertThatThrownBy(() -> service.issueDocuments(request.getId(), PageRequest.of(0, 10)))
            .isInstanceOf(com.enterprise.mm.error.NotFoundException.class);
        assertThatThrownBy(() -> service.issue(request.getId(), issue("1"), "old-key"))
            .isInstanceOf(com.enterprise.mm.error.NotFoundException.class);
        verifyNoInteractions(documents);
        verify(idempotency, never()).findByTenantIdAndCompanyIdAndOperationAndResourceIdAndIdempotencyKeyAndIsDeletedFalse(any(), any(), any(), any(), any());
    }

    private IssueRequestLine line(String approved) {
        var line = new IssueRequestLine();
        line.setId(UUID.randomUUID());
        line.setIssueRequestId(request.getId());
        line.setMaterialId(UUID.randomUUID());
        line.setStockBatchId(UUID.randomUUID());
        line.setRequestedQuantity(new BigDecimal(approved));
        line.setApprovedQuantity(new BigDecimal(approved));
        line.setIssuedQuantity(BigDecimal.ZERO);
        return line;
    }

    private IssueRequestIssueRequest issue(String quantity) {
        var payload = new IssueRequestIssueRequest();
        payload.setWarehouseId(request.getWarehouseId());
        payload.setExpectedVersion(request.getVersion());
        var line = new IssueRequestIssueRequest.Line();
        line.setIssueRequestLineId(first.getId());
        line.setIssuedQuantity(new BigDecimal(quantity));
        line.setStockBatchId(first.getStockBatchId());
        payload.setLines(List.of(line));
        return payload;
    }

    private void prepareStockAndPosting() {
        var material = new Material();
        material.setId(first.getMaterialId());
        material.setTenantId(request.getTenantId());
        material.setCompanyId(request.getCompanyId());
        material.setMaterialType(MaterialType.TMB);
        material.setUnitOfMeasureId(UUID.randomUUID());
        material.setSerialTrackingPolicy(SerialTrackingPolicy.NONE);
        when(materials.findByIdAndIsDeletedFalse(material.getId())).thenReturn(Optional.of(material));
        var batch = new StockBatch();
        batch.setId(first.getStockBatchId());
        batch.setMaterialId(material.getId());
        batch.setWarehouseId(request.getWarehouseId());
        var balance = new StockBalance();
        balance.setMaterialId(material.getId());
        balance.setMaterial(material);
        balance.setStockBatchId(batch.getId());
        balance.setStockBatch(batch);
        balance.setWarehouseId(request.getWarehouseId());
        balance.setAvailableQuantity(BigDecimal.TEN);
        when(balances.findCompanyBalanceForUpdate(anyString(), any(), any(), any(), anyBoolean(), nullable(UUID.class),
            anyBoolean(), nullable(UUID.class), anyBoolean(), nullable(UUID.class))).thenReturn(Optional.of(balance));
        when(documents.create(any())).thenAnswer(call -> {
            var document = new StockMovementDocumentResponse();
            document.setId(UUID.randomUUID());
            document.setVersion(1L);
            return document;
        });
        when(documents.changeStatus(any(), any())).thenAnswer(call -> {
            var document = new StockMovementDocumentResponse();
            document.setId(call.getArgument(0));
            document.setVersion(2L);
            return document;
        });
        when(documents.postManagedIssue(any(), any(), anyString())).thenAnswer(call -> {
            var item = new StockMovementResponse();
            item.setId(UUID.randomUUID());
            item.setStockBatchId(batch.getId());
            item.setUnitCost(new BigDecimal("1200"));
            var document = new StockMovementDocumentResponse();
            document.setId(call.getArgument(0));
            document.setVersion(3L);
            document.setItems(List.of(item));
            return document;
        });
    }
}
