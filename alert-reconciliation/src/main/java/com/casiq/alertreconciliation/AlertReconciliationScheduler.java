package com.casiq.alertreconciliation;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import static io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP;

@ApplicationScoped
public class AlertReconciliationScheduler {
    private static final Logger LOG = Logger.getLogger(AlertReconciliationScheduler.class);

    @Inject
    AlertReconciliationService service;

    @ConfigProperty(name = "casiq.alert-reconciliation.enabled", defaultValue = "false")
    boolean enabled;

    @Scheduled(
            cron = "${casiq.alert-reconciliation.scheduler-cron}",
            timeZone = "${casiq.alert-reconciliation.time-zone:UTC}",
            concurrentExecution = SKIP)
    void schedule() {
        if (!enabled) {
            LOG.debug("Alert reconciliation scheduler tick skipped because it is disabled");
            return;
        }

        try {
            service.runDailyReconciliation();
        } catch (Exception ex) {
            LOG.error("Daily alert reconciliation failed", ex);
            service.notifySupport("Alert reconciliation failure", "Daily alert reconciliation failed: " + ex.getMessage(), ex);
        }
    }
}
