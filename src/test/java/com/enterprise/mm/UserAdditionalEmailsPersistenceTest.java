package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Company;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.repository.CompanyRepository;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.service.ProfileSelfService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "app.seed.enabled=false")
@ActiveProfiles("test")
class UserAdditionalEmailsPersistenceTest {
    @Autowired private UserRepository users;
    @Autowired private CompanyRepository companies;
    @Autowired private ProfileSelfService profiles;
    @Autowired private EntityManager entityManager;

    @AfterEach
    void clearContexts() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    @Transactional
    void additionalAddressSurvivesReloadAndPrimarySwitch() {
        TenantContext.setTenant("multi-email-test");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        Company company = new Company();
        company.setName("Email test company");
        company.setStatus("ACTIVE");
        company = companies.saveAndFlush(company);
        CompanyContext.setCompany(company.getId());

        User user = new User();
        user.setName("Email test user");
        user.setUsername("multi-email-user");
        user.setPasswordHash("not-used-in-this-test");
        user.setStatus("ACTIVE");
        user.setEmail("first@example.com");
        user = users.saveAndFlush(user);

        profiles.addEmail(user, "second@example.com");
        entityManager.flush();
        entityManager.clear();

        User reloaded = users.findByIdAndIsDeletedFalse(user.getId()).orElseThrow();
        assertThat(reloaded.getAdditionalEmails()).containsExactly("second@example.com");
        assertThat(profiles.makePrimary(reloaded, "second@example.com"))
            .containsExactly(new ProfileSelfService.EmailAddress("second@example.com", true),
                new ProfileSelfService.EmailAddress("first@example.com", false));

        entityManager.flush();
        entityManager.clear();
        User switched = users.findByIdAndIsDeletedFalse(user.getId()).orElseThrow();
        assertThat(switched.getEmail()).isEqualTo("second@example.com");
        assertThat(switched.getAdditionalEmails()).containsExactly("first@example.com");
    }
}
