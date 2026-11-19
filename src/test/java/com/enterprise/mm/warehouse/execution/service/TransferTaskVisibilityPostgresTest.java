package com.enterprise.mm.warehouse.execution.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.TransferDocument;
import com.enterprise.mm.entity.TransferDocumentLine;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.TransferDocumentLineRepository;
import com.enterprise.mm.repository.TransferDocumentRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.warehouse.execution.payload.WarehouseExecutionDtos.DocumentType;
import com.enterprise.mm.warehouse.execution.payload.WarehouseExecutionDtos.QueueFilter;
import com.enterprise.mm.warehouse.execution.payload.WarehouseExecutionDtos.QueueResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {"app.seed.enabled=false", "spring.flyway.enabled=true",
    "spring.jpa.hibernate.ddl-auto=validate", "app.firebase.enabled=false", "mm.integrations.cbu.enabled=false"})
@ActiveProfiles("test")
@EntityScan(basePackages = {"com.enterprise.mm.entity", "com.enterprise.mm.procurement.command.entity",
    "com.enterprise.mm.supply.workspace.entity", "com.enterprise.mm.common", "com.enterprise.mm.warehouse"})
@Testcontainers
@Transactional
@WithMockUser(roles = "ADMIN")
class TransferTaskVisibilityPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }
    @Autowired WarehouseExecutionWorkspaceService workspace;
    @Autowired TransferDocumentRepository transfers;
    @Autowired TransferDocumentLineRepository lines;
    @Autowired WarehouseRepository warehouses;
    @Autowired MaterialRepository materials;
    UUID source;
    UUID destination;
    UUID material;

    @BeforeEach void setup() {
        TenantContext.setTenant("transfer-task-test");
        CompanyContext.setCompany(UUID.randomUUID());
        source = warehouse("Sending warehouse");
        destination = warehouse("Receiving warehouse");
        var item = new Material(); item.setName("Test bearing");
        material = materials.saveAndFlush(item).getId();
    }
    @AfterEach void clear() { TenantContext.clear(); CompanyContext.clear(); }

    @Test void draftAndPendingTransfersOnlyBelongToSendingWarehouseTasks() {
        var draft = transfer("DRAFT", "0");
        var submitted = transfer("SUBMITTED", "0");
        var approved = transfer("APPROVED", "0");
        assertThat(queue(source, "tasks", 0, 10).items()).extracting(row -> row.document().id())
            .containsExactlyInAnyOrder(draft.getId(), submitted.getId(), approved.getId());
        assertThat(queue(destination, "tasks", 0, 10).totalElements()).isZero();
        assertThat(queue(destination, null, 0, 10).items()).isEmpty();
        var summary = workspace.overview(destination).queues().stream().filter(q -> q.documentType() == DocumentType.TRANSFER).findFirst().orElseThrow();
        assertThat(summary.count()).isZero();
        assertThat(summary.appliedFilters().action()).isEqualTo("tasks");
        var draftItem = queue(source, "tasks", 0, 10).items().stream().filter(row -> row.document().id().equals(draft.getId())).findFirst().orElseThrow();
        assertThat(draftItem.nextAction()).isEqualTo("SUBMIT");
        assertThat(draftItem.blockerMessage()).isNull();
    }

    @Test void destinationTasksStartInTransitAndDisappearWhenNothingRemainsToReceive() {
        var incoming = transfer("IN_TRANSIT", "4");
        var discrepancy = transfer("DISCREPANCY_REVIEW", "1");
        transfer("IN_TRANSIT", "0");
        transfer("RECEIVED", "0");
        transfer("CLOSED", "0");
        var first = queue(destination, "tasks", 0, 1);
        var second = queue(destination, "tasks", 1, 1);
        assertThat(first.totalElements()).isEqualTo(2);
        assertThat(second.totalElements()).isEqualTo(2);
        assertThat(List.of(first.items().getFirst().document().id(), second.items().getFirst().document().id()))
            .containsExactlyInAnyOrder(incoming.getId(), discrepancy.getId());
        assertThat(workspace.overview(destination).queues().stream().filter(q -> q.documentType() == DocumentType.TRANSFER).findFirst().orElseThrow().count()).isEqualTo(2);
    }

    @Test void dispatchAndReceiptQueuesRespectTheWarehouseDirection() {
        var incoming = transfer("IN_TRANSIT", "4");
        assertThat(queue(source, "dispatch", 0, 10).items()).extracting(row -> row.document().id()).containsExactly(incoming.getId());
        assertThat(queue(destination, "dispatch", 0, 10).items()).isEmpty();
        assertThat(queue(destination, "receive", 0, 10).items()).extracting(row -> row.document().id()).containsExactly(incoming.getId());
        assertThat(queue(source, "receive", 0, 10).items()).isEmpty();
        assertThat(queue(warehouse("Unrelated warehouse"), "tasks", 0, 10).items()).isEmpty();
    }

    private QueueResponse queue(UUID warehouse, String action, int page, int size) {
        return workspace.queue(DocumentType.TRANSFER, new QueueFilter(null, List.of(), warehouse, action), PageRequest.of(page, size));
    }
    private UUID warehouse(String name) {
        var warehouse = new Warehouse(); warehouse.setName(name);
        return warehouses.saveAndFlush(warehouse).getId();
    }
    private TransferDocument transfer(String status, String inTransit) {
        var transfer = new TransferDocument(); transfer.setCode("TR-" + UUID.randomUUID()); transfer.setName("Warehouse transfer");
        transfer.setStatus(status); transfer.setSourceWarehouseId(source); transfer.setDestinationWarehouseId(destination);
        transfer = transfers.saveAndFlush(transfer);
        var line = new TransferDocumentLine(); line.setName("Test bearing"); line.setTransferDocumentId(transfer.getId()); line.setMaterialId(material);
        line.setPlannedQuantity(BigDecimal.TEN); line.setDispatchedQuantity(new BigDecimal(inTransit)); line.setInTransitQuantity(new BigDecimal(inTransit));
        lines.saveAndFlush(line);
        return transfer;
    }
}
