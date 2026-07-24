package com.casiq.workaccount.core.polling;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Provider boundary used by the core polling workflow. Connector modules implement
 * this contract without owning scheduling, database locking, or persistence.
 */
public interface EmailProviderConnector {
    String providerCode();

    PollResult fetch(PollRequest request);

    default ReadResult read(ReadRequest request) {
        throw new UnsupportedOperationException(providerCode() + " does not support direct message reads");
    }

    default ReplyResult reply(ReplyRequest request) {
        throw new UnsupportedOperationException(providerCode() + " does not support replies");
    }

    record PollRequest(
            UUID workAccountId,
            String emailId,
            String refreshToken,
            String accessToken,
            Instant accessTokenExpiresAt,
            Instant after) {
    }

    record PollResult(
            List<EmailMessage> messages,
            String accessToken,
            Instant accessTokenExpiresAt,
            String refreshToken) {
    }

    record ReadRequest(
            UUID workAccountId,
            String emailId,
            String refreshToken,
            String accessToken,
            Instant accessTokenExpiresAt,
            String providerMessageId) {}

    record ReadResult(
            EmailMessage message,
            String accessToken,
            Instant accessTokenExpiresAt,
            String refreshToken) {}

    record EmailMessage(
            String providerMessageId,
            String providerThreadId,
            String rfcMessageId,
            String inReplyTo,
            String referenceIds,
            String subject,
            String sender,
            String recipients,
            Instant sentAt,
            String snippet,
            String payloadJson,
            String contentText,
            String contentHtml,
            List<EmailAttachment> attachments) {
    }

    record EmailAttachment(
            String providerAttachmentId,
            String filename,
            String contentType,
            byte[] content) {
    }

    record ReplyRequest(
            UUID workAccountId,
            String emailId,
            String refreshToken,
            String accessToken,
            Instant accessTokenExpiresAt,
            String recipient,
            String subject,
            String providerThreadId,
            String inReplyTo,
            String referenceIds,
            String htmlBody,
            List<OutgoingAttachment> attachments) {
    }

    record OutgoingAttachment(
            UUID documentId,
            String filename,
            String contentType,
            byte[] content) {}

    record ReplyResult(
            EmailMessage message,
            String accessToken,
            Instant accessTokenExpiresAt,
            String refreshToken) {
    }
}
