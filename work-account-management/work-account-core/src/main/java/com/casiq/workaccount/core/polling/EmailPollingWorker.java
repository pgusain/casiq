package com.casiq.workaccount.core.polling;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;


@ApplicationScoped
public class EmailPollingWorker {
    private static final Logger LOG = Logger.getLogger(EmailPollingWorker.class);
    @Inject EmailPollingProcessor processor;
    @Inject EmailPollingStateService state;

    public void process(Long configId, String owner) {
        LOG.debugf("Email polling worker started configId=%s owner=%s", configId, owner);
        try {
            processor.poll(configId, owner);
            LOG.debugf("Email polling worker finished configId=%s owner=%s", configId, owner);
        } catch (RuntimeException failure) {
            LOG.warnf(failure, "Email polling worker failed configId=%s owner=%s", configId, owner);
            state.fail(configId, owner, failure);
        }
    }
}
