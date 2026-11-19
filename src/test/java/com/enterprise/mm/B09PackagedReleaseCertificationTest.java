package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.net.ServerSocket;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Boots the packaged production JAR against an empty database. Only the bootstrap identity uses SQL. */
@Testcontainers(disabledWithoutDocker = false)
@EnabledIfSystemProperty(named="mm.b09.live", matches="true")
class B09PackagedReleaseCertificationTest {
 @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
 @Test void certifyPackagedRelease() throws Exception {
  var dir=Path.of("../docs/reports/g2-b09-evidence").toAbsolutePath().normalize();Files.createDirectories(dir);
  var json=new ObjectMapper();var jar=Path.of("target/mm-0.1.0.jar").toAbsolutePath();assertThat(jar).exists();
  int port;try(var socket=new ServerSocket(0)){port=socket.getLocalPort();}
  var args=new ArrayList<String>(List.of(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Xmx1200m","-jar",jar.toString(),"--spring.profiles.active=prod", "--server.address=127.0.0.1", "--server.port="+port,"--spring.flyway.enabled=true","--spring.jpa.hibernate.ddl-auto=validate","--spring.jpa.open-in-view=false","--app.seed.enabled=false","--mm.pilot.enabled-capabilities=","--app.warehouse.inventory.workspace-enabled=true","--app.scheduling.enabled=false","--app.firebase.enabled=false","--mm.integrations.cbu.enabled=false","--mm.rfq-mail.enabled=false","--app.integrations.systems.fi.enabled=false","--app.integrations.systems.treasury.enabled=false","--app.integrations.systems.budgeting.enabled=false","--app.integrations.message-queue.enabled=false","--app.cors.allowed-origins=http://127.0.0.1:5189","--app.rate-limit.requests-per-minute=10000","--management.health.rabbit.enabled=false","--management.health.mail.enabled=false"));
  var builder=new ProcessBuilder(args);var env=builder.environment();env.put("SPRING_DATASOURCE_URL",PG.getJdbcUrl());env.put("SPRING_DATASOURCE_USERNAME",PG.getUsername());env.put("SPRING_DATASOURCE_PASSWORD",PG.getPassword());env.put("JWT_SECRET",UUID.randomUUID().toString()+UUID.randomUUID());env.put("RFQ_MAIL_CALLBACK_SECRET",UUID.randomUUID().toString());env.put("RFQ_MAGIC_LINK_PEPPER",UUID.randomUUID().toString());env.put("AI_API_KEY","");env.put("RFQ_SMTP_PASSWORD","");
  Path log=dir.resolve("packaged-server.log");builder.redirectErrorStream(true);builder.redirectOutput(log.toFile());Process server=builder.start();Process browser=null;
  try {
   boolean ready=false;for(int n=0;n<120;n++){if(!server.isAlive())break;if(Files.exists(log)&&Files.readString(log).contains("Started MmApplication")){ready=true;break;}Thread.sleep(1000);}assertThat(ready).as("Packaged server startup; see packaged-server.log").isTrue();
   String password="B09!"+UUID.randomUUID();UUID userId=UUID.randomUUID();
   try(var db=DriverManager.getConnection(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword())) {
    try(var insert=db.prepareStatement("insert into users(id,tenant_id,company_id,created_at,version,is_delete,code,name,status,username,password_hash,full_name,enabled,mfa_enabled,token_version) values (?,'default','00000000-0000-0000-0000-000000000001',now(),0,false,'B09-BOOTSTRAP','Release bootstrap','ACTIVE','b09.bootstrap',?,'Release bootstrap',true,false,0)")){insert.setObject(1,userId);insert.setString(2,new BCryptPasswordEncoder().encode(password));insert.executeUpdate();}
    try(var role=db.prepareStatement("insert into user_roles(user_id,role) values (?,'ADMIN')")){role.setObject(1,userId);role.executeUpdate();}
    var history=new ArrayList<Map<String,Object>>();try(var q=db.createStatement();var rs=q.executeQuery("select version,description,script,checksum,success from flyway_schema_history order by installed_rank")){while(rs.next()){var row=new LinkedHashMap<String,Object>();for(int i=1;i<=5;i++)row.put(rs.getMetaData().getColumnLabel(i),rs.getObject(i));history.add(row);}}
    assertThat(history).allSatisfy(row->assertThat(row.get("success")).isEqualTo(true));assertThat(history.stream().anyMatch(row->"3".equals(row.get("version")))).isTrue();json.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("packaged-migration-history.json").toFile(),history);
    try(var q=db.createStatement();var rs=q.executeQuery("select version()")){rs.next();json.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("packaged-runtime.json").toFile(),Map.of("postgres",rs.getString(1),"image",PG.getDockerImageName(),"backendSha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))),"command",args,"bootstrap","Only an ephemeral administrator identity is inserted after fresh migrations; all other live data is created through HTTP."));}
   }
   var node=new ProcessBuilder("node",dir.resolve("live-certification.cjs").toString());node.environment().put("MM_B09_API","http://127.0.0.1:"+port);node.environment().put("MM_B09_PASSWORD",password);node.redirectErrorStream(true);node.redirectOutput(dir.resolve("live-run.log").toFile());browser=node.start();assertThat(browser.waitFor(25,TimeUnit.MINUTES)).isTrue();assertThat(browser.exitValue()).as("Live API/browser certification; see live-run.log").isZero();
   var refs=json.readTree(dir.resolve("live-references.json").toFile());
   try(var db=DriverManager.getConnection(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword())) {
    var values=new LinkedHashMap<String,Object>();
    for(var entry:Map.of("issued","select sum(issued_quantity) from issue_request_lines where source_demand_plan_item_id=? and is_delete=false","procurement","select sum(allocated_quantity) from demand_plan_item_pr_allocations where demand_plan_item_id=? and is_delete=false","allocations","select count(*) from demand_plan_item_pr_allocations where demand_plan_item_id=? and is_delete=false").entrySet()){
     try(var q=db.prepareStatement(entry.getValue())){q.setObject(1,UUID.fromString(refs.get("demandItemId").asText()));try(var rs=q.executeQuery()){rs.next();values.put(entry.getKey(),rs.getBigDecimal(1));}}
    }
    assertThat((java.math.BigDecimal)values.get("issued")).isEqualByComparingTo("2");assertThat((java.math.BigDecimal)values.get("procurement")).isEqualByComparingTo("3");assertThat((java.math.BigDecimal)values.get("allocations")).isEqualByComparingTo("1");json.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("live-database-results.json").toFile(),values);
   }
  } finally {if(browser!=null&&browser.isAlive())browser.destroyForcibly();server.destroy();if(!server.waitFor(10,TimeUnit.SECONDS))server.destroyForcibly();}
 }
}
