package com.casiq.workaccount.core.polling;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.slf4j.MDC;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP;

@ApplicationScoped
public class ConversationRetentionScheduler {
    private static final Logger LOG =
            Logger.getLogger(ConversationRetentionScheduler.class);

    @Inject ConversationRetentionService retention;
    @ConfigProperty(name = "casiq.conversation-retention.enabled", defaultValue = "true")
    boolean enabled;
    @ConfigProperty(name = "casiq.conversation-retention.hours", defaultValue = "4320")
    long retentionHours;
    @ConfigProperty(name = "casiq.conversation-retention.cache-fallback-hours", defaultValue = "24")
    long cacheFallbackHours;
    @ConfigProperty(name = "casiq.conversation-retention.batch-size", defaultValue = "500")
    int batchSize;

    @Scheduled(
            every = "${casiq.conversation-retention.scheduler-every:1h}",
            concurrentExecution = SKIP)
    void purge() {
        MDC.put("tenantCode", "retention");
        try {
            if (!enabled) {
                LOG.debug(
                        "Conversation retention scheduler tick skipped because it is disabled");
                return;
            }
            Instant now = Instant.now();
            try {
                retention.purgeConversationBatch(
                        now.minus(retentionHours, ChronoUnit.HOURS),
                        batchSize);
            } catch (RuntimeException failure) {
                LOG.error(
                        "Materialized conversation retention batch failed; cache cleanup will continue",
                        failure);
            }
            try {
                retention.clearCommunicationCacheBatch(
                        now.minus(cacheFallbackHours, ChronoUnit.HOURS),
                        batchSize);
            } catch (RuntimeException failure) {
                LOG.error(
                        "Work-item communication cache retention batch failed",
                        failure);
            }
        } finally {
            MDC.remove("tenantCode");
        }
    }
}
