package com.casiq.workaccount.gmail;

import com.casiq.workitem.conversation.ConversationWorkItemMatcher;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.Optional;

@ApplicationScoped
public class GmailConversationWorkItemMatcher implements ConversationWorkItemMatcher {
    private static final Logger LOG = Logger.getLogger(GmailConversationWorkItemMatcher.class);

    @Override
    public String providerCode() {
        return "GOOGLE";
    }

    @Override
    public Optional<Long> matchingExecution(
            InboundConversation conversation,
            ExistingConversationLookup lookup) {
        LOG.debugf("Matching Gmail conversation providerThreadId=%s", conversation.providerThreadId());
        Optional<Long> match = lookup.byProviderThreadId(conversation.providerThreadId());
        LOG.debugf("Gmail conversation match result providerThreadId=%s matched=%s", conversation.providerThreadId(), match.isPresent());
        return match;
    }
}
