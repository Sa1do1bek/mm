package com.enterprise.mm.warehouse.history;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.TransferCreateRequest;
import com.enterprise.mm.repository.TransferDocumentRepository;
import com.enterprise.mm.service.IdempotencyService;
import com.enterprise.mm.service.TransferDocumentService;
import com.enterprise.mm.service.transfer.TransferActionPolicy;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IssueSourcePreparationService {
    private final NamedParameterJdbcTemplate jdbc;
    private final IssueSourceDocumentService sources;
    private final TransferDocumentService transfers;
    private final TransferDocumentRepository transferRepository;
    private final TransferActionPolicy policy;
    private final IdempotencyService idempotency;

    public record Request(@NotNull UUID warehouseId, @NotBlank @Pattern(regexp="[a-f0-9]{64}") String fingerprint,
        @AssertTrue boolean confirmedUnfulfilled, @NotBlank @Size(max=2000) String reviewNote) {}
    public record Prepared(UUID documentId, String code, String status) {}

    @Transactional
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_HEAD')")
    public Prepared prepare(UUID id, Request request, String key) {
        if (request == null || request.warehouseId() == null || !request.confirmedUnfulfilled()
            || request.reviewNote() == null || request.reviewNote().isBlank() || request.reviewNote().length() > 2000)
            throw new BadRequestException("Confirm the current unfulfilled request and record the review result");
        policy.requireCreate(request.warehouseId());
        var p = new MapSqlParameterSource("tenant", TenantContext.currentTenant()).addValue("company", CompanyContext.currentCompany())
            .addValue("id", id).addValue("warehouse", request.warehouseId());
        // Serializes retries and competing preparers before reading/creating the unique source link.
        var locked = jdbc.queryForList("""
            select id from warehouse_history_records where id=:id and tenant_id=:tenant and company_id=:company
                and kind='DOCUMENT' and source_table='_document4470' and warehouse_id=:warehouse for update
            """, p);
        if (locked.isEmpty()) throw new NotFoundException("1C request not found in the issuing warehouse");
        return idempotency.executeRequired("1C_ISSUE_SOURCE_PREPARE", id, key, request, Prepared.class, () -> {
            var detail = sources.detail(id, request.warehouseId());
            if (!detail.fingerprint().equals(request.fingerprint())) throw new ConflictException("1C evidence changed; reopen and review the request");
            if (!detail.canPrepare()) throw new ConflictException("1C request cannot be prepared: " + detail.blockers());
            var draft = new TransferCreateRequest();
            draft.setName("1C · " + detail.document().documentNumber());
            draft.setDescription("1C source: " + detail.document().documentNumber() + " (" + id + "). Review: " + request.reviewNote().trim());
            draft.setSourceWarehouseId(request.warehouseId());
            draft.setDestinationWarehouseId(detail.lines().getFirst().destinationWarehouseId());
            draft.setPlannedDate(LocalDate.now());
            draft.setLines(detail.lines().stream().map(line -> {
                var input = new TransferCreateRequest.Line();
                input.setMaterialId(line.materialId()); input.setPlannedQuantity(new BigDecimal(line.quantity())); return input;
            }).toList());
            var created = transfers.create(draft);
            transferRepository.flush(); // The JDBC link FK must see the JPA draft in this same transaction.
            p.addValue("transfer", created.getId()).addValue("fingerprint", detail.fingerprint())
                .addValue("reviewer", SecurityContextHolder.getContext().getAuthentication().getName()).addValue("note", request.reviewNote().trim());
            jdbc.update("""
                insert into warehouse_issue_source_links(source_document_id,tenant_id,company_id,transfer_document_id,source_fingerprint,reviewed_by,review_note)
                values(:id,:tenant,:company,:transfer,:fingerprint,:reviewer,:note)
                """, p);
            return new Prepared(created.getId(), created.getCode(), created.getStatus());
        });
    }
}
