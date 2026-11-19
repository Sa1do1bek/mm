package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.RfqInvitationDelivery;
import com.enterprise.mm.entity.RfqPublishedVersion;
import com.enterprise.mm.entity.RfqSupplierInvitation;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.enums.RfqDeliveryStatus;
import com.enterprise.mm.payload.RfqMailCallbackDtos.RfqMailEvent;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.repository.RfqInvitationDeliveryRepository;
import com.enterprise.mm.repository.RfqPublishedVersionRepository;
import com.enterprise.mm.repository.RfqSupplierInvitationRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "mm.rfq-mail.callback-secret=callback-test-secret")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RfqMailCallbackSecurityTest {
    private static final String SECRET = "callback-test-secret";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired RFQRepository rfqRepository;
    @Autowired SupplierRepository supplierRepository;
    @Autowired RfqSupplierInvitationRepository invitationRepository;
    @Autowired RfqPublishedVersionRepository versionRepository;
    @Autowired RfqInvitationDeliveryRepository deliveryRepository;
    @Autowired MeterRegistry meterRegistry;

    @BeforeEach
    void setContext() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void onlyTheExactSignedCallbackCanPromoteSentToDelivered() throws Exception {
        RfqInvitationDelivery delivery = sentDelivery("provider-callback-" + suffix());
        RfqMailEvent event = new RfqMailEvent(
            delivery.getProviderMessageId(), "DELIVERED", Instant.parse("2026-07-18T10:15:30Z")
        );
        String body = objectMapper.writeValueAsString(event);

        mockMvc.perform(post("/api/integrations/callbacks/rfq-mail/events")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-RFQ-Mail-Signature", "invalid")
                .content(body))
            .andExpect(status().isForbidden());
        assertThat(deliveryRepository.findById(delivery.getId()).orElseThrow().deliveryStatus())
            .isEqualTo(RfqDeliveryStatus.SENT);

        mockMvc.perform(post("/api/integrations/callbacks/rfq-mail/events")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-RFQ-Mail-Signature", sign(body))
                .content(body))
            .andExpect(status().isOk());

        RfqInvitationDelivery delivered = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(delivered.deliveryStatus()).isEqualTo(RfqDeliveryStatus.DELIVERED);
        assertThat(delivered.getDeliveredAt()).isNotNull();
        var firstDeliveredAt = delivered.getDeliveredAt();

        mockMvc.perform(post("/api/integrations/callbacks/rfq-mail/events")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-RFQ-Mail-Signature", sign(body))
                .content(body))
            .andExpect(status().isOk());
        assertThat(deliveryRepository.findById(delivery.getId()).orElseThrow().getDeliveredAt())
            .isEqualTo(firstDeliveredAt);
        assertThat(meterRegistry.get("mm_rfq_delivery_total")
            .tag("outcome", "DELIVERED").counter().count()).isGreaterThanOrEqualTo(1d);

        mockMvc.perform(post("/api/integrations/callbacks/rfq-mail/other")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-RFQ-Mail-Signature", sign(body))
                .content(body))
            .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(401, 403, 404));
    }

    @Test
    void signedUnknownProviderMessageIsAcceptedWithoutChangingBusinessState() throws Exception {
        RfqMailEvent event = new RfqMailEvent(
            "unknown-provider-message", "DELIVERED", Instant.parse("2026-07-18T10:15:30Z")
        );
        String body = objectMapper.writeValueAsString(event);

        mockMvc.perform(post("/api/integrations/callbacks/rfq-mail/events")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-RFQ-Mail-Signature", sign(body))
                .content(body))
            .andExpect(status().isOk());
    }

    private RfqInvitationDelivery sentDelivery(String providerMessageId) {
        String suffix = suffix();
        RFQ rfq = new RFQ();
        rfq.setCode("RFQ-CB-" + suffix);
        rfq.setName("Callback RFQ " + suffix);
        rfq.setStatus("OPEN");
        rfq.setDueDate(LocalDate.now().plusDays(5));
        rfq = rfqRepository.saveAndFlush(rfq);

        Supplier supplier = new Supplier();
        supplier.setCode("SUP-CB-" + suffix);
        supplier.setName("Callback Supplier " + suffix);
        supplier.setStatus("ACTIVE");
        supplier.setEmail("callback-" + suffix + "@supplier.test");
        supplier = supplierRepository.saveAndFlush(supplier);

        RfqSupplierInvitation invitation = new RfqSupplierInvitation();
        invitation.setCode("RFI-CB-" + suffix);
        invitation.setName("Callback invitation " + suffix);
        invitation.setStatus("INVITED");
        invitation.setRfqId(rfq.getId());
        invitation.setSupplierId(supplier.getId());
        invitation = invitationRepository.saveAndFlush(invitation);

        RfqPublishedVersion version = new RfqPublishedVersion();
        version.setCode("RFQV-CB-" + suffix);
        version.setName("Callback version " + suffix);
        version.setStatus("ACTIVE");
        version.setRfqId(rfq.getId());
        version.setVersionNumber(1);
        version.setSnapshotJson("{}");
        version.setPackageLanguage("ru");
        version.setTemplateVersion("rfq-package-v1");
        version.setPublishedAt(java.time.LocalDateTime.now());
        version.setPublishedBy("callback-test");
        version = versionRepository.saveAndFlush(version);

        RfqInvitationDelivery delivery = new RfqInvitationDelivery();
        delivery.setCode("RFD-CB-" + suffix);
        delivery.setName("Callback delivery " + suffix);
        delivery.setDeliveryStatus(RfqDeliveryStatus.SENT);
        delivery.setInvitationId(invitation.getId());
        delivery.setPublishedVersionId(version.getId());
        delivery.setSupplierId(supplier.getId());
        delivery.setRecipientEmail(supplier.getEmail());
        delivery.setProviderMessageId(providerMessageId);
        delivery.setFirstSentAt(java.time.LocalDateTime.now().minusMinutes(5));
        delivery.setLastSentAt(java.time.LocalDateTime.now().minusMinutes(5));
        return deliveryRepository.saveAndFlush(delivery);
    }

    private String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] signature = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(signature);
    }

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
