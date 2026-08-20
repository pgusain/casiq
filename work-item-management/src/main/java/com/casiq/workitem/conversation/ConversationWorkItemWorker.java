package com.casiq.workitem.conversation;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.slf4j.MDC;

@ApplicationScoped
public class ConversationWorkItemWorker {
    private static final Logger LOG = Logger.getLogger(ConversationWorkItemWorker.class);
    @Inject ConversationWorkItemProcessor processor;
    @Inject ConversationWorkItemStateService state;

    public void process(Long conversationId, String owner) {
        MDC.put("tenantCode", conversationId == null ? "unknown" : String.valueOf(conversationId));
        try {
            LOG.debugf("Conversation work-item worker started conversationId=%s owner=%s",
                    conversationId, owner);
            processor.createExecution(conversationId, owner);
            LOG.debugf("Conversation work-item worker finished conversationId=%s owner=%s",
                    conversationId, owner);
        } catch (RuntimeException failure) {
            LOG.warnf(failure, "Conversation work-item worker failed conversationId=%s owner=%s",
                    conversationId, owner);
            state.fail(conversationId, owner, failure);
        } finally {
            MDC.remove("tenantCode");
        }
    }
}
