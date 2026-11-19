package com.enterprise.mm;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.security.test.context.support.WithMockUser;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.ServiceOrder;
import com.enterprise.mm.entity.ServiceRequest;
import com.enterprise.mm.entity.ServiceAcceptanceAct;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.ServiceOrderRepository;
import com.enterprise.mm.repository.ServiceRequestRepository;
import com.enterprise.mm.repository.ServiceAcceptanceActRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.BusinessDisplayNamePolicy;

import java.util.UUID;
import com.enterprise.mm.enums.RequestType;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(username = "admin", roles = {"ADMIN"})
public class RuntimeNameVerificationTest {
    @Autowired MockMvc mockMvc;
    @Autowired PurchaseOrderRepository purchaseOrderRepository;
    @Autowired ServiceOrderRepository serviceOrderRepository;
    @Autowired ServiceRequestRepository serviceRequestRepository;
    @Autowired ServiceAcceptanceActRepository serviceAcceptanceActRepository;
    @Autowired SupplierRepository supplierRepository;

    @Test
    public void verifyNames() throws Exception {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);

        String baseName = "Information Systems Modernization Plan";
        Supplier supplier = new Supplier();
        supplier.setCode("SUP-" + UUID.randomUUID().toString().substring(0, 8));
        supplier.setName("Runtime Supplier");
        supplier.setStatus("ACTIVE");
        supplier.setLegalName("Runtime Supplier LLC");
        supplier.setTaxId("TIN-" + UUID.randomUUID().toString().substring(0, 8));
        supplier.setRegistrationNumber("REG-" + UUID.randomUUID().toString().substring(0, 8));
        supplier.setEmail("runtime-" + UUID.randomUUID().toString().substring(0, 8) + "@example.uz");
        supplier.setLegalAddress("Runtime address");
        supplier = supplierRepository.saveAndFlush(supplier);
        
        PurchaseOrder po = new PurchaseOrder();
        po.setCode("PO-" + UUID.randomUUID().toString().substring(0, 8));
        po.setName(BusinessDisplayNamePolicy.requireWorkflowDocumentName("PurchaseOrder", null, po.getCode(), "Tender - " + baseName));
        po.setStatus("ACTIVE");
        po.setRequestType(RequestType.SERVICE);
        po.setSupplier(supplier);
        po.setSupplierId(supplier.getId());
        po = purchaseOrderRepository.saveAndFlush(po);

        ServiceRequest sr = new ServiceRequest();
        sr.setCode("SR-" + UUID.randomUUID().toString().substring(0, 8));
        sr.setName(BusinessDisplayNamePolicy.requireWorkflowDocumentName("ServiceRequest", null, sr.getCode(), po.getName()));
        sr.setTitle("Test");
        sr.setStatus("ACTIVE");
        sr = serviceRequestRepository.saveAndFlush(sr);

        ServiceOrder serviceOrder = new ServiceOrder();
        serviceOrder.setCode("SO-" + UUID.randomUUID().toString().substring(0, 8));
        serviceOrder.setName(BusinessDisplayNamePolicy.requireWorkflowDocumentName("ServiceOrder", null, serviceOrder.getCode(), sr.getName()));
        serviceOrder.setStatus("ACTIVE");
        serviceOrder.setServiceRequestId(sr.getId());
        serviceOrder.setPurchaseOrderId(po.getId());
        serviceOrder.setSupplierId(supplier.getId());
        serviceOrder = serviceOrderRepository.saveAndFlush(serviceOrder);

        ServiceAcceptanceAct saa = new ServiceAcceptanceAct();
        saa.setCode("SAA-" + UUID.randomUUID().toString().substring(0, 8));
        saa.setName(BusinessDisplayNamePolicy.requireWorkflowDocumentName("ServiceAcceptanceAct", null, saa.getCode(), sr.getName()));
        saa.setStatus("DRAFT");
        saa.setServiceRequestId(sr.getId());
        saa.setServiceOrderId(serviceOrder.getId());
        saa.setPurchaseOrderId(po.getId());
        saa = serviceAcceptanceActRepository.saveAndFlush(saa);

        System.out.println("===== RUNTIME VERIFICATION OUTPUT =====");
        
        String[] langs = {"en", "ru", "uz"};
        for (String lang : langs) {
            MvcResult resPo = mockMvc.perform(get("/api/purchase-orders/" + po.getId()).header("Accept-Language", lang)).andReturn();
            System.out.println("PurchaseOrder (" + lang + "): " + extractName(resPo.getResponse().getContentAsString()));

            MvcResult resSr = mockMvc.perform(get("/api/service-requests/" + sr.getId()).header("Accept-Language", lang)).andReturn();
            System.out.println("ServiceRequest (" + lang + "): " + extractName(resSr.getResponse().getContentAsString()));

            MvcResult resSaa = mockMvc.perform(get("/api/service-acts/" + saa.getId()).header("Accept-Language", lang)).andReturn();
            System.out.println("ServiceAcceptanceAct (" + lang + "): " + extractName(resSaa.getResponse().getContentAsString()));
        }
        System.out.println("=======================================");
    }

    private String extractName(String json) {
        try {
            int nameIdx = json.indexOf("\"name\":\"");
            if (nameIdx == -1) return "NOT FOUND";
            int endIdx = json.indexOf("\"", nameIdx + 8);
            return json.substring(nameIdx + 8, endIdx);
        } catch (Exception e) { return "ERROR"; }
    }
}
