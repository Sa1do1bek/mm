package com.enterprise.mm.service.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.entity.StockMovementDocument;
import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.repository.StockMovementDocumentRepository;
import com.enterprise.mm.repository.StockMovementRepository;
import com.enterprise.mm.service.AuditService;
import com.enterprise.mm.service.ManualWarehouseOperationService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class ManualWarehouseStockMovementApprovalAdapterTest {
    private final UUID documentId = UUID.randomUUID();
    private final UUID companyId = UUID.randomUUID();
    private final StockMovementRepository movements = mock(StockMovementRepository.class);
    private final StockMovementDocumentRepository documents = mock(StockMovementDocumentRepository.class);
    private final ManualWarehouseOperationService manualOperations = mock(ManualWarehouseOperationService.class);
    private final ObjectProvider<ManualWarehouseOperationService> provider = mock(ObjectProvider.class);
    private final StockMovementDocument document = new StockMovementDocument();
    private final StockMovement line = new StockMovement();
    private StockMovementApprovalAdapter adapter;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("manual-approval-test");
        document.setId(documentId);
        document.setTenantId("manual-approval-test");
        document.setCompanyId(companyId);
        document.setName("Manual receipt");
        document.setStatus("DRAFT");
        document.setReferenceType("MANUAL_WAREHOUSE");
        document.setManualOperationPayload("{}");
        document.setCreatedBy("warehouse.operator");

        line.setId(UUID.randomUUID());
        line.setTenantId("manual-approval-test");
        line.setCompanyId(companyId);
        line.setDocumentId(documentId);
        line.setName("Receipt line");
        line.setStatus("DRAFT");
        line.setMovementType(MovementType.RECEIPT);
        line.setQuantity(BigDecimal.ONE);

        when(movements.findByIdAndIsDeletedFalse(documentId)).thenReturn(Optional.empty());
        when(documents.findByIdAndIsDeletedFalse(documentId)).thenReturn(Optional.of(document));
        when(movements.findByTenantIdAndDocumentIdAndIsDeletedFalseOrderByCreatedAtAsc("manual-approval-test", documentId))
            .thenReturn(List.of(line));
        when(provider.getObject()).thenReturn(manualOperations);
        adapter = new StockMovementApprovalAdapter(movements, documents, mock(AuditService.class), provider);
    }

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void submissionCreatesPendingApprovalWithoutPostingStock() {
        adapter.validateCanSubmit(documentId);
        adapter.markSubmitted(documentId);

        assertThat(document.getStatus()).isEqualTo("PENDING");
        assertThat(line.getStatus()).isEqualTo("PENDING");
        assertThat(adapter.requesterUsername(documentId)).isEqualTo("warehouse.operator");
        verifyNoInteractions(manualOperations);
    }

    @Test
    void finalApprovalPostsTheWholeManualDocumentOnce() {
        document.setStatus("PENDING");
        line.setStatus("PENDING");

        adapter.markApproved(documentId, "Approved");

        assertThat(document.getStatus()).isEqualTo("APPROVED");
        assertThat(line.getStatus()).isEqualTo("APPROVED");
        verify(manualOperations).postApprovedDocument(documentId);
    }

    @Test
    void rejectionReturnsDocumentAndLeavesStockUntouched() {
        document.setStatus("PENDING");
        line.setStatus("PENDING");

        adapter.markRejected(documentId, "Correct the evidence");

        assertThat(document.getStatus()).isEqualTo("RETURNED");
        assertThat(line.getStatus()).isEqualTo("RETURNED");
        verifyNoInteractions(manualOperations);
    }
}
