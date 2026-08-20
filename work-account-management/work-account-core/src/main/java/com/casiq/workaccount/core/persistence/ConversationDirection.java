package com.casiq.workaccount.core.persistence;

import org.jboss.logging.Logger;

public enum ConversationDirection {
    INBOUND,
    OUTBOUND;

    private static final Logger LOG = Logger.getLogger(ConversationDirection.class);

    public String code() {
        LOG.debugf("Conversation direction requested=%s", this.name());
        return this.name();
    }
}
