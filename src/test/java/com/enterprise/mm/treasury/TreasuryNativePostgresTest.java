package com.enterprise.mm.treasury;

import static org.assertj.core.api.Assertions.*;
import com.enterprise.mm.common.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named="MM_PERIOD_PG_URL",matches=".+")
class TreasuryNativePostgresTest {
    final ObjectMapper mapper=new ObjectMapper();
    final String tenant="treasury-test",source="treasury-a",schema="treasury_test_"+UUID.randomUUID().toString().replace("-","");
    final UUID company=UUID.randomUUID(),payment=UUID.randomUUID(),invoice=UUID.randomUUID();
    final UUID organization=UUID.randomUUID(),supplier=UUID.randomUUID(),contract=UUID.randomUUID(),nativeSupplier=UUID.randomUUID(),nativeContract=UUID.randomUUID(),nativeSupplierBank=UUID.randomUUID(),nativeOrganizationBank=UUID.randomUUID();
    JdbcTemplate admin,jdbc;TransactionTemplate tx;TreasuryTaskService tasks;TreasuryEventService events;
    @BeforeEach void setup() {
        var url=System.getenv("MM_PERIOD_PG_URL");var user=System.getenv().getOrDefault("MM_PERIOD_PG_USER","mm_local");var password=System.getenv().getOrDefault("MM_PERIOD_PG_PASSWORD","");
        admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));admin.execute("create schema "+schema);
        var ds=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,user,password);jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        // Clone the actual migrated column/check definitions, so naming/precision/version regressions cannot hide in toy tables.
        jdbc.execute("create table contract_payments (like public.contract_payments including all)");
        jdbc.execute("create table contract_invoices (like public.contract_invoices including all)");
        jdbc.execute("create table integration_events (like public.integration_events including all)");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V94__treasury_native_exchange.sql")).execute(ds);
        TenantContext.setTenant(tenant);CompanyContext.setCompany(company);
        var access=new TreasuryNativeAccess(true,"12345678901234567890123456789012",tenant,company.toString(),source,true);
        org.springframework.test.util.ReflectionTestUtils.setField(access,"organization",organization.toString());
        tasks=new TreasuryTaskService(jdbc,mapper,access);events=new TreasuryEventService(jdbc,mapper,tasks,new TreasurySettlementService(jdbc));
        jdbc.update("insert into contract_payments(id,tenant_id,company_id,contract_invoice_id,amount,currency_code,status,created_at,name,supplier_id,contract_id) values(?,?,?,?,100,'UZS','SENT_TO_TREASURY',current_timestamp,'Treasury test payment',?,?)",payment,tenant,company,invoice,supplier,contract);
        jdbc.update("insert into contract_invoices(id,tenant_id,company_id,amount,paid_amount,status,is_delete,created_at,name,supplier_id,contract_id) values(?,?,?,100,0,'ISSUED',false,current_timestamp,'Treasury test invoice',?,?)",invoice,tenant,company,supplier,contract);
    }
    @AfterEach void clear(){if(admin!=null)admin.execute("drop schema if exists "+schema+" cascade");TenantContext.clear();CompanyContext.clear();}
    ObjectNode base(){return mapper.createObjectNode().put("protocolVersion",1).put("sourceBaseId",source);}
    JsonNode poll(String worker){return mapper.valueToTree(tx.execute(s->tasks.poll(base().put("workerId",worker).put("limit",1))));}
    ObjectNode snapshot(String nativeId){var e=base().put("eventId",UUID.randomUUID().toString()).put("eventType","REQUEST.UPSERT").put("nativeRequestId",nativeId).put("revision",1);var p=e.putObject("payload").put("number","TR-1").put("amount","100").put("currency","UZS").put("organizationId",organization.toString()).put("operationTypeId","ОплатаПоставщику").put("counterpartyId",nativeSupplier.toString()).put("contractId",nativeContract.toString());var fields=p.putObject("nativeFields");fields.putObject("РасчетныйСчет").put("nativeId",nativeSupplierBank.toString());fields.putObject("РасчетныйСчетОрганизации").put("nativeId",nativeOrganizationBank.toString());return e;}
    ObjectNode mapping(){var m=mapper.createObjectNode().put("sourceBaseId",source).put("purpose","Test purchase");for(var f:TreasuryTaskService.MAPPING_FIELDS)m.put(f,UUID.randomUUID().toString());return m.put("nativeOrganizationId",organization.toString()).put("nativeOperationTypeId","ОплатаПоставщику").put("nativeSupplierId",nativeSupplier.toString()).put("nativeContractId",nativeContract.toString()).put("nativeSupplierBankAccountId",nativeSupplierBank.toString()).put("nativeOrganizationBankAccountId",nativeOrganizationBank.toString());}
    void event(JsonNode e){tx.executeWithoutResult(s->events.receive(e));}
    ObjectNode fact(String request,String id,String amount,String kind){var e=base().put("eventId",UUID.randomUUID().toString()).put("eventType",kind).put("nativeRequestId",request).put("revision",1);e.putObject("payload").put("nativePaymentId",id).put("amount",amount).put("currency","UZS").put("paymentDate","2026-11-17").put("finality","FINAL").put("sourceKind","BANK_STATEMENT");return e;}
    @Test void nativeOriginPartialFullDuplicateRefundAndLateStatusPreserveLedger() {
        var nativeId=UUID.randomUUID().toString();event(snapshot(nativeId));
        var rows=tasks.requests(null,true);assertThat(rows).hasSize(1);var id=(UUID)rows.getFirst().get("id");
        tx.executeWithoutResult(s->tasks.saveMapping(payment,mapping()));
        tx.executeWithoutResult(s->tasks.link(id,payment));
        var forty=fact(nativeId,"bank:40","40","PAYMENT.SETTLED");event(forty);event(forty);
        assertThat(tasks.request(id).get("paidAmount")).isEqualTo("40");
        var second=fact(nativeId,"bank:60","60","PAYMENT.SETTLED");event(second);
        assertThat(jdbc.queryForObject("select status from contract_payments where id=?",String.class,payment)).isEqualTo("PAID");
        var duplicate=fact(nativeId,"bank:40","40","PAYMENT.SETTLED");event(duplicate);
        var refund=fact(nativeId,"refund:10","10","PAYMENT.REVERSED");refund.with("payload").put("originalPaymentId","bank:40");event(refund);
        assertThat(tasks.request(id).get("paidAmount")).isEqualTo("90");
        assertThat(jdbc.queryForObject("select paid_amount from contract_invoices where id=?",java.math.BigDecimal.class,invoice)).isEqualByComparingTo("90");
        assertThat(jdbc.queryForObject("select status from contract_payments where id=?",String.class,payment)).isEqualTo("PARTIALLY_PAID");
        var late=snapshot(nativeId);late.put("revision",2);late.with("payload").put("bankStatus","SENT_TO_BANK");event(late);
        assertThat(tasks.request(id).get("paidAmount")).isEqualTo("90");
        var changed=fact(nativeId,"bank:40","45","PAYMENT.SETTLED");assertThatThrownBy(()->event(changed)).hasMessageContaining("changed");
        var foreign=fact(nativeId,"bank:foreign","10","PAYMENT.SETTLED");foreign.with("payload").put("currency","USD");assertThatThrownBy(()->event(foreign)).hasMessageContaining("currency");
        assertThat(tasks.request(id).get("paidAmount")).isEqualTo("90");
    }
    @Test void durableQueueFencesWorkersHashScopeAndExpiredLease() {
        var mapping=mapping();
        tx.executeWithoutResult(s->tasks.saveMapping(payment,mapping));
        assertThat(tasks.readiness(payment).get("ready")).isEqualTo(true);
        tx.executeWithoutResult(s->tasks.enqueue(payment,UUID.randomUUID(),mapper.createObjectNode().put("amount","100").put("currency","UZS")));
        var old=poll("worker-a").path("tasks").get(0);assertThat(poll("worker-b").path("tasks")).isEmpty();
        jdbc.update("update treasury_native_task set lease_expires_at=current_timestamp-interval '1 second'");
        var task=poll("worker-b").path("tasks").get(0);
        var result=base().put("workerId","worker-b").put("leaseToken",task.path("leaseToken").asText()).put("eventId",task.path("eventId").asText()).put("payloadHash",task.path("payloadHash").asText()).put("status","CREATED").put("nativeRequestId",UUID.randomUUID().toString()).put("nativeNumber","TR-2");result.set("payload",snapshot(result.path("nativeRequestId").asText()).path("payload"));
        var stale=result.deepCopy().put("leaseToken",old.path("leaseToken").asText());
        UUID id=UUID.fromString(task.path("taskId").asText());assertThatThrownBy(()->tx.execute(s->tasks.result(id,stale))).hasMessageContaining("lease");
        tx.executeWithoutResult(s->tasks.result(id,result));tx.executeWithoutResult(s->tasks.result(id,result));
        assertThat(tasks.requests(payment,false)).hasSize(1);
        CompanyContext.setCompany(UUID.randomUUID());assertThat(tasks.requests(null,false)).isEmpty();assertThatThrownBy(()->tx.execute(s->tasks.result(id,result))).hasMessageContaining("scope");
    }
    @Test void sourceCredentialCannotImportForeignOrganizationOrPayroll() {
        var foreign=snapshot(UUID.randomUUID().toString());foreign.with("payload").put("organizationId",UUID.randomUUID().toString());
        assertThatThrownBy(()->event(foreign)).hasMessageContaining("scope");
        var payroll=snapshot(UUID.randomUUID().toString());payroll.with("payload").put("operationTypeId","ЗаработнаяПлата");
        assertThatThrownBy(()->event(payroll)).hasMessageContaining("scope");
        assertThat(tasks.requests(null,false)).isEmpty();
        var foreignMapping=mapping().put("nativeOrganizationId",UUID.randomUUID().toString());assertThatThrownBy(()->tx.execute(s->tasks.saveMapping(payment,foreignMapping))).hasMessageContaining("scope");
        event(snapshot(UUID.randomUUID().toString()));UUID id=(UUID)tasks.requests(null,true).getFirst().get("id");tx.executeWithoutResult(s->tasks.saveMapping(payment,mapping()));
        jdbc.update("update treasury_native_request set source_base_id='other-base' where id=?",id);
        assertThatThrownBy(()->tx.execute(s->tasks.link(id,payment))).hasMessageContaining("scope");
    }
    @Test void linkingCannotOverwriteLegacyPaidOrWrongSupplierAndStatusDoesNotPay() {
        var sourceRequest=snapshot(UUID.randomUUID().toString());sourceRequest.with("payload").put("approvalStatus","ОплаченПолностью").put("bankStatus","REPORTED_UNVERIFIED");event(sourceRequest);
        UUID id=(UUID)tasks.requests(null,true).getFirst().get("id");tx.executeWithoutResult(s->tasks.saveMapping(payment,mapping()));
        jdbc.update("update contract_payments set status='PAID' where id=?",payment);
        assertThatThrownBy(()->tx.execute(s->tasks.link(id,payment))).hasMessageContaining("unpaid");
        jdbc.update("update contract_payments set status='DRAFT' where id=?",payment);
        var wrong=mapping().put("nativeSupplierId",UUID.randomUUID().toString());tx.executeWithoutResult(s->tasks.saveMapping(payment,wrong));
        assertThatThrownBy(()->tx.execute(s->tasks.link(id,payment))).hasMessageContaining("supplier or contract");
        assertThat(tasks.request(id).get("paidAmount")).isEqualTo("0");
    }
    @Test void sourceSupplierCorrectionIsDurablyQuarantinedAndCannotPayOriginalInvoice() {
        String nativeId=UUID.randomUUID().toString();event(snapshot(nativeId));UUID id=(UUID)tasks.requests(null,true).getFirst().get("id");
        tx.executeWithoutResult(s->tasks.saveMapping(payment,mapping()));tx.executeWithoutResult(s->tasks.link(id,payment));
        var correction=snapshot(nativeId).put("revision",2);correction.with("payload").put("counterpartyId",UUID.randomUUID().toString());event(correction);
        assertThat(tasks.request(id).get("quarantined")).isEqualTo(true);
        assertThatThrownBy(()->event(fact(nativeId,"must-not-pay","100","PAYMENT.SETTLED"))).hasMessageContaining("quarantined");
        assertThat(tasks.request(id).get("paidAmount")).isEqualTo("0");
        assertThat(jdbc.queryForObject("select paid_amount from contract_invoices where id=?",java.math.BigDecimal.class,invoice)).isEqualByComparingTo("0");
    }
    @Test void nativeRejectionRetriesSameImmutableTaskAndDoesNotBecomeMoney() {
        tx.executeWithoutResult(s->tasks.saveMapping(payment,mapping()));tx.executeWithoutResult(s->tasks.enqueue(payment,UUID.randomUUID(),mapper.createObjectNode()));
        var task=poll("worker-a").path("tasks").get(0);UUID id=UUID.fromString(task.path("taskId").asText());
        var rejected=base().put("workerId","worker-a").put("leaseToken",task.path("leaseToken").asText()).put("eventId",task.path("eventId").asText()).put("payloadHash",task.path("payloadHash").asText()).put("status","REJECTED").put("message","Native validation failed");rejected.putObject("payload");
        tx.executeWithoutResult(s->tasks.result(id,rejected));tx.executeWithoutResult(s->tasks.result(id,rejected));
        assertThat(tasks.requests(payment,false).getFirst().get("deliveryStatus")).isEqualTo("FAILED");
        tx.executeWithoutResult(s->tasks.retry(id));var renewed=poll("worker-b").path("tasks").get(0);
        assertThat(renewed.path("taskId")).isEqualTo(task.path("taskId"));assertThat(renewed.path("payloadHash")).isEqualTo(task.path("payloadHash"));assertThat(renewed.path("leaseToken")).isNotEqualTo(task.path("leaseToken"));
        assertThatThrownBy(()->tx.execute(s->tasks.result(id,rejected))).hasMessageContaining("lease");
        assertThat(jdbc.queryForObject("select count(*) from treasury_native_settlement",Integer.class)).isZero();
    }
    @Test void unknownRequestAndReversalOverrunDoNotAlterTotals() {
        assertThatThrownBy(()->event(fact(UUID.randomUUID().toString(),"unknown","10","PAYMENT.SETTLED"))).hasMessageContaining("REQUEST.UPSERT");
        String nativeId=UUID.randomUUID().toString();event(snapshot(nativeId));event(fact(nativeId,"bank:40","40","PAYMENT.SETTLED"));
        var tooMuch=fact(nativeId,"refund:50","50","PAYMENT.REVERSED");tooMuch.with("payload").put("originalPaymentId","BANK:40");assertThatThrownBy(()->event(tooMuch)).hasMessageContaining("exceeds");
        assertThat(tasks.requests(null,true).getFirst().get("paidAmount")).isEqualTo("40");
    }
    @Test void twoConcurrentWorkersLeaseOnlyOneTask() throws Exception {
        tx.executeWithoutResult(s->tasks.saveMapping(payment,mapping()));tx.executeWithoutResult(s->tasks.enqueue(payment,UUID.randomUUID(),mapper.createObjectNode()));
        var gate=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Integer> work=()->{TenantContext.setTenant(tenant);CompanyContext.setCompany(company);try{gate.await();return poll("worker-"+Thread.currentThread().threadId()).path("tasks").size();}finally{TenantContext.clear();CompanyContext.clear();}};
            var a=pool.submit(work);var b=pool.submit(work);gate.countDown();assertThat(a.get()+b.get()).isEqualTo(1);
        }finally{pool.shutdownNow();}
        assertThat(jdbc.queryForObject("select count(*) from treasury_native_task where state='LEASED'",Integer.class)).isEqualTo(1);
    }
    UUID queuedSourceEvent(){UUID event=UUID.randomUUID();jdbc.update("insert into integration_events(id,tenant_id,company_id,created_at,name,status,event_type,aggregate_type,aggregate_id,idempotency_key) values(?,?,?,current_timestamp,'Native Treasury source','NEW','TREASURY.PAYMENT_REQUEST','TREASURY',?,?)",event,tenant,company,payment,event.toString());tx.executeWithoutResult(s->tasks.saveMapping(payment,mapping()));tx.executeWithoutResult(s->tasks.enqueue(payment,event,mapper.createObjectNode()));return event;}
    ObjectNode createdResult(JsonNode task){String nativeId=UUID.randomUUID().toString();var r=base().put("workerId","worker-a").put("leaseToken",task.path("leaseToken").asText()).put("eventId",task.path("eventId").asText()).put("payloadHash",task.path("payloadHash").asText()).put("status","CREATED").put("nativeRequestId",nativeId).put("nativeNumber","TR-CREATED");r.set("payload",snapshot(nativeId).path("payload"));return r;}
    @Test void nativeAcknowledgementRepairsSourceDeliveryIdempotentlyWithoutChangingFinancialFacts() {
        UUID event=queuedSourceEvent();var task=poll("worker-a").path("tasks").get(0);UUID taskId=UUID.fromString(task.path("taskId").asText());var result=createdResult(task);
        tx.executeWithoutResult(s->tasks.result(taskId,result));
        assertThat(jdbc.queryForObject("select status from integration_events where id=?",String.class,event)).isEqualTo("DELIVERED");
        assertThat(jdbc.queryForObject("select external_id from integration_events where id=?",String.class,event)).isEqualTo(result.path("nativeRequestId").asText());
        assertThat(jdbc.queryForObject("select count(*) from treasury_native_settlement",Integer.class)).isZero();
        event(fact(result.path("nativeRequestId").asText(),"bank:40","40","PAYMENT.SETTLED"));
        Long version=jdbc.queryForObject("select version from integration_events where id=?",Long.class,event);
        tx.executeWithoutResult(s->tasks.result(taskId,result));assertThat(jdbc.queryForObject("select version from integration_events where id=?",Long.class,event)).isEqualTo(version);
        jdbc.update("update integration_events set status='NEW',external_id=null,last_error='Historical delivery gap',next_retry_at=current_timestamp where id=?",event);
        tx.executeWithoutResult(s->tasks.result(taskId,result));
        assertThat(jdbc.queryForObject("select status from integration_events where id=?",String.class,event)).isEqualTo("DELIVERED");
        assertThat(jdbc.queryForObject("select last_error from integration_events where id=?",String.class,event)).isNull();
        assertThat(jdbc.queryForObject("select next_retry_at from integration_events where id=?",java.sql.Timestamp.class,event)).isNull();
        assertThat(jdbc.queryForObject("select paid_amount from contract_invoices where id=?",java.math.BigDecimal.class,invoice)).isEqualByComparingTo("40");
        assertThat(jdbc.queryForObject("select count(*) from treasury_native_task",Integer.class)).isEqualTo(1);
    }
    @Test void rejectedDeliveryFailsSourceEventAndNativeRetryPreservesSourceIdentity() {
        UUID event=queuedSourceEvent();var task=poll("worker-a").path("tasks").get(0);UUID taskId=UUID.fromString(task.path("taskId").asText());
        var rejection=base().put("workerId","worker-a").put("leaseToken",task.path("leaseToken").asText()).put("eventId",task.path("eventId").asText()).put("payloadHash",task.path("payloadHash").asText()).put("status","REJECTED").put("message","Native validation rejected draft");rejection.putObject("payload");
        tx.executeWithoutResult(s->tasks.result(taskId,rejection));assertThat(jdbc.queryForObject("select status from integration_events where id=?",String.class,event)).isEqualTo("FAILED");
        tx.executeWithoutResult(s->tasks.retry(taskId));assertThat(jdbc.queryForObject("select status from integration_events where id=?",String.class,event)).isEqualTo("NEW");
        var retry=poll("worker-a").path("tasks").get(0);assertThat(retry.path("eventId").asText()).isEqualTo(event.toString());assertThat(retry.path("taskId")).isEqualTo(task.path("taskId"));
        var created=createdResult(retry);tx.executeWithoutResult(s->tasks.result(taskId,created));
        assertThat(jdbc.queryForObject("select status from integration_events where id=?",String.class,event)).isEqualTo("DELIVERED");
        assertThat(jdbc.queryForObject("select count(*) from integration_events",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select paid_amount from contract_invoices where id=?",java.math.BigDecimal.class,invoice)).isEqualByComparingTo("0");
    }
    @Test void cachedAcknowledgementCannotUpdateWrongTenantCompanyOrAggregateEvent() {
        UUID event=queuedSourceEvent();var task=poll("worker-a").path("tasks").get(0);UUID taskId=UUID.fromString(task.path("taskId").asText());var result=createdResult(task);tx.executeWithoutResult(s->tasks.result(taskId,result));
        for(String column:List.of("tenant_id","company_id","aggregate_type","event_type","aggregate_id","direction")) {
            Object correct=switch(column){case "tenant_id"->tenant;case "company_id"->company;case "aggregate_type"->"TREASURY";case "event_type"->"TREASURY.PAYMENT_REQUEST";case "aggregate_id"->payment;default->"OUTBOUND";};
            Object wrong=Set.of("company_id","aggregate_id").contains(column)?UUID.randomUUID():"OTHER";
            jdbc.update("update integration_events set status='NEW',"+column+"=? where id=?",wrong,event);tx.executeWithoutResult(s->tasks.result(taskId,result));
            assertThat(jdbc.queryForObject("select status from integration_events where id=?",String.class,event)).isEqualTo("NEW");jdbc.update("update integration_events set "+column+"=? where id=?",correct,event);
        }
        tx.executeWithoutResult(s->tasks.result(taskId,result));assertThat(jdbc.queryForObject("select status from integration_events where id=?",String.class,event)).isEqualTo("DELIVERED");
    }
}
