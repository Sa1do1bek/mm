package com.enterprise.mm.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.entity.ManualUnmatchedIssue;
import com.enterprise.mm.entity.ManualUnmatchedIssueLine;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.ManualUnmatchedIssueDtos;
import com.enterprise.mm.repository.ManualUnmatchedIssueLineRepository;
import com.enterprise.mm.repository.ManualUnmatchedIssueRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ManualUnmatchedIssueServiceTest {
    private final RoleDataScopeService scope = mock(RoleDataScopeService.class);
    private final WarehouseRepository warehouses = mock(WarehouseRepository.class);
    private final MaterialCatalogGuard catalog = mock(MaterialCatalogGuard.class);
    private final ManualUnmatchedIssueRepository issues = mock(ManualUnmatchedIssueRepository.class);
    private final ManualUnmatchedIssueLineRepository lines = mock(ManualUnmatchedIssueLineRepository.class);
    private final AttachmentService attachments = mock(AttachmentService.class);
    private final IdempotencyService idempotency = mock(IdempotencyService.class);
    private final AuditService audit = mock(AuditService.class);
    private final ManualUnmatchedIssueService service = new ManualUnmatchedIssueService(
        scope, warehouses, catalog, issues, lines, attachments, idempotency, audit);
    private final UUID contextId = UUID.randomUUID(), materialId = UUID.randomUUID();

    @BeforeEach void setup() {
        var warehouse = new Warehouse();
        warehouse.setCompanyId(CompanyContext.currentCompany());
        warehouse.setStatus("ACTIVE");
        when(warehouses.findByTenantIdAndIdAndIsDeletedFalse(anyString(), eq(contextId))).thenReturn(Optional.of(warehouse));
        var unit = new UnitOfMeasure(); unit.setCode("EA");
        var material = new Material(); material.setId(materialId); material.setCode("MAT-1");
        material.setName("Valve"); material.setUnitOfMeasureId(UUID.randomUUID()); material.setUnitOfMeasure(unit);
        when(catalog.requireActiveTmbMaterial(materialId, "materialId")).thenReturn(material);
        when(idempotency.executeRequired(anyString(), eq(contextId), anyString(), any(), eq(ManualUnmatchedIssueDtos.Response.class), any()))
            .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(5)).get());
        when(issues.saveAndFlush(any())).thenAnswer(invocation -> {
            ManualUnmatchedIssue issue = invocation.getArgument(0);
            issue.setId(UUID.randomUUID()); issue.setCreatedAt(LocalDateTime.now());
            return issue;
        });
        when(lines.save(any())).thenAnswer(invocation -> {
            ManualUnmatchedIssueLine line = invocation.getArgument(0);
            line.setId(UUID.randomUUID()); return line;
        });
    }

    @Test void postsAnOperationalRecordWithoutClaimingWarehouseStock() {
        var request = new ManualUnmatchedIssueDtos.Create(contextId, LocalDate.now(), "Maintenance", "Note 7",
            List.of(new ManualUnmatchedIssueDtos.LineInput(materialId, new BigDecimal("3"))));
        var result = service.record(request, null, "key-1");
        assertEquals("POSTED_UNMATCHED", result.status());
        assertNull(result.sourceWarehouseId());
        assertEquals("3", result.lines().getFirst().quantity().toPlainString());
        assertEquals("MAT-1", result.lines().getFirst().materialCode());
        verify(scope).assertWarehouseAccess(contextId);
        verify(issues).saveAndFlush(argThat(issue -> issue.getSourceWarehouseId() == null));
        verify(attachments, never()).uploadForCommand(anyString(), any(), anyString(), any());
    }

    @Test void refusesDuplicateMaterialLines() {
        var line = new ManualUnmatchedIssueDtos.LineInput(materialId, BigDecimal.ONE);
        var request = new ManualUnmatchedIssueDtos.Create(contextId, LocalDate.now(), "Maintenance", null,
            List.of(line, line));
        assertThrows(BusinessException.class, () -> service.record(request, null, "key-2"));
        verify(issues, never()).saveAndFlush(any());
    }
}
