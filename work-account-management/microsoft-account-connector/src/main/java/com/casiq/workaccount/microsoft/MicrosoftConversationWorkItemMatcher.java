package com.casiq.workaccount.microsoft;

import com.casiq.workitem.conversation.ConversationWorkItemMatcher;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class MicrosoftConversationWorkItemMatcher implements ConversationWorkItemMatcher {
    private static final Pattern MESSAGE_ID = Pattern.compile("<[^<>\\s]+>");

    @Override
    public String providerCode() {
        return "MICROSOFT";
    }

    @Override
    public Optional<Long> matchingExecution(
            InboundConversation conversation,
            ExistingConversationLookup lookup) {
        Optional<Long> byConversation = lookup.byProviderThreadId(
                conversation.providerThreadId());
        if (byConversation.isPresent()) return byConversation;

        Optional<Long> byReply = lookup.byRfcMessageId(conversation.inReplyTo());
        if (byReply.isPresent()) return byReply;

        Matcher references = MESSAGE_ID.matcher(
                conversation.referenceIds() == null ? "" : conversation.referenceIds());
        Optional<Long> match = Optional.empty();
        while (references.find()) {
            Optional<Long> candidate = lookup.byRfcMessageId(references.group());
            if (candidate.isPresent()) match = candidate;
        }
        return match;
    }
}
