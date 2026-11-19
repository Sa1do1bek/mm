package com.enterprise.mm.softdelete;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@DataJpaTest(properties = {
    "spring.flyway.enabled=false",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
class SoftDeleteRepositoryTest {
    @Autowired
    TestBusinessRecordRepository repository;

    @Test
    void activeReadMethodsIgnoreSoftDeletedRecordsById() {
        TestBusinessRecord active = repository.saveAndFlush(record("ACTIVE-1", false));
        TestBusinessRecord deleted = repository.saveAndFlush(record("DELETED-1", true));

        assertThat(repository.findByIdAndIsDeletedFalse(active.getId())).contains(active);
        assertThat(repository.findByIdAndIsDeletedFalse(deleted.getId())).isEmpty();
        assertThat(repository.existsByIdAndIsDeletedFalse(active.getId())).isTrue();
        assertThat(repository.existsByIdAndIsDeletedFalse(deleted.getId())).isFalse();
    }

    @Test
    void activeReadMethodsFilterCollectionsAndCounts() {
        TestBusinessRecord active = repository.saveAndFlush(record("ACTIVE-2", false));
        repository.saveAndFlush(record("DELETED-2", true));

        assertThat(repository.findAllByIsDeletedFalse()).containsExactly(active);
        assertThat(repository.countByIsDeletedFalse()).isEqualTo(1);
    }

    private TestBusinessRecord record(String code, boolean deleted) {
        TestBusinessRecord record = new TestBusinessRecord();
        record.setCode(code);
        record.setName(code);
        record.setStatus("ACTIVE");
        record.setCreatedAt(LocalDateTime.now());
        if (deleted) {
            record.softDelete();
        }
        return record;
    }

    interface TestBusinessRecordRepository extends org.springframework.data.jpa.repository.JpaRepository<TestBusinessRecord, UUID> {
        Optional<TestBusinessRecord> findByIdAndIsDeletedFalse(UUID id);

        List<TestBusinessRecord> findAllByIsDeletedFalse();

        long countByIsDeletedFalse();

        boolean existsByIdAndIsDeletedFalse(UUID id);
    }

    @Entity
    @Table(name = "test_business_records")
    static class TestBusinessRecord extends BusinessEntity {
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableJpaAuditing(auditorAwareRef = "auditorProvider")
    @EntityScan(basePackageClasses = TestBusinessRecord.class)
    @EnableJpaRepositories(
        considerNestedRepositories = true,
        basePackageClasses = TestBusinessRecordRepository.class
    )
    static class TestApplication {
    }

    @TestConfiguration
    static class AuditTestConfig {
        @Bean
        AuditorAware<String> auditorProvider() {
            return () -> Optional.of("test");
        }
    }
}
