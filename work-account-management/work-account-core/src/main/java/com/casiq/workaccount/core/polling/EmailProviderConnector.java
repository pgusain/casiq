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
            String contentHtml) {
    }
}
