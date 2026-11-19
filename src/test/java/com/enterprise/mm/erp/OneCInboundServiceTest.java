package com.enterprise.mm.erp;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.payload.StockMovementResponse;
import com.enterprise.mm.service.StockMovementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class OneCInboundServiceTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final ObjectMapper mapper=new ObjectMapper();
    private final StockMovementService stock=mock(StockMovementService.class);
    private final OneCInboundService service=new OneCInboundService(jdbc,mapper,stock);
    private final UUID company=UUID.randomUUID(), document=UUID.randomUUID(), eventId=UUID.randomUUID(), material=UUID.randomUUID(), warehouse=UUID.randomUUID();
    @BeforeEach void context() { TenantContext.setTenant("native-test"); CompanyContext.setCompany(company); }
    @AfterEach void clear() { TenantContext.clear(); CompanyContext.clear(); }
    private com.fasterxml.jackson.databind.JsonNode event() {
        return mapper.valueToTree(Map.of("protocolVersion",1,"eventId",eventId,"documentId",document,"tenantId","native-test","companyId",company,
            "documentKind","RECEIPT","postingDate","2025-04-17","posted",true,
            "lines",List.of(Map.of("materialId",material,"warehouseId",warehouse,"quantity","0.25","amount","6250"))));
    }
    @Test void importsThroughNormalStockPostingAndKeepsTheNativeReference() {
        when(jdbc.queryForObject(contains("select exists"),eq(Boolean.class),any(),any(),any())).thenReturn(true);
        when(stock.importOneCApiMovement(any())).thenAnswer(call -> { var response=new StockMovementResponse(); response.setTotalCost(new BigDecimal("6250")); return response; });
        assertThat(service.receive(event(),eventId.toString()).get("status")).isEqualTo("APPLIED");
        var movement=ArgumentCaptor.forClass(StockMovement.class); verify(stock).importOneCApiMovement(movement.capture());
        assertThat(movement.getValue().getReferenceType()).isEqualTo("ONE_C_API");
        assertThat(movement.getValue().getReferenceId()).isEqualTo(document);
        assertThat(movement.getValue().getQuantity()).isEqualByComparingTo("0.25");
        assertThat(movement.getValue().getUnitCost()).isEqualByComparingTo("25000");
    }
    @Test void duplicateAcknowledgesWithoutPostingAgain() throws Exception {
        var body=event(); String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8)));
        when(jdbc.queryForList(anyString(),eq("native-test"),eq(company),eq("RECEIPT"),eq(document))).thenReturn(List.of(Map.of("event_id",eventId,"payload_hash",hash)));
        assertThat(service.receive(body,eventId.toString()).get("replayed")).isEqualTo(true);
        verifyNoInteractions(stock);
    }
    @Test void changedReplayDoesNotApplyAnotherStockMovement() {
        when(jdbc.queryForList(anyString(),eq("native-test"),eq(company),eq("RECEIPT"),eq(document))).thenReturn(List.of(Map.of("event_id",eventId,"payload_hash","different")));
        assertThatThrownBy(() -> service.receive(event(),eventId.toString())).isInstanceOf(ConflictException.class);
        verifyNoInteractions(stock);
    }
}
