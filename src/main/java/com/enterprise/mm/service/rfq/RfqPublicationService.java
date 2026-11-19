package com.enterprise.mm.service.rfq;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.RfqPublicationDtos.ArtifactPreview;
import com.enterprise.mm.payload.RfqPublicationDtos.PublicationPreview;
import com.enterprise.mm.payload.RfqPublicationDtos.PublishRfqRequest;
import com.enterprise.mm.payload.RfqPublicationDtos.PublishRfqResponse;
import com.enterprise.mm.payload.RfqPublicationDtos.SupplierPreview;
import com.enterprise.mm.payload.RfqPublicationDtos.ValidationIssue;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.service.IdempotencyService;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RfqPublicationService {
    private final RFQRepository rfqRepository;
    private final RfqPublicationValidator validator;
    private final RfqPackageBuilder packageBuilder;
    private final RfqArtifactStagingService stagingService;
    private final RfqPublicationTransaction publicationTransaction;
    private final IdempotencyService idempotencyService;



    public PublicationPreview preview(UUID rfqId) {
        RFQ rfq = requireScoped(rfqId);
        List<ValidationIssue> issues = new ArrayList<>(validator.validate(rfq));
        List<ValidationIssue> blockingIssues = new ArrayList<>(validator.blockingIssues(rfq));
        RfqPackageSnapshot snapshot = null;
        RfqArtifactStagingService.StagedPackage staged = null;
        try {
            snapshot = packageBuilder.build(rfqId);
            staged = stagingService.stage(snapshot);
        } catch (Exception exception) {
            issues.add(RfqPublicationValidator.issue("RFQ_PACKAGE_RENDER_FAILED", "artifacts", null));
            blockingIssues.add(RfqPublicationValidator.issue("RFQ_PACKAGE_RENDER_FAILED", "artifacts", null));
        } finally {
            stagingService.cleanup(staged);
        }
        issues.addAll(blockingIssues);
        return preview(rfq, snapshot, issues.stream().distinct().toList(), blockingIssues.stream().distinct().toList());
    }

    public PublishRfqResponse publish(
        UUID rfqId,
        PublishRfqRequest request,
        String idempotencyKey
    ) {
        if (request == null || request.expectedVersion() == null) {
            throw new BusinessException("expectedVersion is required for RFQ publication");
        }
        return idempotencyService.execute(
            "RFQ_PUBLISH",
            rfqId,
            idempotencyKey,
            request,
            PublishRfqResponse.class,
            () -> publishOnce(rfqId, request.expectedVersion())
        );
    }

    private PublishRfqResponse publishOnce(UUID rfqId, long expectedVersion) {
        RFQ rfq = requireScoped(rfqId);
        long actualVersion = rfq.getVersion() == null ? 0L : rfq.getVersion();
        if (actualVersion != expectedVersion) {
            throw new BusinessException("RFQ changed; refresh the publication preview and try again");
        }
        List<ValidationIssue> blockingIssues = validator.blockingIssues(rfq);
        if (!blockingIssues.isEmpty()) {
            throw new RfqPublicationValidationException(blockingIssues);
        }
        RfqPackageSnapshot snapshot = packageBuilder.build(rfqId);
        RfqArtifactStagingService.StagedPackage staged;
        try {
            staged = stagingService.stage(snapshot);
        } catch (Exception exception) {
            throw new RfqPublicationValidationException(List.of(
                RfqPublicationValidator.issue("RFQ_PACKAGE_RENDER_FAILED", "artifacts", null)
            ));
        }
        try {
            return publicationTransaction.commit(new PublicationCandidate(
                rfqId,
                expectedVersion,
                snapshot,
                staged
            ));
        } finally {
            stagingService.cleanup(staged);
        }
    }

    private RFQ requireScoped(UUID rfqId) {
        return rfqRepository.findByIdAndIsDeletedFalse(rfqId)
            .filter(rfq -> TenantContext.currentTenant().equals(rfq.getTenantId()))
            .filter(rfq -> CompanyContext.currentCompany().equals(rfq.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("RFQ not found: " + rfqId));
    }

    private PublicationPreview preview(
        RFQ rfq,
        RfqPackageSnapshot snapshot,
        List<ValidationIssue> issues,
        List<ValidationIssue> blockingIssues
    ) {
        List<SupplierPreview> suppliers = snapshot == null ? List.of() : snapshot.suppliers().stream()
            .map(recipient -> new SupplierPreview(
                recipient.supplierId(),
                recipient.supplierName(),
                recipient.contactName(),
                recipient.email()
            ))
            .toList();
        List<ArtifactPreview> artifacts = new ArrayList<>();
        artifacts.add(new ArtifactPreview("OFFICIAL_PDF", safeCode(rfq) + ".pdf", "application/pdf"));
        artifacts.add(new ArtifactPreview(
            "QUOTATION_TEMPLATE",
            safeCode(rfq) + ".xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        ));
        if (snapshot != null) {
            snapshot.documents().forEach(document -> artifacts.add(new ArtifactPreview(
                "SUPPORTING_DOCUMENT",
                document.fileName(),
                document.contentType()
            )));
        }
        return new PublicationPreview(
            rfq.getId(),
            rfq.getVersion() == null ? 0L : rfq.getVersion(),
            rfq.getPackageLanguage(),
            snapshot == null ? 0 : snapshot.positions().size(),
            suppliers.size(),
            suppliers,
            artifacts,
            issues,
            blockingIssues.isEmpty()
        );
    }

    private String safeCode(RFQ rfq) {
        return Objects.toString(rfq.getCode(), "RFQ").replaceAll("[^A-Za-z0-9._-]", "_");
    }

    public record PublicationCandidate(
        UUID rfqId,
        long expectedVersion,
        RfqPackageSnapshot snapshot,
        RfqArtifactStagingService.StagedPackage staged
    ) {
    }
}
