package com.casiq.workaccount.microsoft;

import com.casiq.workitem.conversation.ConversationWorkItemMatcher;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class MicrosoftConversationWorkItemMatcher implements ConversationWorkItemMatcher {
    private static final Logger LOG = Logger.getLogger(MicrosoftConversationWorkItemMatcher.class);
    private static final Pattern MESSAGE_ID = Pattern.compile("<[^<>\\s]+>");

    @Override
    public String providerCode() {
        return "MICROSOFT";
    }

    @Override
    public Optional<Long> matchingExecution(
            InboundConversation conversation,
            ExistingConversationLookup lookup) {
        LOG.debugf("Matching Microsoft conversation providerThreadId=%s inReplyTo=%s",
                conversation.providerThreadId(), conversation.inReplyTo());
        Optional<Long> byConversation = lookup.byProviderThreadId(
                conversation.providerThreadId());
        if (byConversation.isPresent()) {
            LOG.debugf("Matched Microsoft conversation by provider thread id=%s executionId=%s",
                    conversation.providerThreadId(), byConversation.get());
            return byConversation;
        }

        Optional<Long> byReply = lookup.byRfcMessageId(conversation.inReplyTo());
        if (byReply.isPresent()) {
            LOG.debugf("Matched Microsoft conversation by in-reply-to=%s executionId=%s",
                    conversation.inReplyTo(), byReply.get());
            return byReply;
        }

        Matcher references = MESSAGE_ID.matcher(
                conversation.referenceIds() == null ? "" : conversation.referenceIds());
        Optional<Long> match = Optional.empty();
        while (references.find()) {
            Optional<Long> candidate = lookup.byRfcMessageId(references.group());
            if (candidate.isPresent()) {
                match = candidate;
                LOG.debugf("Matched Microsoft conversation by reference ID=%s executionId=%s",
                        references.group(), candidate.get());
            }
        }
        return match;
    }
}
