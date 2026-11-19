package com.enterprise.mm.treasury;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** The inherited PostgreSQL protocol suite also runs against actual Spring transaction proxies. */
class TreasurySpringProxyPostgresTest extends TreasuryNativePostgresTest {
    AnnotationConfigApplicationContext context;
    @Configuration(proxyBeanMethods=false)
    @EnableTransactionManagement(proxyTargetClass=true)
    static class Transactions {}
    @BeforeEach void bindRealSpringTransactionProxies() {
        context=new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("treasury-test",Map.of(
            "mm.treasury.native-enabled","true","mm.treasury.key","12345678901234567890123456789012",
            "mm.treasury.tenant",tenant,"mm.treasury.company",company.toString(),"mm.treasury.source-base-id",source,
            "mm.treasury.native-organization-id",organization.toString(),"mm.treasury.allow-local-http","true")));
        context.registerBean(JdbcTemplate.class,()->jdbc);context.registerBean(ObjectMapper.class,()->mapper);
        context.registerBean("transactionManager",PlatformTransactionManager.class,()->new DataSourceTransactionManager(jdbc.getDataSource()));
        context.register(Transactions.class,TreasuryNativeAccess.class,TreasuryTaskService.class,TreasurySettlementService.class,TreasuryEventService.class);
        context.refresh();tasks=context.getBean(TreasuryTaskService.class);events=context.getBean(TreasuryEventService.class);
        assertThat(AopUtils.isCglibProxy(tasks)).isTrue();assertThat(AopUtils.isCglibProxy(events)).isTrue();
    }
    @AfterEach void closeSpring(){if(context!=null)context.close();}
    @Test void transactionalProxyCommitsSnapshotAndRollsBackInvalidExecutionWithoutExternalTransaction() {
        String nativeId=UUID.randomUUID().toString();events.receive(snapshot(nativeId));
        assertThat(jdbc.queryForObject("select count(*) from treasury_native_event",Integer.class)).isEqualTo(1);
        var wrongCurrency=fact(nativeId,"invalid:usd","40","PAYMENT.SETTLED");wrongCurrency.with("payload").put("currency","USD");
        assertThatThrownBy(()->events.receive(wrongCurrency)).hasMessageContaining("currency");
        assertThat(jdbc.queryForObject("select count(*) from treasury_native_event",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from treasury_native_settlement",Integer.class)).isZero();
        events.receive(fact(nativeId,"valid:40","40","PAYMENT.SETTLED"));
        assertThat(tasks.requests(null,true).getFirst().get("paidAmount")).isEqualTo("40");
    }
}
