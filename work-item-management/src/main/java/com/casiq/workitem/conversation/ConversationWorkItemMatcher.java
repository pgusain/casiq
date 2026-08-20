package com.casiq.workitem.conversation;

import org.jboss.logging.Logger;

import java.util.Optional;

/**
 * Provider-owned rules for resolving an inbound email to an existing work item.
 * The work-item module owns persistence; provider connectors own identifier semantics.
 */
public interface ConversationWorkItemMatcher {
    Logger LOG = Logger.getLogger(ConversationWorkItemMatcher.class);

    String providerCode();

    Optional<Long> matchingExecution(
            InboundConversation conversation,
            ExistingConversationLookup lookup);

    record InboundConversation(
            String providerMessageId,
            String providerThreadId,
            String rfcMessageId,
            String inReplyTo,
            String referenceIds) {
    }

    interface ExistingConversationLookup {
        Optional<Long> byProviderThreadId(String providerThreadId);

        Optional<Long> byRfcMessageId(String rfcMessageId);
    }
}
