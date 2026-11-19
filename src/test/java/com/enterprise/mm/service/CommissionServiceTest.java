package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Commission;
import com.enterprise.mm.entity.CommissionMember;
import com.enterprise.mm.enums.CommissionMemberRole;
import com.enterprise.mm.enums.CommissionType;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.mapper.CommissionMapper;
import com.enterprise.mm.payload.CommissionCreateRequest;
import com.enterprise.mm.payload.CommissionMemberRequest;
import com.enterprise.mm.payload.TenderCommissionResponse;
import com.enterprise.mm.repository.CommissionMemberRepository;
import com.enterprise.mm.repository.CommissionRepository;
import com.enterprise.mm.repository.EmployeeRepository;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.repository.TenderCommissionRepository;
import com.enterprise.mm.repository.UserRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CommissionServiceTest {
    private static final String TENANT = "tenant-a";
    private static final UUID COMPANY = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID COMMISSION_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID RFQ_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void createRequiresMembers() {
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(COMPANY);
        CommissionRepository repository = mock(CommissionRepository.class);
        CommissionMemberRepository memberRepository = mock(CommissionMemberRepository.class);
        CommissionService service = newService(
            repository,
            memberRepository,
            mock(TenderCommissionRepository.class),
            mock(RFQRepository.class),
            mock(TenderCommissionService.class)
        );
        when(repository.save(any(Commission.class))).thenAnswer(invocation -> {
            Commission commission = invocation.getArgument(0);
            commission.setId(COMMISSION_ID);
            return commission;
        });
        when(memberRepository.findByTenantIdAndCommissionIdAndIsDeletedFalseOrderByCreatedAtAsc(TENANT, COMMISSION_ID))
            .thenReturn(List.of());

        assertThatThrownBy(() -> service.create(createRequest(null, null)))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("commission members are required");
    }

    @Test
    void createWithoutRfqDoesNotAttach() {
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(COMPANY);
        CommissionRepository repository = mock(CommissionRepository.class);
        CommissionMemberRepository memberRepository = mock(CommissionMemberRepository.class);
        TenderCommissionRepository tenderCommissionRepository = mock(TenderCommissionRepository.class);
        TenderCommissionService tenderCommissionService = mock(TenderCommissionService.class);
        CommissionService service = newService(
            repository,
            memberRepository,
            tenderCommissionRepository,
            mock(RFQRepository.class),
            tenderCommissionService
        );
        when(repository.save(any(Commission.class))).thenAnswer(invocation -> {
            Commission commission = invocation.getArgument(0);
            commission.setId(COMMISSION_ID);
            return commission;
        });
        when(memberRepository.findByTenantIdAndCommissionIdAndIsDeletedFalseOrderByCreatedAtAsc(TENANT, COMMISSION_ID))
            .thenReturn(List.of());
        when(memberRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(tenderCommissionRepository.findByTenantIdAndCommissionIdAndActiveTrueAndIsDeletedFalse(TENANT, COMMISSION_ID))
            .thenReturn(List.of());

        var response = service.create(createRequest(List.of(memberRequest("Chair")), null));

        assertThat(response.getId()).isEqualTo(COMMISSION_ID);
        assertThat(response.getAssignedRfqIds()).isEmpty();
        verify(tenderCommissionService, never()).attachCommissionToRfq(any(), any());
        verify(memberRepository).saveAll(any());
    }

    @Test
    void createWithRfqAttachesTenderCommission() {
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(COMPANY);
        CommissionRepository repository = mock(CommissionRepository.class);
        CommissionMemberRepository memberRepository = mock(CommissionMemberRepository.class);
        TenderCommissionRepository tenderCommissionRepository = mock(TenderCommissionRepository.class);
        TenderCommissionService tenderCommissionService = mock(TenderCommissionService.class);
        CommissionService service = newService(
            repository,
            memberRepository,
            tenderCommissionRepository,
            mock(RFQRepository.class),
            tenderCommissionService
        );
        when(repository.save(any(Commission.class))).thenAnswer(invocation -> {
            Commission commission = invocation.getArgument(0);
            commission.setId(COMMISSION_ID);
            return commission;
        });
        when(memberRepository.findByTenantIdAndCommissionIdAndIsDeletedFalseOrderByCreatedAtAsc(TENANT, COMMISSION_ID))
            .thenReturn(List.of());
        when(memberRepository.saveAll(any())).thenAnswer(invocation -> {
            List<CommissionMember> members = invocation.getArgument(0);
            members.forEach(member -> member.setId(UUID.randomUUID()));
            return members;
        });
        when(tenderCommissionService.attachCommissionToRfq(RFQ_ID, COMMISSION_ID))
            .thenReturn(new TenderCommissionResponse());
        when(tenderCommissionRepository.findByTenantIdAndCommissionIdAndActiveTrueAndIsDeletedFalse(TENANT, COMMISSION_ID))
            .thenAnswer(invocation -> {
                com.enterprise.mm.entity.TenderCommission tc = new com.enterprise.mm.entity.TenderCommission();
                tc.setRfqId(RFQ_ID);
                tc.setCommissionId(COMMISSION_ID);
                return List.of(tc);
            });

        var response = service.create(createRequest(List.of(memberRequest("Chair")), RFQ_ID));

        assertThat(response.getAssignedRfqIds()).containsExactly(RFQ_ID);
        verify(tenderCommissionService).attachCommissionToRfq(eq(RFQ_ID), eq(COMMISSION_ID));
    }

    @Test
    void createInventoryRejectsRfqId() {
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(COMPANY);
        CommissionService service = newService(
            mock(CommissionRepository.class),
            mock(CommissionMemberRepository.class),
            mock(TenderCommissionRepository.class),
            mock(RFQRepository.class),
            mock(TenderCommissionService.class)
        );
        CommissionCreateRequest request = createRequest(List.of(memberRequest("Store")), RFQ_ID);
        request.setType(CommissionType.INVENTORY);

        assertThatThrownBy(() -> service.create(request))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("rfqId is only allowed for TENDER");
    }

    private CommissionService newService(
        CommissionRepository repository,
        CommissionMemberRepository memberRepository,
        TenderCommissionRepository tenderCommissionRepository,
        RFQRepository rfqRepository,
        TenderCommissionService tenderCommissionService
    ) {
        return new CommissionService(
            repository,
            memberRepository,
            mock(UserRepository.class),
            mock(EmployeeRepository.class),
            tenderCommissionRepository,
            rfqRepository,
            tenderCommissionService,
            new CommissionMapper(),
            mock(AuditService.class)
        );
    }

    private CommissionCreateRequest createRequest(List<CommissionMemberRequest> members, UUID rfqId) {
        CommissionCreateRequest request = new CommissionCreateRequest();
        request.setCode("COM-1");
        request.setName("Tender commission");
        request.setStatus("ACTIVE");
        request.setType(CommissionType.TENDER);
        request.setMembers(members);
        request.setRfqId(rfqId);
        return request;
    }

    private CommissionMemberRequest memberRequest(String name) {
        return new CommissionMemberRequest(null, CommissionMemberRole.CHAIRMAN, null, name, true);
    }
}
