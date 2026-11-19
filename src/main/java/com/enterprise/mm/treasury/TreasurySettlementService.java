package com.enterprise.mm.treasury;

import com.enterprise.mm.error.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Append-only executions and linked reversals. Status snapshots are never financial evidence. */
@Service
public class TreasurySettlementService {
    private final JdbcTemplate jdbc;
    @Value("${mm.treasury.allow-test-evidence:false}") private boolean testEvidence;
    @Value("${mm.treasury.allow-local-http:false}") private boolean localHttp;
    public TreasurySettlementService(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public record Fact(String id,BigDecimal amount,String currency,LocalDate date,String source,String bankReference,String original){}
    static Fact validate(JsonNode p,boolean reversal){return validate(p,reversal,false);}
    static Fact validate(JsonNode p,boolean reversal,boolean test) {
        String id=normalizedId(TreasuryTaskService.text(p,"nativePaymentId",240)),currency=TreasuryTaskService.text(p,"currency",3);
        if(!currency.matches("[A-Z]{3}"))throw new BadRequestException("Execution currency must be ISO uppercase");
        JsonNode raw=p.path("amount");if(!raw.isTextual()||!raw.asText().matches("[0-9]{1,15}(\\.[0-9]{1,4})?"))throw new BadRequestException("Execution amount must be a positive decimal string");
        BigDecimal amount=new BigDecimal(raw.asText());if(amount.signum()<=0)throw new BadRequestException("Execution amount must be positive");
        String source=p.path("sourceKind").asText();if(!"FINAL".equals(p.path("finality").asText())||!Set.of("BANK_STATEMENT","BANK_EXECUTION").contains(source)&&!(test&&"TEST_FIXTURE".equals(source)))throw new BadRequestException("Final verified bank execution evidence is required");
        LocalDate date;try{date=LocalDate.parse(p.path("paymentDate").asText());}catch(Exception ex){throw new BadRequestException("Execution paymentDate must be ISO date");}
        String original=reversal?normalizedId(TreasuryTaskService.text(p,"originalPaymentId",240)):null;if(Objects.equals(id,original))throw new BadRequestException("Reversal must reference a different originalPaymentId");
        return new Fact(id,amount,currency,date,source,p.path("bankReference").asText(null),original);
    }
    private static String normalizedId(String value){String normalized=value.strip().toLowerCase(Locale.ROOT);if(normalized.isBlank()||normalized.chars().anyMatch(Character::isISOControl))throw new BadRequestException("Stable nativePaymentId cannot contain control characters");return normalized;}
    void apply(Map<String,Object> request,JsonNode payload,boolean reversal) {
        Fact f=validate(payload,reversal,testEvidence&&localHttp&&String.valueOf(request.get("source_base_id")).startsWith("test-"));
        if(!f.currency().equals(request.get("currency")))throw new ConflictException("Execution currency does not match Treasury request");
        UUID requestId=(UUID)request.get("id");String kind=reversal?"REVERSED":"SETTLED";
        // The identity hash deliberately covers economic facts, independent of delivery channel/event id.
        String hash=TreasuryTaskService.hash(requestId+"|"+kind+"|"+f.amount().stripTrailingZeros().toPlainString()+"|"+f.currency()+"|"+f.date()+"|"+f.original());
        var existing=jdbc.queryForList("select fact_hash from treasury_native_settlement where tenant_id=? and company_id=? and source_base_id=? and native_payment_id=?",request.get("tenant_id"),request.get("company_id"),request.get("source_base_id"),f.id());
        if(!existing.isEmpty()){if(hash.equals(existing.getFirst().get("fact_hash")))return;throw new ConflictException("Execution identity already exists with changed economic facts; reconciliation required");}
        if(reversal) {
            var originals=jdbc.queryForList("select amount from treasury_native_settlement where request_id=? and native_payment_id=? and kind='SETTLED'",requestId,f.original());
            if(originals.isEmpty())throw new ConflictException("Reversal originalPaymentId is not a settled execution of this request");
            BigDecimal prior=jdbc.queryForObject("select coalesce(sum(amount),0) from treasury_native_settlement where request_id=? and original_payment_id=? and kind='REVERSED'",BigDecimal.class,requestId,f.original());
            if(prior.add(f.amount()).compareTo((BigDecimal)originals.getFirst().get("amount"))>0)throw new ConflictException("Reversal exceeds original execution");
        }
        jdbc.update("insert into treasury_native_settlement(id,request_id,tenant_id,company_id,source_base_id,native_payment_id,kind,original_payment_id,amount,currency,payment_date,source_kind,bank_reference,fact_hash) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID(),requestId,request.get("tenant_id"),request.get("company_id"),request.get("source_base_id"),f.id(),kind,f.original(),f.amount(),f.currency(),f.date(),f.source(),f.bankReference(),hash);
        refresh(jdbc,requestId);
    }
    static void refresh(JdbcTemplate jdbc,UUID requestId) {
        var r=jdbc.queryForMap("select * from treasury_native_request where id=?",requestId);UUID payment=(UUID)r.get("mm_payment_id");if(payment==null)return;
        var rows=jdbc.queryForList("select * from contract_payments where id=? and tenant_id=? and company_id=? and is_delete=false for update",payment,r.get("tenant_id"),r.get("company_id"));if(rows.isEmpty())throw new ConflictException("Linked MM payment is missing");var p=rows.getFirst();
        if(!Objects.equals(r.get("currency"),p.get("currency_code"))||((BigDecimal)r.get("amount")).compareTo((BigDecimal)p.get("amount"))!=0)throw new ConflictException("Linked MM payment economic terms changed; reconciliation required");
        BigDecimal net=jdbc.queryForObject("select coalesce(sum(case when kind='SETTLED' then amount else -amount end),0) from treasury_native_settlement where request_id=?",BigDecimal.class,requestId);
        String status=net.compareTo((BigDecimal)p.get("amount"))>=0?"PAID":net.signum()>0?"PARTIALLY_PAID":"SENT_TO_TREASURY";
        jdbc.update("update contract_payments set status=?,version=version+1,updated_at=current_timestamp where id=?",status,payment);
        UUID invoice=(UUID)p.get("contract_invoice_id");if(invoice==null)return;
        jdbc.queryForList("select id from contract_invoices where id=? and tenant_id=? and company_id=? for update",invoice,r.get("tenant_id"),r.get("company_id"));
        // Existing legacy paid payments contribute their full amount; native requests contribute only their ledger.
        BigDecimal paid=jdbc.queryForObject("""
            select coalesce(sum(case when tr.id is null then case when p.status='PAID' then p.amount else 0 end
             else coalesce((select sum(case when s.kind='SETTLED' then s.amount else -s.amount end) from treasury_native_settlement s where s.request_id=tr.id),0) end),0)
            from contract_payments p left join treasury_native_request tr on tr.mm_payment_id=p.id and tr.tenant_id=p.tenant_id and tr.company_id=p.company_id
            where p.contract_invoice_id=? and p.tenant_id=? and p.company_id=? and p.is_delete=false
            """,BigDecimal.class,invoice,r.get("tenant_id"),r.get("company_id"));
        jdbc.update("update contract_invoices set paid_amount=?,status=case when ?>=amount then 'PAID' when ?>0 then 'PARTIALLY_PAID' else 'ISSUED' end,version=version+1,updated_at=current_timestamp where id=? and tenant_id=? and company_id=?",paid,paid,paid,invoice,r.get("tenant_id"),r.get("company_id"));
        jdbc.update("update treasury_native_request set updated_at=current_timestamp where id=?",requestId);
    }
}
