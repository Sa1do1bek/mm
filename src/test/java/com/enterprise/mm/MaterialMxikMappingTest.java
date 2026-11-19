package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.entity.MXIK;
import com.enterprise.mm.entity.Manufacturer;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.MaterialGroup;
import com.enterprise.mm.entity.TnvedCode;
import com.enterprise.mm.entity.UnitOfMeasure;
import java.time.LocalDateTime;
import java.util.UUID;
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;

class MaterialMxikMappingTest {
    @Test
    void materialGraphTreatsMissingMxikAsNullAndPreservesExistingMxik() {
        var configuration = new Configuration()
            .addAnnotatedClass(Material.class)
            .addAnnotatedClass(MXIK.class)
            .addAnnotatedClass(MaterialGroup.class)
            .addAnnotatedClass(UnitOfMeasure.class)
            .addAnnotatedClass(Manufacturer.class)
            .addAnnotatedClass(TnvedCode.class)
            .setProperty("hibernate.connection.driver_class", "org.h2.Driver")
            .setProperty("hibernate.connection.url", "jdbc:h2:mem:mxik-" + UUID.randomUUID() + ";MODE=PostgreSQL")
            .setPhysicalNamingStrategy(new CamelCaseToUnderscoresNamingStrategy())
            .setProperty("hibernate.hbm2ddl.halt_on_error", "true")
            .setProperty("hibernate.hbm2ddl.auto", "create-drop");

        try (var factory = configuration.buildSessionFactory()) {
            Material missing = material("MISSING", "07326001057000000");
            Material existing = material("EXISTING", "08303001001000000");
            Material unclassified = material("UNCLASSIFIED", null);
            Long mxikId;
            try (var session = factory.openSession()) {
                var transaction = session.beginTransaction();
                MXIK mxik = new MXIK();
                mxik.setKod(existing.getMxikCode());
                mxik.setName("Safe");
                mxik.setType("GOODS");
                session.persist(mxik);
                session.persist(missing);
                session.persist(existing);
                session.persist(unclassified);
                transaction.commit();
                mxikId = mxik.getId();
            }

            try (var session = factory.openSession()) {
                var graph = session.createEntityGraph(Material.class);
                graph.addAttributeNodes("mxik", "materialGroup", "unitOfMeasure", "replacementMaterial", "manufacturerRef");
                var loaded = session.createQuery("select m from Material m", Material.class)
                    .setHint("jakarta.persistence.fetchgraph", graph).getResultList();

                Material missingResult = loaded.stream().filter(m -> m.getId().equals(missing.getId())).findFirst().orElseThrow();
                assertThat(missingResult.getMxik()).isNull();
                assertThat(missingResult.getMxikCode()).isEqualTo("07326001057000000");
                Material existingResult = loaded.stream().filter(m -> m.getId().equals(existing.getId())).findFirst().orElseThrow();
                assertThat(existingResult.getMxik().getId()).isEqualTo(mxikId);
                assertThat(existingResult.getMxik().getType()).isEqualTo("GOODS");
                Material unclassifiedResult = loaded.stream().filter(m -> m.getId().equals(unclassified.getId())).findFirst().orElseThrow();
                assertThat(unclassifiedResult.getMxik()).isNull();
            }
        }
    }

    private Material material(String code, String mxikCode) {
        Material material = new Material();
        material.setTenantId("default");
        material.setCompanyId(UUID.randomUUID());
        material.setCreatedAt(LocalDateTime.now());
        material.setCode(code);
        material.setName(code);
        material.setMxikCode(mxikCode);
        return material;
    }
}
