package com.enterprise.mm.integration.faktura;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Contract;
import com.enterprise.mm.repository.CompanyRepository;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.ContractRepository;
import com.enterprise.mm.repository.SupplierRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.core.context.SecurityContextHolder;

@Service
public class FakturaDocumentQuery {
    private final JdbcTemplate jdbc;
    private final ContractRepository contracts;
    private final CompanyRepository companies;
    private final SupplierRepository suppliers;

    public FakturaDocumentQuery(JdbcTemplate jdbc, ContractRepository contracts,
                                CompanyRepository companies, SupplierRepository suppliers) {
        this.jdbc = jdbc;
        this.contracts = contracts;
        this.companies = companies;
        this.suppliers = suppliers;
    }

    public Candidates candidates(UUID contractId, LocalDate receiptDate) {
        Contract contract = contracts.findByIdAndIsDeletedFalse(contractId)
            .filter(c -> TenantContext.currentTenant().equals(c.getTenantId())
                && CompanyContext.currentCompany().equals(c.getCompanyId()))
            .orElseThrow(() -> new BusinessException("Contract not found in this company"));
        LocalDate date = receiptDate == null ? LocalDate.now() : receiptDate;
        List<Document> documents = jdbc.query("""
                SELECT id, source_unique_id, document_type, source_status, document_number, document_date,
                       valid_to, owner_name, contractor_name, recipient_name, issuer_name, expediter_name,
                       amount, match_state, match_reason
                FROM public.faktura_source_documents
                WHERE tenant_id=? AND company_id=? AND contract_id=?
                ORDER BY document_date DESC NULLS LAST, source_unique_id
                LIMIT 201
                """, (rs, row) -> document(rs, date),
            TenantContext.currentTenant(), CompanyContext.currentCompany(), contract.getId());
        if (documents.size() > 200) throw new BusinessException("Too many Faktura documents for this contract; review the source links");
        List<Document> powers = documents.stream().filter(d -> d.type() == 20).toList();
        List<Document> invoices = documents.stream().filter(d -> d.type() == 28 || d.type() == 29).toList();
        return new Candidates(powers, invoices, soleEligible(powers), soleEligible(invoices));
    }

    public List<Document> reviewQueue() {
        return jdbc.query("""
                SELECT id, source_unique_id, document_type, source_status, document_number, document_date,
                       valid_to, owner_name, contractor_name, recipient_name, issuer_name, expediter_name,
                       amount, match_state, match_reason
                FROM public.faktura_source_documents
                WHERE tenant_id=? AND company_id=? AND match_state IN ('UNMATCHED','AMBIGUOUS')
                ORDER BY last_seen_at DESC LIMIT 100
                """, (rs, row) -> document(rs, LocalDate.now()),
            TenantContext.currentTenant(), CompanyContext.currentCompany());
    }

    private static final Map<String, String> BROWSE_SORT_COLUMNS = Map.of(
        "type", "d.document_type", "providerStatus", "d.source_status", "number", "lower(d.document_number)",
        "date", "d.document_date", "ownerOrganization", "lower(d.owner_name)",
        "contractorOrganization", "lower(d.contractor_name)", "contractNumber", "lower(d.contract_number)",
        "matchState", "d.match_state", "lastSeenAt", "d.last_seen_at");

