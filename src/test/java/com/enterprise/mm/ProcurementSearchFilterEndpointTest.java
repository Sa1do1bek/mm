package com.enterprise.mm;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.mm.entity.Contract;
import com.enterprise.mm.entity.Department;
import com.enterprise.mm.entity.ProcurementCampaign;
import com.enterprise.mm.entity.ProcurementPlan;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.repository.ContractRepository;
import com.enterprise.mm.repository.DepartmentRepository;
import com.enterprise.mm.repository.ProcurementCampaignRepository;
import com.enterprise.mm.repository.ProcurementPlanRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.SupplierRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Year;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProcurementSearchFilterEndpointTest {
    @Autowired MockMvc mockMvc;
    @Autowired PurchaseRequestRepository purchaseRequestRepository;
    @Autowired DepartmentRepository departmentRepository;
    @Autowired ProcurementPlanRepository procurementPlanRepository;
    @Autowired ProcurementCampaignRepository procurementCampaignRepository;
    @Autowired SupplierRepository supplierRepository;
    @Autowired ContractRepository contractRepository;

    @Test
    void purchaseRequestSearchCombinesEntityFieldFilters() throws Exception {
        String suffix = suffix();
        Department department = department("DEP-PR-FILTER-" + suffix);
        LocalDate requiredDate = LocalDate.now().plusDays(9);

        purchaseRequest("PR-FILTER-MATCH-" + suffix, department.getId(), "buyer-" + suffix, requiredDate, "1250.00");
        purchaseRequest("PR-FILTER-OTHER-" + suffix, department.getId(), "buyer-" + suffix, requiredDate.plusDays(5), "2250.00");

        mockMvc.perform(get("/api/purchase-requests")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default")
                .param("departmentId", department.getId().toString())
                .param("requestedBy", "buyer-" + suffix)
                .param("requiredDateFrom", requiredDate.toString())
                .param("requiredDateTo", requiredDate.toString())
                .param("totalAmountMin", "1200")
                .param("totalAmountMax", "1300"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content.length()").value(1))
            .andExpect(jsonPath("$.data.content[0].code").value("PR-FILTER-MATCH-" + suffix));
    }

    @Test
    void procurementCampaignSearchCombinesEntityFieldFilters() throws Exception {
        String suffix = suffix();
        ProcurementPlan plan = procurementPlan("PLAN-CAMPAIGN-FILTER-" + suffix);

        procurementCampaign("CAMPAIGN-FILTER-MATCH-" + suffix, plan.getId(), "ANNUAL_TENDER", "OPEN_COMPETITION", 4, "18000.00");
        procurementCampaign("CAMPAIGN-FILTER-OTHER-" + suffix, plan.getId(), "ANNUAL_TENDER", "REQUEST_FOR_QUOTATION", 2, "9000.00");

        mockMvc.perform(get("/api/procurement/campaigns")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default")
                .param("procurementPlanId", plan.getId().toString())
                .param("campaignType", "ANNUAL_TENDER")
                .param("procedureType", "OPEN_COMPETITION")
                .param("targetSupplierCount", "4")
                .param("plannedSpendAmountMin", "17000")
                .param("plannedSpendAmountMax", "19000"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content.length()").value(1))
            .andExpect(jsonPath("$.data.content[0].code").value("CAMPAIGN-FILTER-MATCH-" + suffix));
    }

    @Test
    void contractSearchCombinesEntityFieldFilters() throws Exception {
        String suffix = suffix();
        Supplier supplier = supplier("SUP-CONTRACT-FILTER-" + suffix);
        LocalDate startDate = LocalDate.now().minusDays(2);

        contract("CONTRACT-FILTER-MATCH-" + suffix, supplier.getId(), startDate, "50000.00", "UZS");
        contract("CONTRACT-FILTER-OTHER-" + suffix, supplier.getId(), startDate.plusDays(10), "25000.00", "USD");

        mockMvc.perform(get("/api/contracts")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default")
                .param("supplierId", supplier.getId().toString())
                .param("startDateFrom", startDate.toString())
                .param("startDateTo", startDate.toString())
                .param("limitAmountMin", "49000")
                .param("limitAmountMax", "51000")
                .param("currencyCode", "UZS"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content.length()").value(1))
            .andExpect(jsonPath("$.data.content[0].code").value("CONTRACT-FILTER-MATCH-" + suffix));
    }

    private Department department(String code) {
        Department department = new Department();
        department.setCode(code);
        department.setName(code);
        department.setStatus("ACTIVE");
        return departmentRepository.save(department);
    }

    private PurchaseRequest purchaseRequest(String code, UUID departmentId, String requestedBy, LocalDate requiredDate, String totalAmount) {
        PurchaseRequest request = new PurchaseRequest();
        request.setCode(code);
        request.setName(code);
        request.setStatus("DRAFT");
        request.setDepartmentId(departmentId);
        request.setRequestedBy(requestedBy);
        request.setRequiredDate(requiredDate);
        request.setTotalAmount(new BigDecimal(totalAmount));
        request.setCurrencyCode("UZS");
        return purchaseRequestRepository.save(request);
    }

    private ProcurementPlan procurementPlan(String code) {
        ProcurementPlan plan = new ProcurementPlan();
        plan.setCode(code);
        plan.setName(code);
        plan.setStatus("ACTIVE");
        plan.setPlanNumber(code);
        plan.setPlanYear(Year.now().getValue());
        plan.setPeriodType("YEAR");
        plan.setCurrencyCode("UZS");
        return procurementPlanRepository.save(plan);
    }

    private ProcurementCampaign procurementCampaign(
        String code,
        UUID procurementPlanId,
        String campaignType,
        String procedureType,
        Integer targetSupplierCount,
        String plannedSpendAmount
    ) {
        ProcurementCampaign campaign = new ProcurementCampaign();
        campaign.setCode(code);
        campaign.setName(code);
        campaign.setStatus("DRAFT");
        campaign.setCampaignNumber(code);
        campaign.setProcurementPlanId(procurementPlanId);
        campaign.setCampaignType(campaignType);
        campaign.setProcedureType(procedureType);
        campaign.setTargetSupplierCount(targetSupplierCount);
        campaign.setPlannedSpendAmount(new BigDecimal(plannedSpendAmount));
        campaign.setApprovedSpendAmount(BigDecimal.ZERO);
        campaign.setSavingsTargetAmount(BigDecimal.ZERO);
        campaign.setCurrencyCode("UZS");
        return procurementCampaignRepository.save(campaign);
    }

    private Supplier supplier(String code) {
        Supplier supplier = new Supplier();
        supplier.setCode(code);
        supplier.setName(code);
        supplier.setStatus("ACTIVE");
        supplier.setCategory("A");
        supplier.setRating(BigDecimal.valueOf(90));
        return supplierRepository.save(supplier);
    }

    private Contract contract(String code, UUID supplierId, LocalDate startDate, String limitAmount, String currencyCode) {
        Contract contract = new Contract();
        contract.setCode(code);
        contract.setName(code);
        contract.setStatus("ACTIVE");
        contract.setSupplierId(supplierId);
        contract.setContractNumber(code);
        contract.setStartDate(startDate);
        contract.setEndDate(startDate.plusMonths(6));
        contract.setLimitAmount(new BigDecimal(limitAmount));
        contract.setUsedAmount(BigDecimal.ZERO);
        contract.setCurrencyCode(currencyCode);
        return contractRepository.save(contract);
    }

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
