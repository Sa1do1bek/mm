package com.enterprise.mm.service.rfq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.RfqPublicationDtos.PublishRfqRequest;
import com.enterprise.mm.payload.RfqPublicationDtos.PublishRfqResponse;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.service.IdempotencyService;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RfqPublicationServiceTest {
    @Mock private RFQRepository rfqRepository;
    @Mock private RfqPublicationValidator validator;
    @Mock private RfqPackageBuilder packageBuilder;
    @Mock private RfqArtifactStagingService stagingService;
    @Mock private RfqPublicationTransaction publicationTransaction;
    @Mock private IdempotencyService idempotencyService;

    private RfqPublicationService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        service = new RfqPublicationService(
            rfqRepository,
            validator,
            packageBuilder,
            stagingService,
            publicationTransaction,
            idempotencyService
        );
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void publishStagesCommitsAndAlwaysCleansPrivateFiles() {
        UUID rfqId = UUID.randomUUID();
        RFQ rfq = draft(rfqId, 7L);
        RfqPackageSnapshot snapshot = snapshot(rfqId);
        RfqArtifactStagingService.StagedPackage staged = staged();
        PublishRfqRequest request = new PublishRfqRequest(7L);
        PublishRfqResponse response = new PublishRfqResponse(
            rfqId,
            UUID.randomUUID(),
            1,
            1,
            "OPEN"
        );
        when(rfqRepository.findByIdAndIsDeletedFalse(rfqId)).thenReturn(Optional.of(rfq));
        when(validator.blockingIssues(rfq)).thenReturn(List.of());
        when(packageBuilder.build(rfqId)).thenReturn(snapshot);
        when(stagingService.stage(snapshot)).thenReturn(staged);
        when(publicationTransaction.commit(any())).thenReturn(response);
        executeIdempotently(request, rfqId, "publish-1");

        PublishRfqResponse actual = service.publish(rfqId, request, "publish-1");

        assertThat(actual).isEqualTo(response);
        verify(publicationTransaction).commit(any());
        verify(stagingService).cleanup(staged);
    }

    @Test
    void commitFailureLeavesCleanupResponsibilityWithPublicationService() {
        UUID rfqId = UUID.randomUUID();
        RFQ rfq = draft(rfqId, 2L);
        RfqPackageSnapshot snapshot = snapshot(rfqId);
        RfqArtifactStagingService.StagedPackage staged = staged();
        PublishRfqRequest request = new PublishRfqRequest(2L);
        when(rfqRepository.findByIdAndIsDeletedFalse(rfqId)).thenReturn(Optional.of(rfq));
        when(validator.blockingIssues(rfq)).thenReturn(List.of());
        when(packageBuilder.build(rfqId)).thenReturn(snapshot);
        when(stagingService.stage(snapshot)).thenReturn(staged);
        when(publicationTransaction.commit(any())).thenThrow(new BusinessException("database rejected publication"));
        executeIdempotently(request, rfqId, null);

        assertThatThrownBy(() -> service.publish(rfqId, request, null))
            .isInstanceOf(BusinessException.class)
            .hasMessage("database rejected publication");
        verify(stagingService).cleanup(staged);
    }

    @Test
    void validationIssuesPreventPackageStaging() {
        UUID rfqId = UUID.randomUUID();
        RFQ rfq = draft(rfqId, 0L);
        PublishRfqRequest request = new PublishRfqRequest(0L);
        when(rfqRepository.findByIdAndIsDeletedFalse(rfqId)).thenReturn(Optional.of(rfq));
        when(validator.blockingIssues(rfq)).thenReturn(List.of(
            RfqPublicationValidator.issue("RFQ_SUPPLIERS_REQUIRED", "suppliers", null)
        ));
        executeIdempotently(request, rfqId, "invalid");

        assertThatThrownBy(() -> service.publish(rfqId, request, "invalid"))
            .isInstanceOf(RfqPublicationValidationException.class);
        verify(packageBuilder, never()).build(any());
        verify(stagingService, never()).stage(any());
    }

    @Test
    void staleExpectedVersionFailsBeforeValidationOrRendering() {
        UUID rfqId = UUID.randomUUID();
        RFQ rfq = draft(rfqId, 5L);
        PublishRfqRequest request = new PublishRfqRequest(4L);
        when(rfqRepository.findByIdAndIsDeletedFalse(rfqId)).thenReturn(Optional.of(rfq));
        executeIdempotently(request, rfqId, "stale");

        assertThatThrownBy(() -> service.publish(rfqId, request, "stale"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("refresh");
        verify(validator, never()).validate(any());
        verify(packageBuilder, never()).build(any());
    }

    @Test
    void packageRenderFailureReturnsStableValidationIssueAndKeepsDraft() {
        UUID rfqId = UUID.randomUUID();
        RFQ rfq = draft(rfqId, 1L);
        RfqPackageSnapshot snapshot = snapshot(rfqId);
        PublishRfqRequest request = new PublishRfqRequest(1L);
        when(rfqRepository.findByIdAndIsDeletedFalse(rfqId)).thenReturn(Optional.of(rfq));
        when(validator.blockingIssues(rfq)).thenReturn(List.of());
        when(packageBuilder.build(rfqId)).thenReturn(snapshot);
        when(stagingService.stage(snapshot)).thenThrow(new BusinessException("PDF renderer failed"));
        executeIdempotently(request, rfqId, "render-failed");

        assertThatThrownBy(() -> service.publish(rfqId, request, "render-failed"))
            .isInstanceOfSatisfying(RfqPublicationValidationException.class, exception ->
                assertThat(exception.getIssues()).extracting(issue -> issue.code())
                    .containsExactly("RFQ_PACKAGE_RENDER_FAILED")
            );
        assertThat(rfq.getStatus()).isEqualTo("DRAFT");
        verify(publicationTransaction, never()).commit(any());
    }

    private void executeIdempotently(PublishRfqRequest request, UUID rfqId, String key) {
        when(idempotencyService.execute(
            eq("RFQ_PUBLISH"),
            eq(rfqId),
            key == null ? isNull() : eq(key),
            eq(request),
            eq(PublishRfqResponse.class),
            any()
        )).thenAnswer(invocation -> {
            Supplier<PublishRfqResponse> action = invocation.getArgument(5);
            return action.get();
        });
    }

    private RFQ draft(UUID id, long version) {
        RFQ rfq = new RFQ();
        rfq.setId(id);
        rfq.setVersion(version);
        rfq.setTenantId("default");
        rfq.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        rfq.setCode("RFQ-PUBLISH-1");
        rfq.setName("Publish test");
        rfq.setStatus("DRAFT");
        rfq.setDueDate(LocalDate.now().plusDays(5));
        return rfq;
    }

    private RfqPackageSnapshot snapshot(UUID rfqId) {
        return new RfqPackageSnapshot(
            rfqId,
            "RFQ-PUBLISH-1",
            "Publish test",
            "Description",
            "ru",
            LocalDate.now().plusDays(5),
            null,
            "Reply by email or portal",
            List.of(),
            List.of(),
            List.of(),
            List.of()
        );
    }

    private RfqArtifactStagingService.StagedPackage staged() {
        Path directory = Path.of("target", "rfq-stage-test");
        return new RfqArtifactStagingService.StagedPackage(
            directory,
            directory.resolve("request.pdf"),
            directory.resolve("quotation.xlsx"),
            "hash",
            "RFQ-PUBLISH-1"
        );
    }
}
