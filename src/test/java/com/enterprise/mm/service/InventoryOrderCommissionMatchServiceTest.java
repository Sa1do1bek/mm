package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.repository.CommissionMemberRepository;
import com.enterprise.mm.repository.CommissionRepository;
import com.enterprise.mm.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InventoryOrderCommissionMatchServiceTest {
    private final UUID company = UUID.randomUUID();
    private final CommissionRepository commissions = mock(CommissionRepository.class);
    private final CommissionMemberRepository members = mock(CommissionMemberRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final InventoryOrderCommissionMatchService service = new InventoryOrderCommissionMatchService(commissions, members, users);

    @BeforeEach
    void setup() {
        TenantContext.setTenant("tenant-a");
        CompanyContext.setCompany(company);
        when(commissions.findByTenantIdAndCompanyIdAndIsDeletedFalse("tenant-a", company)).thenReturn(List.of());
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void resolvesUniquePunctuationVariationWithinCurrentCompany() throws Exception {
        User user = user("A. B. Karimov");
        when(users.findAllByTenantIdAndCompanyIdAndIsDeletedFalse("tenant-a", company)).thenReturn(List.of(user));
        var recommendation = service.match(analysis()).get(0);
        assertThat(recommendation.createMembers().get(0).userId()).isEqualTo(user.getId());
        assertThat(recommendation.createMembers().get(0).resolved()).isTrue();
    }

    @Test
    void leavesAmbiguousNameForManualSelection() throws Exception {
        when(users.findAllByTenantIdAndCompanyIdAndIsDeletedFalse("tenant-a", company))
            .thenReturn(List.of(user("A. B. Karimov"), user("A-B Karimov")));
        var recommendation = service.match(analysis()).get(0);
        assertThat(recommendation.createMembers().get(0).userId()).isNull();
        assertThat(recommendation.createMembers().get(0).resolved()).isFalse();
    }

    private User user(String name) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setFullName(name);
        user.setEnabled(true);
        return user;
    }

    private com.fasterxml.jackson.databind.JsonNode analysis() throws Exception {
        return new ObjectMapper().readTree("""
            {"orderAnalysis":{"schemaVersion":2,"commissionRosters":[{
              "sourceKey":"roster:1","name":"Local commission","kind":"LOCAL",
              "rosterCompleteness":"COMPLETE","createEligible":true,
              "members":[{"fullName":"A B Karimov","erpRoleSuggestion":"CHAIRMAN"},
                         {"fullName":"Second Person","erpRoleSuggestion":"MEMBER"}]
            }]}}
            """);
    }
}
