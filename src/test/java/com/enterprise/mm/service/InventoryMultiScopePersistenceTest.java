package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.InventoryDocument;
import com.enterprise.mm.enums.InventoryScopeMode;
import com.enterprise.mm.repository.InventoryDocumentRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {"app.seed.enabled=false", "app.scheduling.enabled=false", "app.firebase.enabled=false"})
@ActiveProfiles("test")
class InventoryMultiScopePersistenceTest {
    @Autowired InventoryDocumentRepository documents;
    @Autowired EntityManager entityManager;

    @AfterEach void clear() { TenantContext.clear(); CompanyContext.clear(); }

    @Test @Transactional
    void resolvedWarehouseSetSurvivesReloadAndWarehouseSearch() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        UUID department = UUID.randomUUID(), first = UUID.randomUUID(), second = UUID.randomUUID();
        var document = new InventoryDocument();
        document.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        document.setName("Multi warehouse persistence");
        document.setCode("MULTI-" + UUID.randomUUID().toString().substring(0, 8));
        document.setStatus("PLANNED");
        document.setScopeMode(InventoryScopeMode.DEPARTMENTS);
        document.setDepartmentIds(Set.of(department));
        document.setResolvedWarehouseIds(Set.of(first, second));
        document = documents.saveAndFlush(document);
        UUID id = document.getId();
        entityManager.clear();

        assertThat(documents.findById(id).orElseThrow().getResolvedWarehouseIds()).containsExactlyInAnyOrder(first, second);
        assertThat(search(first)).contains(id);
        assertThat(search(second)).contains(id);
    }

    private List<UUID> search(UUID warehouse) {
        return documents.search("default", CompanyContext.DEFAULT_COMPANY_ID, false, null, null, null,
            List.of("PLANNED"), false, warehouse, List.of(warehouse), false, true, false, UUID.randomUUID(),
            PageRequest.of(0, 20)).map(InventoryDocument::getId).getContent();
    }
}
