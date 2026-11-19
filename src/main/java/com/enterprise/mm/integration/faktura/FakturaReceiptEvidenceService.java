package com.enterprise.mm.integration.faktura;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.BusinessException;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FakturaReceiptEvidenceService {
    private final FakturaDocumentQuery documents;
    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;

    public FakturaReceiptEvidenceService(FakturaDocumentQuery documents, JdbcTemplate jdbc, EntityManager entityManager) {
        this.documents = documents;
        this.jdbc = jdbc;
        this.entityManager = entityManager;
    }

    /** Call inside the receipt transaction after its header has been saved. */
    @Transactional
    public void attach(UUID contractId, ReceiptKind kind, UUID receiptId, LocalDate receiptDate,
                       UUID powerId, UUID invoiceId, String missingReason) {
        if (contractId == null) {
            if (powerId != null || invoiceId != null) throw new BusinessException("Faktura documents require a contract");
            return;
        }
        // JDBC evidence rows reference JPA receipt headers created in the same transaction.
        entityManager.flush();
        FakturaDocumentQuery.Candidates candidates = documents.candidates(contractId, receiptDate);
        attachOne(contractId, kind, receiptId, "POWER_OF_ATTORNEY", candidates.powersOfAttorney(),
            powerId, candidates.recommendedPowerOfAttorneyId(), missingReason, receiptDate == null ? LocalDate.now() : receiptDate);
        attachOne(contractId, kind, receiptId, "INVOICE", candidates.invoices(),
            invoiceId, candidates.recommendedInvoiceId(), missingReason, receiptDate == null ? LocalDate.now() : receiptDate);
    }

    private void attachOne(UUID contractId, ReceiptKind kind, UUID receiptId, String role,
                           List<FakturaDocumentQuery.Document> choices, UUID requestedId, UUID recommendation,
                           String missingReason, LocalDate receiptDate) {
        UUID chosen = requestedId == null ? recommendation : requestedId;
        if (chosen == null && choices.stream().filter(FakturaDocumentQuery.Document::eligible).count() > 1) {
            throw new BusinessException("Select a Faktura " + role + " for this receipt");
        }
        if (chosen != null && choices.stream().noneMatch(d -> chosen.equals(d.id()) && d.eligible())) {
            throw new BusinessException("Selected Faktura document is not eligible for this contract and date");
        }
        if (chosen != null) validateLockedDocument(chosen, contractId, role, receiptDate);
        String reason = chosen == null
            ? missingReason == null || missingReason.isBlank() ? "No eligible Faktura " + role + " document was available" : missingReason.trim()
            : requestedId == null ? "Unique eligible document automatically selected" : "Selected by user";
        if (reason.length() > 500) throw new BusinessException("Faktura receipt reason is too long");
        String actor = SecurityContextHolder.getContext().getAuthentication() == null ? null
            : SecurityContextHolder.getContext().getAuthentication().getName();
        String headerColumn = switch (kind) {
            case STOCK_MOVEMENT -> "stock_movement_document_id";
            case GOODS_RECEIPT -> "goods_receipt_id";
            case ASSET_ACCEPTANCE -> "asset_acceptance_id";
        };
        jdbc.update("""
            INSERT INTO public.faktura_receipt_evidence (
                id, tenant_id, company_id, contract_id, document_role, faktura_document_id,
                %s, source_payload_sha256, source_status_at_selection, source_number_at_selection,
                source_snapshot, selection_reason, selected_by)
            SELECT ?, ?, ?, ?, ?, d.id, ?, d.payload_sha256, d.source_status, d.document_number,
                   jsonb_build_object('number',d.document_number,'date',d.document_date,'validTo',d.valid_to,
                       'recipient',d.recipient_name,'issuer',d.issuer_name,'expediter',d.expediter_name,
                       'owner',d.owner_name,'contractor',d.contractor_name,'status',d.source_status), ?, ?
            FROM (SELECT 1) AS base
            LEFT JOIN public.faktura_source_documents d ON d.id = ? AND d.tenant_id = ? AND d.company_id = ?
            """.formatted(headerColumn), UUID.randomUUID(), TenantContext.currentTenant(), CompanyContext.currentCompany(),
            contractId, role, receiptId, reason, actor, chosen, TenantContext.currentTenant(), CompanyContext.currentCompany());
    }

    private void validateLockedDocument(UUID id, UUID contractId, String role, LocalDate receiptDate) {
        if (jdbc.query("SELECT 1 FROM public.faktura_source_documents "
                + "WHERE id=? AND tenant_id=? AND company_id=? AND contract_id=? AND source_status=24 "
                + "AND (document_type=20 AND ?='POWER_OF_ATTORNEY' OR document_type IN (28,29) AND ?='INVOICE') "
                + "AND (document_type<>20 OR (document_date IS NOT NULL AND document_date<=? AND valid_to IS NOT NULL AND valid_to>=?)) "
                + "FOR SHARE", (rs, row) -> rs.getInt(1), id,
            TenantContext.currentTenant(), CompanyContext.currentCompany(), contractId, role, role,
            java.sql.Date.valueOf(receiptDate), java.sql.Date.valueOf(receiptDate)).isEmpty()) {
            throw new BusinessException("Selected Faktura document is no longer eligible");
        }
    }

    @Transactional
    public void recheck(ReceiptKind kind, UUID receiptId, LocalDate receiptDate) {
        String column = switch (kind) {
            case STOCK_MOVEMENT -> "stock_movement_document_id";
            case GOODS_RECEIPT -> "goods_receipt_id";
            case ASSET_ACCEPTANCE -> "asset_acceptance_id";
        };
        List<LinkedDocument> linked = jdbc.query("SELECT e.contract_id,e.faktura_document_id,e.document_role "
                + "FROM public.faktura_receipt_evidence e WHERE e.tenant_id=? AND e.company_id=? AND e." + column + "=?",
            (rs, row) -> new LinkedDocument((UUID) rs.getObject(1), (UUID) rs.getObject(2), rs.getString(3)),
            TenantContext.currentTenant(), CompanyContext.currentCompany(), receiptId);
        if (linked.isEmpty()) return;
        var candidates = documents.candidates(linked.get(0).contractId(), receiptDate);
        for (LinkedDocument link : linked) {
            if (link.documentId() == null) continue;
            List<FakturaDocumentQuery.Document> rows = "POWER_OF_ATTORNEY".equals(link.role())
                ? candidates.powersOfAttorney() : candidates.invoices();
            if (rows.stream().noneMatch(d -> link.documentId().equals(d.id()) && d.eligible())) {
                throw new BusinessException("Faktura evidence changed or expired since receipt creation");
            }
            validateLockedDocument(link.documentId(), link.contractId(), link.role(), receiptDate == null ? LocalDate.now() : receiptDate);
        }
    }

    public List<Evidence> evidence(ReceiptKind kind, UUID receiptId) {
        String column = switch (kind) {
            case STOCK_MOVEMENT -> "stock_movement_document_id";
            case GOODS_RECEIPT -> "goods_receipt_id";
            case ASSET_ACCEPTANCE -> "asset_acceptance_id";
        };
        return jdbc.query("SELECT e.document_role,e.faktura_document_id,e.source_number_at_selection,"
                + "e.source_status_at_selection,e.source_snapshot::text,e.selection_reason,"
                + "d.source_status AS latest_status FROM public.faktura_receipt_evidence e "
                + "LEFT JOIN public.faktura_source_documents d ON d.id=e.faktura_document_id "
                + "WHERE e.tenant_id=? AND e.company_id=? AND e." + column + "=? ORDER BY e.document_role",
            (rs, row) -> new Evidence(rs.getString("document_role"), (UUID) rs.getObject("faktura_document_id"),
                rs.getString("source_number_at_selection"), (Integer) rs.getObject("source_status_at_selection"),
                rs.getString("source_snapshot"), rs.getString("selection_reason"),
                (Integer) rs.getObject("latest_status")),
            TenantContext.currentTenant(), CompanyContext.currentCompany(), receiptId);
    }

    private record LinkedDocument(UUID contractId, UUID documentId, String role) {}
    public record Evidence(String role, UUID fakturaDocumentId, String numberAtSelection, Integer statusAtSelection,
                           String snapshot, String selectionReason, Integer latestStatus) {}
    public enum ReceiptKind { STOCK_MOVEMENT, GOODS_RECEIPT, ASSET_ACCEPTANCE }
}
