package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;

class ProcurementPlanBackfillMigrationTest {
    @Test
    void backfillsOnlyExplicitScopedUnambiguousCampaignLinksAndIsIdempotent() throws Exception {
        try (var db = DriverManager.getConnection("jdbc:h2:mem:backfill;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"); var s = db.createStatement()) {
            s.execute("create table procurement_plans(id varchar primary key,tenant_id varchar,company_id varchar,is_delete boolean)");
            s.execute("create table procurement_campaigns(id varchar primary key,tenant_id varchar,company_id varchar,is_delete boolean,procurement_plan_id varchar,procurement_procedure_id varchar)");
            s.execute("create table procurement_procedures(id varchar primary key,tenant_id varchar,company_id varchar,is_delete boolean,procurement_plan_id varchar,campaign_id varchar)");
            s.execute("insert into procurement_plans values ('plan1','t','c',false),('plan2','t','c',false),('deleted','t','c',true)");
            s.execute("insert into procurement_campaigns values ('good','t','c',false,'plan1','p1'),('other-company','t','other',false,'plan1',null),('conflict','t','c',false,'plan1','another'),('deleted-plan','t','c',false,'deleted',null),('removed','t','c',true,'plan1',null)");
            s.execute("insert into procurement_procedures values ('p1','t','c',false,null,'good'),('p2','t','c',false,null,'other-company'),('p3','t','c',false,null,'conflict'),('p4','t','c',false,null,'deleted-plan'),('p5','t','c',false,null,'removed'),('p6','t','c',false,'plan2','good'),('p7','t','c',true,null,'good')");
            String sql = Files.readString(Path.of("src/main/resources/db/migration/V99__backfill_procurement_procedure_plan.sql"));
            assertThat(s.executeUpdate(sql)).isEqualTo(1);
            assertThat(s.executeUpdate(sql)).isZero();
            try (var r = s.executeQuery("select id,procurement_plan_id from procurement_procedures order by id")) {
                assertThat(r.next()).isTrue(); assertThat(r.getString(2)).isEqualTo("plan1");
                for (int i = 2; i <= 5; i++) { assertThat(r.next()).isTrue(); assertThat(r.getString(2)).isNull(); }
                assertThat(r.next()).isTrue(); assertThat(r.getString(2)).isEqualTo("plan2");
                assertThat(r.next()).isTrue(); assertThat(r.getString(2)).isNull();
            }
        }
    }
}
