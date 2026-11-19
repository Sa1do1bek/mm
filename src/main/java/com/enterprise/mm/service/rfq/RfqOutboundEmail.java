package com.enterprise.mm.service.rfq;

import java.util.List;

public record RfqOutboundEmail(
    String from,
    String recipientEmail,
    String replyTo,
    String subject,
    String htmlBody,
    String textBody,
    List<Attachment> attachments
) {
    public RfqOutboundEmail {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    public record Attachment(String fileName, String contentType, byte[] content) {
        public Attachment {
            content = content == null ? new byte[0] : content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }
}
