package com.enterprise.mm.service.rfq;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.config.RfqMailProperties;
import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

class SpringMailRfqEmailTransportTest {
    @Test
    void mimeEmailContainsHtmlPdfExcelSupportingFilesAndMagicLink() throws Exception {
        RfqMailProperties properties = new RfqMailProperties();
        properties.setEnabled(true);
        SpringMailRfqEmailTransport transport = new SpringMailRfqEmailTransport(new JavaMailSenderImpl(), properties);

        MimeMessage message = transport.createMessage(outboundEmail());

        assertThat(message.getSubject()).contains("RFQ-26-010");
        assertThat(message.getReplyTo()).extracting(Address::toString).contains("procurement@example.uz");
        assertThat(mimeText(message)).contains("/supplier-portal/magic-link");
        assertThat(attachmentNames(message))
            .containsExactlyInAnyOrder("RFQ-26-010.pdf", "RFQ-26-010.xlsx", "specification.pdf");
    }

    private RfqOutboundEmail outboundEmail() {
        return new RfqOutboundEmail(
            "no-reply@example.uz",
            "supplier@example.uz",
            "procurement@example.uz",
            "RFQ RFQ-26-010 — Pumps",
            "<p>Full RFQ information</p><a href=\"https://portal.example.uz/supplier-portal/magic-link?token=secret\">Open portal</a>",
            "Open https://portal.example.uz/supplier-portal/magic-link?token=secret",
            List.of(
                new RfqOutboundEmail.Attachment("RFQ-26-010.pdf", "application/pdf", "%PDF".getBytes(StandardCharsets.UTF_8)),
                new RfqOutboundEmail.Attachment(
                    "RFQ-26-010.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "xlsx".getBytes(StandardCharsets.UTF_8)
                ),
                new RfqOutboundEmail.Attachment("specification.pdf", "application/pdf", "spec".getBytes(StandardCharsets.UTF_8))
            )
        );
    }

    private String mimeText(Part part) throws Exception {
        if (part.isMimeType("text/*")) {
            return String.valueOf(part.getContent());
        }
        if (part.getContent() instanceof Multipart multipart) {
            StringBuilder value = new StringBuilder();
            for (int i = 0; i < multipart.getCount(); i++) {
                value.append(mimeText(multipart.getBodyPart(i)));
            }
            return value.toString();
        }
        return "";
    }

    private List<String> attachmentNames(Part part) throws Exception {
        List<String> names = new ArrayList<>();
        if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()) && part.getFileName() != null) {
            names.add(MimeUtility.decodeText(part.getFileName()));
        }
        if (part.getContent() instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart bodyPart = multipart.getBodyPart(i);
                names.addAll(attachmentNames(bodyPart));
            }
        }
        return names;
    }
}
