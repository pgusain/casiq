package com.casiq.workaccount.gmail;

import com.casiq.workitem.conversation.ConversationWorkItemMatcher;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;

@ApplicationScoped
public class GmailConversationWorkItemMatcher implements ConversationWorkItemMatcher {
    @Override
    public String providerCode() {
        return "GOOGLE";
    }

    @Override
    public Optional<Long> matchingExecution(
            InboundConversation conversation,
            ExistingConversationLookup lookup) {
        return lookup.byProviderThreadId(conversation.providerThreadId());
    }
}
