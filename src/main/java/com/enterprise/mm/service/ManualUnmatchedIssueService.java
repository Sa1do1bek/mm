package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ManualUnmatchedIssue;
import com.enterprise.mm.entity.ManualUnmatchedIssueLine;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.ManualUnmatchedIssueDtos;
import com.enterprise.mm.repository.ManualUnmatchedIssueLineRepository;
import com.enterprise.mm.repository.ManualUnmatchedIssueRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import java.io.IOException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** Records a completed handover without claiming an unknown warehouse stock position. */
@Service
@RequiredArgsConstructor
public class ManualUnmatchedIssueService {
    public static final String STATUS = "POSTED_UNMATCHED";
    public static final String ATTACHMENT_TYPE = "MANUAL_UNMATCHED_ISSUE";

    private final RoleDataScopeService scope;
    private final WarehouseRepository warehouses;
    private final MaterialCatalogGuard catalog;
    private final ManualUnmatchedIssueRepository issues;
    private final ManualUnmatchedIssueLineRepository lines;
    private final AttachmentService attachments;
    private final IdempotencyService idempotency;
    private final AuditService audit;

    private void requireEntryContext(UUID warehouseId) {
        scope.assertWarehouseAccess(warehouseId);
        warehouses.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), warehouseId)
            .filter(w -> CompanyContext.currentCompany().equals(w.getCompanyId()) && "ACTIVE".equals(w.getStatus()))
            .orElseThrow(() -> new NotFoundException("Active entry context not found"));
    }

    @Transactional
    public ManualUnmatchedIssueDtos.Response record(ManualUnmatchedIssueDtos.Create request,
        List<MultipartFile> proofFiles, String key) {
        requireEntryContext(request.entryContextWarehouseId());
        if (request.issueDate() == null || request.issueDate().isAfter(LocalDate.now()))
            throw new BusinessException("Enter the date of the completed issue");
        if (request.issuedTo() == null || request.issuedTo().isBlank())
            throw new BusinessException("Enter who received the material");
        if (request.lines() == null || request.lines().isEmpty() || request.lines().size() > 100)
            throw new BusinessException("Enter between one and one hundred material lines");
        var files = proofFiles == null ? List.<MultipartFile>of() : proofFiles;
        if (files.size() > 10 || files.stream().anyMatch(file -> file == null || file.isEmpty()))
            throw new BusinessException("Attach up to ten non-empty proof files");
        var fingerprint = new Submission(request, files.stream().map(this::fileIdentity).toList());
        return idempotency.executeRequired("MANUAL_UNMATCHED_ISSUE", request.entryContextWarehouseId(), key,
            fingerprint, ManualUnmatchedIssueDtos.Response.class, () -> save(request, files));
    }

    private ManualUnmatchedIssueDtos.Response save(ManualUnmatchedIssueDtos.Create request, List<MultipartFile> files) {
        var materialIds = new HashSet<UUID>();
        var snapshots = new ArrayList<LineSnapshot>();
        for (var input : request.lines()) {
            if (input == null || input.materialId() == null || input.quantity() == null || input.quantity().signum() <= 0)
                throw new BusinessException("Select a material and enter a positive quantity on every line");
            if (!materialIds.add(input.materialId())) throw new BusinessException("Combine duplicate material lines");
            var material = catalog.requireActiveTmbMaterial(input.materialId(), "materialId");
            StockUomPolicy.quantity(material, input.quantity(), false);
            snapshots.add(new LineSnapshot(material.getId(), material.getCode(), material.getName(),
                material.getUnitOfMeasure().getCode(), input.quantity()));
        }

        var issue = new ManualUnmatchedIssue();
        issue.setCode("MUI-" + UUID.randomUUID());
        issue.setName("Manual issue · " + request.issuedTo().trim());
        issue.setStatus(STATUS);
        issue.setIssueDate(request.issueDate());
        issue.setIssuedTo(request.issuedTo().trim());
        issue.setReference(request.reference() == null ? null : request.reference().trim());
        issue.setEntryContextWarehouseId(request.entryContextWarehouseId());
        // The selected workspace context is not a claim about physical stock origin.
        issue.setSourceWarehouseId(null);
        issue = issues.saveAndFlush(issue);

        var savedLines = new ArrayList<ManualUnmatchedIssueLine>();
        for (int index = 0; index < snapshots.size(); index++) {
            var source = snapshots.get(index);
            var line = new ManualUnmatchedIssueLine();
            line.setIssueId(issue.getId());
            line.setLineNumber(index + 1);
            line.setMaterialId(source.materialId());
            line.setMaterialCodeSnapshot(source.code());
            line.setMaterialNameSnapshot(source.name());
            line.setUnitCodeSnapshot(source.unitCode());
            line.setQuantity(source.quantity());
            line.setName(source.name());
            line.setStatus(STATUS);
            savedLines.add(lines.save(line));
        }
        for (var file : files) attachments.uploadForCommand(ATTACHMENT_TYPE, issue.getId(), "DOCUMENT", file);
        var response = response(issue, savedLines);
        audit.log("ManualUnmatchedIssue", issue.getId(), "POST", issue.getCode(), null, response);
        return response;
    }

    @Transactional(readOnly = true)
    public List<ManualUnmatchedIssueDtos.Response> list(UUID entryContextWarehouseId) {
        requireEntryContext(entryContextWarehouseId);
        return issues.findTop50ByTenantIdAndCompanyIdAndEntryContextWarehouseIdAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), entryContextWarehouseId)
            .stream().map(issue -> response(issue, lines.findByIssueIdAndIsDeletedFalseOrderByLineNumberAsc(issue.getId()))).toList();
    }

    @Transactional(readOnly = true)
    public ManualUnmatchedIssueDtos.Response get(UUID id) {
        var issue = issues.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), id)
            .orElseThrow(() -> new NotFoundException("Manual issue not found"));
        requireEntryContext(issue.getEntryContextWarehouseId());
        return response(issue, lines.findByIssueIdAndIsDeletedFalseOrderByLineNumberAsc(id));
    }

    private ManualUnmatchedIssueDtos.Response response(ManualUnmatchedIssue issue, List<ManualUnmatchedIssueLine> issueLines) {
        return new ManualUnmatchedIssueDtos.Response(issue.getId(), issue.getCode(), issue.getStatus(), issue.getIssueDate(),
            issue.getIssuedTo(), issue.getReference(), issue.getEntryContextWarehouseId(), issue.getSourceWarehouseId(),
            issue.getCreatedAt(), issue.getCreatedBy(), issueLines.stream().map(line -> new ManualUnmatchedIssueDtos.Line(
                line.getId(), line.getMaterialId(), line.getMaterialCodeSnapshot(), line.getMaterialNameSnapshot(),
                line.getUnitCodeSnapshot(), line.getQuantity())).toList());
    }

    private String fileIdentity(MultipartFile file) {
        try {
            return file.getOriginalFilename() + ":" + file.getSize() + ":" +
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.getBytes()));
        } catch (IOException | java.security.NoSuchAlgorithmException exception) {
            throw new BusinessException("Cannot read issue evidence");
        }
    }

    private record Submission(ManualUnmatchedIssueDtos.Create request, List<String> files) {}
    private record LineSnapshot(UUID materialId, String code, String name, String unitCode, BigDecimal quantity) {}
}
