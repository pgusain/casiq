package com.casiq.workitem.persistence;

import org.jboss.logging.Logger;

public enum DocumentOrigin {
    INBOUND,
    INTERNAL,
    OUTBOUND;

    private static final Logger LOG = Logger.getLogger(DocumentOrigin.class);

    public String code() {
        LOG.debugf("Document origin requested=%s", this.name());
        return this.name();
    }
}
