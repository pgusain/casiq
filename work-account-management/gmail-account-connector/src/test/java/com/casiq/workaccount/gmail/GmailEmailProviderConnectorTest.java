package com.casiq.workaccount.gmail;

import com.casiq.workaccount.core.polling.EmailProviderConnector;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GmailEmailProviderConnectorTest {
    private static final String OVERSIZED_ATTACHMENT_ID = "opaque-".repeat(400);

    @Test
    void fetchesHtmlAndAttachmentDataWithoutRenderingTheAttachmentAsBody() {
        FakeGmailClient gmail = new FakeGmailClient();
        GmailEmailProviderConnector connector = connector(gmail);

        EmailProviderConnector.PollResult result = connector.fetch(
                new EmailProviderConnector.PollRequest(
                        UUID.randomUUID(),
                        "work@example.com",
                        "refresh",
                        "valid-access",
                        Instant.now().plusSeconds(3600),
                        Instant.now().minusSeconds(60)));

        assertEquals(1, result.messages().size());
        EmailProviderConnector.EmailMessage message = result.messages().get(0);
        assertEquals("<p>Hello <strong>world</strong></p>", message.contentHtml());
        assertEquals(1, message.attachments().size());
        assertEquals("filing.pdf", message.attachments().get(0).filename());
        assertTrue(message.attachments().get(0).providerAttachmentId().startsWith("gmail:"));
        assertEquals(70, message.attachments().get(0).providerAttachmentId().length());
        assertArrayEquals("pdf-data".getBytes(StandardCharsets.UTF_8),
                message.attachments().get(0).content());
        assertFalse(message.contentHtml().contains("pdf-data"));
    }

    @Test
    void readsOneReferencedMessageDirectlyFromGmail() {
        FakeGmailClient gmail = new FakeGmailClient();
        GmailEmailProviderConnector connector = connector(gmail);

        EmailProviderConnector.ReadResult result = connector.read(
                new EmailProviderConnector.ReadRequest(
                        UUID.randomUUID(),
                        "work@example.com",
                        "refresh",
                        "valid-access",
                        Instant.now().plusSeconds(3600),
                        "incoming-1"));

        assertEquals("incoming-1", result.message().providerMessageId());
        assertEquals("GST filing", result.message().subject());
        assertEquals("<p>Hello <strong>world</strong></p>",
                result.message().contentHtml());
        assertEquals("full", gmail.lastMessageFormat);
        assertTrue(gmail.downloadedHtmlBody);
    }

    @Test
    void sendsAThreadedHtmlReplyAndReturnsTheStoredProviderMessage() {
        FakeGmailClient gmail = new FakeGmailClient();
        GmailEmailProviderConnector connector = connector(gmail);

        EmailProviderConnector.ReplyResult result = connector.reply(
                new EmailProviderConnector.ReplyRequest(
                        UUID.randomUUID(),
                        "work@example.com",
                        "refresh",
                        "valid-access",
                        Instant.now().plusSeconds(3600),
                        "Sender Name <sender@example.com>",
                        "GST filing",
                        "thread-1",
                        "<message-1@example.com>",
                        "<older@example.com>",
                        "<p>Reviewed <strong>successfully</strong>.</p>",
                        List.of(new EmailProviderConnector.OutgoingAttachment(
                                UUID.randomUUID(),
                                "GST filing.pdf",
                                "application/pdf",
                                "pdf-data".getBytes(StandardCharsets.UTF_8)))));

        assertNotNull(gmail.sent);
        assertEquals("thread-1", gmail.sent.threadId());
        String raw = new String(
                Base64.getUrlDecoder().decode(gmail.sent.raw()),
                StandardCharsets.UTF_8);
        assertTrue(raw.contains("To: sender@example.com"));
        assertTrue(raw.contains("In-Reply-To: <message-1@example.com>"));
        assertTrue(raw.contains("References: <older@example.com> <message-1@example.com>"));
        assertTrue(raw.contains("Content-Type: multipart/mixed"));
        assertTrue(raw.contains("Content-Type: text/html; charset=UTF-8"));
        assertTrue(raw.contains("Content-Disposition: attachment; filename*=UTF-8''GST%20filing.pdf"));
        assertEquals("sent-1", result.message().providerMessageId());
        assertEquals("thread-1", result.message().providerThreadId());
        assertEquals("<sent-1@example.com>", result.message().rfcMessageId());
    }

    private static GmailEmailProviderConnector connector(FakeGmailClient gmail) {
        GmailEmailProviderConnector connector = new GmailEmailProviderConnector();
        connector.gmail = gmail;
        connector.json = new ObjectMapper();
        connector.pageSize = 100;
        connector.maxAttachmentBytes = 26_214_400;
        return connector;
    }

    private static final class FakeGmailClient implements GmailMailboxClient {
        private SendMessage sent;
        private String lastMessageFormat;
        private boolean downloadedHtmlBody;

        @Override
        public MessageList messages(
                String authorization,
                String query,
                int maxResults,
                String pageToken) {
            return new MessageList(List.of(new MessageReference("incoming-1", "thread-1")), null);
        }

        @Override
        public GmailMessage message(String authorization, String id, String format) {
            lastMessageFormat = format;
            if ("sent-1".equals(id)) return sentMessage();
            return incomingMessage();
        }

        @Override
        public MessageBody attachment(
                String authorization,
                String messageId,
                String attachmentId) {
            if ("body-1".equals(attachmentId)) {
                downloadedHtmlBody = true;
                return new MessageBody(
                        attachmentId,
                        35L,
                        Base64.getUrlEncoder().withoutPadding().encodeToString(
                                "<p>Hello <strong>world</strong></p>"
                                        .getBytes(StandardCharsets.UTF_8)));
            }
            return new MessageBody(
                    attachmentId,
                    8L,
                    Base64.getUrlEncoder().withoutPadding().encodeToString(
                            "pdf-data".getBytes(StandardCharsets.UTF_8)));
        }

        @Override
        public GmailMessage send(String authorization, SendMessage message) {
            sent = message;
            return new GmailMessage("sent-1", "thread-1", null, null, null);
        }

        private static GmailMessage incomingMessage() {
            MessagePayload html = new MessagePayload(
                    "text/html",
                    "",
                    List.of(),
                    new MessageBody(
                            "body-1",
                            35L,
                            null),
                    List.of());
            MessagePayload attachment = new MessagePayload(
                    "application/pdf",
                    "filing.pdf",
                    List.of(),
                    new MessageBody(OVERSIZED_ATTACHMENT_ID, 8L, null),
                    List.of());
            return new GmailMessage(
                    "incoming-1",
                    "thread-1",
                    "Hello world",
                    String.valueOf(Instant.now().toEpochMilli()),
                    new MessagePayload(
                            "multipart/mixed",
                            "",
                            headers(
                                    "<message-1@example.com>",
                                    null,
                                    null,
                                    "GST filing",
                                    "Sender <sender@example.com>",
                                    "work@example.com"),
                            null,
                            List.of(html, attachment)));
        }

        private static GmailMessage sentMessage() {
            return new GmailMessage(
                    "sent-1",
                    "thread-1",
                    "Reviewed successfully.",
                    String.valueOf(Instant.now().toEpochMilli()),
                    new MessagePayload(
                            "text/html",
                            "",
                            headers(
                                    "<sent-1@example.com>",
                                    "<message-1@example.com>",
                                    "<older@example.com> <message-1@example.com>",
                                    "GST filing",
                                    "work@example.com",
                                    "sender@example.com"),
                            new MessageBody(
                                    null,
                                    45L,
                                    Base64.getUrlEncoder().withoutPadding().encodeToString(
                                            "<p>Reviewed <strong>successfully</strong>.</p>"
                                                    .getBytes(StandardCharsets.UTF_8))),
                            List.of()));
        }

        private static List<MessageHeader> headers(
                String messageId,
                String inReplyTo,
                String references,
                String subject,
                String from,
                String to) {
            java.util.ArrayList<MessageHeader> headers = new java.util.ArrayList<>();
            headers.add(new MessageHeader("Message-ID", messageId));
            if (inReplyTo != null) headers.add(new MessageHeader("In-Reply-To", inReplyTo));
            if (references != null) headers.add(new MessageHeader("References", references));
            headers.add(new MessageHeader("Subject", subject));
            headers.add(new MessageHeader("From", from));
            headers.add(new MessageHeader("To", to));
            return List.copyOf(headers);
        }
    }
}
