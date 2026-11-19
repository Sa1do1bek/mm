package com.enterprise.mm.integration.faktura;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Contract;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.repository.CompanyRepository;
import com.enterprise.mm.repository.ContractRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FakturaSourceStore {
    private final JdbcTemplate jdbc;
    private final ContractRepository contracts;
    private final SupplierRepository suppliers;
    private final CompanyRepository companies;

    public FakturaSourceStore(JdbcTemplate jdbc, ContractRepository contracts, SupplierRepository suppliers, CompanyRepository companies) {
        this.jdbc = jdbc;
        this.contracts = contracts;
        this.suppliers = suppliers;
        this.companies = companies;
    }

    /** One provider document is committed independently, so a bad content row cannot erase a whole page. */
    @Transactional
    public Change upsert(int type, JsonNode header, JsonNode content) {
        String uniqueId = FakturaClient.text(header, "uniqueId", "UniqueId");
        if (uniqueId == null || uniqueId.isBlank() || content == null || !content.isObject()) {
            throw new IllegalArgumentException("Missing Faktura document ID or content");
        }
        String hash = sha256(header.toString() + content);
        ObjectNode safePayload = ((ObjectNode) content).deepCopy();
        safePayload.remove(List.of("delivererPassportSerialChar", "delivererPassportSerialNumber", "file"));
        String payload = safePayload.toString();
        String number = type == 20 ? value(content, "documentNumber") : value(content, "invoiceNumber");
        LocalDate documentDate = checkedDate(type == 20 ? value(content, "documentDate") : value(content, "invoiceDate"));
        String contractNumber = type == 20 ? value(content, "contractNumber")
            : firstNonBlank(value(content, "invoiceContractNumber"), value(content, "actContractNumber"));
        LocalDate contractDate = checkedDate(type == 20 ? value(content, "contractDate")
            : firstNonBlank(value(content, "invoiceContractDate"), value(content, "actContractDate")));
        String ownerInn = digits(value(content, "ownerInn"));
        String contractorInn = digits(value(content, "contractorInn"));
        Match match = match(type, contractNumber, ownerInn, contractorInn);
        Integer status = header.path("status").isNumber() ? header.path("status").asInt() : null;
        String oldHash = jdbc.query("SELECT payload_sha256 FROM public.faktura_source_documents "
                + "WHERE tenant_id=? AND company_id=? AND source_unique_id=?",
            rs -> rs.next() ? rs.getString(1) : null, TenantContext.currentTenant(), CompanyContext.currentCompany(), uniqueId);
        jdbc.update("""
            INSERT INTO public.faktura_source_documents (
                id, tenant_id, company_id, source_unique_id, roaming_uid, document_type, source_status,
                source_created_at, source_updated_at, document_number, document_date, valid_to,
                contract_number, contract_date, owner_inn, owner_name, contractor_inn, contractor_name,
                recipient_name, issuer_name, expediter_name, amount, contract_id, match_state,
                match_reason, payload_sha256, source_payload)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            ON CONFLICT (tenant_id, company_id, source_unique_id) DO UPDATE SET
                roaming_uid=EXCLUDED.roaming_uid, document_type=EXCLUDED.document_type,
                source_status=EXCLUDED.source_status, source_created_at=EXCLUDED.source_created_at,
                source_updated_at=EXCLUDED.source_updated_at, last_seen_at=now(),
                document_number=EXCLUDED.document_number, document_date=EXCLUDED.document_date,
                valid_to=EXCLUDED.valid_to, contract_number=EXCLUDED.contract_number,
                contract_date=EXCLUDED.contract_date, owner_inn=EXCLUDED.owner_inn,
                owner_name=EXCLUDED.owner_name, contractor_inn=EXCLUDED.contractor_inn,
                contractor_name=EXCLUDED.contractor_name, recipient_name=EXCLUDED.recipient_name,
                issuer_name=EXCLUDED.issuer_name, expediter_name=EXCLUDED.expediter_name,
                amount=EXCLUDED.amount,
                contract_id=CASE WHEN faktura_source_documents.match_state='MANUAL_VERIFIED'
                    AND faktura_source_documents.owner_inn IS NOT DISTINCT FROM EXCLUDED.owner_inn
                    AND faktura_source_documents.contractor_inn IS NOT DISTINCT FROM EXCLUDED.contractor_inn
                    THEN faktura_source_documents.contract_id ELSE EXCLUDED.contract_id END,
                match_state=CASE WHEN faktura_source_documents.match_state='MANUAL_VERIFIED'
                    AND faktura_source_documents.owner_inn IS NOT DISTINCT FROM EXCLUDED.owner_inn
                    AND faktura_source_documents.contractor_inn IS NOT DISTINCT FROM EXCLUDED.contractor_inn
                    THEN 'MANUAL_VERIFIED' ELSE EXCLUDED.match_state END,
                match_reason=CASE WHEN faktura_source_documents.match_state='MANUAL_VERIFIED'
                    AND faktura_source_documents.owner_inn IS NOT DISTINCT FROM EXCLUDED.owner_inn
                    AND faktura_source_documents.contractor_inn IS NOT DISTINCT FROM EXCLUDED.contractor_inn
                    THEN faktura_source_documents.match_reason ELSE EXCLUDED.match_reason END,
                matched_by=CASE WHEN faktura_source_documents.match_state='MANUAL_VERIFIED'
                    AND faktura_source_documents.owner_inn IS NOT DISTINCT FROM EXCLUDED.owner_inn
                    AND faktura_source_documents.contractor_inn IS NOT DISTINCT FROM EXCLUDED.contractor_inn
                    THEN faktura_source_documents.matched_by ELSE NULL END,
                matched_at=CASE WHEN faktura_source_documents.match_state='MANUAL_VERIFIED'
                    AND faktura_source_documents.owner_inn IS NOT DISTINCT FROM EXCLUDED.owner_inn
                    AND faktura_source_documents.contractor_inn IS NOT DISTINCT FROM EXCLUDED.contractor_inn
                    THEN faktura_source_documents.matched_at ELSE NULL END,
                payload_sha256=EXCLUDED.payload_sha256, source_payload=EXCLUDED.source_payload,
                updated_at=now()
            """,
            UUID.randomUUID(), TenantContext.currentTenant(), CompanyContext.currentCompany(), uniqueId,
            FakturaClient.text(header, "roamingUid", "RoamingUid"), type, status,
            timestamp(header.path("createdDateTime")), timestamp(header.path("updatedDateTime")),
            number, sqlDate(documentDate), sqlDate(type == 20 ? checkedDate(value(content, "documentValidDate")) : null),
            contractNumber, sqlDate(contractDate), ownerInn, value(content, "ownerName"),
            contractorInn, value(content, "contractorName"),
            type == 20 ? value(content, "contractorName") : null,
            type == 20 ? value(content, "ownerName") : null,
            type == 20 ? value(content, "deliverer") : null,
            type == 20 ? decimal(firstNonBlank(value(content, "totalPrice"), value(header, "totalPrice")))
                : decimal(value(content, "invoiceServicesTotalPrice")),
            match.contractId(), match.state(), match.reason(), hash, payload);
        if (oldHash == null || !oldHash.equals(hash)) {
            UUID sourceId = jdbc.queryForObject("SELECT id FROM public.faktura_source_documents "
                    + "WHERE tenant_id=? AND company_id=? AND source_unique_id=?", UUID.class,
                TenantContext.currentTenant(), CompanyContext.currentCompany(), uniqueId);
            replaceLines(sourceId, "SERVICES", content.path(type == 20 ? "services" : "invoiceServices"));
            if (type == 29) replaceLines(sourceId, "ACT_SERVICES", content.path("actServices"));
        }
        return oldHash == null ? Change.CREATED : oldHash.equals(hash) ? Change.UNCHANGED : Change.UPDATED;
    }

    private void replaceLines(UUID sourceId, String kind, JsonNode rows) {
        jdbc.update("DELETE FROM public.faktura_source_lines WHERE source_document_id=? AND line_kind=?", sourceId, kind);
        if (!rows.isArray()) return;
        int index = 0;
        for (JsonNode row : rows) {
            if (!row.isObject()) throw new IllegalArgumentException("Invalid Faktura document line");
            jdbc.update("INSERT INTO public.faktura_source_lines "
                    + "(id,source_document_id,line_kind,line_index,source_line_number,title,measurement,quantity,unit_price,amount,catalog_code,source_line) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?::jsonb)",
                UUID.randomUUID(), sourceId, kind, index++, value(row, "number"), value(row, "title"),
                value(row, "measurement"), decimal(value(row, "quantity")), decimal(value(row, "pricePerItem")),
                decimal(firstNonBlank(value(row, "deliveryCostWithTaxes"), value(row, "deliveryCost"))),
                value(row.path("catalog"), "code"), row.toString());
        }
    }

    Match match(int type, String number, String ownerInn, String contractorInn) {
        if (number == null || number.isBlank() || ownerInn == null || contractorInn == null) {
            return new Match(null, "UNMATCHED", "Missing contract number or party INN");
        }
        String companyInn = companies.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany())
            .map(company -> digits(company.getTaxId())).orElse(null);
        if (companyInn == null || !(type == 20 && companyInn.equals(ownerInn)
            || (type == 28 || type == 29) && companyInn.equals(contractorInn))) {
            return new Match(null, "UNMATCHED", "Faktura document direction does not match a purchase receipt");
        }
        List<Contract> candidates = contracts.findFakturaContractCandidates(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), number.trim());
        List<Contract> verified = candidates.stream().filter(contract -> {
            if (contract.getSupplierId() == null) return false;
            Supplier supplier = suppliers.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), contract.getSupplierId()).orElse(null);
            if (supplier == null || !CompanyContext.currentCompany().equals(supplier.getCompanyId())) return false;
            String supplierInn = FakturaSupplierInn.from(supplier);
            return supplierInn != null && (type == 20 ? supplierInn.equals(contractorInn) : supplierInn.equals(ownerInn));
        }).toList();
        if (verified.size() == 1) return new Match(verified.get(0).getId(), "AUTO_MATCHED", "Contract number and supplier INN");
        if (verified.size() > 1) return new Match(null, "AMBIGUOUS", "Several contracts share the number and supplier INN");
        return new Match(null, "UNMATCHED", "No matching company contract and supplier INN");
    }

    static LocalDate date(String value) {
        if (value == null || value.isBlank()) return null;
        String text = value.trim();
        try { return LocalDate.parse(text.length() > 10 ? text.substring(0, 10) : text); }
        catch (Exception ignored) {
            try { return LocalDate.parse(text, DateTimeFormatter.ofPattern("dd.MM.yyyy")); }
            catch (Exception ignoredAgain) { return null; }
        }
    }

    private static LocalDate checkedDate(String value) {
        LocalDate parsed = date(value);
        if (value != null && !value.isBlank() && parsed == null) {
            throw new IllegalArgumentException("Unrecognized Faktura date format");
        }
        return parsed;
    }

    private static Timestamp timestamp(JsonNode value) {
        if (value == null || !value.isNumber()) return null;
        long epoch = value.asLong();
        return Timestamp.valueOf(LocalDateTime.ofInstant(Instant.ofEpochMilli(epoch), ZoneId.of("Asia/Tashkent")));
    }

    private static String value(JsonNode node, String key) {
        JsonNode value = node.path(key);
        return value.isMissingNode() || value.isNull() ? null : value.asText(null);
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private static String digits(String value) { return value == null ? null : value.replaceAll("\\D", ""); }
    private static Date sqlDate(LocalDate date) { return date == null ? null : Date.valueOf(date); }
    private static BigDecimal decimal(String value) {
        if (value == null || value.isBlank()) return null;
        try { return new BigDecimal(value.replace(" ", "").replace(",", ".")); }
        catch (NumberFormatException ignored) { return null; }
    }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    record Match(UUID contractId, String state, String reason) {}
    public enum Change { CREATED, UPDATED, UNCHANGED }
}
