package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.IlliquidStockPolicyCreateRequest;
import com.enterprise.mm.payload.mxik.MXIKCreateRequest;
import com.enterprise.mm.service.IlliquidStockPolicyService;
import com.enterprise.mm.service.MXIKService;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@WithMockUser(username = "admin", roles = "ADMIN")
class MaterialTypeAuditTest {

    @Autowired
    private MXIKService mxikService;

    @Autowired
    private IlliquidStockPolicyService illiquidStockPolicyService;

    @Test
    void mxikCreateWithWorksTypeRejects() {
        MXIKCreateRequest request = new MXIKCreateRequest();
        request.setKod("MXIK-TEST-WORKS-001");
        request.setName("Civil construction works");
        request.setType("WORKS");

        assertThatThrownBy(() -> mxikService.createMXIK(request))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("WORKS is not a valid Material or MXIK type");
    }

    @Test
    void illiquidStockPolicyCreateWithWorksMaterialTypeRejects() {
        IlliquidStockPolicyCreateRequest request = new IlliquidStockPolicyCreateRequest();
        request.setCode("ISP-TEST-WORKS-001");
        request.setName("Works policy");
        request.setMaterialType("WORKS");
        request.setEffectiveFrom(LocalDate.now());
        request.setAgingDays(90);


        assertThatThrownBy(() -> illiquidStockPolicyService.create(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("WORKS is not a valid material type");
    }
}