    public DocumentPage browse(BrowseFilters filter, int page, int size) {
        if (page < 0 || size < 1 || size > 100 || filter.type() != null
            && filter.type() != 20 && filter.type() != 28 && filter.type() != 29
            || filter.providerStatus() != null && filter.providerStatus() < 0
            || filter.dateFrom() != null && filter.dateTo() != null && filter.dateTo().isBefore(filter.dateFrom())
            || filter.seenFrom() != null && filter.seenTo() != null && filter.seenTo().isBefore(filter.seenFrom())) {
            throw new BusinessException("Invalid Faktura document filters");
        }
        String sortColumn = BROWSE_SORT_COLUMNS.get(filter.sortBy());
        if (sortColumn == null || !("asc".equalsIgnoreCase(filter.sortDir()) || "desc".equalsIgnoreCase(filter.sortDir()))) {
            throw new BusinessException("Invalid Faktura document sort");
        }
        StringBuilder where = new StringBuilder(" WHERE d.tenant_id=? AND d.company_id=?");
        List<Object> args = new ArrayList<>();
        args.add(TenantContext.currentTenant());
        args.add(CompanyContext.currentCompany());
        if (filter.contractId() != null) {
            where.append(" AND d.contract_id=?");
            args.add(filter.contractId());
        }
        if (filter.documentGroup() != null) {
            if (!"INVOICES".equals(filter.documentGroup())) throw new BusinessException("Invalid Faktura document group");
            where.append(" AND d.document_type IN (28,29)");
        }
        if (filter.type() != null) {
            where.append(" AND d.document_type=?");
            args.add(filter.type());
        }
        if (filter.providerStatus() != null) {
            where.append(" AND d.source_status=?");
            args.add(filter.providerStatus());
        }
        if (filter.matchState() != null && !filter.matchState().isBlank()) {
            if (!List.of("AUTO_MATCHED", "MANUAL_VERIFIED", "UNMATCHED", "AMBIGUOUS", "NEEDS_REVIEW").contains(filter.matchState())) {
                throw new BusinessException("Invalid Faktura match state");
            }
            if ("NEEDS_REVIEW".equals(filter.matchState())) where.append(" AND d.match_state IN ('UNMATCHED','AMBIGUOUS')");
            else {
                where.append(" AND d.match_state=?");
                args.add(filter.matchState());
            }
        }
        if (filter.search() != null && !filter.search().isBlank()) {
            String needle = filter.search().trim();
            if (needle.length() > 120) throw new BusinessException("Faktura search is too long");
            where.append(" AND (LOWER(d.document_number) LIKE ? OR LOWER(d.contract_number) LIKE ? "
                + "OR LOWER(d.owner_name) LIKE ? OR LOWER(d.contractor_name) LIKE ?)");
            String pattern = "%" + needle.toLowerCase(Locale.ROOT) + "%";
            for (int i = 0; i < 4; i++) args.add(pattern);
        }
        addTextFilter(where, args, "d.document_number", filter.number());
        addTextFilter(where, args, "d.owner_name", filter.owner());
        addTextFilter(where, args, "d.contractor_name", filter.contractor());
        addTextFilter(where, args, "d.contract_number", filter.contractNumber());
        if (filter.dateFrom() != null) { where.append(" AND d.document_date>=?"); args.add(filter.dateFrom()); }
        if (filter.dateTo() != null) { where.append(" AND d.document_date<=?"); args.add(filter.dateTo()); }
        if (filter.seenFrom() != null) { where.append(" AND d.last_seen_at>=?"); args.add(filter.seenFrom().atStartOfDay()); }
        if (filter.seenTo() != null) { where.append(" AND d.last_seen_at<?"); args.add(filter.seenTo().plusDays(1).atStartOfDay()); }
        Long total = jdbc.queryForObject("SELECT count(*) FROM public.faktura_source_documents d" + where,
            Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long) page * size);
        List<DocumentSummary> rows = jdbc.query("""
                SELECT d.id,d.document_type,d.source_status,d.document_number,d.document_date,
                       d.owner_name,d.contractor_name,d.recipient_name,d.issuer_name,d.expediter_name,
                       d.valid_to,d.amount,d.contract_number,d.contract_id,
                       d.match_state,d.match_reason,d.last_seen_at
                FROM public.faktura_source_documents d
                """ + where + " ORDER BY " + sortColumn + " " + filter.sortDir().toUpperCase(Locale.ROOT)
                + " NULLS LAST,d.id DESC LIMIT ? OFFSET ?",
            (rs, row) -> new DocumentSummary((UUID) rs.getObject("id"), rs.getInt("document_type"),
                (Integer) rs.getObject("source_status"), rs.getString("document_number"),
                date(rs, "document_date"), rs.getString("owner_name"), rs.getString("contractor_name"),
                rs.getString("recipient_name"), rs.getString("issuer_name"), rs.getString("expediter_name"),
                date(rs, "valid_to"), rs.getBigDecimal("amount"),
                rs.getString("contract_number"), (UUID) rs.getObject("contract_id"),
                rs.getString("match_state"), rs.getString("match_reason"), time(rs, "last_seen_at")),
            pageArgs.toArray());
        return new DocumentPage(rows, total == null ? 0 : total, page, size);
    }

    private static void addTextFilter(StringBuilder where, List<Object> args, String column, String value) {
        if (value == null || value.isBlank()) return;
        String trimmed = value.trim();
        if (trimmed.length() > 120) throw new BusinessException("Faktura filter is too long");
        where.append(" AND LOWER(").append(column).append(") LIKE ?");
        args.add("%" + trimmed.toLowerCase(Locale.ROOT) + "%");
    }

    public DocumentDetails details(UUID id) {
        List<DocumentDetails> rows = jdbc.query("""
                SELECT id,source_unique_id,roaming_uid,document_type,source_status,
                       source_created_at,source_updated_at,document_number,document_date,valid_to,
                       contract_number,contract_date,owner_inn,owner_name,contractor_inn,contractor_name,
                       recipient_name,issuer_name,expediter_name,amount,contract_id,match_state,match_reason,
                       matched_by,matched_at,first_seen_at,last_seen_at
                FROM public.faktura_source_documents
                WHERE id=? AND tenant_id=? AND company_id=?
                """, (rs, row) -> new DocumentDetails((UUID) rs.getObject("id"),
                    rs.getString("source_unique_id"), rs.getString("roaming_uid"), rs.getInt("document_type"),
                    (Integer) rs.getObject("source_status"), time(rs, "source_created_at"),
                    time(rs, "source_updated_at"), rs.getString("document_number"), date(rs, "document_date"),
                    date(rs, "valid_to"), rs.getString("contract_number"), date(rs, "contract_date"),
                    rs.getString("owner_inn"), rs.getString("owner_name"), rs.getString("contractor_inn"),
                    rs.getString("contractor_name"), rs.getString("recipient_name"), rs.getString("issuer_name"),
                    rs.getString("expediter_name"), rs.getBigDecimal("amount"), (UUID) rs.getObject("contract_id"),
                    rs.getString("match_state"), rs.getString("match_reason"), rs.getString("matched_by"),
                    time(rs, "matched_at"), time(rs, "first_seen_at"), time(rs, "last_seen_at"), List.of()),
            id, TenantContext.currentTenant(), CompanyContext.currentCompany());
        if (rows.isEmpty()) throw new BusinessException("Faktura document not found in this company");
        List<DocumentLine> lines = jdbc.query("""
                SELECT line_kind,line_index,source_line_number,title,measurement,quantity,unit_price,amount,catalog_code
                FROM public.faktura_source_lines WHERE source_document_id=? ORDER BY line_kind,line_index
                """, (rs, row) -> new DocumentLine(rs.getString("line_kind"), rs.getInt("line_index"),
                    rs.getString("source_line_number"), rs.getString("title"), rs.getString("measurement"),
                    rs.getBigDecimal("quantity"), rs.getBigDecimal("unit_price"), rs.getBigDecimal("amount"),
                    rs.getString("catalog_code")), id);
        DocumentDetails found = rows.get(0);
        return new DocumentDetails(found.id(), found.sourceUniqueId(), found.roamingUid(), found.type(),
            found.providerStatus(), found.sourceCreatedAt(), found.sourceUpdatedAt(), found.number(), found.date(),
            found.validTo(), found.contractNumber(), found.contractDate(), found.ownerInn(), found.ownerOrganization(),
            found.contractorInn(), found.contractorOrganization(), found.recipient(), found.issuer(), found.expediter(),
            found.amount(), found.contractId(), found.matchState(), found.matchReason(), found.matchedBy(),
            found.matchedAt(), found.firstSeenAt(), found.lastSeenAt(), lines);
    }

    private static LocalDate date(ResultSet rs, String column) throws SQLException {
        java.sql.Date value = rs.getDate(column);
        return value == null ? null : value.toLocalDate();
    }

    private static LocalDateTime time(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    @Transactional
    public void linkToContract(UUID sourceId, UUID contractId) {
        Contract contract = contracts.findByIdAndIsDeletedFalse(contractId)
            .filter(c -> TenantContext.currentTenant().equals(c.getTenantId())
                && CompanyContext.currentCompany().equals(c.getCompanyId()))
            .orElseThrow(() -> new BusinessException("Contract not found in this company"));
        String companyInn = companies.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany())
            .map(c -> digits(c.getTaxId())).orElse(null);
        String supplierInn = contract.getSupplierId() == null ? null
            : suppliers.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), contract.getSupplierId())
                .filter(s -> CompanyContext.currentCompany().equals(s.getCompanyId()))
                .map(FakturaSupplierInn::from).orElse(null);
        List<DocumentParties> parties = jdbc.query("SELECT document_type,owner_inn,contractor_inn FROM public.faktura_source_documents "
                + "WHERE id=? AND tenant_id=? AND company_id=? FOR UPDATE",
            (rs, row) -> new DocumentParties(rs.getInt(1), rs.getString(2), rs.getString(3)),
            sourceId, TenantContext.currentTenant(), CompanyContext.currentCompany());
        if (parties.size() != 1 || companyInn == null || supplierInn == null
            || !(parties.get(0).type() == 20 && companyInn.equals(parties.get(0).ownerInn())
                && supplierInn.equals(parties.get(0).contractorInn())
                || (parties.get(0).type() == 28 || parties.get(0).type() == 29)
                && supplierInn.equals(parties.get(0).ownerInn())
                && companyInn.equals(parties.get(0).contractorInn()))) {
            throw new BusinessException("Faktura party INNs or document direction do not match this purchase contract");
        }
        String actor = SecurityContextHolder.getContext().getAuthentication() == null ? null
            : SecurityContextHolder.getContext().getAuthentication().getName();
        jdbc.update("UPDATE public.faktura_source_documents SET contract_id=?, match_state='MANUAL_VERIFIED', "
                + "match_reason='Manually verified against both party INNs', matched_by=?, matched_at=now(), updated_at=now() "
                + "WHERE id=? AND tenant_id=? AND company_id=?",
            contractId, actor, sourceId, TenantContext.currentTenant(), CompanyContext.currentCompany());
    }

    private static UUID soleEligible(List<Document> documents) {
        List<Document> eligible = documents.stream().filter(Document::eligible).toList();
        return eligible.size() == 1 ? eligible.get(0).id() : null;
    }

    private static Document document(ResultSet rs, LocalDate date) throws SQLException {
        int type = rs.getInt("document_type");
        Integer status = (Integer) rs.getObject("source_status");
        LocalDate documentDate = rs.getDate("document_date") == null ? null : rs.getDate("document_date").toLocalDate();
        LocalDate validTo = rs.getDate("valid_to") == null ? null : rs.getDate("valid_to").toLocalDate();
        String match = rs.getString("match_state");
        String reason = ineligibleReason(match, rs.getString("match_reason"), status, type, documentDate, validTo, date);
        return new Document((UUID) rs.getObject("id"), rs.getString("source_unique_id"), type, status,
            rs.getString("document_number"), documentDate,
            validTo, rs.getString("owner_name"), rs.getString("contractor_name"), rs.getString("recipient_name"),
            rs.getString("issuer_name"), rs.getString("expediter_name"), rs.getBigDecimal("amount"),
            match, reason == null, reason);
    }

    static String ineligibleReason(String match, String matchReason, Integer status, int type,
                                   LocalDate documentDate, LocalDate validTo, LocalDate date) {
        if (!("AUTO_MATCHED".equals(match) || "MANUAL_VERIFIED".equals(match))) return matchReason;
        if (status == null) return "Provider status is unknown";
        if (status != 24) return "Provider document is not published";
        if (type == 20 && (documentDate == null || validTo == null)) return "Power of attorney dates are incomplete";
        if (type == 20 && documentDate != null && documentDate.isAfter(date)) return "Power of attorney is not yet issued";
        if (type == 20 && validTo != null && validTo.isBefore(date)) return "Power of attorney has expired";
        return null;
    }

    public record Candidates(List<Document> powersOfAttorney, List<Document> invoices,
                             UUID recommendedPowerOfAttorneyId, UUID recommendedInvoiceId) {}
    public record BrowseFilters(String search, Integer type, String matchState, String number,
                                String owner, String contractor, String contractNumber, Integer providerStatus,
                                LocalDate dateFrom, LocalDate dateTo, LocalDate seenFrom, LocalDate seenTo,
                                String sortBy, String sortDir, UUID contractId, String documentGroup) {}
    public record DocumentPage(List<DocumentSummary> content, long totalElements, int page, int size) {}
    public record DocumentSummary(UUID id, int type, Integer providerStatus, String number, LocalDate date,
                                  String ownerOrganization, String contractorOrganization, String recipient,
                                  String issuer, String expediter, LocalDate validTo, BigDecimal amount, String contractNumber,
                                  UUID contractId, String matchState, String matchReason, LocalDateTime lastSeenAt) {}
    public record DocumentDetails(UUID id, String sourceUniqueId, String roamingUid, int type, Integer providerStatus,
                                  LocalDateTime sourceCreatedAt, LocalDateTime sourceUpdatedAt,
                                  String number, LocalDate date, LocalDate validTo, String contractNumber,
                                  LocalDate contractDate, String ownerInn, String ownerOrganization,
                                  String contractorInn, String contractorOrganization, String recipient,
                                  String issuer, String expediter, BigDecimal amount, UUID contractId,
                                  String matchState, String matchReason, String matchedBy, LocalDateTime matchedAt,
                                  LocalDateTime firstSeenAt, LocalDateTime lastSeenAt, List<DocumentLine> lines) {}
    public record DocumentLine(String kind, int index, String sourceNumber, String title, String measurement,
                               BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount, String catalogCode) {}
    public record Document(UUID id, String sourceUniqueId, int type, Integer providerStatus, String number,
                           LocalDate date, LocalDate validTo, String ownerOrganization, String contractorOrganization,
                           String recipient, String issuer, String expediter, java.math.BigDecimal amount,
                           String matchState, boolean eligible, String ineligibleReason) {}
    private record DocumentParties(int type, String ownerInn, String contractorInn) {}
    private static String digits(String value) { return value == null ? null : value.replaceAll("\\D", ""); }
}
