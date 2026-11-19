package com.enterprise.mm.service.rfq;

import com.enterprise.mm.service.rfq.RfqPackageLabels.Labels;
import com.enterprise.mm.service.rfq.RfqPackageSnapshot.Position;
import com.enterprise.mm.service.rfq.RfqPackageSnapshot.SupplierRecipient;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RfqEmailRenderer {
    private static final DateTimeFormatter EMAIL_DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final String TABLE_STYLE = "border-collapse:collapse;border-spacing:0;width:100%;border:1px solid #d0d7de";
    private static final String TH_STYLE = "border:1px solid #d0d7de;padding:8px 10px;text-align:left;background:#f6f8fa;font-weight:700;vertical-align:top";
    private static final String TD_STYLE = "border:1px solid #d0d7de;padding:8px 10px;text-align:left;vertical-align:top";

    private final int maximumHtmlBytes;

    public RfqEmailRenderer(@Value("${mm.rfq-mail.max-html-bytes:768000}") int maximumHtmlBytes) {
        this.maximumHtmlBytes = maximumHtmlBytes;
    }

    public RenderedEmail render(
        RfqPackageSnapshot snapshot,
        SupplierRecipient recipient,
        URI magicLink
    ) {
        Labels labels = RfqPackageLabels.forLanguage(snapshot.language());
        String fullHtml = html(snapshot, recipient, magicLink, true);
        String renderedHtml = fullHtml.getBytes(StandardCharsets.UTF_8).length <= maximumHtmlBytes
            ? fullHtml
            : html(snapshot, recipient, magicLink, false);
        String title = emailTitle(snapshot);
        String plainText = title + "\n"
            + text(snapshot.title()) + "\n"
            + labels.deadline() + ": " + date(snapshot.dueDate()) + "\n"
            + labels.openPortalPlainText() + ": " + magicLink;
        return new RenderedEmail(title, renderedHtml, plainText);
    }

    private String html(
        RfqPackageSnapshot snapshot,
        SupplierRecipient recipient,
        URI magicLink,
        boolean includeAllPositions
    ) {
        Labels labels = RfqPackageLabels.forLanguage(snapshot.language());
        StringBuilder html = new StringBuilder(4096);
        html.append("<!doctype html><html><body style=\"font-family:Arial,sans-serif;color:#172033\">");
        html.append("<h1>").append(escape(documentTitle(snapshot.language()))).append("</h1>");
        html.append("<p>").append(escape(labels.dear())).append(" ")
            .append(escape(firstNonBlank(recipient.contactName(), recipient.supplierName())))
            .append(",</p>");
        html.append("<h2>").append(escape(emailTitle(snapshot))).append("</h2>");
        html.append("<p>").append(escape(snapshot.description())).append("</p>");
        html.append("<p><strong>").append(escape(labels.buyer())).append(":</strong> ").append(escape(snapshot.buyer() == null ? null : snapshot.buyer().companyName()))
            .append("<br><strong>").append(escape(labels.contact())).append(":</strong> ").append(escape(snapshot.buyer() == null ? null : snapshot.buyer().contactName()))
            .append("<br><strong>").append(escape(labels.email())).append(":</strong> ").append(escape(snapshot.buyer() == null ? null : snapshot.buyer().email()))
            .append("<br><strong>").append(escape(labels.deadline())).append(":</strong> ").append(escape(date(snapshot.dueDate())))
            .append("</p>");
        html.append("<p><strong>").append(escape(labels.responseInstructions())).append(":</strong> ")
            .append(escape(snapshot.responseInstructions())).append("</p>");
        if (includeAllPositions) {
            html.append("<table role=\"presentation\" border=\"1\" cellpadding=\"0\" cellspacing=\"0\" style=\"")
                .append(TABLE_STYLE).append("\"><thead><tr>")
                .append("<th style=\"").append(TH_STYLE).append("\">").append(escape(labels.code())).append("</th>")
                .append("<th style=\"").append(TH_STYLE).append("\">").append(escape(labels.material())).append("</th>")
                .append("<th style=\"").append(TH_STYLE).append("\">").append(escape(labels.specification())).append("</th>")
                .append("<th style=\"").append(TH_STYLE).append("\">").append(escape(labels.unit())).append("</th>")
                .append("<th style=\"").append(TH_STYLE).append("\">").append(escape(labels.quantity())).append("</th>")
                .append("<th style=\"").append(TH_STYLE).append("\">").append(escape(labels.requiredDate())).append("</th>")
                .append("</tr></thead><tbody>");
            for (Position position : snapshot.positions()) {
                html.append("<tr><td style=\"").append(TD_STYLE).append("\">").append(escape(position.materialCode()))
                    .append("</td><td style=\"").append(TD_STYLE).append("\">").append(escape(position.materialName()))
                    .append("</td><td style=\"").append(TD_STYLE).append("\">").append(escape(position.specification()))
                    .append("</td><td style=\"").append(TD_STYLE).append("\">").append(escape(position.unit()))
                    .append("</td><td style=\"").append(TD_STYLE).append("\">").append(escape(text(position.quantity())))
                    .append("</td><td style=\"").append(TD_STYLE).append("\">").append(escape(date(position.requiredDate())))
                    .append("</td></tr>");
            }
            html.append("</tbody></table>");
        } else {
            html.append("<p>").append(escape(labels.omittedPositions().formatted(snapshot.positions().size())))
                .append("</p>");
        }
        html.append("<p>").append(escape(labels.responsePrompt())).append("</p>")
            .append("<p><a href=\"").append(escape(magicLink.toString()))
            .append("\" style=\"display:inline-block;padding:12px 18px;background:#2457d6;color:white;text-decoration:none\">")
            .append(escape(labels.openPortal())).append("</a></p>");
        html.append("<p>").append(escape(labels.linkValidity())).append("</p>");
        html.append("</body></html>");
        return html.toString();
    }

    private String emailTitle(RfqPackageSnapshot snapshot) {
        StringBuilder title = new StringBuilder(documentTitle(snapshot.language()));
        String code = text(snapshot.rfqCode()).trim();
        if (!code.isEmpty()) {
            title.append(' ').append(code);
        }
        String name = text(snapshot.title()).trim();
        if (!name.isEmpty()) {
            title.append(" - ").append(name);
        }
        return title.toString();
    }

    private String documentTitle(String language) {
        String normalized = language == null ? "ru" : language.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "en" -> "Request for quotation";
            case "uz" -> "Tijorat taklifi uchun so\u2018rov";
            default -> "\u0417\u0430\u043f\u0440\u043e\u0441 \u043a\u043e\u0442\u0438\u0440\u043e\u0432\u043e\u043a";
        };
    }

    private String escape(Object value) {
        return text(value)
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? text(second) : first;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String date(LocalDate value) {
        return value == null ? "" : EMAIL_DATE_FORMAT.format(value);
    }

    public record RenderedEmail(String subject, String html, String text) {
    }
}
