package com.enterprise.mm.erp;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class ErpFifoPostgresTest {
    @Test @EnabledIfEnvironmentVariable(named="ERP_FIFO_TEST_POSTGRES_URL",matches=".+")
    void delayedAndHeldHeadBlockOnlyTheirCompanyAndConcurrentWorkersCannotOvertake() throws Exception {
        String url=System.getenv("ERP_FIFO_TEST_POSTGRES_URL");
        if (!url.equals("jdbc:postgresql://127.0.0.1:15439/mm_erp_review_fifo_20261115")) throw new IllegalArgumentException("FIFO test requires isolated review DB");
        var ds=new DriverManagerDataSource(url,"mm_local",""); var jdbc=new JdbcTemplate(ds);
        var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.execute("drop table if exists erp_exchange_envelope");
        jdbc.execute("drop table if exists erp_exchange_connection");
        jdbc.execute("drop sequence if exists erp_exchange_delivery_order_seq");
        jdbc.execute("create table erp_exchange_connection (tenant_id text,company_id uuid,base_url text,enabled boolean)");
        jdbc.execute("create table erp_exchange_envelope (id uuid primary key,tenant_id text,company_id uuid,payload text,confirm_requested boolean default false,remote_document_ref uuid,remote_posted boolean,operation text,delivery_state text,next_attempt_at timestamp,created_at timestamp,rejection_reason text,attempt_count integer default 0)");
        UUID company=UUID.randomUUID(), other=UUID.randomUUID(), first=UUID.randomUUID(), second=UUID.randomUUID(), independent=UUID.randomUUID(), reference=UUID.randomUUID();
        var requests=Collections.synchronizedList(new ArrayList<String>()); var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/mm/v1/documents",ex -> {
            String id=ex.getRequestHeaders().getFirst("Idempotency-Key"); String path=ex.getRequestURI().getPath(); requests.add(id+":"+path);
            boolean held=id.equals(first.toString()) && !path.endsWith("/confirm");
            if (held) { entered.countDown(); try { if (!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException(); } catch(InterruptedException interrupted){Thread.currentThread().interrupt();} }
            String status=held ? "AWAITING_CONFIRMATION":"POSTED";
            byte[] body=("{\"protocolVersion\":1,\"eventId\":\""+id+"\",\"status\":\""+status+"\",\"posted\":"+(!held)+",\"documentRef\":\""+reference+"\"}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200,body.length);try(var out=ex.getResponseBody()){out.write(body);}
        });
        server.start(); var pool=Executors.newFixedThreadPool(2);
        try {
            String base="http://127.0.0.1:"+server.getAddress().getPort();
            jdbc.update("insert into erp_exchange_connection values ('t',?,?,true),('t',?,?,true)",company,base,other,base);
            jdbc.update("insert into erp_exchange_envelope (id,tenant_id,company_id,payload,operation,delivery_state,next_attempt_at,created_at) values (?,'t',?,'{}','RECEIPT','RETRY',current_timestamp+interval '1 hour',current_timestamp-interval '3 minutes')",first,company);
            jdbc.update("insert into erp_exchange_envelope (id,tenant_id,company_id,payload,operation,delivery_state,next_attempt_at,created_at) values (?,'t',?,'{}','ISSUE','PENDING',current_timestamp,current_timestamp-interval '2 minutes')",second,company);
            jdbc.update("insert into erp_exchange_envelope (id,tenant_id,company_id,payload,operation,delivery_state,next_attempt_at,created_at) values (?,'t',?,'{}','RECEIPT','PENDING',current_timestamp,current_timestamp-interval '1 minute')",independent,other);
            new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/migration/V90__erp_delivery_order.sql")).execute(ds);
            assertThat(jdbc.queryForObject("select delivery_order from erp_exchange_envelope where id=?",Long.class,first)).isLessThan(jdbc.queryForObject("select delivery_order from erp_exchange_envelope where id=?",Long.class,second));
            jdbc.update("update erp_exchange_envelope set created_at=current_timestamp where id=?",first);
            jdbc.update("update erp_exchange_envelope set created_at=current_timestamp-interval '1 hour' where id=?",second);
            var worker=new ErpDeliveryWorker(jdbc,new ObjectMapper(),tx); worker.deliver();
            assertThat(requests).hasSize(1); assertThat(requests.get(0)).startsWith(independent.toString());
            worker.deliver(); assertThat(requests).hasSize(1);
            jdbc.update("update erp_exchange_envelope set next_attempt_at=current_timestamp where id=?",first);
            var companyLocked=new CountDownLatch(1); var unlockCompany=new CountDownLatch(1);
            Future<?> capture=pool.submit(() -> tx.executeWithoutResult(status -> {
                jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(cast(? as text) || ':' || cast(? as text), 0))",Object.class,"t",company);
                companyLocked.countDown(); try { if (!unlockCompany.await(5,TimeUnit.SECONDS)) throw new IllegalStateException(); } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }));
            assertThat(companyLocked.await(5,TimeUnit.SECONDS)).isTrue(); worker.deliver(); assertThat(requests).hasSize(1);
            unlockCompany.countDown(); capture.get(5,TimeUnit.SECONDS);
            Future<?> firstRun=pool.submit(worker::deliver); assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            Future<?> concurrent=pool.submit(worker::deliver); concurrent.get(5,TimeUnit.SECONDS); assertThat(requests).hasSize(2);
            release.countDown(); firstRun.get(5,TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("select delivery_state from erp_exchange_envelope where id=?",String.class,first)).isEqualTo("AWAITING_CONFIRMATION");
            worker.deliver(); assertThat(requests).hasSize(2);
            jdbc.update("update erp_exchange_envelope set confirm_requested=true where id=?",first);
            worker.deliver(); assertThat(requests).hasSize(3); assertThat(requests.get(2)).contains("/"+first+"/confirm");
            worker.deliver(); assertThat(requests).hasSize(4); assertThat(requests.get(3)).startsWith(second.toString());
        } finally { release.countDown();pool.shutdownNow();server.stop(0); }
    }
}
