package com.casiq.workaccount.microsoft;

import com.casiq.workaccount.core.polling.EmailProviderConnector;
import com.casiq.workitem.conversation.ConversationWorkItemMatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class MicrosoftEmailProviderConnectorTest {
    @Test
    void fetchesInboxMessagesAndFileAttachments() {
        FakeGraphClient graph = new FakeGraphClient();
        MicrosoftEmailProviderConnector connector = connector(graph);

        EmailProviderConnector.PollResult result = connector.fetch(
                new EmailProviderConnector.PollRequest(
                        7L,
                        "work@example.com",
                        "refresh",
                        "valid-access",
                        Instant.now().plusSeconds(3600),
                        Instant.now().minusSeconds(60)));

        assertEquals(1, result.messages().size());
        EmailProviderConnector.EmailMessage message = result.messages().get(0);
        assertEquals("message-1", message.providerMessageId());
        assertEquals("conversation-1", message.providerThreadId());
        assertEquals("<message-1@example.com>", message.rfcMessageId());
        assertEquals("Sender <sender@example.com>", message.sender());
        assertEquals("<p>Hello from Microsoft 365</p>", message.contentHtml());
        assertEquals(1, message.attachments().size());
        assertEquals("evidence.pdf", message.attachments().get(0).filename());
        assertArrayEquals("pdf-data".getBytes(StandardCharsets.UTF_8),
                message.attachments().get(0).content());
    }

    @Test
    void createsAndSendsAThreadedMimeReply() {
        FakeGraphClient graph = new FakeGraphClient();
        MicrosoftEmailProviderConnector connector = connector(graph);

        EmailProviderConnector.ReplyResult result = connector.reply(
                new EmailProviderConnector.ReplyRequest(
                        7L,
                        "work@example.com",
                        "refresh",
                        "valid-access",
                        Instant.now().plusSeconds(3600),
                        "Sender <sender@example.com>",
                        "Evidence requested",
                        "message-1",
                        "conversation-1",
                        "<message-1@example.com>",
                        "<older@example.com>",
                        "<p>Attached is the evidence.</p>",
                        List.of(new EmailProviderConnector.OutgoingAttachment(
                                3L,
                                "evidence.pdf",
                                "application/pdf",
                                "pdf-data".getBytes(StandardCharsets.UTF_8)))));

        assertEquals("message-1", graph.repliedTo);
        assertEquals("draft-1", graph.sentDraft);
        String mime = new String(
                Base64.getDecoder().decode(graph.replyMime),
                StandardCharsets.UTF_8);
        assertTrue(mime.contains("In-Reply-To: <message-1@example.com>"));
        assertTrue(mime.contains(
                "References: <older@example.com> <message-1@example.com>"));
        assertTrue(mime.contains("Content-Type: multipart/mixed"));
        assertTrue(mime.contains("filename*=UTF-8''evidence.pdf"));
        assertEquals("draft-1", result.message().providerMessageId());
        assertEquals("conversation-1", result.message().providerThreadId());
    }

    @Test
    void matchesByOutlookConversationThenRfcReplyHeaders() {
        MicrosoftConversationWorkItemMatcher matcher =
                new MicrosoftConversationWorkItemMatcher();
        ConversationWorkItemMatcher.ExistingConversationLookup lookup =
                new ConversationWorkItemMatcher.ExistingConversationLookup() {
                    @Override
                    public Optional<Long> byProviderThreadId(String threadId) {
                        return "conversation-1".equals(threadId)
                                ? Optional.of(11L) : Optional.empty();
                    }

                    @Override
                    public Optional<Long> byRfcMessageId(String messageId) {
                        return "<older@example.com>".equals(messageId)
                                ? Optional.of(12L) : Optional.empty();
                    }
                };

        assertEquals(11L, matcher.matchingExecution(
                new ConversationWorkItemMatcher.InboundConversation(
                        "message-2", "conversation-1", null, null, null),
                lookup).orElseThrow());
        assertEquals(12L, matcher.matchingExecution(
                new ConversationWorkItemMatcher.InboundConversation(
                        "message-3", "different", null, null,
                        "<missing@example.com> <older@example.com>"),
                lookup).orElseThrow());
    }

    private static MicrosoftEmailProviderConnector connector(FakeGraphClient graph) {
        MicrosoftEmailProviderConnector connector =
                new MicrosoftEmailProviderConnector();
        connector.graph = graph;
        connector.json = new ObjectMapper().findAndRegisterModules();
        connector.pageSize = 100;
        connector.maxAttachmentBytes = 26_214_400;
        return connector;
    }

    private static final class FakeGraphClient implements MicrosoftGraphClient {
        private String repliedTo;
        private String replyMime;
        private String sentDraft;

        @Override
        public GraphUser me(String authorization, String select) {
            return new GraphUser("work@example.com", "work@example.com");
        }

        @Override
        public MessagePage inboxMessages(
                String authorization,
                String prefer,
                String filter,
                String orderBy,
                String select,
                int top) {
            assertTrue(filter.startsWith("receivedDateTime ge "));
            assertEquals("receivedDateTime asc", orderBy);
            return new MessagePage(List.of(incoming()), null);
        }

        @Override
        public MessagePage messagesAt(
                String authorization,
                String prefer,
                String nextLink) {
            return new MessagePage(List.of(), null);
        }

        @Override
        public GraphMessage message(
                String authorization,
                String prefer,
                String id,
                String select) {
            return incoming();
        }

        @Override
        public AttachmentPage attachments(
                String authorization,
                String prefer,
                String id) {
            return new AttachmentPage(List.of(new GraphAttachment(
                    "attachment-1",
                    "evidence.pdf",
                    "application/pdf",
                    8,
                    false,
                    "#microsoft.graph.fileAttachment",
                    Base64.getEncoder().encodeToString(
                            "pdf-data".getBytes(StandardCharsets.UTF_8)))));
        }

        @Override
        public GraphMessage createReply(
                String authorization,
                String prefer,
                String id,
                String base64MimeContent) {
            repliedTo = id;
            replyMime = base64MimeContent;
            return new GraphMessage(
                    "draft-1",
                    "conversation-1",
                    "<draft-1@example.com>",
                    "Re: Evidence requested",
                    null,
                    List.of(),
                    List.of(),
                    List.of(),
                    null,
                    null,
                    null,
                    null,
                    false,
                    List.of());
        }

        @Override
        public void send(String authorization, String prefer, String id) {
            sentDraft = id;
        }

        private static GraphMessage incoming() {
            return new GraphMessage(
                    "message-1",
                    "conversation-1",
                    "<message-1@example.com>",
                    "Evidence requested",
                    new Recipient(new EmailAddress(
                            "Sender", "sender@example.com")),
                    List.of(new Recipient(new EmailAddress(
                            "Work", "work@example.com"))),
                    List.of(),
                    List.of(),
                    Instant.parse("2026-07-28T08:00:00Z"),
                    Instant.parse("2026-07-28T08:00:00Z"),
                    "Hello from Microsoft 365",
                    new ItemBody("html", "<p>Hello from Microsoft 365</p>"),
                    true,
                    List.of(
                            new InternetMessageHeader(
                                    "In-Reply-To", "<older@example.com>"),
                            new InternetMessageHeader(
                                    "References", "<older@example.com>")));
        }
    }
}
