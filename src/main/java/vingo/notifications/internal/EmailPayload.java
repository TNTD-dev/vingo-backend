package vingo.notifications.internal;

import java.util.List;

public record EmailPayload(String recipient, String subject, String html, List<Attachment> attachments, String sender) {
    public EmailPayload(String recipient, String subject, String html, List<Attachment> attachments) {
        this(recipient, subject, html, attachments, null);
    }
    public record Attachment(String filename, String content) {
        @Override public String toString() { return "Attachment[redacted]"; }
    }
    @Override public String toString() { return "EmailPayload[redacted]"; }
}
