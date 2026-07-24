package com.casiq.workaccount.core.polling;

import com.casiq.workaccount.core.persistence.WorkAccountConversationEntity;
import com.casiq.workitem.persistence.WorkItemCommunicationEntity;
import com.casiq.workitem.persistence.WorkItemExecutionEntity;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP;

@ApplicationScoped
public class ConversationRetentionScheduler {
    private static final Logger LOG =
            Logger.getLogger(ConversationRetentionScheduler.class);

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
    @Transactional
    void purge() {
        if (!enabled) return;
        Instant now = Instant.now();
        @SuppressWarnings("unchecked")
        List<String> rows = Panache.getEntityManager().createNativeQuery("""
                        SELECT CAST(id AS VARCHAR)
                        FROM work_account_conversation
                        WHERE received_at < ?1
                          AND (
                            work_item_processed_at IS NOT NULL
                            OR (
                                direction = 'OUTBOUND'
                                AND work_item_execution_id IS NOT NULL
                            )
                          )
                        ORDER BY received_at, id
                        LIMIT ?2
                        FOR UPDATE SKIP LOCKED
                        """)
                .setParameter(1, now.minus(retentionHours, ChronoUnit.HOURS))
                .setParameter(2, Math.max(1, batchSize))
                .getResultList();
        List<UUID> ids = rows.stream().map(UUID::fromString).toList();
        if (!ids.isEmpty()) {
            WorkItemExecutionEntity.update(
                    "conversationId = null where conversationId in ?1", ids);
            long deleted = WorkAccountConversationEntity.delete("id in ?1", ids);
            LOG.infof("Purged %d materialized email conversation row(s)", deleted);
        }

        long cleared = WorkItemCommunicationEntity.update("""
                cachedSnippet = null,
                cachedContentText = null,
                cachedContentHtml = null,
                cacheExpiresAt = null
                where cacheRefreshedAt < ?1
                  and (
                    cachedSnippet is not null
                    or cachedContentText is not null
                    or cachedContentHtml is not null
                  )
                """, now.minus(cacheFallbackHours, ChronoUnit.HOURS));
        if (cleared > 0) {
            LOG.infof("Cleared expired fallback content from %d work-item communication(s)",
                    cleared);
        }
    }
}
