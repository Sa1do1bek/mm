package com.enterprise.mm.service.rfq;

import com.enterprise.mm.config.RfqMailProperties;
import com.enterprise.mm.error.BusinessException;
import jakarta.mail.internet.MimeMessage;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

@Component
public class SpringMailRfqEmailTransport implements RfqEmailTransport {
    private final Supplier<JavaMailSender> senderSupplier;
    private final RfqMailProperties properties;

    @Autowired
    public SpringMailRfqEmailTransport(
        ObjectProvider<JavaMailSender> senderProvider,
        RfqMailProperties properties
    ) {
        this(senderProvider::getIfAvailable, properties);
    }

    public SpringMailRfqEmailTransport(JavaMailSender sender, RfqMailProperties properties) {
        this(() -> sender, properties);
    }

    private SpringMailRfqEmailTransport(Supplier<JavaMailSender> senderSupplier, RfqMailProperties properties) {
        this.senderSupplier = senderSupplier;
        this.properties = properties;
    }

    @Override
    public SendResult send(RfqOutboundEmail email) {
        if (!properties.isEnabled()) {
            throw new BusinessException("RFQ email delivery is disabled");
        }
        JavaMailSender sender = sender();
        MimeMessage message = createMessage(sender, email);
        sender.send(message);
        try {
            return new SendResult(message.getMessageID());
        } catch (Exception exception) {
            return new SendResult(null);
        }
    }

    public MimeMessage createMessage(RfqOutboundEmail email) {
        return createMessage(sender(), email);
    }

    private MimeMessage createMessage(JavaMailSender sender, RfqOutboundEmail email) {
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(required(email.from(), "RFQ sender"));
            helper.setTo(required(email.recipientEmail(), "RFQ recipient"));
            helper.setReplyTo(required(email.replyTo(), "RFQ reply-to"));
            helper.setSubject(required(email.subject(), "RFQ subject"));
            helper.setText(value(email.textBody()), value(email.htmlBody()));
            for (RfqOutboundEmail.Attachment attachment : email.attachments()) {
                ByteArrayResource resource = new ByteArrayResource(attachment.content()) {
                    @Override
                    public String getFilename() {
                        return attachment.fileName();
                    }
                };
                helper.addAttachment(attachment.fileName(), resource, attachment.contentType());
            }
            message.saveChanges();
            return message;
        } catch (Exception exception) {
            throw new BusinessException("Cannot create RFQ email: " + safeMessage(exception));
        }
    }

    private JavaMailSender sender() {
        JavaMailSender sender = senderSupplier.get();
        if (sender == null) {
            throw new BusinessException("SMTP transport is not configured");
        }
        return sender;
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(field + " is required");
        }
        return value.trim();
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private String safeMessage(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
